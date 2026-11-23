package ru.nsu.naboka;

import java.util.List;
import java.util.stream.Collectors;

import static ru.nsu.naboka.Ast.*;

public final class AstPrinter {
    private final SemanticAnalyzer.Result semantics;

    private AstPrinter(SemanticAnalyzer.Result semantics) {
        this.semantics = semantics;
    }

    public static String print(Node node) {
        return new AstPrinter(null).node(node);
    }

    public static String print(SemanticAnalyzer.Result result) {
        AstPrinter printer = new AstPrinter(result);
        String symbols = result.symbols().stream().map(symbol -> "(symbol #" + symbol.id() + " "
                + symbol.kind().name().toLowerCase(java.util.Locale.ROOT) + " " + symbol.name() + " "
                + type(symbol.type()) + " @" + symbol.token().location() + ")").collect(Collectors.joining(" "));
        return "(typed-ast 1 (symbols " + symbols + ") " + printer.node(result.module()) + ")";
    }

    private String node(Node node) {
        String content = content(node);
        if (semantics != null && node instanceof Expr expression) {
            SemanticAnalyzer.Symbol symbol = semantics.bindings().get(node);
            return "(: " + type(semantics.typeOf(expression)) + " @" + node.token().location() + " "
                    + (symbol == null ? "_" : "#" + symbol.id()) + " " + content + ")";
        }
        return content;
    }

    private String content(Node node) {
        if (node == null) return "none";
        if (node instanceof Ast.Module module) return group("module", module.declarations());
        if (node instanceof Variable variable) {
            if (semantics == null) return "(" + variable.name() + " " + (variable.type() == null ? "inferred" : variable.type()) + ")";
            SemanticAnalyzer.Symbol symbol = semantics.bindings().get(variable);
            return "(" + variable.name() + " " + type(semantics.typeOf(variable)) + " @" + variable.token().location()
                    + " " + (symbol == null ? "_" : "#" + symbol.id()) + ")";
        }
        if (node instanceof Function function) {
            return "(function " + function.name() + " " + group("parameters", function.parameters())
                    + " " + form("requires", function.requires()) + " " + group("returns", function.returns())
                    + " " + form("ensures", function.ensures()) + " " + group("uses", function.locals())
                    + " " + node(function.body()) + ")";
        }
        if (node instanceof Formula formula) {
            return "(formula " + formula.name() + " " + group("parameters", formula.parameters()) + " " + node(formula.predicate()) + ")";
        }
        if (node instanceof Block block) return group("block", block.statements());
        if (node instanceof Assignment assignment) return "(assign " + group("targets", assignment.targets()) + " " + node(assignment.value()) + ")";
        if (node instanceof If conditional) return form("if", conditional.condition(), conditional.thenBranch(), conditional.elseBranch());
        if (node instanceof While loop) return form("while", loop.condition(), loop.invariant(), loop.body());
        if (node instanceof Assertion assertion) return form(assertion.kind(), assertion.predicate());
        if (node instanceof Ast.Number number) return number.value();
        if (node instanceof Name name) return name.value();
        if (node instanceof Bool bool) return Boolean.toString(bool.value());
        if (node instanceof Unary unary) return form(unary.operator(), unary.operand());
        if (node instanceof Binary binary) return form(binary.operator(), binary.left(), binary.right());
        if (node instanceof Call call) return group("call " + call.name(), call.arguments());
        if (node instanceof Access access) return form("index", access.array(), access.index());
        if (node instanceof Update update) return form("set", update.array(), update.index(), update.value());
        if (node instanceof Quantified quantified) return form(quantified.kind(), quantified.variable(), quantified.body());
        throw new IllegalArgumentException("Неизвестный узел AST: " + node.getClass().getName());
    }

    private String form(String name, Node... nodes) {
        return group(name, java.util.Arrays.asList(nodes));
    }

    private String group(String name, List<? extends Node> nodes) {
        String children = nodes.stream().map(this::node).collect(Collectors.joining(" "));
        return "(" + name + (children.isEmpty() ? "" : " " + children) + ")";
    }

    private static String type(Types.Type type) {
        if (type instanceof Types.Function function) {
            return "(func (" + typeList(function.parameters()) + ") (" + typeList(function.returns()) + "))";
        }
        if (type instanceof Types.Tuple tuple) return "(tuple " + typeList(tuple.elements()) + ")";
        return type.toString();
    }

    private static String typeList(List<Types.Type> types) {
        return types.stream().map(AstPrinter::type).collect(Collectors.joining(" "));
    }
}
