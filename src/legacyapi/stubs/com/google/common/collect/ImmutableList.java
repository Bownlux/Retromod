// Compile-only stub of the Guava type Minecraft ships, for src/legacyapi; never packaged.
package com.google.common.collect;

import java.util.AbstractList;
import java.util.Collection;

public abstract class ImmutableList<E> extends AbstractList<E> {
    public static <E> ImmutableList<E> copyOf(Collection<? extends E> elements) { throw new UnsupportedOperationException(); }
}
