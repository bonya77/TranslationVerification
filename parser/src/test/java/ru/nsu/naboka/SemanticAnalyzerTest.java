package ru.nsu.naboka;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;
import static ru.nsu.naboka.Ast.*;
import static ru.nsu.naboka.Types.Basic.*;

public class SemanticAnalyzerTest {
    private SemanticAnalyzer.Result check(String source) {
        Parser.Result parsed = new Parser(source).parse();
        assertTrue(parsed.errors().toString(), parsed.errors().isEmpty());
        return SemanticAnalyzer.analyze(parsed.module());
    }

    private SemanticAnalyzer.Result valid(String source) {
        SemanticAnalyzer.Result result = check(source);
        assertTrue(result.diagnostics().toString(), result.diagnostics().isEmpty());
        return result;
    }

    private List<String> codes(SemanticAnalyzer.Result result) {
        return result.diagnostics().stream().map(SemanticAnalyzer.Diagnostic::code).toList();
    }

    private void hasError(String source, String code) {
        SemanticAnalyzer.Result result = check(source);
        assertTrue(result.diagnostics().toString(), codes(result).contains(code));
    }

    private Types.Type localType(SemanticAnalyzer.Result result, String name) {
        return result.symbols().stream().filter(symbol -> symbol.kind() == SemanticAnalyzer.Kind.LOCAL
                && symbol.name().equals(name)).findFirst().orElseThrow().type();
    }

    // Корректный пример со всеми поддерживаемыми конструкциями проходит семантическую проверку.
    @Test
    public void acceptsAllSupportedConstructs() throws Exception {
        valid(Files.readString(Path.of("examples/types-positive.funny")));
    }

    // Типы, символы и привязки в аннотированном AST должны точно совпадать с сохранённым эталоном.
    @Test
    public void matchesAnnotatedAstSnapshot() throws Exception {
        SemanticAnalyzer.Result result = valid(Files.readString(Path.of("examples/minimal.funny")));
        assertEquals(Files.readString(Path.of("examples/minimal.typed.ast")).strip(), AstPrinter.print(result));
    }

    // Все ошибки отрицательного примера сравниваются с эталоном; у каждой есть позиция и описание.
    @Test
    public void matchesAllDiagnosticsWithPositions() throws Exception {
        SemanticAnalyzer.Result result = check(Files.readString(Path.of("examples/types-negative.funny")));
        assertEquals(Files.readString(Path.of("examples/types-negative.errors")).strip(),
                String.join("\n", result.diagnostics().stream().map(Object::toString).toList()));
        assertTrue(result.diagnostics().size() > 5);
        result.diagnostics().forEach(error -> {
            assertTrue(error.token().line() > 0);
            assertTrue(error.token().column() > 0);
            assertFalse(error.message().isBlank());
        });
    }

    // Предварительный сбор сигнатур разрешает вызовы вперёд и взаимную рекурсию функций и формул.
    @Test
    public void supportsForwardCallsAndMutualRecursion() {
        valid("f(x: int) returns r: int { if (x > 0) r = g(x - 1); else r = 0; } "
                + "g(x: int) returns r: int { r = f(x); }");
        valid("p(x: int) => q(x); q(x: int) => p(x);");
    }

    // Функции и формулы делят глобальную область: повторяющиеся имена дают DUPLICATE_NAME.
    @Test
    public void rejectsDuplicateGlobalsIncludingFunctionFormulaCollision() {
        hasError("f() returns r: int { r = 0; } f() => true;", "DUPLICATE_NAME");
        hasError("p() => true; p() => false;", "DUPLICATE_NAME");
    }

    // Внутри функции имена параметров, результатов и локальных переменных не должны повторяться.
    @Test
    public void rejectsDuplicateParametersReturnsAndLocals() {
        for (String source : new String[]{
                "f(x: int, x: int) returns r: int { r = 0; }",
                "f(x: int) returns x: int { x = 0; }",
                "f() returns r: int, r: int { r = 0; }",
                "f() returns r: int uses r: int { r = 0; }",
                "f() returns r: int uses x, x: int { r = 0; }"}) {
            hasError(source, "DUPLICATE_NAME");
        }
    }

