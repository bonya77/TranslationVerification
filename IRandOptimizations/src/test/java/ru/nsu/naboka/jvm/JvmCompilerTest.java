package ru.nsu.naboka.jvm;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import ru.nsu.naboka.ir.Optimizer;
import ru.nsu.naboka.runtime.ImmutableIntArray;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.*;

public class JvmCompilerTest {
    private static final Optimizer.Options OPTIMIZED = new Optimizer.Options(true, true, true);
    private static final List<Optimizer.Options> MODES = List.of(OPTIMIZED, new Optimizer.Options(false, false, false));
    private static final Path DEMO = Path.of("examples/demo.funny");

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void runsArithmeticBranchesAndLoopsInBothModes() throws Exception {
        for (Optimizer.Options options : MODES) {
            Class<?> arithmetic = compile(Files.readString(Path.of("examples/arithmetic.funny")), options);
            assertEquals(21, call(arithmetic, "calc", 3, 7));
            assertEquals(-10, call(arithmetic, "calc", -2, 1));

            Class<?> control = compile(Files.readString(Path.of("examples/control.funny")), options);
            assertEquals(0, call(control, "compute", 0));
            assertEquals(3, call(control, "compute", 1));
            assertEquals(15, call(control, "compute", 4));
            assertEquals(32, call(control, "compute", 6));
        }
    }

    @Test
    public void runsRecursionAndKeepsAnnotationsAsCheckedSyntax() throws Exception {
        for (Optimizer.Options options : MODES) {
            Class<?> demo = compile(Files.readString(DEMO), options);
            assertEquals(120, call(demo, "factorial", 5));
            assertEquals(3, call(demo, "sum", ImmutableIntArray.of(2, -3, 4)));
            assertEquals(0, call(demo, "sum", ImmutableIntArray.of()));
            assertThrows(NoSuchMethodException.class, () -> demo.getDeclaredMethod("bounds", ImmutableIntArray.class));
        }
    }

