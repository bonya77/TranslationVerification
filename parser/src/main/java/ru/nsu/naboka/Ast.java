package ru.nsu.naboka;

import java.util.List;

public final class Ast {
    public interface Node {
        Token token();
    }

    public record Module(List<Node> declarations, Token token) implements Node {}
    public record Variable(String name, String type, Token token) implements Node {}
    public record Function(String name, List<Variable> parameters, Expr requires, List<Variable> returns,
                           Expr ensures, List<Variable> locals, Statement body, Token token) implements Node {}
    public record Formula(String name, List<Variable> parameters, Expr predicate, Token token) implements Node {}

    public interface Statement extends Node {}
    public record Block(List<Statement> statements, Token token) implements Statement {}
    public record Assignment(List<Expr> targets, Expr value, Token token) implements Statement {}
    public record If(Expr condition, Statement thenBranch, Statement elseBranch, Token token) implements Statement {}
    public record While(Expr condition, Expr invariant, Statement body, Token token) implements Statement {}
    public record Assertion(String kind, Expr predicate, Token token) implements Statement {}

    public interface Expr extends Node {}
    public record Number(String value, Token token) implements Expr {}
    public record Name(String value, Token token) implements Expr {}
    public record Bool(boolean value, Token token) implements Expr {}
    public record Unary(String operator, Expr operand, Token token) implements Expr {}
    public record Binary(String operator, Expr left, Expr right, Token token) implements Expr {}
    public record Call(String name, List<Expr> arguments, Token token) implements Expr {}
    public record Access(Expr array, Expr index, Token token) implements Expr {}
    public record Update(Expr array, Expr index, Expr value, Token token) implements Expr {}
    public record Quantified(String kind, Variable variable, Expr body, Token token) implements Expr {}
}
