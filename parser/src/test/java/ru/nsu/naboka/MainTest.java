package ru.nsu.naboka;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class MainTest {
    private record Run(int exit, String output, String errors) {}

    private Run run(String input, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", "target/classes", Main.class.getName()));
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).start();
        try (var stdin = process.getOutputStream()) {
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String errors = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(process.waitFor(), output, errors);
    }

    // CLI читает stdin и файл; обычный режим печатает типизированный AST, --parse-only — AST без типов.
    @Test(timeout = 10000)
    public void checksStdinAndFileAndKeepsParseOnlyMode() throws Exception {
        Run stdin = run("f() returns r: int { r = 1; }");
        assertEquals(0, stdin.exit());
        assertTrue(stdin.output().startsWith("(typed-ast 1 "));
        assertEquals("", stdin.errors());
        Run file = run("", "examples/types-positive.funny");
        assertEquals(file.errors(), 0, file.exit());
        Run parseOnly = run("", "--parse-only", "examples/positive.funny");
        assertEquals(0, parseOnly.exit());
        assertTrue(parseOnly.output().startsWith("(module "));
    }

    // Ошибки языка дают код 1; после синтаксической ошибки семантическая проверка не запускается.
    @Test(timeout = 10000)
    public void returnsLanguageErrorsWithAnnotatedAst() throws Exception {
        Run run = run("f() returns r: int { r = missing; }");
        assertEquals(1, run.exit());
        assertTrue(run.output().contains("(: error @"));
        assertTrue(run.errors().contains("[UNKNOWN_NAME]"));
        Run syntax = run("f() returns r: int { r = ; r = missing; }");
        assertEquals(1, syntax.exit());
        assertFalse(syntax.output().contains("typed-ast"));
        assertFalse(syntax.errors().contains("UNKNOWN_NAME"));
    }

    // Неизвестный флаг, лишние аргументы и отсутствующий входной файл дают код завершения 2.
    @Test(timeout = 10000)
    public void returnsUsageAndIoErrors() throws Exception {
        assertEquals(2, run("", "--unknown").exit());
        assertEquals(2, run("", "one", "two").exit());
        assertEquals(2, run("", "--parse-only", "one", "two").exit());
        assertEquals(2, run("", "examples/does-not-exist.funny").exit());
    }
}