    // Чужие локальные не видны; requires не видит результаты, ensures видит результаты, но не локальные.
    @Test
    public void isolatesFunctionsAndContractScopes() {
        hasError("f() returns r: int uses x: int { x = 1; r = x; } "
                + "g() returns r: int { r = x; }", "UNKNOWN_NAME");
        hasError("f() requires r > 0 returns r: int { r = 1; }", "UNKNOWN_NAME");
        hasError("f() returns r: int ensures x > 0 uses x: int { x = 1; r = x; }", "UNKNOWN_NAME");
        valid("f(x: int) requires x > 0 returns r: int ensures r >= x { r = x; }");
    }

    // Квантор создаёт отдельный символ и может затенить параметр; его переменная не видна снаружи.
    @Test
    public void scopesQuantifiersAndAllowsShadowing() {
        SemanticAnalyzer.Result result = valid("p(x: int) => exists(x: int | x > 0) and x > 0;");
        List<SemanticAnalyzer.Symbol> xs = result.symbols().stream().filter(s -> s.name().equals("x")).toList();
        assertEquals(2, xs.size());
        assertNotEquals(xs.get(0).id(), xs.get(1).id());
        Formula formula = (Formula) result.module().declarations().get(0);
        Binary and = (Binary) formula.predicate();
        Quantified quantified = (Quantified) and.left();
        Name inner = (Name) ((Binary) quantified.body()).left();
        Name outer = (Name) ((Binary) and.right()).left();
        assertEquals(xs.get(1), result.bindings().get(inner));
        assertEquals(xs.get(0), result.bindings().get(outer));
        hasError("p() => exists(i: int | i > 0) and i > 0;", "UNKNOWN_NAME");
        hasError("p() => exists(i: int | i > 0); q() => i > 0;", "UNKNOWN_NAME");
    }

    // Необъявленное имя даёт UNKNOWN_NAME при чтении, записи и вызове.
    @Test
    public void rejectsUnknownReadsTargetsAndCalls() {
        for (String statement : new String[]{"r = missing;", "missing = 1; r = 0;", "r = missing(1);"}) {
            hasError("f() returns r: int { " + statement + " }", "UNKNOWN_NAME");
        }
    }

    // Функцию нельзя использовать как переменную, а переменную — вызывать; учитывается затенение имён.
    @Test
    public void separatesVariablesAndCallables() {
        hasError("g() returns r: int { r = 1; } f() returns r: int { r = g; }", "NOT_VARIABLE");
        hasError("g() => true; f() returns r: int { g = 1; r = 0; }", "NOT_VARIABLE");
        hasError("f(x: int) returns r: int { r = x(); }", "NOT_CALLABLE");
        hasError("g() returns r: int { r = 1; } f(g: int) returns r: int { r = g(); }", "NOT_CALLABLE");
    }

    // Локальные и результаты нельзя читать до присваивания, даже если их тип указан явно.
    @Test
    public void requiresDefiniteAssignmentIncludingTypedLocalsAndResults() {
        hasError("f() returns r: int uses x: int { r = x; }", "USE_BEFORE_ASSIGNMENT");
        hasError("f() returns r: int { r = r + 1; }", "USE_BEFORE_ASSIGNMENT");
        hasError("f() returns r: int uses x { x = x + 1; r = 0; }", "CANNOT_INFER");
        hasError("f() returns r: int {}", "UNASSIGNED_RETURN");
        valid("f() returns r: int { r = 0; r = r + 1; }");
    }

    // Каждый результат должен получить значение своего типа на всех путях к концу функции.
    @Test
    public void checksReturnTypesAndAssignmentOnEveryPath() {
        // Присваивание массива в результат int ошибочно и не считается корректной инициализацией.
        assertEquals(List.of("TYPE_MISMATCH", "UNASSIGNED_RETURN"),
                codes(check("f(a: int[]) returns r: int { r = a; }")));
        // Без else есть путь, на котором результат не присваивается.
        assertEquals(List.of("UNASSIGNED_RETURN"),
                codes(check("f(x: int) returns r: int { if (x > 0) r = 1; }")));
        // Присваивания в обеих ветвях гарантируют наличие результата после if.
        valid("f(x: int) returns r: int { if (x > 0) r = 1; else r = 0; }");
        // При нескольких результатах нужно присвоить каждый из них.
        assertEquals(List.of("UNASSIGNED_RETURN"),
                codes(check("f() returns a: int, b: int { a = 1; }")));
        // Инициализация до цикла достаточна, даже если цикл не выполнится.
        valid("f() returns r: int { r = 0; while (r < 3) r = r + 1; }");
    }

