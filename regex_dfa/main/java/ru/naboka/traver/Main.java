package ru.naboka.traver;

import java.util.*;
import java.nio.file.*;

public class Main {
    public static void main(String[] args) throws Exception {
        List<TokenSpec> specs = buildTokenSpecs();

        NFA nfa = new NFA();
        int combinedStart = nfa.newState();
        for (int i = 0; i < specs.size(); i++) {
            RegexToNFA parser = new RegexToNFA(specs.get(i).regex, nfa);
            NFA.Frag frag = parser.parse();
            nfa.addEdge(combinedStart, NFA.EPS, frag.start);
            nfa.acceptLabel.put(frag.accept, i);
        }

        DFA dfa = SubsetConstruction.build(nfa, specs, combinedStart);
        int beforeSize = dfa.numStates;

        DFA minDfa = Hopcroft.minimize(dfa);
        int afterSize = minDfa.numStates;

        System.out.println("=== Funny lexer: regex -> NFA (Thompson) -> DFA (subset construction) -> minimized DFA (Hopcroft) ===\n");
        System.out.println("Token specs (" + specs.size() + "), in priority order:");
        for (int i = 0; i < specs.size(); i++) {
            TokenSpec t = specs.get(i);
            System.out.printf("  [%2d] %-10s skip=%-5b /%s/%n", i, t.name, t.skip, t.regex);
        }
        System.out.println();
        System.out.println("NFA states:                     " + nfa.size());
        System.out.println("DFA states before minimization:  " + beforeSize);
        System.out.println("DFA states after minimization:   " + afterSize);
        System.out.println();

        String json = Export.toJson(minDfa, specs);
        Files.writeString(Path.of("dfa_table.json"), json);
        System.out.println("Transition table exported to dfa_table.json (" + json.length() + " bytes)\n");

        runTests(minDfa, specs);
    }
    private static List<TokenSpec> buildTokenSpecs() {
        List<TokenSpec> specs = new ArrayList<>();

        String[] keywords = {"function", "returns", "while", "if", "else",
                              "assert", "assume", "invariant", "length"};
        for (String kw : keywords) {
            specs.add(new TokenSpec("KEYWORD", kw, false)); // keywords come before IDENT -> win ties
        }

        specs.add(new TokenSpec("IDENT", "[a-zA-Z_][a-zA-Z0-9_]*", false));
        specs.add(new TokenSpec("INT", "0|[1-9][0-9]*", false));
        specs.add(new TokenSpec("WS", "[ \\t\\r\\n]+", true));
        specs.add(new TokenSpec("COMMENT", "//[^\\n]*", true));

        // {display, regex}. Two-char ops are naturally preferred over one-char
        // ones by maximal munch alone; order here only matters for exact ties.
        String[][] ops = {
            {"(", "\\("}, {")", "\\)"}, {"[", "\\["}, {"]", "\\]"},
            {"{", "\\{"}, {"}", "\\}"}, {",", ","},
            {"==", "=="}, {"!=", "!="}, {"<=", "<="}, {">=", ">="},
            {"+", "\\+"}, {"-", "-"}, {"*", "\\*"}, {"/", "/"},
            {"<", "<"}, {">", ">"}, {"=", "="}
        };
        for (String[] op : ops) {
            specs.add(new TokenSpec("OP(" + op[0] + ")", op[1], false));
        }
        return specs;
    }

    private static void runTests(DFA dfa, List<TokenSpec> specs) {
        List<TestCase> tests = buildTests();
        int passed = 0;
        System.out.println("=== Tests ===");
        for (TestCase tc : tests) {
            LexerRunner.Result r = LexerRunner.run(dfa, specs, tc.input);
            boolean ok = matches(r, tc);
            System.out.println((ok ? "PASS" : "FAIL") + "  " + tc.description);
            if (!ok) {
                System.out.println("    input:    " + show(tc.input));
                System.out.println("    expected: " + tc.expectedTokens + (tc.expectError ? " + ERROR" : ""));
                System.out.println("    got:      " + r.tokens + (r.errors.isEmpty() ? "" : " ERRORS=" + r.errors));
            }
            if (ok) passed++;
        }
        System.out.println();
        System.out.println(passed + "/" + tests.size() + " tests passed");
    }

    private static boolean matches(LexerRunner.Result r, TestCase tc) {
        if (tc.expectError && r.errors.isEmpty()) return false;
        if (!tc.expectError && !r.errors.isEmpty()) return false;
        if (tc.expectedTokens == null) return true;
        if (r.tokens.size() != tc.expectedTokens.size()) return false;
        for (int i = 0; i < r.tokens.size(); i++) {
            if (!r.tokens.get(i).type.equals(tc.expectedTokens.get(i))) return false;
        }
        return true;
    }

    private static String show(String s) {
        return "\"" + s.replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r") + "\"";
    }

    private static class TestCase {
        final String description, input;
        final List<String> expectedTokens; // null = only check error/no-error
        final boolean expectError;
        TestCase(String d, String i, List<String> e, boolean err) {
            description = d; input = i; expectedTokens = e; expectError = err;
        }
    }

    private static List<TestCase> buildTests() {
        List<TestCase> t = new ArrayList<>();
        t.add(new TestCase("empty string", "", List.of(), false));
        t.add(new TestCase("only spaces/tabs", "   \t\t  ", List.of(), false));
        t.add(new TestCase("CRLF only", "\r\n\r\n", List.of(), false));
        t.add(new TestCase("single zero is a valid INT", "0", List.of("INT"), false));
        t.add(new TestCase("00 is two INT tokens, not one", "00", List.of("INT", "INT"), false));
        t.add(new TestCase("01 is two INT tokens, not one", "01", List.of("INT", "INT"), false));
        t.add(new TestCase("identifier with underscore", "_my_var1", List.of("IDENT"), false));
        for (String kw : new String[]{"function", "returns", "while", "if", "else",
                                       "assert", "assume", "invariant", "length"}) {
            t.add(new TestCase("keyword '" + kw + "'", kw, List.of("KEYWORD"), false));
        }
        t.add(new TestCase("identifier that starts like a keyword", "iffy",
                List.of("IDENT"), false));
        t.add(new TestCase("delimiters", "()[]{},",
                List.of("OP(()", "OP())", "OP([)", "OP(])", "OP({)", "OP(})", "OP(,)"), false));
        t.add(new TestCase("arithmetic and relational operators",
                "+ - * / == != <= >= < > =",
                List.of("OP(+)", "OP(-)", "OP(*)", "OP(/)", "OP(==)", "OP(!=)",
                        "OP(<=)", "OP(>=)", "OP(<)", "OP(>)", "OP(=)"), false));
        t.add(new TestCase("line comment runs to end of line",
                "x = 1 // this is a comment\nreturns",
                List.of("IDENT", "OP(=)", "INT", "KEYWORD"), false));
        t.add(new TestCase("non-ASCII character falls into the trap",
                "x = \u00E9", null, true));
        t.add(new TestCase("small function snippet",
                "function foo(x, y) returns x",
                List.of("KEYWORD", "IDENT", "OP(()", "IDENT", "OP(,)", "IDENT", "OP())",
                        "KEYWORD", "IDENT"), false));
        return t;
    }
}
