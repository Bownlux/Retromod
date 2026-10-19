/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc;

/**
 * Stands in for Minecraft's {@code Identifier} (or {@code ResourceLocation}) once a test renames
 * it into Minecraft's package. It parses and prints ids the way Minecraft does, nothing more.
 */
public final class FakeIdentifier {

    private final String namespace;
    private final String path;

    private FakeIdentifier(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    /** A bare path is Minecraft's, as in the real parser. */
    public static FakeIdentifier parse(String text) {
        int colon = text.indexOf(':');
        return colon < 0
                ? new FakeIdentifier("minecraft", text)
                : new FakeIdentifier(text.substring(0, colon), text.substring(colon + 1));
    }

    public static FakeIdentifier fromNamespaceAndPath(String namespace, String path) {
        return new FakeIdentifier(namespace, path);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FakeIdentifier id && id.namespace.equals(namespace) && id.path.equals(path);
    }

    @Override
    public int hashCode() {
        return namespace.hashCode() * 31 + path.hashCode();
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
