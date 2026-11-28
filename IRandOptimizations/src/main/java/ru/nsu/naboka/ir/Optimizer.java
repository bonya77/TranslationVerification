package ru.nsu.naboka.ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ru.nsu.naboka.ir.ControlFlow.Block;
import static ru.nsu.naboka.ir.Ir.*;

public final class Optimizer {
    private Optimizer() {}

    public record Options(boolean constants, boolean algebra, boolean dce) {}

    public static Function optimize(Function function, Options options) {
        if (!options.constants() && !options.algebra() && !options.dce()) return function;
        List<Block> blocks = ControlFlow.build(function);
        if (options.constants() || options.algebra()) {
            blocks = blocks.stream().map(block -> rewrite(block, options)).toList();
        }
        if (options.dce()) {
            Map<String, Set<Slot>> out = Liveness.liveOut(blocks);
            blocks = blocks.stream().map(block -> eliminateDead(block, out.get(block.label()))).toList();
        }
        List<Instruction> code = new ArrayList<>();
        for (Block block : blocks) {
            code.add(new Label(block.label()));
            code.addAll(block.instructions());
        }
        return new Function(function.name(), function.parameters(), function.returns(), code);
    }

    private static Block rewrite(Block block, Options options) {
        Map<Slot, Constant> constants = new HashMap<>();
        List<Instruction> code = new ArrayList<>();
        for (Instruction original : block.instructions()) {
            Instruction instruction = options.constants() ? substitute(original, constants) : original;
            if (instruction instanceof Assign assign) {
                if (options.constants()) assign = fold(assign);
                if (options.algebra()) assign = simplify(assign);
                instruction = assign;
            }
            if (options.constants()) {
                instruction.defs().forEach(constants::remove);
                if (instruction instanceof Assign assign && assign.op() == Op.COPY
                        && assign.inputs().getFirst() instanceof Constant constant) {
                    constants.put(assign.target(), constant);
                }
            }
            code.add(instruction);
        }
        return new Block(block.label(), code, block.successors());
    }

    private static Instruction substitute(Instruction instruction, Map<Slot, Constant> constants) {
        List<Value> args = instruction.operands().stream()
                .map(value -> value instanceof Slot slot && constants.containsKey(slot) ? constants.get(slot) : value)
                .toList();
        return switch (instruction) {
            case Assign assign -> new Assign(assign.target(), assign.op(), args);
            case Call call -> new Call(call.targets(), call.function(), args);
            case Branch branch -> new Branch(args.getFirst(), branch.yes(), branch.no());
            case Return ignored -> new Return(args);
            default -> instruction;
        };
    }

    private static Assign fold(Assign assign) {
        if (assign.op() != Op.COPY && discardable(assign) && assign.inputs().stream().allMatch(Constant.class::isInstance)) {
            List<Object> args = assign.inputs().stream().map(Constant.class::cast).map(Constant::value).toList();
            return copy(assign.target(), new Constant(assign.target().type(), assign.op().evaluate(args)));
        }
        return assign;
    }

    private static Assign simplify(Assign assign) {
        if (assign.inputs().size() != 2) return assign;
        Value a = assign.inputs().getFirst();
        Value b = assign.inputs().get(1);
        Value replacement = switch (assign.op()) {
            case ADD -> isInt(a, 0) ? b : isInt(b, 0) ? a : null;
            case SUB -> isInt(b, 0) ? a : null;
            case MUL -> {
                if (isInt(a, 0) || isInt(b, 1)) yield a;
                if (isInt(b, 0) || isInt(a, 1)) yield b;
                yield null;
            }
            case DIV -> isInt(b, 1) ? a : null;
            default -> null;
        };
        return replacement == null ? assign : copy(assign.target(), replacement);
    }

    private static boolean isInt(Value value, int expected) {
        return value instanceof Constant constant && Integer.valueOf(expected).equals(constant.value());
    }

    private static Assign copy(Slot target, Value value) {
        return new Assign(target, Op.COPY, List.of(value));
    }

    private static boolean discardable(Assign assign) {
        return switch (assign.op()) {
            case LENGTH, GET, SET -> false;
            case DIV -> assign.inputs().get(1) instanceof Constant constant && !isInt(constant, 0);
            default -> true;
        };
    }

    private static Block eliminateDead(Block block, Set<Slot> liveOut) {
        Set<Slot> live = new HashSet<>(liveOut);
        List<Instruction> kept = new ArrayList<>();
        for (Instruction instruction : block.instructions().reversed()) {
            if (instruction instanceof Assign assign && !live.contains(assign.target()) && discardable(assign)) continue;
            Liveness.stepBack(instruction, live);
            kept.add(instruction);
        }
        return new Block(block.label(), kept.reversed(), block.successors());
    }
}
