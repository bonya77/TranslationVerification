package ru.nsu.naboka.jvm;

import ru.nsu.naboka.Types.Basic;
import ru.nsu.naboka.ir.Ir;
import ru.nsu.naboka.runtime.ImmutableIntArray;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;
import static ru.nsu.naboka.Types.Basic.INT_ARRAY;
import static ru.nsu.naboka.ir.Ir.*;

final class BytecodeEmitter {
    private static final ClassDesc ARRAY = ClassDesc.of(ImmutableIntArray.class.getName());
    private static final ClassDesc TUPLE = CD_int.arrayType();
    private static final ClassDesc OBJECTS = ClassDesc.of("java.util.Objects");

    private final ClassDesc owner;
    private final List<Function> functions;
    private final Map<String, MethodTypeDesc> signatures = new HashMap<>();

    BytecodeEmitter(String className, List<Function> functions) {
        owner = ClassDesc.of(className);
        this.functions = functions;
        for (Function function : functions) {
            var parameters = function.parameters().stream().map(Slot::type).map(BytecodeEmitter::descriptor).toList();
            ClassDesc result = function.returns().size() == 1 ? descriptor(function.returns().getFirst()) : TUPLE;
            signatures.put(function.name(), MethodTypeDesc.of(result, parameters));
        }
    }

    byte[] emit(Set<String> publicMethods) {
        return ClassFile.of().build(owner, builder -> {
            builder.withVersion(JAVA_25_VERSION, 0).withFlags(ACC_PUBLIC | ACC_FINAL | ACC_SUPER);
            for (Function function : functions) {
                int visibility = publicMethods.contains(function.name()) ? ACC_PUBLIC : ACC_PRIVATE;
                builder.withMethodBody(function.name(), signatures.get(function.name()), ACC_STATIC | visibility,
                        code -> emitFunction(code, function));
            }
        });
    }

    private void emitFunction(CodeBuilder code, Function function) {
        Map<String, Label> labels = new HashMap<>();
        Map<Slot, Integer> slots = new HashMap<>();
        for (Slot slot : function.parameters()) slots.put(slot, slots.size());
        for (Instruction instruction : function.instructions()) {
            if (instruction instanceof Ir.Label label) labels.put(label.name(), code.newLabel());
            for (Slot slot : instruction.defs()) slots.computeIfAbsent(slot, ignored -> slots.size());
        }
        for (Instruction instruction : function.instructions()) {
            switch (instruction) {
                case Ir.Label label -> code.labelBinding(labels.get(label.name()));
                case Jump jump -> code.goto_(labels.get(jump.target()));
                case Branch branch -> {
                    load(code, branch.condition(), slots);
                    code.ifne(labels.get(branch.yes())).goto_(labels.get(branch.no()));
                }
                case Assign assign -> emitAssignment(code, assign, slots);
                case Call call -> emitCall(code, call, slots);
                case Return returned -> emitReturn(code, returned, slots);
            }
        }
    }

    private static void emitAssignment(CodeBuilder code, Assign assign, Map<Slot, Integer> slots) {
        for (Value input : assign.inputs()) load(code, input, slots);
        switch (assign.op()) {
            case COPY -> {}
            case NEG -> code.ineg();
            case NOT -> code.iconst_1().ixor();
            case ADD -> code.iadd();
            case SUB -> code.isub();
            case MUL -> code.imul();
            case DIV -> code.idiv();
            case EQ, NE, LT, LE, GT, GE -> compare(code, assign);
            case LENGTH -> code.invokevirtual(ARRAY, "length", MethodTypeDesc.of(CD_int));
            case GET -> code.invokevirtual(ARRAY, "get", MethodTypeDesc.of(CD_int, CD_int));
            case SET -> code.invokevirtual(ARRAY, "with", MethodTypeDesc.of(ARRAY, CD_int, CD_int));
        }
        store(code, assign.target(), slots);
    }

    private static void compare(CodeBuilder code, Assign assign) {
        if (assign.inputs().getFirst().type() == INT_ARRAY) {
            code.invokestatic(OBJECTS, "equals", MethodTypeDesc.of(CD_boolean, CD_Object, CD_Object));
            if (assign.op() == Op.NE) code.iconst_1().ixor();
            return;
        }
        Opcode branch = switch (assign.op()) {
            case EQ -> Opcode.IF_ICMPEQ;
            case NE -> Opcode.IF_ICMPNE;
            case LT -> Opcode.IF_ICMPLT;
            case LE -> Opcode.IF_ICMPLE;
            case GT -> Opcode.IF_ICMPGT;
            case GE -> Opcode.IF_ICMPGE;
            default -> throw new IllegalArgumentException("неизвестная операция сравнения: " + assign.op());
        };
        Label yes = code.newLabel();
        Label end = code.newLabel();
        code.branch(branch, yes).iconst_0().goto_(end).labelBinding(yes).iconst_1().labelBinding(end);
    }

    private void emitCall(CodeBuilder code, Call call, Map<Slot, Integer> slots) {
        for (Value input : call.inputs()) load(code, input, slots);
        code.invokestatic(owner, call.function(), signatures.get(call.function()));
        if (call.targets().size() == 1) {
            store(code, call.targets().getFirst(), slots);
            return;
        }
        for (int i = 0; i < call.targets().size(); i++) {
            code.dup().loadConstant(i).iaload();
            store(code, call.targets().get(i), slots);
        }
        code.pop();
    }

    private static void emitReturn(CodeBuilder code, Return returned, Map<Slot, Integer> slots) {
        List<Value> values = returned.values();
        if (values.size() == 1) {
            Value value = values.getFirst();
            load(code, value, slots);
            code.return_(kind(value.type()));
            return;
        }
        code.loadConstant(values.size()).newarray(TypeKind.INT);
        for (int i = 0; i < values.size(); i++) {
            code.dup().loadConstant(i);
            load(code, values.get(i), slots);
            code.iastore();
        }
        code.areturn();
    }

    private static void load(CodeBuilder code, Value value, Map<Slot, Integer> slots) {
        if (value instanceof Slot slot) {
            code.loadLocal(kind(slot.type()), slots.get(slot));
        } else {
            Object constant = ((Constant) value).value();
            switch (constant) {
                case Integer number -> code.loadConstant(number.intValue());
                case Boolean bool -> code.loadConstant(bool ? 1 : 0);
                case null -> code.aconst_null();
                default -> throw new IllegalArgumentException("неизвестная константа: " + constant);
            }
        }
    }

    private static void store(CodeBuilder code, Slot slot, Map<Slot, Integer> slots) {
        code.storeLocal(kind(slot.type()), slots.get(slot));
    }

    private static TypeKind kind(Basic type) {
        return type == INT_ARRAY ? TypeKind.REFERENCE : TypeKind.INT;
    }

    private static ClassDesc descriptor(Basic type) {
        return switch (type) {
            case INT -> CD_int;
            case BOOL -> CD_boolean;
            case INT_ARRAY -> ARRAY;
            default -> throw new IllegalArgumentException("неизвестный тип JVM: " + type);
        };
    }
}
