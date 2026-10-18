package ru.nsu.naboka;

import ru.naboka.traver.DFA;
import ru.naboka.traver.Hopcroft;
import ru.naboka.traver.NFA;
import ru.naboka.traver.RegexToNFA;
import ru.naboka.traver.SubsetConstruction;
import ru.naboka.traver.TokenSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Lexer {
    private static final Set<String> KEYWORDS = Set.of("function", "returns", "requires", "ensures", "uses",
            "int", "if", "else", "while", "invariant", "assert", "assume", "length", "true", "false",
            "not", "and", "or", "forall", "exists");
    private static final List<TokenSpec> RULES = List.of(
            new TokenSpec("IDENT", "[a-zA-Z][a-zA-Z0-9]*", false),
            new TokenSpec("INT", "0|[1-9][0-9]*", false),
            new TokenSpec("WS", "[ \\t\\r\\n]+", true),
            new TokenSpec("COMMENT", "//[^\\r\\n]*", true),
            new TokenSpec("SYMBOL", "==|!=|<=|>=|=>|->|[(){}\\[\\],:;=+*/<>|]|-", false));
    private static final DFA AUTOMATON = buildAutomaton();

    public record Result(List<Token> tokens, List<String> errors) {}

    public static Result scan(String source) {
        List<Token> tokens = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int offset = 0;
        int line = 1;
        int column = 1;
        while (offset < source.length()) {
            int state = AUTOMATON.start;
            int end = offset;
            Integer rule = null;
            for (int i = offset; i < source.length() && source.charAt(i) < NFA.ALPHABET; i++) {
                state = AUTOMATON.trans[state][source.charAt(i)];
                if (state == AUTOMATON.trapState) break;
                if (AUTOMATON.acceptTokenIndex[state] != null) {
                    end = i + 1;
                    rule = AUTOMATON.acceptTokenIndex[state];
                }
            }
            if (rule == null) {
                errors.add(line + ":" + column + ": недопустимый символ U+" + String.format("%04X", (int) source.charAt(offset)));
                end = offset + 1;
            } else if (!RULES.get(rule).skip) {
                String text = source.substring(offset, end);
                String kind = KEYWORDS.contains(text) ? text : RULES.get(rule).name;
                tokens.add(new Token(kind, text, offset, line, column));
            }
            while (offset < end) {
                char c = source.charAt(offset++);
                if (c == '\r' || c == '\n' && (offset < 2 || source.charAt(offset - 2) != '\r')) {
                    line++;
                    column = 1;
                } else if (c != '\n') {
                    column++;
                }
            }
        }
        tokens.add(new Token("EOF", "<EOF>", offset, line, column));
        return new Result(List.copyOf(tokens), List.copyOf(errors));
    }

    private static DFA buildAutomaton() {
        NFA nfa = new NFA();
        int start = nfa.newState();
        for (int i = 0; i < RULES.size(); i++) {
            NFA.Frag fragment = new RegexToNFA(RULES.get(i).regex, nfa).parse();
            nfa.addEdge(start, NFA.EPS, fragment.start);
            nfa.acceptLabel.put(fragment.accept, i);
        }
        return Hopcroft.minimize(SubsetConstruction.build(nfa, RULES, start));
    }
}
