package ru.nsu.naboka;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ru.nsu.naboka.Ast.*;
import static ru.nsu.naboka.Types.Basic.*;
import ru.nsu.naboka.Types.Type;

public final class SemanticAnalyzer {
    public enum Kind { FUNCTION, FORMULA, BUILTIN, PARAMETER, RETURN, LOCAL, QUANTIFIED }

    public record Diagnostic(String code, String message, Token token) {
        @Override
        public String toString() {
            return token.location() + ": [" + code + "] " + message;
        }
    }

    public record Result(Ast.Module module, Map<Expr, Type> types, List<Symbol> symbols,
                         Map<Node, Symbol> bindings, List<Diagnostic> diagnostics) {
        public Result {
            types = Collections.unmodifiableMap(new IdentityHashMap<>(types));
            symbols = List.copyOf(symbols);
            bindings = Collections.unmodifiableMap(new IdentityHashMap<>(bindings));
            diagnostics = List.copyOf(diagnostics);
        }

        public Type typeOf(Expr expression) {
            return types.getOrDefault(expression, ERROR);
        }

        public Type typeOf(Variable variable) {
            Symbol symbol = bindings.get(variable);
            return symbol == null ? ERROR : symbol.type();
        }
    }

    // Тип уточняется при анализе; снаружи символ доступен только для чтения.
    public static final class Symbol {
        private final int id;
        private final String name;
        private final Kind kind;
        private final Token token;
        private Type type;

        private Symbol(int id, String name, Kind kind, Type type, Token token) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.type = type;
            this.token = token;
        }

