package ru.nsu.naboka.ir;

import ru.nsu.naboka.Parser;
import ru.nsu.naboka.SemanticAnalyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static ru.nsu.naboka.ir.Ir.*;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        boolean constants = true;
        boolean algebra = true;
        boolean dce = true;
        String file = null;
        for (String arg : args) {
            switch (arg) {
                case "--no-opt" -> constants = algebra = dce = false;
                case "--no-const" -> constants = false;
                case "--no-algebra" -> algebra = false;
                case "--no-dce" -> dce = false;
                default -> {
                    if (arg.startsWith("--") || file != null) {
                        System.err.println("Использование: Main [--no-opt | --no-const --no-algebra --no-dce] [файл.funny]");
                        System.exit(2);
                    }
                    file = arg;
                }
            }
        }

        try {
            String source = file == null ? new String(System.in.readAllBytes(), UTF_8) : Files.readString(Path.of(file));
            Parser.Result parsed = new Parser(source).parse();
            if (!parsed.errors().isEmpty()) {
                parsed.errors().forEach(System.err::println);
                System.exit(1);
            }
            SemanticAnalyzer.Result checked = SemanticAnalyzer.analyze(parsed.module());
            if (!checked.diagnostics().isEmpty()) {
                checked.diagnostics().forEach(System.err::println);
                System.exit(1);
            }
            var options = new Optimizer.Options(constants, algebra, dce);
            List<Function> before = IrGenerator.generate(checked);
            List<Function> after = before.stream().map(function -> Optimizer.optimize(function, options)).toList();
            print(before, after);
        } catch (IOException error) {
            System.err.println(error.getMessage());
            System.exit(2);
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.exit(1);
        }
    }

    private static void print(List<Function> before, List<Function> after) {
        System.out.println("=== IR до оптимизаций ===");
        before.forEach(System.out::print);
        System.out.println("=== IR после оптимизаций ===");
        after.forEach(System.out::print);
        for (int i = 0; i < after.size(); i++) {
            Function original = before.get(i);
            Function optimized = after.get(i);
            var blocks = ControlFlow.build(optimized);
            var allocation = RegisterAllocation.allocate(blocks);
            var originalAllocation = RegisterAllocation.allocate(ControlFlow.build(original));

            System.out.println("=== " + optimized.name() + " ===");
            System.out.println("CFG:");
            blocks.forEach(block -> System.out.println("  " + block.label() + " -> " + block.successors()));
            System.out.println("Граф конфликтов и цвета:");
            allocation.conflicts().forEach((slot, neighbors) -> System.out.println("  " + slot + " ["
                    + allocation.colors().get(slot) + "] -> " + neighbors.stream().map(Slot::toString).sorted().toList()));
            System.out.printf("Инструкции %d -> %d, временные %d -> %d, цвета %d -> %d%n",
                    instructionCount(original), instructionCount(optimized),
                    originalAllocation.colors().size(), allocation.colors().size(),
                    originalAllocation.colorCount(), allocation.colorCount());
        }
    }

    private static long instructionCount(Function function) {
        return function.instructions().stream().filter(instruction -> !(instruction instanceof Label)).count();
    }
}
