package ru.naboka.traver;

/** One lexical rule: name, its regex, and whether it should be skipped (WS/COMMENT). */
public class TokenSpec {
    public final String name;
    public final String regex;
    public final boolean skip;

    public TokenSpec(String name, String regex, boolean skip) {
        this.name = name;
        this.regex = regex;
        this.skip = skip;
    }
}
