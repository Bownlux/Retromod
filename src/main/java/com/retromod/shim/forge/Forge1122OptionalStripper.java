/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Set;

/**
 * FML 1.12's {@code @Optional} stripping, done once at transform time. A 1.12 mod marks the
 * interfaces and methods that belong to another mod's API with {@code @Optional.Interface},
 * {@code @Optional.InterfaceList} and {@code @Optional.Method}, and FML removed them when that
 * mod was absent. Modern loaders ignore the annotation, so the class keeps a missing interface
 * and fails to load with {@code NoClassDefFoundError}, often a proxy or event handler the whole
 * mod depends on.
 *
 * <p>The marked parts are always removed. The other mod's 1.12 API classes do not exist on a
 * modern host, even when a mod with the same id is installed, so keeping them can only fail.
 * Only Minecraft and Forge themselves are treated as present.
 */
final class Forge1122OptionalStripper {

    private Forge1122OptionalStripper() {}

    static final String MARKER = "net/minecraftforge/fml/common/Optional";
    private static final String INTERFACE = "Lnet/minecraftforge/fml/common/Optional$Interface;";
    private static final String INTERFACE_LIST = "Lnet/minecraftforge/fml/common/Optional$InterfaceList;";
    private static final String METHOD = "Lnet/minecraftforge/fml/common/Optional$Method;";
    private static final Set<String> ALWAYS_PRESENT = Set.of("minecraft", "forge", "fml", "mcp");

    /** Removes every optional interface and method of {@code cn}. Returns true when it changed. */
    static boolean strip(ClassNode cn) {
        boolean changed = false;
        for (AnnotationNode a : annotations(cn.visibleAnnotations, cn.invisibleAnnotations)) {
            if (a.desc.equals(INTERFACE)) {
                changed |= stripInterface(cn, a);
            } else if (a.desc.equals(INTERFACE_LIST)) {
                Object list = value(a, "value");
                if (list instanceof List<?> entries) {
                    for (Object entry : entries) {
                        if (entry instanceof AnnotationNode inner) changed |= stripInterface(cn, inner);
                    }
                }
            }
        }
        changed |= cn.methods.removeIf(Forge1122OptionalStripper::isOptionalMethod);
        return changed;
    }

    private static boolean stripInterface(ClassNode cn, AnnotationNode a) {
        if (!(value(a, "iface") instanceof String iface) || keeps(a) || cn.interfaces == null) {
            return false;
        }
        return cn.interfaces.remove(iface.replace('.', '/'));
    }

    private static boolean isOptionalMethod(MethodNode m) {
        if (m.name.equals("<init>") || m.name.equals("<clinit>")) return false;
        for (AnnotationNode a : annotations(m.visibleAnnotations, m.invisibleAnnotations)) {
            if (a.desc.equals(METHOD) && !keeps(a)) return true;
        }
        return false;
    }

    private static boolean keeps(AnnotationNode a) {
        Object modid = value(a, "modid");
        return modid instanceof String id && ALWAYS_PRESENT.contains(id.toLowerCase(java.util.Locale.ROOT));
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible,
                                                    List<AnnotationNode> invisible) {
        if (visible == null) return invisible == null ? List.of() : invisible;
        if (invisible == null) return visible;
        java.util.ArrayList<AnnotationNode> all = new java.util.ArrayList<>(visible);
        all.addAll(invisible);
        return all;
    }

    private static Object value(AnnotationNode a, String name) {
        if (a.values == null) return null;
        for (int i = 0; i + 1 < a.values.size(); i += 2) {
            if (name.equals(a.values.get(i))) return a.values.get(i + 1);
        }
        return null;
    }
}
