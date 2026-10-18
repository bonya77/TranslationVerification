package ru.naboka.traver;

import java.util.*;

/** Serializes a DFA + token specs into a JSON transition table for reuse by a real lexer. */
public class Export {
    public static String toJson(DFA dfa, List<TokenSpec> specs) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"alphabet\": \"ASCII 0-127\",\n");
        sb.append("  \"numStates\": ").append(dfa.numStates).append(",\n");
        sb.append("  \"start\": ").append(dfa.start).append(",\n");
        sb.append("  \"trap\": ").append(dfa.trapState).append(",\n");

        sb.append("  \"tokens\": [\n");
        for (int i = 0; i < specs.size(); i++) {
            TokenSpec t = specs.get(i);
            sb.append("    {\"index\": ").append(i)
              .append(", \"name\": \"").append(t.name).append("\"")
              .append(", \"skip\": ").append(t.skip)
              .append(", \"regex\": ").append(jsonString(t.regex)).append("}")
              .append(i < specs.size() - 1 ? ",\n" : "\n");
        }
        sb.append("  ],\n");

        sb.append("  \"accepting\": {\n");
        boolean firstAcc = true;
        for (int s = 0; s < dfa.numStates; s++) {
            if (dfa.acceptTokenIndex[s] != null) {
                if (!firstAcc) sb.append(",\n");
                sb.append("    \"").append(s).append("\": ").append(dfa.acceptTokenIndex[s]);
                firstAcc = false;
            }
        }
        sb.append("\n  },\n");

        sb.append("  \"transitions\": [\n");
        for (int s = 0; s < dfa.numStates; s++) {
            List<String> edges = new ArrayList<>();
            for (int c = 0; c < dfa.trans[s].length; c++) {
                int t = dfa.trans[s][c];
                if (t != dfa.trapState) {
                    edges.add("[" + c + "," + t + "]");
                }
            }
            sb.append("    {\"state\": ").append(s).append(", \"edges\": [")
              .append(String.join(",", edges)).append("]}")
              .append(s < dfa.numStates - 1 ? ",\n" : "\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
