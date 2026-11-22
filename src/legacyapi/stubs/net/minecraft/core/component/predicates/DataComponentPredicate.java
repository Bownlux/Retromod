// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.core.component.predicates;

import com.mojang.serialization.Codec;
import net.minecraft.core.component.DataComponentGetter;

public interface DataComponentPredicate {
    boolean matches(DataComponentGetter components);

    interface Type<T extends DataComponentPredicate> {
        Codec<T> codec();
    }

    abstract class TypeBase<T extends DataComponentPredicate> implements Type<T> {
        public TypeBase(Codec<T> codec) {}
        public Codec<T> codec() { throw new UnsupportedOperationException(); }
    }

    final class ConcreteType<T extends DataComponentPredicate> extends TypeBase<T> {
        public ConcreteType(Codec<T> codec) { super(codec); }
    }
}
