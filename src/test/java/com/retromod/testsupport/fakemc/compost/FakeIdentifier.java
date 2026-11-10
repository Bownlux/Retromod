/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.compost;


/** A namespaced id in Minecraft's namespace. */
public final class FakeIdentifier {
    private final String path;

    private FakeIdentifier(String path) {
        this.path = path;
    }

    public static FakeIdentifier withDefaultNamespace(String path) {
        return new FakeIdentifier(path);
    }

    public String getNamespace() {
        return "minecraft";
    }

    public String getPath() {
        return path;
    }
}