    @Test
    public void preservesShortCircuitAndJvmIntegerSemantics() throws Exception {
        String source = """
                positive(x: int) => x > 0;
                f(x: int) returns r: int uses flag {
                  flag = positive(x);
                  r = 0;
                  if (x == 0 or 10 / x > 0) r = r + 1;
                  if (x != 0 and 10 / x > 0) r = r + 2;
                  if (x != 0 -> 10 / x > 0) r = r + 4;
                  if (not flag == positive(0)) r = r + 8;
                }
                overflow() returns a: int, b: int {
                  a = 2147483647 + 1;
                  b = -2147483648 / -1;
                }
                """;
        for (Optimizer.Options options : MODES) {
            Class<?> module = compile(source, options);
            assertEquals(5, call(module, "f", 0));
            assertEquals(15, call(module, "f", 2));
            assertEquals(0, call(module, "f", -2));
            assertArrayEquals(new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE}, (int[]) call(module, "overflow"));
            assertTrue(Modifier.isPrivate(module.getDeclaredMethod("positive", int.class).getModifiers()));
        }
    }

    @Test
    public void arrayCopiesAndFunctionalUpdatesPreserveAliases() throws Exception {
        int[] javaInput = {2, -3, 4};
        ImmutableIntArray input = ImmutableIntArray.copyOf(javaInput);
        ImmutableIntArray fromVarargs = ImmutableIntArray.of(javaInput);
        javaInput[1] = 90;
        assertEquals(-3, input.get(1));
        assertEquals(-3, fromVarargs.get(1));
        String source = """
                same(a: int[], b: int[]) returns r: int {
                  if (a == b) r = 1;
                  if (a != b) r = 2;
                }
                """;
        for (Optimizer.Options options : MODES) {
            Class<?> demo = compile(Files.readString(DEMO) + source, options);
            ImmutableIntArray changed = (ImmutableIntArray) call(demo, "updated", input, 1, -2);
            assertNotSame(input, changed);
            assertEquals(-3, input.get(1));
            assertEquals(ImmutableIntArray.of(2, -2, 4), changed);
            assertEquals(1, call(demo, "same", input, fromVarargs));
            assertEquals(2, call(demo, "same", changed, input));
        }
    }

    @Test
    public void returnsFreshTuplesInDeclaredOrderAndUnpacksCalls() throws Exception {
        String source = """
                pair(x: int, y: int) returns a: int, b: int {
                  a = x;
                  b = y;
                }
                swapped(x: int, y: int) returns first: int, second: int {
                  first, second = pair(x, y);
                  first, second = pair(second, first);
                }
                """;
        for (Optimizer.Options options : MODES) {
            Class<?> demo = compile(Files.readString(DEMO) + source, options);
            ImmutableIntArray input = ImmutableIntArray.of(2, -3, 4);
            int[] tuple = (int[]) call(demo, "details", input, 1);
            assertArrayEquals(new int[]{-3, -2, 3}, tuple);
            tuple[0] = 90;
            assertArrayEquals(new int[]{-3, -2, 3}, (int[]) call(demo, "details", input, 1));
            assertArrayEquals(new int[]{7, 5}, (int[]) call(demo, "swapped", 5, 7));
            assertEquals(-3, call(demo, "summary", input, 1));
        }
    }

    @Test
    public void reportsPositionedSyntaxTypeAndAbiErrors() {
        assertDiagnostic("f() returns r: int { r = ; }", "1:26:");
        assertDiagnostic("f(x: int) returns r: int { x = 1; }", "[IMMUTABLE_PARAMETER]");
        assertDiagnostic("f(a: int[]) returns r: int { a[0] = 1; }", "[IMMUTABLE_PARAMETER]");
        assertDiagnostic("f(a: int[]) returns r: int { r = a; }", "[TYPE_MISMATCH]");
        assertDiagnostic("f() returns r: int { r = missing; }", "[UNKNOWN_NAME]");
        assertDiagnostic("f(a: int[]) requires a > 1 returns r: int {}", "[TYPE_MISMATCH]");
        assertDiagnostic("f() returns r: int { r = f(1); }", "[ARGUMENT_COUNT]");
        assertDiagnostic("f(x: int[]) returns r: int { r = f(1); }", "[TYPE_MISMATCH]");
        assertDiagnostic("f() returns r: int, s: int { r = f(); }", "[RESULT_COUNT]");
        assertDiagnostic("f() returns r: int, s: int { r, r = f(); }", "[DUPLICATE_TARGET]");
        assertDiagnostic("f(a: int[]) returns r: int, b: int[] { b = a; }", "[JVM_TUPLE_TYPE]");
    }

    @Test
    public void rejectsSignaturesBeyondJvmParameterLimit() throws Exception {
        String params = IntStream.range(0, 255).mapToObj(i -> "a" + i + ": int").collect(Collectors.joining(", "));
        String args = String.join(", ", Collections.nCopies(255, "1"));
        String atLimit = "f(" + params + ") returns r: int { r = a254; }\n"
                + "entry() returns r: int { r = f(" + args + "); }";
        assertEquals(1, call(compile(atLimit, OPTIMIZED), "entry"));
        assertDiagnostic("f(" + params + ", extra: int) returns r: int {}", "[JVM_PARAMETER_LIMIT]");
    }

    @Test
    public void optimizationKeepsDivisionAndBoundsExceptionsEvenForDeadResults() throws Exception {
        String source = """
                divide(x: int) returns r: int uses dead { dead = 9 / x; r = 7; }
                zero() returns r: int uses dead { dead = 1 / 0; r = 7; }
                get(a: int[], i: int) returns r: int uses dead { dead = a[i]; r = 7; }
                set(a: int[], i: int) returns r: int uses dead { dead = a; dead[i] = 1; r = 7; }
                """;
        for (Optimizer.Options options : MODES) {
            Class<?> module = compile(source, options);
            assertEquals(7, call(module, "divide", 2));
            assertCause(ArithmeticException.class, () -> call(module, "divide", 0));
            assertCause(ArithmeticException.class, () -> call(module, "zero"));
            assertCause(ArrayIndexOutOfBoundsException.class, () -> call(module, "get", ImmutableIntArray.of(), 0));
            assertCause(ArrayIndexOutOfBoundsException.class, () -> call(module, "set", ImmutableIntArray.of(1), -1));
        }
    }

    @Test
    public void cliWritesOnePublicFinalModuleWithTheSpecifiedAbi() throws Exception {
        Path output = temporary.newFolder().toPath();
        CliResult result = cli("compile", "--out", output.toString(), "--class", "Example", DEMO.toString());
        assertEquals(result.stderr(), 0, result.status());
        Path classFile = output.resolve("Example.class");
        assertEquals(classFile.toString(), result.stdout().strip());
        try (var files = Files.list(output)) {
            assertEquals(List.of(classFile), files.toList());
        }
        try (var loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader())) {
            Class<?> module = loader.loadClass("Example");
            assertTrue(Modifier.isPublic(module.getModifiers()));
            assertTrue(Modifier.isFinal(module.getModifiers()));
            assertEquals(List.of("details", "divide", "factorial", "sum", "summary", "updated"),
                    Arrays.stream(module.getDeclaredMethods()).map(Method::getName).sorted().toList());
            for (Method method : module.getDeclaredMethods()) {
                assertTrue(Modifier.isPublic(method.getModifiers()));
                assertTrue(Modifier.isStatic(method.getModifiers()));
            }
            assertEquals(int.class, module.getMethod("factorial", int.class).getReturnType());
            assertEquals(int[].class, module.getMethod("details", ImmutableIntArray.class, int.class).getReturnType());
            assertEquals(ImmutableIntArray.class,
                    module.getMethod("updated", ImmutableIntArray.class, int.class, int.class).getReturnType());
            assertEquals(120, call(module, "factorial", 5));
        }
    }

    @Test
    public void cliPrintsResultsAndOriginalJvmExceptions() throws Exception {
        Path output = temporary.newFolder().toPath();
        assertEquals(new CliResult(0, "[-3, -2, 3]", ""), runDemo(output, "details", "[2, -3, 4]", "1"));
        assertEquals(new CliResult(0, "[2, -9, 4]", ""), runDemo(output, "updated", "[2, -3, 4]", "1", "-9"));
        assertTrue(Files.isRegularFile(output.resolve("demo.class")));

        CliResult divide = runDemo(output, "divide", "9", "0");
        assertEquals(3, divide.status());
        assertTrue(divide.stderr().startsWith("java.lang.ArithmeticException: / by zero"));
        assertEquals("", divide.stdout());
        CliResult outOfBounds = runDemo(output, "updated", "[]", "0", "1");
        assertEquals(3, outOfBounds.status());
        assertTrue(outOfBounds.stderr().startsWith("java.lang.ArrayIndexOutOfBoundsException"));
        assertEquals(2, runDemo(output, "sum", "[1,]").status());
    }

    @Test
    public void failedCompilationCreatesNoClass() throws Exception {
        Path input = temporary.newFile("Invalid.funny").toPath();
        Files.writeString(input, "f(a: int[]) returns r: int { a[0] = 1; }");
        Path output = temporary.newFolder().toPath();
        CliResult result = cli("compile", "--out", output.toString(), input.toString());
        assertEquals(1, result.status());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().contains("[IMMUTABLE_PARAMETER]"));
        assertFalse(Files.exists(output.resolve("Invalid.class")));
    }

    private static void assertDiagnostic(String source, String expected) {
        FunnyCompiler.CompilationException error = assertThrows(FunnyCompiler.CompilationException.class,
                () -> FunnyCompiler.compile(source, "Invalid", OPTIMIZED));
        assertTrue(error.getMessage(), error.getMessage().matches("(?s)^\\d+:\\d+:.*"));
        assertTrue(error.getMessage(), error.getMessage().contains(expected));
    }

    private static void assertCause(Class<? extends Throwable> type, org.junit.function.ThrowingRunnable operation) {
        InvocationTargetException error = assertThrows(InvocationTargetException.class, operation);
        assertEquals(type, error.getCause().getClass());
    }

    private static Class<?> compile(String source, Optimizer.Options options) {
        byte[] bytes = FunnyCompiler.compile(source, "Example", options);
        return new ClassLoader() {
            Class<?> define() {
                return defineClass("Example", bytes, 0, bytes.length);
            }
        }.define();
    }

    private static Object call(Class<?> type, String name, Object... args) throws ReflectiveOperationException {
        Method method = Arrays.stream(type.getMethods()).filter(candidate -> candidate.getName().equals(name))
                .findFirst().orElseThrow();
        return method.invoke(null, args);
    }

    private static CliResult runDemo(Path output, String... arguments) {
        String[] command = {"run", "--out", output.toString(), DEMO.toString()};
        String[] args = Arrays.copyOf(command, command.length + arguments.length);
        System.arraycopy(arguments, 0, args, command.length, arguments.length);
        return cli(args);
    }

    private static CliResult cli(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (var out = new PrintStream(stdout, true, UTF_8); var err = new PrintStream(stderr, true, UTF_8)) {
            return new CliResult(Main.run(args, out, err), stdout.toString(UTF_8).strip(), stderr.toString(UTF_8).strip());
        }
    }

    private record CliResult(int status, String stdout, String stderr) {}
}
