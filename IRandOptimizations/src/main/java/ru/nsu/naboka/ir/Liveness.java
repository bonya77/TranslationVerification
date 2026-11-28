package ru.nsu.naboka.ir;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ru.nsu.naboka.ir.ControlFlow.Block;
import static ru.nsu.naboka.ir.Ir.*;

final class Liveness {
    private Liveness() {}

    static Map<String, Set<Slot>> liveOut(List<Block> blocks) {
        Map<String, Set<Slot>> in = new HashMap<>();
        Map<String, Set<Slot>> out = new HashMap<>();
        for (Block block : blocks) in.put(block.label(), Set.of());

        boolean changed;
        do {
            changed = false;
            for (Block block : blocks.reversed()) {
                Set<Slot> live = new HashSet<>();
                for (String successor : block.successors()) live.addAll(in.get(successor));
                out.put(block.label(), Set.copyOf(live));
                for (Instruction instruction : block.instructions().reversed()) stepBack(instruction, live);
                changed |= !in.put(block.label(), live).equals(live);
            }
        } while (changed);
        return out;
    }

    static void stepBack(Instruction instruction, Set<Slot> live) {
        live.removeAll(instruction.defs());
        for (Value operand : instruction.operands()) {
            if (operand instanceof Slot slot) live.add(slot);
        }
    }
}
