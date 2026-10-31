// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.core;

import java.util.AbstractList;
import java.util.List;

public class NonNullList<E> extends AbstractList<E> {
    protected NonNullList(List<E> list, E defaultValue) { throw new UnsupportedOperationException(); }
    public static <E> NonNullList<E> create() { throw new UnsupportedOperationException(); }
    public static <E> NonNullList<E> withSize(int size, E defaultValue) { throw new UnsupportedOperationException(); }
    @Override public E get(int index) { throw new UnsupportedOperationException(); }
    @Override public E set(int index, E element) { throw new UnsupportedOperationException(); }
    @Override public int size() { throw new UnsupportedOperationException(); }
}
