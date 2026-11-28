package ru.nsu.naboka.ir;

import java.util.ArrayList;
import java.util.List;

import static ru.nsu.naboka.ir.Ir.*;

public final class ControlFlow {
    private ControlFlow() {}

    public record Block(String label, List<Instruction> instructions, List<String> successors) {
        public Block {
            instructions = List.copyOf(instructions);
            successors = List.copyOf(successors);
        }
    }

    public static List<Block> build(Function function) {
        List<Block> blocks = new ArrayList<>();
        List<Instruction> code = function.instructions();
        int start = 0;
        for (int end = 1; end <= code.size(); end++) {
            if (end < code.size() && !(code.get(end) instanceof Label)) continue;
            String name = ((Label) code.get(start)).name();
            String next = end < code.size() ? ((Label) code.get(end)).name() : null;
            List<Instruction> instructions = code.subList(start + 1, end);
            List<String> successors = switch (code.get(end - 1)) {
                case Jump jump -> List.of(jump.target());
                case Branch branch -> List.of(branch.yes(), branch.no());
                case Return ignored -> List.of();
                default -> next == null ? List.of() : List.of(next);
            };
            blocks.add(new Block(name, instructions, successors));
            start = end;
        }
        return List.copyOf(blocks);
    }
}
