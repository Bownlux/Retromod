/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

/** Keeps an old pack a {@code PackResources} once 26.3's move rebases it off the deleted {@code AbstractPackResources}. */
public final class LegacyPackResourcesBaseAdapter {

    /** Post-process flag: the host replaced {@code AbstractPackResources} with {@code AbstractPackMetadataResources}. */
    public static final String FLAG = "legacy-pack-resources-base";

    static final String OLD_BASE = "net/minecraft/server/packs/AbstractPackResources";
    static final String NEW_BASE = "net/minecraft/server/packs/AbstractPackMetadataResources";
    static final String RESOURCES = "net/minecraft/server/packs/PackResources";
    private static final String TYPE = "Lnet/minecraft/server/packs/PackType;";
    /** The members {@code PackResources} adds to the metadata ones, which an old pack implemented itself. */
    static final List<String> RESOURCE_METHODS = List.of(
            "getResource(" + TYPE + "Lnet/minecraft/resources/Identifier;)Lnet/minecraft/server/packs/resources/IoSupplier;",
            "listResources(" + TYPE + "Ljava/lang/String;Ljava/lang/String;L" + RESOURCES + "$ResourceOutput;)V",
            "getNamespaces(" + TYPE + ")Ljava/util/Set;");
    /** The release that deleted the old base. */
    static final String VERSION = "26.3";

    private LegacyPackResourcesBaseAdapter() {}

    /** Turns the pass on where the host has the new base and not the old one; offline, the target decides. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (hostReplacedIt(host)) t.enablePostProcess(FLAG);
    }

    static boolean hostReplacedIt(Function<String, ClassNode> host) {
        if (host.apply(NEW_BASE) == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, VERSION) >= 0;
        }
        return host.apply(OLD_BASE) == null;
    }

    /** Post-remap pass; {@code modVersion} is null when the mod declares none. Returns the original array when nothing changes. */
    public static byte[] apply(byte[] classBytes, String modVersion) {
        if (classBytes == null) return null;
        ClassReader reader = new ClassReader(classBytes);
        if (!NEW_BASE.equals(reader.getSuperName()) || List.of(reader.getInterfaces()).contains(RESOURCES)) return classBytes;
        // A mod built for 26.3 may extend the metadata base on purpose; only an older mod got it from the move.
        if (modVersion != null && RetromodVersion.compareMcVersions(modVersion, VERSION) >= 0) return classBytes;
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        if (OldRenderShapes.isMixin(node) || !answersResources(node, modVersion != null)) return classBytes;
        node.interfaces.add(RESOURCES);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Whether the class declares every resource member; an abstract one of a known old mod may leave them to its subclasses, as before. */
    private static boolean answersResources(ClassNode node, boolean knownOld) {
        if (knownOld && (node.access & Opcodes.ACC_ABSTRACT) != 0) return true;
        return RESOURCE_METHODS.stream().allMatch(method -> node.methods.stream()
                .anyMatch(m -> method.equals(m.name + m.desc) && (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0));
    }
}
