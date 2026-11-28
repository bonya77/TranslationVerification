package ru.nsu.naboka.ir;

import org.junit.Test;
import ru.nsu.naboka.Parser;
import ru.nsu.naboka.SemanticAnalyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static ru.nsu.naboka.Types.Basic.*;
import static ru.nsu.naboka.ir.ControlFlow.Block;
import static ru.nsu.naboka.ir.Ir.*;

public class IrTest {
    private static final Optimizer.Options ALL = new Optimizer.Options(true, true, true);

    @Test
    public void arithmeticAndOptimizationFlags() throws IOException {
        List<Function> program = compile(Files.readString(Path.of("examples/arithmetic.funny")));
        Function original = program.getFirst();
        Function folded = Optimizer.optimize(original, new Optimizer.Options(true, false, false));
        Function simplified = Optimizer.optimize(original, new Optimizer.Options(false, true, false));
        Function smaller = Optimizer.optimize(original, new Optimizer.Options(false, false, true));
        Function optimized = Optimizer.optimize(original, ALL);

        assertEquals(original, Optimizer.optimize(original, new Optimizer.Options(false, false, false)));
        assertTrue(folded.toString().contains(" = copy 5\n"));
        assertFalse(simplified.toString().contains("add $x, 0"));
        assertFalse(smaller.toString().contains("$dead"));
        assertTrue(optimized.instructions().size() < original.instructions().size());
        for (Function version : List.of(original, folded, simplified, smaller, optimized)) {
            assertEquals(List.of(21), execute(List.of(version), "calc", 3, 7));
            assertEquals(List.of(-10), execute(List.of(version), "calc", -2, 1));
        }
    }

    @Test(timeout = 3000)
    public void branchesLoopAndMultipleReturns() throws IOException {
        List<Function> program = compile(Files.readString(Path.of("examples/control.funny")));
        assertSameResult(program, "pair", List.of(6, 4), 5);
        assertSameResult(program, "compute", List.of(0), 0);
        assertSameResult(program, "compute", List.of(3), 1);
        assertSameResult(program, "compute", List.of(15), 4);
        assertSameResult(program, "compute", List.of(32), 6);
    }

    @Test
    public void arraysAndExecutableFormula() throws IOException {
        List<Function> program = compile(Files.readString(Path.of("../parser/examples/types-positive.funny")));
        int[] input = {2, -3, 4};
        assertSameResult(program, "sum", List.of(6), input);
        assertArrayEquals(new int[]{2, -3, 4}, input);

        List<Function> withFormula = compile("""
                positive(x: int) => x > 0 and not false;
                f(x: int) returns r: int uses b {
                  b = positive(x);
                  if (b == b and x > 0) r = 1; else r = 0;
                }
                """);
        assertSameResult(withFormula, "positive", List.of(true), 1);
        assertSameResult(withFormula, "positive", List.of(false), -1);
        assertSameResult(withFormula, "f", List.of(1), 1);
    }

    @Test
    public void unusedOperationsCanStillFail() {
        List<Function> program = compile("""
                fail(n: int) returns r: int { r = 12 / n; }
                div(n: int) returns r: int uses dead { dead = fail(n) * 0; r = 7; }
                index(a: int[]) returns r: int uses dead { dead = a[1]; r = 7; }
                zero() returns r: int uses dead { dead = (1 / 0) * 0; r = 7; }
                """);
        for (List<Function> version : List.of(program, optimize(program))) {
            assertEquals(List.of(7), execute(version, "div", 2));
            assertThrows(ArithmeticException.class, () -> execute(version, "div", 0));
            assertThrows(ArrayIndexOutOfBoundsException.class, () -> execute(version, "index", new int[0]));
            assertThrows(ArithmeticException.class, () -> execute(version, "zero"));
        }
    }

    @Test
    public void allocationUsesLivenessAcrossBlocks() {
        Slot first = new Slot("t1", INT, true);
        Slot second = new Slot("t2", INT, true);
        Slot last = new Slot("t3", INT, true);
        Slot flag = new Slot("flag", BOOL, false);
        Constant one = new Constant(INT, 1);
        Function function = new Function("loop", List.of(flag), List.of(INT), List.of(
                new Label("entry"), new Assign(first, Op.COPY, List.of(one)),
                new Label("head"), new Assign(second, Op.ADD, List.of(first, one)), new Branch(flag, "body", "end"),
                new Label("body"), new Assign(flag, Op.LT, List.of(second, one)), new Jump("head"),
                new Label("end"), new Assign(last, Op.ADD, List.of(first, second)), new Return(List.of(last))));
        List<Block> cfg = ControlFlow.build(function);
        assertEquals(List.of("head"), cfg.get(0).successors());
        assertEquals(List.of("body", "end"), cfg.get(1).successors());
        assertEquals(List.of("head"), cfg.get(2).successors());
        assertTrue(cfg.get(3).successors().isEmpty());

        RegisterAllocation result = RegisterAllocation.allocate(cfg);
        assertEquals(Set.of(second), result.conflicts().get(first));
        assertEquals(Set.of(first), result.conflicts().get(second));
        assertNotEquals(result.colors().get(first), result.colors().get(second));
        assertEquals(result.colors().get(first), result.colors().get(last));
        assertEquals(2, result.colorCount());
    }

    private static List<Function> compile(String source) {
        var parsed = new Parser(source).parse();
        assertTrue(parsed.errors().toString(), parsed.errors().isEmpty());
        var annotated = SemanticAnalyzer.analyze(parsed.module());
        assertTrue(annotated.diagnostics().toString(), annotated.diagnostics().isEmpty());
        return IrGenerator.generate(annotated);
    }

    private static List<Function> optimize(List<Function> program) {
        return program.stream().map(function -> Optimizer.optimize(function, ALL)).toList();
    }

    private static void assertSameResult(List<Function> program, String name, List<Object> expected, Object... args) {
        List<Object> original = execute(program, name, args);
        assertEquals(expected, original);
        assertEquals(original, execute(optimize(program), name, args));
    }

    private static List<Object> execute(List<Function> program, String name, Object... args) {
        Function function = program.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
        List<Block> cfg = ControlFlow.build(function);
        Map<String, Block> blocks = cfg.stream().collect(Collectors.toMap(Block::label, block -> block));
        Map<Slot, Object> slots = new HashMap<>();
        for (int i = 0; i < args.length; i++) slots.put(function.parameters().get(i), args[i]);

        Block block = cfg.getFirst();
        while (true) {
            String next = block.successors().isEmpty() ? null : block.successors().getFirst();
            for (Instruction instruction : block.instructions()) {
                List<Object> values = instruction.operands().stream().map(value -> switch (value) {
                    case Constant constant -> constant.value();
                    case Slot slot -> {
                        assertTrue("Нет значения у " + slot, slots.containsKey(slot));
                        yield slots.get(slot);
                    }
                }).toList();
                switch (instruction) {
                    case Assign assign -> slots.put(assign.target(), assign.op().evaluate(values));
                    case Call call -> {
                        List<Object> returned = execute(program, call.function(), values.toArray());
                        for (int i = 0; i < returned.size(); i++) slots.put(call.targets().get(i), returned.get(i));
                    }
                    case Branch branch -> next = (boolean) values.getFirst() ? branch.yes() : branch.no();
                    case Return ignored -> { return values; }
                    default -> {}
                }
            }
            block = blocks.get(next);
        }
    }
}
