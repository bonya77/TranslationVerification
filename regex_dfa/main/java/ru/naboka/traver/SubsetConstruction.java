package ru.naboka.traver;

import java.util.*;

/** Classic subset construction (NFA -> DFA) via epsilon-closure, producing a total DFA. */
public class SubsetConstruction {

    public static DFA build(NFA nfa, List<TokenSpec> specs, int startState) {
        int alphabet = NFA.ALPHABET;

        Map<Set<Integer>, Integer> stateIds = new LinkedHashMap<>();
        List<Set<Integer>> stateSets = new ArrayList<>();
        Map<Integer, int[]> transMap = new HashMap<>();

        Set<Integer> startSet = epsClosure(nfa, Collections.singleton(startState));
        stateIds.put(startSet, 0);
        stateSets.add(startSet);

        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(0);

        while (!queue.isEmpty()) {
            int id = queue.poll();
            Set<Integer> set = stateSets.get(id);
            int[] row = new int[alphabet];
            Arrays.fill(row, -1); // -1 = undefined -> trap
            for (int c = 0; c < alphabet; c++) {
                Set<Integer> moved = move(nfa, set, c);
                if (moved.isEmpty()) continue;
                Set<Integer> closure = epsClosure(nfa, moved);
                Integer nid = stateIds.get(closure);
                if (nid == null) {
                    nid = stateSets.size();
                    stateIds.put(closure, nid);
                    stateSets.add(closure);
                    queue.add(nid);
                }
                row[c] = nid;
            }
            transMap.put(id, row);
        }

        // explicit trap/dead state: non-accepting, loops to itself on every symbol
        int trapId = stateSets.size();
        int[] trapRow = new int[alphabet];
        Arrays.fill(trapRow, trapId);
        transMap.put(trapId, trapRow);

        int numStates = trapId + 1;
        DFA dfa = new DFA(numStates, alphabet);
        dfa.start = 0;
        dfa.trapState = trapId;

        for (int id = 0; id < numStates; id++) {
            int[] row = transMap.get(id);
            for (int c = 0; c < alphabet; c++) {
                dfa.trans[id][c] = (row[c] == -1) ? trapId : row[c];
            }
        }

        for (int id = 0; id < stateSets.size(); id++) {
            Integer best = null;
            for (int nfaState : stateSets.get(id)) {
                Integer tokenIdx = nfa.acceptLabel.get(nfaState);
                if (tokenIdx != null && (best == null || tokenIdx < best)) {
                    best = tokenIdx; // smaller index = higher priority (earlier rule wins on tie)
                }
            }
            dfa.acceptTokenIndex[id] = best;
        }
        dfa.acceptTokenIndex[trapId] = null;

        return dfa;
    }

    private static Set<Integer> epsClosure(NFA nfa, Set<Integer> states) {
        Deque<Integer> stack = new ArrayDeque<>(states);
        Set<Integer> result = new TreeSet<>(states);
        while (!stack.isEmpty()) {
            int s = stack.pop();
            for (int[] edge : nfa.trans.get(s)) {
                if (edge[0] == NFA.EPS && result.add(edge[1])) {
                    stack.push(edge[1]);
                }
            }
        }
        return result;
    }

    private static Set<Integer> move(NFA nfa, Set<Integer> states, int symbol) {
        Set<Integer> result = new TreeSet<>();
        for (int s : states) {
            for (int[] edge : nfa.trans.get(s)) {
                if (edge[0] == symbol) result.add(edge[1]);
            }
        }
        return result;
    }
}
