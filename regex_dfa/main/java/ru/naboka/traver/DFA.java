package ru.naboka.traver;

/**
 * Deterministic finite automaton with a *total* transition function
 * (every state has an outgoing edge for every symbol 0..127 - undefined
 * edges are explicitly routed to the trap state).
 */
public class DFA {
    public final int numStates;
    public final int[][] trans;              // trans[state][symbol] -> state
    public final Integer[] acceptTokenIndex;  // null if state is non-accepting
    public int start;
    public int trapState;

    public DFA(int numStates, int alphabet) {
        this.numStates = numStates;
        this.trans = new int[numStates][alphabet];
        this.acceptTokenIndex = new Integer[numStates];
    }
}
