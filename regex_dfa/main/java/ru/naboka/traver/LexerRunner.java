package ru.naboka.traver;

import java.util.*;

/** Scans input using the DFA table with the "maximal munch" rule. */
public class LexerRunner {

    public static class Token {
        public final String type;
        public final String lexeme;
        public final int pos;
        public Token(String type, String lexeme, int pos) {
            this.type = type;
            this.lexeme = lexeme;
            this.pos = pos;
        }
        @Override public String toString() {
            return type + "(" + lexeme.replace("\n", "\\n") + ")@" + pos;
        }
    }

    public static class Result {
        public final List<Token> tokens = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
    }

    public static Result run(DFA dfa, List<TokenSpec> specs, String input) {
        Result result = new Result();
        int i = 0;
        int n = input.length();
        while (i < n) {
            int state = dfa.start;
            int lastAcceptPos = -1;
            Integer lastAcceptToken = null;
            int j = i;
            while (j < n) {
                int c = input.charAt(j);
                if (c >= NFA.ALPHABET) break; // outside declared ASCII alphabet
                int nextState = dfa.trans[state][c];
                if (nextState == dfa.trapState) break;
                state = nextState;
                j++;
                if (dfa.acceptTokenIndex[state] != null) {
                    lastAcceptPos = j;
                    lastAcceptToken = dfa.acceptTokenIndex[state];
                }
            }
            if (lastAcceptToken != null) {
                String lexeme = input.substring(i, lastAcceptPos);
                TokenSpec spec = specs.get(lastAcceptToken);
                if (!spec.skip) {
                    result.tokens.add(new Token(spec.name, lexeme, i));
                }
                i = lastAcceptPos;
            } else {
                char bad = input.charAt(i);
                result.errors.add("Unexpected character '" + escape(bad) + "' (code " + (int) bad + ") at position " + i);
                i++; // trap: skip one character and resynchronize
            }
        }
        return result;
    }

    private static String escape(char c) {
        if (c == '\n') return "\\n";
        if (c == '\t') return "\\t";
        if (c == '\r') return "\\r";
        return String.valueOf(c);
    }
}
