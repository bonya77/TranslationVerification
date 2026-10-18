package ru.naboka.traver;

import java.util.*;

/**
 * Hopcroft's DFA minimization: partition refinement.
 * Initial partition groups states by their accepting label (or "none"),
 * so we never merge states that accept different tokens or have different
 * skip semantics. Then we repeatedly split blocks that are distinguishable
 * by some symbol, using a worklist, until a fixpoint is reached.
 */
public class Hopcroft {

    public static DFA minimize(DFA dfa) {
        int n = dfa.numStates;
        int alphabet = dfa.trans[0].length;

        Map<Integer, Set<Integer>> initialGroups = new LinkedHashMap<>();
        for (int s = 0; s < n; s++) {
            int key = (dfa.acceptTokenIndex[s] == null) ? -1 : dfa.acceptTokenIndex[s];
            initialGroups.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(s);
        }

        List<Set<Integer>> partition = new ArrayList<>(initialGroups.values());
        Deque<Set<Integer>> worklist = new ArrayDeque<>(partition);

        // reverse transitions: for symbol c, target state -> set of source states
        List<Map<Integer, Set<Integer>>> rev = new ArrayList<>();
        for (int c = 0; c < alphabet; c++) {
            Map<Integer, Set<Integer>> byTarget = new HashMap<>();
            for (int s = 0; s < n; s++) {
                byTarget.computeIfAbsent(dfa.trans[s][c], k -> new HashSet<>()).add(s);
            }
            rev.add(byTarget);
        }

        while (!worklist.isEmpty()) {
            Set<Integer> A = worklist.poll();
            for (int c = 0; c < alphabet; c++) {
                Set<Integer> X = new HashSet<>();
                for (int t : A) {
                    Set<Integer> src = rev.get(c).get(t);
                    if (src != null) X.addAll(src);
                }
                if (X.isEmpty()) continue;

                List<Set<Integer>> newPartition = new ArrayList<>();
                for (Set<Integer> Y : partition) {
                    Set<Integer> inter = new LinkedHashSet<>(Y);
                    inter.retainAll(X);
                    Set<Integer> diff = new LinkedHashSet<>(Y);
                    diff.removeAll(X);
                    if (!inter.isEmpty() && !diff.isEmpty()) {
                        newPartition.add(inter);
                        newPartition.add(diff);
                        if (worklist.contains(Y)) {
                            worklist.remove(Y);
                            worklist.add(inter);
                            worklist.add(diff);
                        } else {
                            worklist.add(inter.size() <= diff.size() ? inter : diff);
                        }
                    } else {
                        newPartition.add(Y);
                    }
                }
                partition = newPartition;
            }
        }

        Map<Integer, Integer> stateToBlock = new HashMap<>();
        for (int i = 0; i < partition.size(); i++) {
            for (int s : partition.get(i)) stateToBlock.put(s, i);
        }

        DFA min = new DFA(partition.size(), alphabet);
        min.start = stateToBlock.get(dfa.start);
        min.trapState = stateToBlock.get(dfa.trapState);
        for (int i = 0; i < partition.size(); i++) {
            int rep = partition.get(i).iterator().next();
            min.acceptTokenIndex[i] = dfa.acceptTokenIndex[rep];
            for (int c = 0; c < alphabet; c++) {
                min.trans[i][c] = stateToBlock.get(dfa.trans[rep][c]);
            }
        }
        return min;
    }
}