        public int id() { return id; }
        public String name() { return name; }
        public Kind kind() { return kind; }
        public Type type() { return type; }
        public Token token() { return token; }
    }

    private static final class Scope {
        final Scope parent;
        final Map<String, Symbol> entries = new HashMap<>();

        Scope(Scope parent) {
            this.parent = parent;
        }

        Symbol find(String name) {
            Symbol entry = entries.get(name);
            return entry != null || parent == null ? entry : parent.find(name);
        }
    }

    private final Scope globals = new Scope(null);
    private final Map<Expr, Type> types = new IdentityHashMap<>();
    private final Map<Node, Symbol> bindings = new IdentityHashMap<>();
    private final List<Symbol> symbols = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private SemanticAnalyzer() {}

    public static Result analyze(Ast.Module module) {
        return new SemanticAnalyzer().run(module);
    }

    private Result run(Ast.Module module) {
        Token builtin = new Token("BUILTIN", "length", 0, 1, 1);
        declare(globals, "length", Kind.BUILTIN, new Types.Function(List.of(INT_ARRAY), List.of(INT)), builtin);
        // Сначала собираем сигнатуры, чтобы разрешить вызовы вперёд и рекурсию.
        for (Node node : module.declarations()) {
            if (node instanceof Function function) {
                Symbol entry = declare(globals, function.name(), Kind.FUNCTION,
                        new Types.Function(annotations(function.parameters()), annotations(function.returns())), function.token());
                bindings.put(function, entry);
            } else if (node instanceof Formula formula) {
                Symbol entry = declare(globals, formula.name(), Kind.FORMULA,
                        new Types.Function(annotations(formula.parameters()), List.of(BOOL)), formula.token());
                bindings.put(formula, entry);
            }
        }
        for (Node node : module.declarations()) {
            if (node instanceof Function function) function(function);
            else if (node instanceof Formula formula) formula(formula);
        }
        types.replaceAll((expression, type) -> type == UNKNOWN && bindings.containsKey(expression)
                ? bindings.get(expression).type : type);
        return new Result(module, types, symbols, bindings, diagnostics);
    }

    private void function(Function function) {
        Scope scope = new Scope(globals);
        Set<Symbol> readable = new HashSet<>();
        Types.Function signature = (Types.Function) bindings.get(function).type;
        parameters(function.parameters(), signature.parameters(), scope, readable);
        expect(expression(function.requires(), scope, readable), BOOL, function.requires().token(), "requires");
        for (int i = 0; i < function.returns().size(); i++) {
            readable.add(variable(function.returns().get(i), Kind.RETURN, signature.returns().get(i), scope));
        }
        expect(expression(function.ensures(), scope, readable), BOOL, function.ensures().token(), "ensures");
        List<Symbol> locals = new ArrayList<>();
        for (Variable local : function.locals()) {
            Symbol entry = variable(local, Kind.LOCAL, local.type() == null ? UNKNOWN : annotation(local), scope);
            locals.add(entry);
            if (local.type() != null) readable.add(entry);
        }
        statement(function.body(), scope, readable);
        for (Symbol entry : locals) {
            if (entry.type == UNKNOWN) error("CANNOT_INFER", entry.token,
                    "невозможно вывести тип локальной переменной '" + entry.name + "': нет корректного присваивания");
        }
    }

    private void formula(Formula formula) {
        Scope scope = new Scope(globals);
        Set<Symbol> readable = new HashSet<>();
        Types.Function signature = (Types.Function) bindings.get(formula).type;
        parameters(formula.parameters(), signature.parameters(), scope, readable);
        expect(expression(formula.predicate(), scope, readable), BOOL, formula.predicate().token(), "тело формулы");
    }

    private void parameters(List<Variable> parameters, List<Type> parameterTypes, Scope scope, Set<Symbol> readable) {
        for (int i = 0; i < parameters.size(); i++) {
            readable.add(variable(parameters.get(i), Kind.PARAMETER, parameterTypes.get(i), scope));
        }
    }

    private Set<Symbol> statement(Statement statement, Scope scope, Set<Symbol> readable) {
        if (statement instanceof Block block) {
            // Объявлений внутри операторов нет; новая область нужна только кванторам.
            for (Statement child : block.statements()) readable = statement(child, scope, readable);
        } else if (statement instanceof Assignment assignment) {
            assignment(assignment, scope, readable);
        } else if (statement instanceof If conditional) {
            expect(expression(conditional.condition(), scope, readable), BOOL, conditional.condition().token(), "условие if");
            Set<Symbol> thenReadable = statement(conditional.thenBranch(), scope, new HashSet<>(readable));
            Set<Symbol> elseReadable = conditional.elseBranch() == null ? new HashSet<>(readable)
                    : statement(conditional.elseBranch(), scope, new HashSet<>(readable));
            // После if доступны для чтения переменные, доступные в обеих ветвях.
            thenReadable.retainAll(elseReadable);
            readable = thenReadable;
        } else if (statement instanceof While loop) {
            expect(expression(loop.condition(), scope, readable), BOOL, loop.condition().token(), "условие while");
            expect(expression(loop.invariant(), scope, readable), BOOL, loop.invariant().token(), "invariant");
            statement(loop.body(), scope, new HashSet<>(readable));
        } else if (statement instanceof Assertion assertion) {
            expect(expression(assertion.predicate(), scope, readable), BOOL, assertion.predicate().token(), assertion.kind());
        } else {
            throw new IllegalArgumentException("Неизвестный оператор AST: " + statement.getClass().getName());
        }
        return readable;
    }

    private void assignment(Assignment assignment, Scope scope, Set<Symbol> readable) {
        Type value = expression(assignment.value(), scope, readable);
        List<Type> values = value instanceof Types.Tuple tuple ? tuple.elements() : List.of(value);
        boolean arityMatches = value == ERROR || values.size() == assignment.targets().size();
        if (!arityMatches) error("RESULT_COUNT", assignment.token(),
                "присваивание: ожидалось результатов " + assignment.targets().size() + ", получено " + values.size());
        Set<Symbol> seen = new HashSet<>();
        for (int i = 0; i < assignment.targets().size(); i++) {
            Expr target = assignment.targets().get(i);
            if (!(target instanceof Name name)) {
                expression(target, scope, readable);
                error("INVALID_TARGET", target.token(), "цель присваивания должна быть именем переменной");
                continue;
            }
            Symbol entry = resolve(name.value(), name, scope);
            if (entry == null) {
                types.put(target, ERROR);
                continue;
            }
            types.put(target, entry.type);
            if (!isVariable(entry)) {
                error("NOT_VARIABLE", target.token(), "'" + entry.name + "' не является переменной");
                continue;
            }
            if (!seen.add(entry)) {
                error("DUPLICATE_TARGET", target.token(), "повторная цель присваивания '" + entry.name + "'");
                continue;
            }
            if (entry.kind == Kind.PARAMETER || entry.kind == Kind.QUANTIFIED) {
                error("IMMUTABLE_PARAMETER", target.token(), "нельзя изменять параметр '" + entry.name + "'");
                continue;
            }
            if (!arityMatches || value == ERROR) continue;
            Type actual = values.get(i);
            Type unified = Types.unify(entry.type, actual);
            if (unified == ERROR) {
                expect(actual, entry.type, target.token(), "присваивание '" + entry.name + "'");
            } else {
                entry.type = unified;
                types.put(target, unified);
                readable.add(entry);
            }
        }
    }

    private Type expression(Expr expression, Scope scope, Set<Symbol> readable) {
        Type type;
        if (expression instanceof Ast.Number) type = INT;
        else if (expression instanceof Bool) type = BOOL;
        else if (expression instanceof Name name) {
            Symbol entry = resolve(name.value(), name, scope);
            if (entry == null) type = ERROR;
            else if (!isVariable(entry)) {
                error("NOT_VARIABLE", name.token(), "'" + name.value() + "' не является переменной");
                type = ERROR;
            } else if (!readable.contains(entry)) {
                error("USE_BEFORE_ASSIGNMENT", name.token(), "переменная '" + name.value() + "' используется до присваивания");
                type = ERROR;
            } else type = entry.type;
        } else if (expression instanceof Unary unary) {
            Type operand = expression(unary.operand(), scope, readable);
            Type expected = unary.operator().equals("-") ? INT : BOOL;
            type = expect(operand, expected, unary.token(), "оператор '" + unary.operator() + "'") ? expected : ERROR;
        } else if (expression instanceof Binary binary) {
            type = binary(binary, scope, readable);
        } else if (expression instanceof Call call) {
            type = call(call, scope, readable);
        } else if (expression instanceof Access access) {
            Type array = expression(access.array(), scope, readable);
            Type index = expression(access.index(), scope, readable);
            boolean arrayOk = expect(array, INT_ARRAY, access.array().token(), "индексируемое значение");
            boolean indexOk = expect(index, INT, access.index().token(), "индекс массива");
            type = arrayOk && indexOk ? INT : ERROR;
        } else if (expression instanceof Update update) {
            Type array = expression(update.array(), scope, readable);
            Type index = expression(update.index(), scope, readable);
            Type value = expression(update.value(), scope, readable);
            boolean arrayOk = expect(array, INT_ARRAY, update.array().token(), "обновляемый массив");
            boolean indexOk = expect(index, INT, update.index().token(), "индекс массива");
            boolean valueOk = expect(value, INT, update.value().token(), "элемент массива");
            type = arrayOk && indexOk && valueOk ? INT_ARRAY : ERROR;
        } else if (expression instanceof Quantified quantified) {
            Scope nested = new Scope(scope);
            Symbol entry = variable(quantified.variable(), Kind.QUANTIFIED, annotation(quantified.variable()), nested);
            Set<Symbol> nestedReadable = new HashSet<>(readable);
            nestedReadable.add(entry);
            Type body = expression(quantified.body(), nested, nestedReadable);
            type = expect(body, BOOL, quantified.body().token(), "тело квантора") ? BOOL : ERROR;
        } else {
            throw new IllegalArgumentException("Неизвестное выражение AST: " + expression.getClass().getName());
        }
        types.put(expression, type);
        return type;
    }

    private Type binary(Binary binary, Scope scope, Set<Symbol> readable) {
        Type left = expression(binary.left(), scope, readable);
        Type right = expression(binary.right(), scope, readable);
        String context = "оператор '" + binary.operator() + "'";
        if (binary.operator().equals("==") || binary.operator().equals("!=")) {
            if (left == ERROR || right == ERROR) return ERROR;
            if (left instanceof Types.Tuple || right instanceof Types.Tuple || Types.unify(left, right) == ERROR) {
                error("TYPE_MISMATCH", binary.token(), context + ": несовместимые типы " + left + " и " + right);
                return ERROR;
            }
            return BOOL;
        }
        boolean arithmetic = List.of("+", "-", "*", "/").contains(binary.operator());
        boolean comparison = List.of("<", "<=", ">", ">=").contains(binary.operator());
        Type expected = arithmetic || comparison ? INT : BOOL;
        boolean leftOk = expect(left, expected, binary.left().token(), context);
        boolean rightOk = expect(right, expected, binary.right().token(), context);
        return leftOk && rightOk ? (arithmetic ? INT : BOOL) : ERROR;
    }

    private Type call(Call call, Scope scope, Set<Symbol> readable) {
        List<Type> arguments = new ArrayList<>();
        for (Expr argument : call.arguments()) arguments.add(expression(argument, scope, readable));
        Symbol entry = resolve(call.name(), call, scope);
        if (entry == null) return ERROR;
        if (!(entry.type instanceof Types.Function function)) {
            error("NOT_CALLABLE", call.token(), "'" + call.name() + "' не является функцией или формулой");
            return ERROR;
        }
        boolean valid = arguments.size() == function.parameters().size();
        if (!valid) error("ARGUMENT_COUNT", call.token(), "вызов '" + call.name() + "': ожидалось аргументов "
                + function.parameters().size() + ", получено " + arguments.size());
        for (int i = 0; i < Math.min(arguments.size(), function.parameters().size()); i++) {
            boolean ok = expect(arguments.get(i), function.parameters().get(i), call.arguments().get(i).token(),
                    "аргумент " + (i + 1) + " вызова '" + call.name() + "'");
            valid &= ok;
        }
        if (!valid || arguments.contains(ERROR) || function.returns().contains(ERROR)) return ERROR;
        return function.returns().size() == 1 ? function.returns().get(0) : new Types.Tuple(function.returns());
    }

    private Symbol resolve(String name, Node node, Scope scope) {
        Symbol entry = scope.find(name);
        if (entry == null) error("UNKNOWN_NAME", node.token(), "неизвестное имя '" + name + "' (не объявлено в этой области видимости)");
        else bindings.put(node, entry);
        return entry;
    }

    private Symbol variable(Variable variable, Kind kind, Type type, Scope scope) {
        Symbol entry = declare(scope, variable.name(), kind, type, variable.token());
        bindings.put(variable, entry);
        return entry;
    }

    private Symbol declare(Scope scope, String name, Kind kind, Type type, Token token) {
        Symbol entry = new Symbol(symbols.size(), name, kind, type, token);
        symbols.add(entry);
        Symbol previous = scope.entries.putIfAbsent(name, entry);
        if (previous == null && kind == Kind.QUANTIFIED) {
            Symbol outer = scope.parent.find(name);
            if (outer != null && isVariable(outer)) previous = outer;
        }
        if (previous != null) error("DUPLICATE_NAME", token,
                "повторное объявление '" + name + "'; первое объявление в " + previous.token.location());
        return entry;
    }

    private List<Type> annotations(List<Variable> variables) {
        return variables.stream().map(this::annotation).toList();
    }

    private Type annotation(Variable variable) {
        if (variable.type() == null) {
            error("MISSING_TYPE", variable.token(), "для '" + variable.name() + "' требуется явный тип");
            return ERROR;
        }
        if (variable.type().equals("int")) return INT;
        if (variable.type().equals("int[]")) return INT_ARRAY;
        error("UNSUPPORTED_TYPE", variable.token(), "неподдерживаемый тип '" + variable.type() + "'");
        return ERROR;
    }

    private boolean expect(Type actual, Type expected, Token token, String context) {
        if (actual == ERROR || expected == ERROR) return false;
        if (Types.unify(actual, expected) != ERROR) return true;
        error("TYPE_MISMATCH", token, context + ": ожидался " + expected + ", получен " + actual);
        return false;
    }

    private static boolean isVariable(Symbol entry) {
        return entry.kind == Kind.PARAMETER || entry.kind == Kind.RETURN
                || entry.kind == Kind.LOCAL || entry.kind == Kind.QUANTIFIED;
    }

    private void error(String code, Token token, String message) {
        diagnostics.add(new Diagnostic(code, message, token));
    }
}
