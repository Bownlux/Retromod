/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testsupport.fakemc.atlas;

/** Stands in for {@code Identifier}: a namespace and a path. */
public final class FakeAtlasId {
    private final String namespace;
    private final String path;

    private FakeAtlasId(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    public static FakeAtlasId parse(String text) {
        int colon = text.indexOf(':');
        return colon < 0 ? new FakeAtlasId("minecraft", text) : new FakeAtlasId(text.substring(0, colon), text.substring(colon + 1));
    }

    public String getPath() {
        return path;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FakeAtlasId id && id.namespace.equals(namespace) && id.path.equals(path);
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
