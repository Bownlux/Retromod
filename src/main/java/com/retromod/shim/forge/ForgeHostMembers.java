/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/** Answers whether a Forge host's class lacks one field or method, asking ForgeHostShape about the host first. */
final class ForgeHostMembers {

    private ForgeHostMembers() {}

    /** Whether the Forge host's {@code owner} lacks the field; offline, a Forge target from {@code offlineFrom}. */
    static boolean lacksField(String owner, String name, String offlineFrom) {
        return lacksField(owner, name, offlineFrom, ClassResourceInspector::read);
    }

    /** Whether the Forge host's {@code owner} lacks the method; offline, a Forge target from {@code offlineFrom}. */
    static boolean lacksMethod(String owner, String name, String desc, String offlineFrom) {
        return lacksMethod(owner, name, desc, offlineFrom, ClassResourceInspector::read);
    }

    static boolean lacksField(String owner, String name, String offlineFrom, Function<String, ClassNode> host) {
        return lacks(owner, offlineFrom, host, node -> node.fields.stream().noneMatch(f -> f.name.equals(name)));
    }

    static boolean lacksMethod(String owner, String name, String desc, String offlineFrom,
            Function<String, ClassNode> host) {
        return lacks(owner, offlineFrom, host,
                node -> node.methods.stream().noneMatch(m -> m.name.equals(name) && m.desc.equals(desc)));
    }

    private static boolean lacks(String owner, String offlineFrom, Function<String, ClassNode> host,
            Predicate<ClassNode> lacksMember) {
        // Another loader, an older target, or a Forge host without the owner class takes no member bridge.
        if (!ForgeHostShape.matches(List.of(owner), List.of(), offlineFrom, host)) return false;
        ClassNode node = host.apply(owner);
        // Offline the CLI cannot read Forge's classes, so the target version has already answered.
        return node == null || lacksMember.test(node);
    }
}
