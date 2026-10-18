package ru.nsu.naboka;

public record Token(String kind, String text, int offset, int line, int column) {
    public String location() {
        return line + ":" + column;
    }
}
