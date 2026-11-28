package ru.nsu.naboka.ir;

import ru.nsu.naboka.Ast;
import ru.nsu.naboka.SemanticAnalyzer;
import ru.nsu.naboka.Types.Basic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ru.nsu.naboka.ir.Ir.*;

public final class IrGenerator {
    private final SemanticAnalyzer.Result ast;
    private final Map<String, Ast.Formula> formulas = new HashMap<>();
    private final List<Ast.Node> pending = new ArrayList<>();
    private List<Instruction> code;
    private int temporaries;
    private int labels;

    private IrGenerator(SemanticAnalyzer.Result ast) {
        this.ast = ast;
    }

    public static List<Function> generate(SemanticAnalyzer.Result ast) {
        if (!ast.diagnostics().isEmpty()) {
            throw new IllegalArgumentException("Генерации IR требуется корректный аннотированный AST");
        }
        return new IrGenerator(ast).generate();
    }

    private List<Function> generate() {
        for (Ast.Node node : ast.module().declarations()) {
            if (node instanceof Ast.Function) pending.add(node);
            else if (node instanceof Ast.Formula formula) formulas.put(formula.name(), formula);
        }
        List<Function> result = new ArrayList<>();
        for (int i = 0; i < pending.size(); i++) {
            code = new ArrayList<>();
            temporaries = 0;
            labels = 0;
            code.add(new Label(label()));
            if (pending.get(i) instanceof Ast.Function function) {
                List<Slot> returns = function.returns().stream().map(this::slot).toList();
                returns.forEach(this::initialize);
                function.locals().stream().map(this::slot).forEach(this::initialize);
                statement(function.body());
                code.add(new Return(new ArrayList<>(returns)));
                result.add(new Function(function.name(), function.parameters().stream().map(this::slot).toList(),
                        returns.stream().map(Slot::type).toList(), code));
            } else if (pending.get(i) instanceof Ast.Formula formula) {
                code.add(new Return(List.of(value(formula.predicate()))));
                result.add(new Function(formula.name(), formula.parameters().stream().map(this::slot).toList(),
                        List.of(Basic.BOOL), code));
            }
        }
        return List.copyOf(result);
    }

    private void initialize(Slot slot) {
        Object initial = switch (slot.type()) {
            case INT -> 0;
            case BOOL -> false;
            case INT_ARRAY -> null;
            default -> throw new IllegalArgumentException("Неизвестный тип " + slot.type());
        };
        assign(slot, Op.COPY, new Constant(slot.type(), initial));
    }

    private void statement(Ast.Statement statement) {
        switch (statement) {
            case Ast.Block block -> block.statements().forEach(this::statement);
            case Ast.Assignment assignment -> {
                List<Slot> targets = assignment.targets().stream().map(this::slot).toList();
                if (targets.size() == 1) assign(targets.getFirst(), Op.COPY, value(assignment.value()));
                else call((Ast.Call) assignment.value(), targets);
            }
            case Ast.If conditional -> {
                String yes = label();
                String no = label();
                String end = conditional.elseBranch() == null ? no : label();
                condition(conditional.condition(), yes, no);
                code.add(new Label(yes));
                statement(conditional.thenBranch());
                if (conditional.elseBranch() != null) {
                    code.add(new Jump(end));
                    code.add(new Label(no));
                    statement(conditional.elseBranch());
                }
                code.add(new Label(end));
            }
            case Ast.While loop -> {
                String head = label();
                String body = label();
                String end = label();
                code.add(new Label(head));
                condition(loop.condition(), body, end);
                code.add(new Label(body));
                statement(loop.body());
                code.add(new Jump(head));
                code.add(new Label(end));
            }
            // assert и assume относятся к верификации, как и контракты функций.
            case Ast.Assertion ignored -> {}
            default -> throw new IllegalArgumentException("Неизвестный оператор AST: " + statement);
        }
    }

