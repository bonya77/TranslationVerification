package ru.nsu.naboka;

import java.util.List;
import java.util.stream.Collectors;

import static ru.nsu.naboka.Ast.*;

public final class AstPrinter {
    public static String print(Node node) {
        if (node == null) return "none";
        if (node instanceof Ast.Module module) return group("module", module.declarations());
        if (node instanceof Variable variable) return "(" + variable.name() + " " + (variable.type() == null ? "inferred" : variable.type()) + ")";
        if (node instanceof Function function) {
            return "(function " + function.name() + " " + group("parameters", function.parameters())
                    + " " + form("requires", function.requires()) + " " + group("returns", function.returns())
                    + " " + form("ensures", function.ensures()) + " " + group("uses", function.locals())
                    + " " + print(function.body()) + ")";
        }
        if (node instanceof Formula formula) {
            return "(formula " + formula.name() + " " + group("parameters", formula.parameters()) + " " + print(formula.predicate()) + ")";
        }
        if (node instanceof Block block) return group("block", block.statements());
        if (node instanceof Assignment assignment) return "(assign " + group("targets", assignment.targets()) + " " + print(assignment.value()) + ")";
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

    private static String form(String name, Node... nodes) {
        return group(name, java.util.Arrays.asList(nodes));
    }

    private static String group(String name, List<? extends Node> nodes) {
        String children = nodes.stream().map(AstPrinter::print).collect(Collectors.joining(" "));
        return "(" + name + (children.isEmpty() ? "" : " " + children) + ")";
    }
}