    // После if остаётся пересечение assigned ветвей; тело while не гарантирует присваивания после цикла.
    @Test
    public void checksBothBranchesAndDoesNotAssumeLoopsExecute() {
        valid("f(x: int) returns r: int uses y { if (x > 0) y = 1; else y = 2; r = y; }");
        hasError("f(x: int) returns r: int uses y { if (x > 0) y = 1; r = y; }", "USE_BEFORE_ASSIGNMENT");
        hasError("f(x: int) returns r: int uses y { if (x > 0) y = 1; else r = y; r = 0; }",
                "USE_BEFORE_ASSIGNMENT");
        hasError("f() returns r: int uses y { while (true) y = 1; r = y; }", "USE_BEFORE_ASSIGNMENT");
        hasError("f() returns r: int { while (true) r = 1; }", "UNASSIGNED_RETURN");
        valid("f() returns r: int uses y { y = 0; while (y < 3) y = y + 1; r = y; }");
    }

    // Параметры неизменяемы, включая элементы массива; копию массива в результате изменять можно.
    @Test
    public void checksImmutableParametersIncludingArrayUpdates() {
        hasError("f(x: int) returns r: int { x = 2; r = 0; }", "IMMUTABLE_PARAMETER");
        hasError("f(a: int[]) returns r: int { a[0] = 2; r = 0; }", "IMMUTABLE_PARAMETER");
        valid("f(a: int[]) returns b: int[] { b = a; b[0] = 2; }");
    }

    // По корректным присваиваниям выводятся типы int, int[] и bool; UNKNOWN не остаётся.
    @Test
    public void infersIntArrayAndBooleanLocals() {
        SemanticAnalyzer.Result result = valid("p() => true; f(a: int[]) returns r: int uses x, b, flag "
                + "{ x = 1; b = a; flag = p(); assert flag == p(); r = x + length(b); }");
        assertEquals(INT, localType(result, "x"));
        assertEquals(INT_ARRAY, localType(result, "b"));
        assertEquals(BOOL, localType(result, "flag"));
        for (SemanticAnalyzer.Symbol symbol : result.symbols()) assertNotEquals(UNKNOWN, symbol.type());
    }

    // Тип закрепляет первое корректное присваивание; противоречия и невозможность вывода дают ошибки.
    @Test
    public void infersFromFirstCorrectAssignmentAndRejectsContradictions() {
        hasError("f(a: int[]) returns r: int uses x { x = 1; x = a; r = 0; }", "TYPE_MISMATCH");
        // Ошибка length(1) не закрепляет тип x: следующее корректное присваивание выводит int[].
        SemanticAnalyzer.Result result = check("f(a: int[]) returns r: int uses x { x = length(1); x = a; r = length(x); }");
        assertEquals(List.of("TYPE_MISMATCH"), codes(result));
        assertEquals(INT_ARRAY, localType(result, "x"));
        hasError("f(a: int[]) returns r: int uses x { if (true) x = 1; else x = a; r = 0; }", "TYPE_MISMATCH");
        hasError("f() returns r: int uses x { r = 0; }", "CANNOT_INFER");
        hasError("f() returns r: int uses x, y { x = y; y = 1; r = 0; }", "CANNOT_INFER");
    }

    // Арифметика и сравнения порядка требуют int; равенство допускает одинаковые типы, включая int[].
    @Test
    public void checksArithmeticComparisonAndAssignmentTypes() {
        for (String statement : new String[]{"r = a + 1;", "r = -a;", "assert a < 1;", "r = a;"}) {
            hasError("f(a: int[]) returns r: int { " + statement + " }", "TYPE_MISMATCH");
        }
        valid("f(a: int[], b: int[]) returns r: int { assert a == b; r = 1; }");
        hasError("f(a: int[]) returns r: int { assert a == 1; r = 1; }", "TYPE_MISMATCH");
    }