    private void condition(Ast.Expr expression, String yes, String no) {
        switch (expression) {
            case Ast.Unary unary when unary.operator().equals("not") -> condition(unary.operand(), no, yes);
            case Ast.Binary binary when binary.operator().equals("and") -> {
                String next = label();
                condition(binary.left(), next, no);
                code.add(new Label(next));
                condition(binary.right(), yes, no);
            }
            case Ast.Binary binary when binary.operator().equals("or") || binary.operator().equals("->") -> {
                String next = label();
                if (binary.operator().equals("or")) condition(binary.left(), yes, next);
                else condition(binary.left(), next, yes);
                code.add(new Label(next));
                condition(binary.right(), yes, no);
            }
            default -> code.add(new Branch(value(expression), yes, no));
        }
    }

    private Value value(Ast.Expr expression) {
        Basic type = (Basic) ast.typeOf(expression);
        return switch (expression) {
            case Ast.Number number -> new Constant(type, new BigInteger(number.value()).intValue());
            case Ast.Bool bool -> new Constant(type, bool.value());
            case Ast.Name name -> slot(name);
            case Ast.Unary unary -> compute(type, unary.operator().equals("-") ? Op.NEG : Op.NOT,
                    value(unary.operand()));
            case Ast.Binary binary when List.of("and", "or", "->").contains(binary.operator()) -> logical(binary);
            case Ast.Binary binary -> compute(type, binaryOp(binary.operator()),
                    value(binary.left()), value(binary.right()));
            case Ast.Access access -> compute(type, Op.GET, value(access.array()), value(access.index()));
            case Ast.Update update -> compute(type, Op.SET,
                    value(update.array()), value(update.index()), value(update.value()));
            case Ast.Call call -> {
                Slot target = temp(type);
                call(call, List.of(target));
                yield target;
            }
            case Ast.Quantified quantifier -> throw new IllegalArgumentException(quantifier.token().location()
                    + ": квантор допустим только в предикате верификации");
            default -> throw new IllegalArgumentException("Неизвестное выражение AST: " + expression);
        };
    }

    private Value logical(Ast.Binary binary) {
        Slot result = temp(Basic.BOOL);
        String yes = label();
        String no = label();
        String end = label();
        condition(binary, yes, no);
        code.add(new Label(yes));
        assign(result, Op.COPY, new Constant(Basic.BOOL, true));
        code.add(new Jump(end));
        code.add(new Label(no));
        assign(result, Op.COPY, new Constant(Basic.BOOL, false));
        code.add(new Label(end));
        return result;
    }

    private void call(Ast.Call call, List<Slot> targets) {
        var symbol = ast.bindings().get(call);
        List<Value> args = call.arguments().stream().map(this::value).toList();
        if (symbol.kind() == SemanticAnalyzer.Kind.BUILTIN) {
            assign(targets.getFirst(), Op.LENGTH, args.getFirst());
        } else {
            Ast.Formula formula = formulas.remove(symbol.name());
            if (formula != null) pending.add(formula);
            code.add(new Call(targets, symbol.name(), args));
        }
    }

    private Slot slot(Ast.Node node) {
        var symbol = ast.bindings().get(node);
        return new Slot(symbol.name(), (Basic) symbol.type(), false);
    }

    private Slot temp(Basic type) {
        return new Slot("t" + ++temporaries, type, true);
    }

    private String label() {
        return "L" + labels++;
    }

    private Slot compute(Basic type, Op op, Value... operands) {
        Slot target = temp(type);
        assign(target, op, operands);
        return target;
    }

    private void assign(Slot target, Op op, Value... operands) {
        code.add(new Assign(target, op, List.of(operands)));
    }

    private static Op binaryOp(String operator) {
        return switch (operator) {
            case "+" -> Op.ADD;
            case "-" -> Op.SUB;
            case "*" -> Op.MUL;
            case "/" -> Op.DIV;
            case "==" -> Op.EQ;
            case "!=" -> Op.NE;
            case "<" -> Op.LT;
            case "<=" -> Op.LE;
            case ">" -> Op.GT;
            case ">=" -> Op.GE;
            default -> throw new IllegalArgumentException("Неизвестный оператор: " + operator);
        };
    }
}
