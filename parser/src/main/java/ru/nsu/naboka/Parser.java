package ru.nsu.naboka;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static ru.nsu.naboka.Ast.*;

public final class Parser {
    private static final String[][] OPERATORS = {
            {"->"}, {"or"}, {"and"}, {"==", "!=", "<", "<=", ">", ">="}, {"+", "-"}, {"*", "/"}
    };

    public record Result(Ast.Module module, List<String> errors) {}

    private final List<Token> tokens;
    private final List<String> errors;
    private int cursor;

    public Parser(String source) {
        Lexer.Result result = Lexer.scan(source);
        tokens = result.tokens();
        errors = new ArrayList<>(result.errors());
    }

    public Result parse() {
        List<Node> declarations = new ArrayList<>();
        Token start = peek();
        if (atEnd()) errors.add(start.location() + ": ожидалось определение функции или формулы");
        while (!atEnd()) {
            int before = cursor;
            try {
                declarations.add(declaration());
            } catch (ParseError error) {
                errors.add(error.getMessage());
                if (cursor == before) advance();
                while (!atEnd() && !declarationStart()) advance();
            }
        }
        return new Result(new Ast.Module(List.copyOf(declarations), start), List.copyOf(errors));
    }

    private Node declaration() {
        boolean functionKeyword = match("function");
        Token name = identifier();
        expect("(");
        List<Variable> parameters = new ArrayList<>();
        if (!check(")")) {
            do {
                parameters.add(variable(true));
            } while (match(","));
        }
        expect(")");
        if (!functionKeyword && match("=>")) {
            Expr body = predicate(false);
            match(";");
            return new Formula(name.text(), List.copyOf(parameters), body, name);
        }
        Expr requires = match("requires") ? predicate(false) : new Bool(true, name);
        expect("returns");
        List<Variable> returns = new ArrayList<>();
        do {
            returns.add(variable(true));
        } while (match(","));
        Expr ensures = match("ensures") ? predicate(false) : new Bool(false, name);
        List<Variable> locals = new ArrayList<>();
        if (match("uses")) {
            do {
                locals.add(variable(false));
            } while (match(","));
        }
        Statement body = statement();
        return new Function(name.text(), List.copyOf(parameters), requires, List.copyOf(returns), ensures,
                List.copyOf(locals), body, name);
    }

    private Variable variable(boolean typed) {
        Token name = identifier();
        String type = null;
        if (typed || check(":")) {
            expect(":");
            expect("int");
            type = "int";
            if (match("[")) {
                expect("]");
                type = "int[]";
            }
        }
        return new Variable(name.text(), type, name);
    }

    private Statement statement() {
        Token start = peek();
        if (match("{")) {
            List<Statement> statements = new ArrayList<>();
            while (!atEnd() && !check("}")) {
                if (declarationStart()) throw failure("ожидалось '}' перед следующим определением");
                int before = cursor;
                try {
                    statements.add(statement());
                } catch (ParseError error) {
                    errors.add(error.getMessage());
                    synchronizeStatement(before);
                }
            }
            expect("}");
            return new Block(List.copyOf(statements), start);
        }
        if (match("if")) {
            expect("(");
            Expr condition = predicate(true);
            expect(")");
            Statement thenBranch = statement();
            Statement elseBranch = match("else") ? statement() : null;
            return new If(condition, thenBranch, elseBranch, start);
        }
        if (match("while")) {
            expect("(");
            Expr condition = predicate(true);
            expect(")");
            Expr invariant = match("invariant") ? predicate(false) : new Bool(true, start);
            return new While(condition, invariant, statement(), start);
        }
        if (match("assert") || match("assume")) {
            Expr value = predicate(false);
            expect(";");
            return new Assertion(start.text(), value, start);
        }
        Token name = identifier();
        List<Expr> targets = new ArrayList<>();
        Expr target = new Name(name.text(), name);
        while (match("[")) {
            Expr index = arithmetic();
            expect("]");
            target = new Access(target, index, name);
        }
        targets.add(target);
        if (match(",")) {
            if (!(target instanceof Name)) throw failure("в групповом присваивании нужны имена переменных");
            do {
                Token next = identifier();
                targets.add(new Name(next.text(), next));
            } while (match(","));
        }
        expect("=");
        Expr value = arithmetic();
        if (targets.size() > 1 && !(value instanceof Call)) {
            throw failure("в правой части группового присваивания нужен вызов функции");
        }
        expect(";");
        if (target instanceof Access access) {
            value = new Update(access.array(), access.index(), value, access.token());
            target = access.array();
            while (target instanceof Access parent) {
                value = new Update(parent.array(), parent.index(), value, parent.token());
                target = parent.array();
            }
            targets.set(0, target);
        }
        return new Assignment(List.copyOf(targets), value, start);
    }

    private Expr arithmetic() {
        Expr result = expression(0);
        if (!isArithmetic(result)) throw invalid(result, "ожидалось арифметическое выражение");
        return result;
    }

