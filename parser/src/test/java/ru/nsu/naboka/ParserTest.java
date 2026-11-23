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

    // AST минимальной программы должен точно совпадать с сохранённым эталоном.
    @Test
    public void matchesAstSnapshot() throws Exception {
        String source = Files.readString(Path.of("examples/minimal.funny"));
        String expected = Files.readString(Path.of("examples/minimal.ast")).strip();
        assertEquals(expected, AstPrinter.print(valid(source).module()));
    }

    // Пример со всеми видами операторов должен разбираться без синтаксических ошибок.
    @Test
    public void parsesExampleWithAllStatements() throws Exception {
        valid(Files.readString(Path.of("examples/positive.funny")));
    }

    // Сообщения об ошибках отрицательного примера должны совпадать с эталоном, включая позиции.
    @Test
    public void matchesDiagnosticSnapshot() throws Exception {
        String source = Files.readString(Path.of("examples/negative.funny"));
        String expected = Files.readString(Path.of("examples/negative.errors")).strip();
        assertEquals(expected, String.join("\n", new Parser(source).parse().errors()));
    }

    // Допустимы оба варианта определения функции: с ключевым словом function и без него.
    @Test
    public void parsesBareAndPrefixedFunctions() {
        assertEquals(2, valid("f() returns r: int {} function g() returns r: int {}").module().declarations().size());
    }

    // При пропуске контрактов парсер подставляет requires=true, ensures=false и invariant=true.
    @Test
    public void suppliesContractAndInvariantDefaults() {
        Function function = (Function) valid("f() returns r: int while (true) {}").module().declarations().get(0);
        assertTrue(((Bool) function.requires()).value());
        assertFalse(((Bool) function.ensures()).value());
        assertTrue(((Bool) ((While) function.body()).invariant()).value());
    }

    // Разбираются локальные с аннотацией и без неё, несколько результатов и тело без фигурных скобок.
    @Test
    public void parsesTypedAndInferredLocalsWithUnbracedBody() {
        Function function = (Function) valid("f(x: int[]) returns a: int, b: int[] uses i, j: int a = 1;")
                .module().declarations().get(0);
        assertNull(function.locals().get(0).type());
        assertEquals("int", function.locals().get(1).type());
        assertEquals(2, function.returns().size());
    }

    // Умножение и унарный минус связывают сильнее вычитания; цепочка вычитаний группируется слева.
    @Test
    public void preservesArithmeticPrecedenceAndAssociativity() {
        Assignment assignment = (Assignment) body("r = 10 - 3 - 2 * -4;");
        assertEquals("(- (- 10 3) (* 2 (- 4)))", AstPrinter.print(assignment.value()));
    }

    // Скобки вокруг арифметики и сравнений внутри предиката должны давать правильное дерево.
    @Test
    public void parsesArithmeticGroupsInsidePredicates() {
        assertEquals("(assert (< (+ x 1) 5))", AstPrinter.print(body("assert (x + 1) < 5;")));
        assertEquals("(assert (< (+ x 1) 5))", AstPrinter.print(body("assert ((x + 1) < 5);")));
        body("assert (f(x) + 1) * 2 >= length(a);");
        body("assert f(x) + 1 > 0 and f(x);");
    }

    // Проверяются приоритеты not, and, or и группировка цепочки импликаций справа.
    @Test
    public void preservesBooleanPrecedenceAndRightAssociativeImplication() {
        If statement = (If) body("if (not x > 0 and false or true -> false -> true) {} ");
        assertEquals("(-> (or (and (not (> x 0)) false) true) (-> false true))", AstPrinter.print(statement.condition()));
    }

    // Без фигурных скобок else должен относиться к ближайшему незавершённому if.
    @Test
    public void associatesElseWithNearestIf() {
        If outer = (If) body("if (true) if (false) r = 1; else r = 2;");
        assertNull(outer.elseBranch());
        assertNotNull(((If) outer.thenBranch()).elseBranch());
    }

    // Формулы разбираются с необязательным разделителем, вложенными кванторами и вызовами в контрактах.
    @Test
    public void parsesFormulasWithoutSeparatorsAndNestedQuantifiers() {
        Parser.Result result = valid("p(x: int) => exists(i: int | forall(j: int | j >= i)) q() => true f() returns r: int ensures p(r) {}");
        assertEquals(3, result.module().declarations().size());
        assertTrue(result.module().declarations().get(0) instanceof Formula);
        valid("p() => true; f() requires p() returns r: int {}");
    }

    // Групповое присваивание сохраняет цели, а запись в массив преобразуется во вложенные узлы set.
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

    // Позиции токенов учитывают пропущенные комментарии, табуляцию и переводы строк CR, LF, CRLF.
    @Test
    public void tracksPositionsAcrossSkippedTriviaAndLineEndings() {
        Lexer.Result result = Lexer.scan("// one\r\n\tx\r y\n+");
        assertTrue(result.errors().isEmpty());
        assertEquals(new Token("IDENT", "x", 9, 2, 2), result.tokens().get(0));
        assertEquals("3:2", result.tokens().get(1).location());
        assertEquals("4:1", result.tokens().get(2).location());
    }

    // Лексер выбирает самый длинный токен и отличает ключевое слово if от идентификатора iffy.
    @Test
    public void usesLongestMatchAndKeywordBoundaries() {
        Lexer.Result result = Lexer.scan("if iffy == = -> - => / // comment");
        assertEquals("[if, iffy, ==, =, ->, -, =>, /, <EOF>]", result.tokens().stream().map(Token::text).toList().toString());
        assertEquals("IDENT", result.tokens().get(1).kind());
    }

    // Недопустимые символы дают ошибки с позициями; число 01 разбивается на два числовых токена.
    @Test
    public void reportsLexicalErrorsAtTheirPositions() {
        invalid("f() returns r: int { r = 1 @; }");
        assertEquals("2:1", Lexer.scan("\n_é").errors().get(0).substring(0, 3));
        assertEquals(2, Lexer.scan("\n_é").errors().size());
        assertEquals(3, Lexer.scan("01").tokens().size());
    }

    // Пустая программа, лишние токены и ошибочные присваивания отклоняются без зависания.
    @Test(timeout = 2000)
    public void rejectsEmptyInputExtraTokensAndWrongOperators() {
        for (String source : new String[]{"", " // empty", "}", "f() returns r: int {} ;",
                "f() returns r: int { r == 1; }", "f() returns r: int { r = 01; }",
                "f() returns r: int { r = ; }", "f() returns r: int { r = 1 +; }"}) invalid(source);
    }

    // Незакрытые скобки и отсутствие обязательных аннотаций параметров или результатов дают ошибки.
    @Test(timeout = 2000)
    public void rejectsUnclosedBracketsAndMissingTypes() {
        for (String source : new String[]{"f(x: int returns r: int {}", "f() returns r: int {",
                "f() returns r: int { r = a[1; }", "f() returns r: int { r = (1 + 2; }",
                "f(x) returns r: int {}", "f() returns r {}"}) invalid(source);
    }

    // Парсер отвергает выражения, запрещённые в данной синтаксической категории грамматикой HW2.
    @Test
    public void rejectsWrongExpressionCategories() {
        for (String statement : new String[]{"r = true;", "assert x;", "if (f()) {}", "assert true -> false;",
                "if (forall(i: int | true)) {}", "r = 1 + false;", "r = f(true);", "r = a[true];",
                "assert 1 < 2 < 3;", "x, y = 1 + 2;", "a[i], b = f();", "assert length(a);"}) {
            invalid("f() returns r: int { " + statement + " }");
        }
    }

    // После синтаксической ошибки разбор продолжается со следующих операторов и определений.
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

    // На 500 случайных последовательностях токенов парсер завершается без исключений и зависания.
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