    // Предикаты требуют bool; результат формулы нельзя присвоить в int или использовать в арифметике.
    @Test
    public void checksBooleanContextsAndFormulaResults() {
        hasError("g() returns r: int { r = 1; } f() requires g() returns r: int { r = 0; }", "TYPE_MISMATCH");
        hasError("g() returns r: int { r = 1; } p() => not g();", "TYPE_MISMATCH");
        hasError("p() => true; f() returns r: int { r = p(); }", "TYPE_MISMATCH");
        hasError("p() => true; f() returns r: int { r = p() + 1; }", "TYPE_MISMATCH");
        valid("p(x: int) => x > 0; f() returns r: int { assert p(1) and not p(0); r = 0; }");
    }

    // Проверяются число и типы аргументов функций и формул; ошибки ищутся даже в лишних аргументах.
    @Test
    public void checksCallSignaturesAndVisitsEveryArgument() {
        String prefix = "g(a: int, b: int[]) returns r: int { r = a; } ";
        hasError(prefix + "f() returns r: int { r = g(1); }", "ARGUMENT_COUNT");
        hasError(prefix + "f() returns r: int { r = g(1, 2); }", "TYPE_MISMATCH");
        SemanticAnalyzer.Result result = check(prefix + "f() returns r: int { r = g(1, 2, missing); }");
        assertTrue(codes(result).containsAll(List.of("UNKNOWN_NAME", "ARGUMENT_COUNT", "TYPE_MISMATCH")));
        hasError("p(x: int) => x > 0; f() requires p() returns r: int { r = 0; }", "ARGUMENT_COUNT");
    }

    // Встроенная length принимает ровно один аргумент int[] и возвращает int.
    @Test
    public void checksLengthSignature() {
        hasError("f() returns r: int { r = length(); }", "ARGUMENT_COUNT");
        hasError("f() returns r: int { r = length(1); }", "TYPE_MISMATCH");
        valid("f(a: int[]) returns r: int { r = length(a); }");
    }

    // Индексировать можно только int[] целым индексом; элемент должен быть int, вложенные массивы запрещены.
    @Test
    public void checksArrayIndicesElementsAndDimensions() {
        for (String statement : new String[]{"r = a[a];", "r = n[0];", "b = a; b[0] = a; r = 0;",
                "b = a; b[a] = 1; r = 0;", "b = a; b[0][0] = 1; r = 0;"}) {
            hasError("f(a: int[], n: int) returns r: int uses b: int[] { " + statement + " }", "TYPE_MISMATCH");
        }
    }

    // При нескольких результатах проверяются число целей, их типы и повторы; типы целей выводятся по кортежу.
    @Test
    public void checksMultipleResultsAndInfersTupleTargets() {
        String prefix = "pair(a: int[]) returns n: int, b: int[] { n = length(a); b = a; } ";
        SemanticAnalyzer.Result result = valid(prefix + "f(a: int[]) returns r: int uses x, y { x, y = pair(a); r = x + length(y); }");
        assertEquals(INT, localType(result, "x"));
        assertEquals(INT_ARRAY, localType(result, "y"));
        hasError(prefix + "f(a: int[]) returns r: int { r = pair(a); }", "RESULT_COUNT");
        hasError(prefix + "f(a: int[]) returns r: int uses x: int, y: int { x, y = pair(a); r = 0; }", "TYPE_MISMATCH");
        hasError(prefix + "f(a: int[]) returns r: int uses x: int { x, x = pair(a); r = 0; }", "DUPLICATE_TARGET");
        hasError(prefix + "f(a: int[]) returns r: int { r = pair(a) + 1; }", "TYPE_MISMATCH");
        hasError("g() returns r: int { r = 1; } f() returns r: int uses x, y { x, y = g(); r = 0; }", "RESULT_COUNT");
    }

    // Вся правая часть проверяется до записи в цели: x, y = pair(x) не инициализирует аргумент x заранее.
    @Test
    public void keepsParallelAssignmentReadsBeforeWrites() {
        hasError("pair(x: int) returns a: int, b: int { a = x; b = x; } "
                + "f() returns r: int uses x, y { x, y = pair(x); r = 0; }", "USE_BEFORE_ASSIGNMENT");
    }

