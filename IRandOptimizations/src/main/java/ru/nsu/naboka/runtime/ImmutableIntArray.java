package ru.nsu.naboka.runtime;

import java.util.Arrays;

public final class ImmutableIntArray {
    private final int[] data;

    private ImmutableIntArray(int[] data) {
        this.data = data;
    }

    public static ImmutableIntArray of(int... elems) {
        return copyOf(elems);
    }

    public static ImmutableIntArray copyOf(int[] src) {
        return new ImmutableIntArray(src.clone());
    }

    public int length() {
        return data.length;
    }

    public int get(int idx) {
        return data[idx];
    }

    public ImmutableIntArray with(int idx, int value) {
        int[] copy = data.clone();
        copy[idx] = value;
        return new ImmutableIntArray(copy);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImmutableIntArray array && Arrays.equals(data, array.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return Arrays.toString(data);
    }
}
