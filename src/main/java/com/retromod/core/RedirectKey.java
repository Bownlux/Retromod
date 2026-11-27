/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import java.util.Objects;

/**
 * A class or member name the loader API runtime can claim in {@link RetromodTransformer}.
 *
 * @param kind  what the key names
 * @param owner internal class name, such as {@code net/fabricmc/fabric/api/event/Event}
 * @param name  member name, {@code <init>} for a constructor, or null for a type or implements key
 * @param desc  method descriptor, or null for a type, field or implements key
 */
public record RedirectKey(Kind kind, String owner, String name, String desc) {

    /** What a key names. */
    public enum Kind { TYPE, METHOD, FIELD, IMPLEMENTS }

    public RedirectKey {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(owner, "owner");
        if (kind != Kind.TYPE && kind != Kind.IMPLEMENTS) Objects.requireNonNull(name, "name");
        if (kind == Kind.METHOD) Objects.requireNonNull(desc, "desc");
    }

    /** A whole class: its name and every member keyed on it. */
    public static RedirectKey type(String owner) {
        return new RedirectKey(Kind.TYPE, owner, null, null);
    }

    /** One method, or a constructor when {@code name} is {@code <init>}. */
    public static RedirectKey method(String owner, String name, String desc) {
        return new RedirectKey(Kind.METHOD, owner, name, desc);
    }

    /** One field, by owner and name. */
    public static RedirectKey field(String owner, String name) {
        return new RedirectKey(Kind.FIELD, owner, name, null);
    }

    /** An interface's place in implements lists only; members and calls keyed on it stay open to other registrations. */
    public static RedirectKey implemented(String iface) {
        return new RedirectKey(Kind.IMPLEMENTS, iface, null, null);
    }

    /** {@code owner}, {@code owner.name(desc)}, {@code owner.name} or {@code implements owner}, for logs and reports. */
    @Override
    public String toString() {
        return switch (kind) {
            case TYPE -> owner;
            case METHOD -> owner + "." + name + desc;
            case FIELD -> owner + "." + name;
            case IMPLEMENTS -> "implements " + owner;
        };
    }
}
