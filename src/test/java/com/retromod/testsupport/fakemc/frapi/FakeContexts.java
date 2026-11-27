/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** The old render context calls as translated code makes them; the test loads Retromod's helper under this name. */
public final class FakeContexts {
    private FakeContexts() {}

    public static boolean isFaceCulled(FakeQuadEmitter context, FakeDirection face) {
        throw new AssertionError("replaced by the rebuilt helper");
    }

    public static FakeModelConsumer bakedModelConsumer(FakeQuadEmitter context) {
        throw new AssertionError("replaced by the rebuilt helper");
    }

    public static void pushTransform(FakeQuadEmitter context, FakeQuadTransform transform) {
        throw new AssertionError("replaced by the rebuilt helper");
    }
}
