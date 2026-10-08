/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge.capability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Forge capability data is saved only through the attachment serializer shape the proxy speaks. */
class ForgeCapabilityStorageTest {

    /** NeoForge 21.1: {@code S write(T, HolderLookup.Provider)}. */
    interface TagSerializer {
        Object read(Object holder, Object tag, HolderLookup.Provider provider);

        Object write(Object attachment, HolderLookup.Provider provider);
    }

    /** NeoForge 21.6 and newer: {@code boolean write(T, ValueOutput)}. */
    interface ValueSerializer {
        Object read(Object holder, Object input);

        boolean write(Object attachment, Object output);
    }

    static final class HolderLookup {
        interface Provider {}
    }

    @Test
    void tagShapedSerializerIsSupported() {
        assertTrue(ForgeCapabilityStorage.usesTagSerializer(TagSerializer.class));
    }

    @Test
    void valueOutputSerializerIsLeftAlone() {
        assertFalse(ForgeCapabilityStorage.usesTagSerializer(ValueSerializer.class),
                "a proxy returning a tag from boolean write(T, ValueOutput) fails every save");
    }
}
