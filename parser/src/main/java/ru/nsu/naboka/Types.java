package ru.nsu.naboka;

import java.util.List;
import java.util.stream.Collectors;

public final class Types {
    private Types() {}

    public sealed interface Type permits Basic, Function, Tuple {}

    public enum Basic implements Type {
        INT("int"), BOOL("bool"), INT_ARRAY("int[]"), UNKNOWN("?"), ERROR("error");

        private final String text;

        Basic(String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    public record Function(List<Type> parameters, List<Type> returns) implements Type {
        public Function {
            parameters = List.copyOf(parameters);
            returns = List.copyOf(returns);
        }

        @Override
        public String toString() {
            return "func(" + join(parameters) + ")->(" + join(returns) + ")";
        }
    }

    public record Tuple(List<Type> elements) implements Type {
        public Tuple {
            elements = List.copyOf(elements);
        }

        @Override
        public String toString() {
            return "tuple(" + join(elements) + ")";
        }
    }

    public static Type unify(Type left, Type right) {
        if (left == Basic.ERROR || right == Basic.ERROR) return Basic.ERROR;
        if (left == Basic.UNKNOWN) return right;
        if (right == Basic.UNKNOWN || left.equals(right)) return left;
        if (left instanceof Function a && right instanceof Function b) {
            List<Type> parameters = unifyLists(a.parameters(), b.parameters());
            List<Type> returns = unifyLists(a.returns(), b.returns());
            return parameters == null || returns == null ? Basic.ERROR : new Function(parameters, returns);
        }
        if (left instanceof Tuple a && right instanceof Tuple b) {
            List<Type> elements = unifyLists(a.elements(), b.elements());
            return elements == null ? Basic.ERROR : new Tuple(elements);
        }
        return Basic.ERROR;
    }

    private static List<Type> unifyLists(List<Type> left, List<Type> right) {
        if (left.size() != right.size()) return null;
        java.util.ArrayList<Type> result = new java.util.ArrayList<>();
        for (int i = 0; i < left.size(); i++) {
            Type type = unify(left.get(i), right.get(i));
            if (type == Basic.ERROR) return null;
            result.add(type);
        }
        return List.copyOf(result);
    }

    private static String join(List<Type> types) {
        return types.stream().map(Object::toString).collect(Collectors.joining(","));
    }
}
