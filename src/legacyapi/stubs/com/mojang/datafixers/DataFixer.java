// Compile-only stub of a 26.3 host type for src/legacyapi; never packaged.
package com.mojang.datafixers;

import com.mojang.serialization.Dynamic;

public interface DataFixer {
    <T> Dynamic<T> update(DSL.TypeReference type, Dynamic<T> input, int version, int newVersion);
}
