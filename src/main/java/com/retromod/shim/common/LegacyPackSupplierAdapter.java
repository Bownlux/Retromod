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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Arrays;
import java.util.function.Function;

/** Gives a pack supplier written before 26.3 the openMetadata and openResources that 26.3 calls. */
public final class LegacyPackSupplierAdapter {

    /** Post-process flag: the host's pack supplier opens metadata and resource streams, not single packs. */
    public static final String FLAG = "legacy-pack-supplier";

    static final String SUPPLIER = "net/minecraft/server/packs/repository/Pack$ResourcesSupplier";
    private static final String LOCATION = "Lnet/minecraft/server/packs/PackLocationInfo;";
    private static final String METADATA = "Lnet/minecraft/server/packs/repository/Pack$Metadata;";
    private static final String RESOURCES = "Lnet/minecraft/server/packs/PackResources;";
    static final String OPEN_PRIMARY = "(" + LOCATION + ")" + RESOURCES;
    static final String OPEN_FULL = "(" + LOCATION + METADATA + ")" + RESOURCES;
    static final String OPEN_METADATA = "(" + LOCATION + ")Lnet/minecraft/server/packs/PackMetadataResources;";
    static final String OPEN_RESOURCES = "(" + LOCATION + METADATA + ")Ljava/util/stream/Stream;";
    /** The release that replaced openPrimary and openFull. */
    static final String VERSION = "26.3";

    private LegacyPackSupplierAdapter() {}

    /** Turns the pass on where the host's supplier has openMetadata and no openPrimary; offline, the target decides. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (hostReplacedIt(host.apply(SUPPLIER))) t.enablePostProcess(FLAG);
    }

    static boolean hostReplacedIt(ClassNode supplier) {
        if (supplier == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, VERSION) >= 0;
        return declares(supplier, "openMetadata", OPEN_METADATA) && !declares(supplier, "openPrimary", OPEN_PRIMARY);
    }

    /** Post-remap pass; {@code modVersion} is null when the mod declares none. Returns the original array when nothing changes. */
    public static byte[] apply(byte[] classBytes, String modVersion) {
        if (classBytes == null) return null;
        ClassReader reader = new ClassReader(classBytes);
        if ((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0 || !Arrays.asList(reader.getInterfaces()).contains(SUPPLIER)) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        // A mixin's members are merged into its target, and a mod built for 26.3 already answers the new methods.
        if (OldRenderShapes.isMixin(node) || !builtBefore263(node, modVersion)) return classBytes;
        boolean changed = false;
        // The host reads the pack's metadata section from what openMetadata returns, and a pack is metadata too.
        if (declaresConcrete(node, "openPrimary", OPEN_PRIMARY) && !declares(node, "openMetadata", OPEN_METADATA)) {
            node.methods.add(openMetadata(node.name));
            changed = true;
        }
        // The host's own suppliers stream one pack, with any overlays already folded in, as openFull did.
        if (declaresConcrete(node, "openFull", OPEN_FULL) && !declares(node, "openResources", OPEN_RESOURCES)) {
            node.methods.add(openResources(node.name));
            changed = true;
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** {@code openMetadata(location)}, answered by the class's own {@code openPrimary(location)}. */
    private static MethodNode openMetadata(String owner) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "openMetadata", OPEN_METADATA, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "openPrimary", OPEN_PRIMARY, false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    /** {@code openResources(location, metadata)}, a stream of the class's own {@code openFull(location, metadata)}. */
    private static MethodNode openResources(String owner) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "openResources", OPEN_RESOURCES, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "openFull", OPEN_FULL, false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/stream/Stream", "of",
                "(Ljava/lang/Object;)Ljava/util/stream/Stream;", true));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    /** Whether the mod was built before 26.3; without a version, only a concrete class counts, since it must answer the host. */
    private static boolean builtBefore263(ClassNode node, String modVersion) {
        if (modVersion == null) return (node.access & Opcodes.ACC_ABSTRACT) == 0;
        return RetromodVersion.compareMcVersions(modVersion, VERSION) < 0;
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    private static boolean declaresConcrete(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc)
                && (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0);
    }
}
