package ru.nsu.naboka;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.Assert.*;
import static ru.nsu.naboka.Ast.*;

public class ParserTest {
    private Parser.Result valid(String source) {
        Parser.Result result = new Parser(source).parse();
        assertTrue(result.errors().toString(), result.errors().isEmpty());
        return result;
    }

    private Statement body(String source) {
        Function function = (Function) valid("f() returns r: int { " + source + " }").module().declarations().get(0);
        return ((Block) function.body()).statements().get(0);
    }

    private void invalid(String source) {
        assertFalse(source, new Parser(source).parse().errors().isEmpty());
    }

    @Test
    public void matchesAstSnapshot() throws Exception {
        String source = Files.readString(Path.of("examples/minimal.funny"));
        String expected = Files.readString(Path.of("examples/minimal.ast")).strip();
        assertEquals(expected, AstPrinter.print(valid(source).module()));
    }

    @Test
    public void parsesExampleWithAllStatements() throws Exception {
        valid(Files.readString(Path.of("examples/positive.funny")));
    }

    @Test
    public void matchesDiagnosticSnapshot() throws Exception {
        String source = Files.readString(Path.of("examples/negative.funny"));
        String expected = Files.readString(Path.of("examples/negative.errors")).strip();
        assertEquals(expected, String.join("\n", new Parser(source).parse().errors()));
    }

    @Test
    public void parsesBareAndPrefixedFunctions() {
        assertEquals(2, valid("f() returns r: int {} function g() returns r: int {}").module().declarations().size());
    }

    @Test
    public void suppliesContractAndInvariantDefaults() {
        Function function = (Function) valid("f() returns r: int while (true) {}").module().declarations().get(0);
        assertTrue(((Bool) function.requires()).value());
        assertFalse(((Bool) function.ensures()).value());
        assertTrue(((Bool) ((While) function.body()).invariant()).value());
    }

    @Test
    public void parsesTypedAndInferredLocalsWithUnbracedBody() {
        Function function = (Function) valid("f(x: int[]) returns a: int, b: int[] uses i, j: int a = 1;")
                .module().declarations().get(0);
        assertNull(function.locals().get(0).type());
        assertEquals("int", function.locals().get(1).type());
        assertEquals(2, function.returns().size());
    }

    @Test
    public void preservesArithmeticPrecedenceAndAssociativity() {
        Assignment assignment = (Assignment) body("r = 10 - 3 - 2 * -4;");
        assertEquals("(- (- 10 3) (* 2 (- 4)))", AstPrinter.print(assignment.value()));
    }

    @Test
    public void parsesArithmeticGroupsInsidePredicates() {
        assertEquals("(assert (< (+ x 1) 5))", AstPrinter.print(body("assert (x + 1) < 5;")));
        assertEquals("(assert (< (+ x 1) 5))", AstPrinter.print(body("assert ((x + 1) < 5);")));
        body("assert (f(x) + 1) * 2 >= length(a);");
        body("assert f(x) + 1 > 0 and f(x);");
    }

    @Test
    public void preservesBooleanPrecedenceAndRightAssociativeImplication() {
        If statement = (If) body("if (not x > 0 and false or true -> false -> true) {} ");
        assertEquals("(-> (or (and (not (> x 0)) false) true) (-> false true))", AstPrinter.print(statement.condition()));
    }

    @Test
    public void associatesElseWithNearestIf() {
        If outer = (If) body("if (true) if (false) r = 1; else r = 2;");
        assertNull(outer.elseBranch());
        assertNotNull(((If) outer.thenBranch()).elseBranch());
    }

    @Test
    public void parsesFormulasWithoutSeparatorsAndNestedQuantifiers() {
        Parser.Result result = valid("p(x: int) => exists(i: int | forall(j: int | j >= i)) q() => true f() returns r: int ensures p(r) {}");
        assertEquals(3, result.module().declarations().size());
        assertTrue(result.module().declarations().get(0) instanceof Formula);
        valid("p() => true; f() requires p() returns r: int {}");
    }

