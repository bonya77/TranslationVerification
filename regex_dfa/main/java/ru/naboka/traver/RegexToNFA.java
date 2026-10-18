package ru.naboka.traver;

import java.util.*;

/**
 * Minimal regex language supported (enough for a lexer spec):
 *   literals            a, b, 0, ...
 *   escapes             \n \t \r \\ \( \) \[ \] \{ \} \* \+ \? \. \|
 *   concatenation       ab
 *   alternation         a|b
 *   Kleene star / plus / optional   a*  a+  a?
 *   grouping            (a|b)c
 *   character class     [a-z] [^\n] [ \t\r\n]
 *   any ASCII char      .
 *
 * Builds the Thompson-construction NFA fragment directly during parsing
 * (classic textbook approach), all states shared in a single NFA instance
 * so several token regexes can be combined into one automaton.
 */
public class RegexToNFA {
    private final String re;
    private int pos;
    private final NFA nfa;

    public RegexToNFA(String re, NFA nfa) {
        this.re = re;
        this.nfa = nfa;
        this.pos = 0;
    }

    public NFA.Frag parse() {
        NFA.Frag f = parseUnion();
        if (pos != re.length()) {
            throw new IllegalArgumentException("Unexpected character at " + pos + " in /" + re + "/");
        }
        return f;
    }

    private char peek() { return pos < re.length() ? re.charAt(pos) : '\0'; }
    private char next() { return re.charAt(pos++); }
    private boolean eof() { return pos >= re.length(); }

    private NFA.Frag parseUnion() {
        NFA.Frag left = parseConcat();
        while (!eof() && peek() == '|') {
            next();
            NFA.Frag right = parseConcat();
            int s = nfa.newState(), a = nfa.newState();
            nfa.addEdge(s, NFA.EPS, left.start);
            nfa.addEdge(s, NFA.EPS, right.start);
            nfa.addEdge(left.accept, NFA.EPS, a);
            nfa.addEdge(right.accept, NFA.EPS, a);
            left = new NFA.Frag(s, a);
        }
        return left;
    }

    private NFA.Frag parseConcat() {
        NFA.Frag left = null;
        while (!eof() && peek() != '|' && peek() != ')') {
            NFA.Frag right = parseRepeat();
            left = (left == null) ? right : concat(left, right);
        }
        if (left == null) {
            int s = nfa.newState();
            left = new NFA.Frag(s, s); // epsilon fragment
        }
        return left;
    }

    private NFA.Frag concat(NFA.Frag a, NFA.Frag b) {
        nfa.addEdge(a.accept, NFA.EPS, b.start);
        return new NFA.Frag(a.start, b.accept);
    }

    private NFA.Frag parseRepeat() {
        NFA.Frag atom = parseAtom();
        while (!eof() && (peek() == '*' || peek() == '+' || peek() == '?')) {
            char op = next();
            int s = nfa.newState(), a = nfa.newState();
            nfa.addEdge(s, NFA.EPS, atom.start);
            nfa.addEdge(atom.accept, NFA.EPS, a);
            if (op == '*' || op == '+') {
                nfa.addEdge(atom.accept, NFA.EPS, atom.start);
            }
            if (op == '*' || op == '?') {
                nfa.addEdge(s, NFA.EPS, a);
            }
            atom = new NFA.Frag(s, a);
        }
        return atom;
    }

    private NFA.Frag parseAtom() {
        char c = peek();
        if (c == '(') {
            next();
            NFA.Frag f = parseUnion();
            expect(')');
            return f;
        }
        if (c == '[') {
            return parseClass();
        }
        if (c == '.') {
            next();
            boolean[] all = new boolean[NFA.ALPHABET];
            Arrays.fill(all, true);
            return anyOf(all);
        }
        int ch = readLiteralChar();
        int s = nfa.newState(), a = nfa.newState();
        nfa.addEdge(s, ch, a);
        return new NFA.Frag(s, a);
    }

    private void expect(char c) {
        if (eof() || next() != c) {
            throw new IllegalArgumentException("Expected '" + c + "' in /" + re + "/ at position " + pos);
        }
    }

    private int readLiteralChar() {
        char c = next();
        if (c == '\\') {
            char e = next();
            switch (e) {
                case 'n': return '\n';
                case 't': return '\t';
                case 'r': return '\r';
                default: return e; // \\, \(, \), \[, \], \{, \}, \*, \+, \?, \., \|
            }
        }
        return c;
    }

    private NFA.Frag parseClass() {
        next(); // consume '['
        boolean negate = false;
        if (peek() == '^') {
            negate = true;
            next();
        }
        boolean[] set = new boolean[NFA.ALPHABET];
        boolean first = true;
        while (!eof() && (peek() != ']' || first)) {
            first = false;
            int c1 = readLiteralChar();
            if (!eof() && peek() == '-' && pos + 1 < re.length() && re.charAt(pos + 1) != ']') {
                next(); // consume '-'
                int c2 = readLiteralChar();
                for (int c = c1; c <= c2; c++) set[c] = true;
            } else {
                set[c1] = true;
            }
        }
        expect(']');
        if (negate) {
            boolean[] neg = new boolean[NFA.ALPHABET];
            for (int i = 0; i < NFA.ALPHABET; i++) neg[i] = !set[i];
            set = neg;
        }
        return anyOf(set);
    }

    private NFA.Frag anyOf(boolean[] set) {
        int s = nfa.newState(), a = nfa.newState();
        for (int c = 0; c < NFA.ALPHABET; c++) {
            if (set[c]) nfa.addEdge(s, c, a);
        }
        return new NFA.Frag(s, a);
    }
}
