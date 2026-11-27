/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.frapi;

/** Stands in for Identifier. */
public final class FakeIdentifier {
    private final String text;

    private FakeIdentifier(String text) {
        this.text = text;
    }

    public static FakeIdentifier fromNamespaceAndPath(String namespace, String path) {
        return new FakeIdentifier(namespace + ":" + path);
    }

    @Override
    public String toString() {
        return text;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FakeIdentifier id && id.text.equals(text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }
}
