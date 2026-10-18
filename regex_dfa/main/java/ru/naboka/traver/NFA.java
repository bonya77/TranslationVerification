package ru.naboka.traver;

import java.util.*;

/**
 * Non-deterministic finite automaton over the ASCII alphabet (0..127).
 * Symbol -1 (EPS) denotes an epsilon-transition.
 *
 * Represented as an adjacency list: trans.get(state) is a list of
 * {symbol, targetState} pairs.
 */
public class NFA {
    public static final int EPS = -1;
    public static final int ALPHABET = 128;

    public final List<List<int[]>> trans = new ArrayList<>();

    /** NFA accepting state -> index of the TokenSpec it accepts (set by the caller). */
    public final Map<Integer, Integer> acceptLabel = new HashMap<>();
 
    public int newState() {
        trans.add(new ArrayList<>());
        return trans.size() - 1;
    }

    public void addEdge(int from, int symbol, int to) {
        trans.get(from).add(new int[]{symbol, to});
    }

    public int size() {
        return trans.size();
    }

    /** A Thompson fragment: single start state, single accept state. */
    public static class Frag {
        public final int start, accept;
        public Frag(int start, int accept) {
            this.start = start;
            this.accept = accept;
        }
    }
}