    private Expr predicate(boolean condition) {
        Expr result = expression(0);
        if (!isPredicate(result, condition)) {
            throw invalid(result, condition ? "ожидалось условие" : "ожидался предикат");
        }
        return result;
    }

    private Expr expression(int level) {
        if (level == OPERATORS.length) return unary();
        if (level == 3 && match("not")) {
            Token operator = previous();
            return new Unary("not", expression(level), operator);
        }
        Expr result = expression(level + 1);
        while (Arrays.asList(OPERATORS[level]).contains(peek().text())) {
            Token operator = advance();
            Expr right = expression(level == 0 ? level : level + 1);
            result = new Binary(operator.text(), result, right, operator);
            if (level == 0 || level == 3) break;
        }
        return result;
    }

    private Expr unary() {
        if (match("-")) {
            Token operator = previous();
            return new Unary("-", unary(), operator);
        }
        Token start = peek();
        if (match("(")) {
            Expr result = expression(0);
            expect(")");
            return result;
        }
        if (match("true") || match("false")) return new Bool(start.text().equals("true"), start);
        if (match("forall") || match("exists")) {
            expect("(");
            Variable variable = variable(true);
            expect("|");
            Expr body = predicate(false);
            expect(")");
            return new Quantified(start.text(), variable, body, start);
        }
        if (start.kind().equals("INT")) {
            advance();
            return new Ast.Number(start.text(), start);
        }
        boolean length = match("length");
        Token name = length ? start : identifier();
        if (check("(") || length) {
            expect("(");
            List<Expr> arguments = new ArrayList<>();
            if (!check(")")) {
                do {
                    arguments.add(arithmetic());
                } while (match(","));
            }
            expect(")");
            return new Call(name.text(), List.copyOf(arguments), name);
        }
        Expr result = new Name(name.text(), name);
        while (match("[")) {
            Expr index = arithmetic();
            expect("]");
            result = new Access(result, index, name);
        }
        return result;
    }

    private boolean isArithmetic(Expr expr) {
        if (expr instanceof Ast.Number || expr instanceof Name || expr instanceof Call || expr instanceof Access) return true;
        if (expr instanceof Unary unary) return unary.operator().equals("-") && isArithmetic(unary.operand());
        if (expr instanceof Binary binary) {
            return List.of("+", "-", "*", "/").contains(binary.operator())
                    && isArithmetic(binary.left()) && isArithmetic(binary.right());
        }
        return false;
    }

    private boolean isPredicate(Expr expr, boolean condition) {
        if (expr instanceof Bool) return true;
        if (expr instanceof Call call) return !condition && !call.name().equals("length");
        if (expr instanceof Quantified) return !condition;
        if (expr instanceof Unary unary) return unary.operator().equals("not") && isPredicate(unary.operand(), condition);
        if (expr instanceof Binary binary) {
            if (Arrays.asList(OPERATORS[3]).contains(binary.operator())) {
                return isArithmetic(binary.left()) && isArithmetic(binary.right());
            }
            boolean logical = binary.operator().equals("and") || binary.operator().equals("or")
                    || condition && binary.operator().equals("->");
            return logical && isPredicate(binary.left(), condition) && isPredicate(binary.right(), condition);
        }
        return false;
    }

    private void synchronizeStatement(int before) {
        if (cursor == before && !atEnd() && !check("}")) advance();
        while (!atEnd() && !check("}") && !declarationStart()) {
            if (match(";")) return;
            if (List.of("if", "while", "assert", "assume", "{").contains(peek().text())) return;
            advance();
        }
    }

    private boolean declarationStart() {
        return check("function") || peek().kind().equals("IDENT") && cursor + 1 < tokens.size()
                && tokens.get(cursor + 1).text().equals("(");
    }

    private Token identifier() {
        if (!peek().kind().equals("IDENT")) throw failure("ожидался идентификатор");
        return advance();
    }

    private Token expect(String text) {
        if (!check(text)) throw failure("ожидалось '" + text + "'");
        return advance();
    }

    private boolean match(String text) {
        if (!check(text)) return false;
        advance();
        return true;
    }

    private boolean check(String text) {
        return peek().text().equals(text);
    }

    private boolean atEnd() {
        return peek().kind().equals("EOF");
    }

    private Token peek() {
        return tokens.get(cursor);
    }

    private Token previous() {
        return tokens.get(cursor - 1);
    }

    private Token advance() {
        Token result = peek();
        if (!atEnd()) cursor++;
        return result;
    }

    private ParseError failure(String message) {
        return new ParseError(peek().location() + ": " + message + ", получено '" + peek().text() + "'");
    }

    private ParseError invalid(Expr expr, String message) {
        return new ParseError(expr.token().location() + ": " + message);
    }

    private static final class ParseError extends RuntimeException {
        ParseError(String message) {
            super(message);
        }
    }
}
