package ru.nsu.naboka.ir;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ru.nsu.naboka.ir.ControlFlow.Block;
import static ru.nsu.naboka.ir.Ir.*;

public record RegisterAllocation(Map<Slot, Set<Slot>> conflicts, Map<Slot, Integer> colors) {
    public static RegisterAllocation allocate(List<Block> blocks) {
        Map<Slot, Set<Slot>> graph = new LinkedHashMap<>();
        for (Block block : blocks) {
            for (Instruction instruction : block.instructions()) {
                instruction.defs().forEach(slot -> addTemporary(graph, slot));
                for (Value operand : instruction.operands()) {
                    if (operand instanceof Slot slot) addTemporary(graph, slot);
                }
            }
        }

        Map<String, Set<Slot>> liveOut = Liveness.liveOut(blocks);
        for (Block block : blocks) {
            Set<Slot> live = new HashSet<>(liveOut.get(block.label()));
            for (Instruction instruction : block.instructions().reversed()) {
                for (Slot target : instruction.defs()) {
                    if (!target.temporary()) continue;
                    for (Slot other : live) {
                        if (other.temporary() && !target.equals(other)) {
                            graph.get(target).add(other);
                            graph.get(other).add(target);
                        }
                    }
                }
                Liveness.stepBack(instruction, live);
            }
        }

        Map<Slot, Integer> colors = new LinkedHashMap<>();
        for (var entry : graph.entrySet()) {
            Set<Integer> occupied = new HashSet<>();
            for (Slot neighbor : entry.getValue()) occupied.add(colors.get(neighbor));
            int color = 0;
            while (occupied.contains(color)) color++;
            colors.put(entry.getKey(), color);
        }
        graph.replaceAll((slot, neighbors) -> Collections.unmodifiableSet(neighbors));
        return new RegisterAllocation(Collections.unmodifiableMap(graph), Collections.unmodifiableMap(colors));
    }

    public int colorCount() {
        return colors.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
    }

    private static void addTemporary(Map<Slot, Set<Slot>> graph, Slot slot) {
        if (slot.temporary()) graph.computeIfAbsent(slot, unused -> new LinkedHashSet<>());
    }
}
