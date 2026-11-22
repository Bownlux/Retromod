/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.deferredids;

/** Stand-in for {@code Identifier}: a namespace and a path. */
public final class FakeIdentifier {
    private final String namespace;
    private final String path;

    private FakeIdentifier(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    public static FakeIdentifier fromNamespaceAndPath(String namespace, String path) {
        return new FakeIdentifier(namespace, path);
    }

    public static FakeIdentifier tryParse(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? new FakeIdentifier("minecraft", id) : new FakeIdentifier(id.substring(0, colon), id.substring(colon + 1));
    }

    public String getNamespace() {
        return namespace;
    }

    public String getPath() {
        return path;
    }

    public FakeIdentifier withPrefix(String prefix) {
        return new FakeIdentifier(namespace, prefix + path);
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
