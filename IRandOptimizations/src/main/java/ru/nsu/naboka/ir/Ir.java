package ru.nsu.naboka.ir;

import ru.nsu.naboka.Types.Basic;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public final class Ir {
    private Ir() {}

    public sealed interface Value permits Slot, Constant {
        Basic type();
    }

    public record Slot(String name, Basic type, boolean temporary) implements Value {
        @Override
        public String toString() {
            return (temporary ? "%" : "$") + name;
        }

        String declaration() {
            return this + ":" + type;
        }
    }

    public record Constant(Basic type, Object value) implements Value {
        @Override
        public String toString() {
            return String.valueOf(value);
        }
    }

    public enum Op {
        COPY, NEG, NOT, ADD, SUB, MUL, DIV, EQ, NE, LT, LE, GT, GE, LENGTH, GET, SET;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }

        Object evaluate(List<Object> args) {
            Object a = args.getFirst();
            return switch (this) {
                case COPY -> a;
                case NEG -> -(int) a;
                case NOT -> !(boolean) a;
                case ADD -> (int) a + (int) args.get(1);
                case SUB -> (int) a - (int) args.get(1);
                case MUL -> (int) a * (int) args.get(1);
                case DIV -> (int) a / (int) args.get(1);
                case EQ -> Objects.deepEquals(a, args.get(1));
                case NE -> !Objects.deepEquals(a, args.get(1));
                case LT -> (int) a < (int) args.get(1);
                case LE -> (int) a <= (int) args.get(1);
                case GT -> (int) a > (int) args.get(1);
                case GE -> (int) a >= (int) args.get(1);
                case LENGTH -> ((int[]) a).length;
                case GET -> ((int[]) a)[(int) args.get(1)];
                case SET -> {
                    int[] copy = ((int[]) a).clone();
                    copy[(int) args.get(1)] = (int) args.get(2);
                    yield copy;
                }
            };
        }
    }

    public sealed interface Instruction permits Label, Assign, Call, Jump, Branch, Return {
        default List<Slot> defs() {
            return switch (this) {
                case Assign assign -> List.of(assign.target());
                case Call call -> call.targets();
                default -> List.of();
            };
        }

        default List<Value> operands() {
            return switch (this) {
                case Assign assign -> assign.inputs();
                case Call call -> call.inputs();
                case Branch branch -> List.of(branch.condition());
                case Return returned -> returned.values();
                default -> List.of();
            };
        }
    }

    public record Label(String name) implements Instruction {
        @Override
        public String toString() { return name + ":"; }
    }

    public record Assign(Slot target, Op op, List<Value> inputs) implements Instruction {
        public Assign {
            inputs = List.copyOf(inputs);
        }

        @Override
        public String toString() { return target.declaration() + " = " + op + " " + join(inputs); }
    }

    public record Call(List<Slot> targets, String function, List<Value> inputs) implements Instruction {
        public Call {
            targets = List.copyOf(targets);
            inputs = List.copyOf(inputs);
        }

        @Override
        public String toString() {
            return join(targets.stream().map(Slot::declaration).toList()) + " = call " + function + "(" + join(inputs) + ")";
        }
    }

    public record Jump(String target) implements Instruction {
        @Override
        public String toString() { return "jump " + target; }
    }

    public record Branch(Value condition, String yes, String no) implements Instruction {
        @Override
        public String toString() { return "branch " + condition + ", " + yes + ", " + no; }
    }

    public record Return(List<Value> values) implements Instruction {
        public Return {
            values = List.copyOf(values);
        }

        @Override
        public String toString() { return "return " + join(values); }
    }

    public record Function(String name, List<Slot> parameters, List<Basic> returns, List<Instruction> instructions) {
        public Function {
            parameters = List.copyOf(parameters);
            returns = List.copyOf(returns);
            instructions = List.copyOf(instructions);
        }

        @Override
        public String toString() {
            String signature = "fun " + name + "(" + join(parameters.stream().map(Slot::declaration).toList())
                    + ") -> (" + join(returns) + ")\n";
            return instructions.stream()
                    .map(instruction -> (instruction instanceof Label ? "" : "  ") + instruction + "\n")
                    .collect(Collectors.joining("", signature, ""));
        }
    }

    private static String join(List<?> values) {
        return values.stream().map(Object::toString).collect(Collectors.joining(", "));
    }
}