    @Test
    public void parsesTupleAssignmentAndNestedArrayUpdates() {
        Assignment tuple = (Assignment) body("x, y = f(1, a[2]);");
        assertEquals(2, tuple.targets().size());
        assertTrue(tuple.value() instanceof Call);
        Assignment array = (Assignment) body("a[i][j] = 42;");
        assertEquals("a", AstPrinter.print(array.targets().get(0)));
        assertEquals("(set a i (set (index a i) j 42))", AstPrinter.print(array.value()));
        Assignment single = (Assignment) body("a[i] = 7;");
        assertEquals("(set a i 7)", AstPrinter.print(single.value()));
    }

    @Test
    public void tracksPositionsAcrossSkippedTriviaAndLineEndings() {
        Lexer.Result result = Lexer.scan("// one\r\n\tx\r y\n+");
        assertTrue(result.errors().isEmpty());
        assertEquals(new Token("IDENT", "x", 9, 2, 2), result.tokens().get(0));
        assertEquals("3:2", result.tokens().get(1).location());
        assertEquals("4:1", result.tokens().get(2).location());
    }

    @Test
    public void usesLongestMatchAndKeywordBoundaries() {
        Lexer.Result result = Lexer.scan("if iffy == = -> - => / // comment");
        assertEquals("[if, iffy, ==, =, ->, -, =>, /, <EOF>]", result.tokens().stream().map(Token::text).toList().toString());
        assertEquals("IDENT", result.tokens().get(1).kind());
    }

    @Test
    public void reportsLexicalErrorsAtTheirPositions() {
        invalid("f() returns r: int { r = 1 @; }");
        assertEquals("2:1", Lexer.scan("\n_é").errors().get(0).substring(0, 3));
        assertEquals(2, Lexer.scan("\n_é").errors().size());
        assertEquals(3, Lexer.scan("01").tokens().size());
    }

    @Test(timeout = 2000)
    public void rejectsEmptyInputExtraTokensAndWrongOperators() {
        for (String source : new String[]{"", " // empty", "}", "f() returns r: int {} ;",
                "f() returns r: int { r == 1; }", "f() returns r: int { r = 01; }",
                "f() returns r: int { r = ; }", "f() returns r: int { r = 1 +; }"}) invalid(source);
    }

    @Test(timeout = 2000)
    public void rejectsUnclosedBracketsAndMissingTypes() {
        for (String source : new String[]{"f(x: int returns r: int {}", "f() returns r: int {",
                "f() returns r: int { r = a[1; }", "f() returns r: int { r = (1 + 2; }",
                "f(x) returns r: int {}", "f() returns r {}"}) invalid(source);
    }

    @Test
    public void rejectsWrongExpressionCategories() {
        for (String statement : new String[]{"r = true;", "assert x;", "if (f()) {}", "assert true -> false;",
                "if (forall(i: int | true)) {}", "r = 1 + false;", "r = f(true);", "r = a[true];",
                "assert 1 < 2 < 3;", "x, y = 1 + 2;", "a[i], b = f();", "assert length(a);"}) {
            invalid("f() returns r: int { " + statement + " }");
        }
    }

    @Test(timeout = 2000)
    public void recoversToLaterStatementsAndDeclarations() {
        Parser.Result result = new Parser("f() returns r: int { r = ; assert true; } g() returns r: int {}").parse();
        assertFalse(result.errors().isEmpty());
        assertEquals(2, result.module().declarations().size());
        assertEquals(1, ((Block) ((Function) result.module().declarations().get(0)).body()).statements().size());
        result = new Parser("f() returns r: int { r = 1; g() returns r: int {}").parse();
        assertFalse(result.errors().isEmpty());
        assertEquals("g", ((Function) result.module().declarations().get(0)).name());
    }

    @Test(timeout = 5000)
    public void finishesOnRandomMalformedInputs() {
        String[] words = {"f", "function", "returns", "int", "assert", "if", "else", "while", "not", "forall",
                "true", "1", "(", ")", "{", "}", "[", "]", ";", ",", "=", "+", "|", "=>", "->", ":"};
        Random random = new Random(42);
        for (int n = 0; n < 500; n++) {
            StringBuilder source = new StringBuilder();
            for (int i = 0; i < 40; i++) source.append(words[random.nextInt(words.length)]).append(' ');
            new Parser(source.toString()).parse();
        }
    }
}