    // Каждое выражение получает конкретный тип, привязки ведут к символам, исходный AST HW2 сохраняется.
    @Test
    public void annotatesAllExpressionNodesAndPreservesHw2Ast() throws Exception {
        SemanticAnalyzer.Result result = valid(Files.readString(Path.of("examples/types-positive.funny")));
        assertAnnotated(result.module(), result);
        for (var annotation : result.types().entrySet()) {
            assertNotEquals(ERROR, annotation.getValue());
            assertNotEquals(UNKNOWN, annotation.getValue());
        }
        for (var binding : result.bindings().entrySet()) {
            assertTrue(result.symbols().contains(binding.getValue()));
            if (binding.getKey() instanceof Name || binding.getKey() instanceof Call) {
                assertTrue(result.types().containsKey(binding.getKey()));
            }
        }
        assertEquals(Files.readString(Path.of("examples/minimal.ast")).strip(),
                AstPrinter.print(valid(Files.readString(Path.of("examples/minimal.funny"))).module()));
        assertTrue(AstPrinter.print(result).contains("(tuple int int[])"));
    }

    private void assertAnnotated(Node node, SemanticAnalyzer.Result result) throws Exception {
        if (node instanceof Expr expression) assertTrue(result.types().containsKey(expression));
        for (var component : node.getClass().getRecordComponents()) {
            Object value = component.getAccessor().invoke(node);
            if (value instanceof Node child) assertAnnotated(child, result);
            else if (value instanceof List<?> list) {
                for (Object item : list) if (item instanceof Node child) assertAnnotated(child, result);
            }
        }
    }

    // Унификация уточняет UNKNOWN, отвергает несовместимые типы и поэлементно согласует сигнатуры и кортежи.
    @Test
    public void unifiesSimpleTypesTuplesAndSignaturesStructurally() {
        assertEquals(INT, Types.unify(UNKNOWN, INT));
        assertEquals(INT_ARRAY, Types.unify(INT_ARRAY, UNKNOWN));
        assertEquals(ERROR, Types.unify(INT, BOOL));
        assertEquals(ERROR, Types.unify(ERROR, UNKNOWN));
        Types.Function signature = new Types.Function(List.of(UNKNOWN, INT_ARRAY), List.of(INT));
        Types.Function concrete = new Types.Function(List.of(INT, INT_ARRAY), List.of(INT));
        assertEquals(concrete, Types.unify(signature, concrete));
        assertEquals(ERROR, Types.unify(signature, new Types.Function(List.of(INT), List.of(INT))));
        assertEquals(ERROR, Types.unify(concrete, new Types.Function(List.of(INT, BOOL), List.of(INT))));
        assertEquals(ERROR, Types.unify(concrete, new Types.Function(List.of(INT, INT_ARRAY), List.of(INT_ARRAY))));
        assertEquals(new Types.Tuple(List.of(INT, BOOL)), Types.unify(new Types.Tuple(List.of(UNKNOWN, BOOL)),
                new Types.Tuple(List.of(INT, BOOL))));
        assertEquals(ERROR, Types.unify(new Types.Tuple(List.of(INT)), new Types.Tuple(List.of(INT, BOOL))));
    }

    // Даже при создании AST вручную отсутствие обязательного типа параметра даёт MISSING_TYPE.
    @Test
    public void reportsMissingMandatoryAnnotationsForAstClients() {
        Token token = new Token("IDENT", "x", 0, 1, 1);
        Variable missing = new Variable("x", null, token);
        Formula formula = new Formula("p", List.of(missing), new Bool(true, token), token);
        SemanticAnalyzer.Result result = SemanticAnalyzer.analyze(new Ast.Module(List.of(formula), token));
        assertEquals(List.of("MISSING_TYPE"), codes(result));
    }

    // ERROR подавляет производные ошибки типов; диагностики предыдущего анализа не переходят в следующий.
    @Test
    public void errorTypesSuppressCascadesAndDoNotLeakBetweenRuns() {
        SemanticAnalyzer.Result result = check("f() returns r: int { r = (missing + 1) * 2; }");
        assertEquals(List.of("UNKNOWN_NAME", "UNASSIGNED_RETURN"), codes(result));
        assertTrue(result.types().containsValue(ERROR));
        valid("f() returns r: int { r = 1; }");
    }
}
