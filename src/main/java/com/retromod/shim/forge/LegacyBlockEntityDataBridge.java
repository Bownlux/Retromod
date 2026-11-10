/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** Rebuilds a block entity's {@code getPersistentData()}, which Forge 26.2 removed, on the host's ValueIO saves. */
public final class LegacyBlockEntityDataBridge {

    public static final String FLAG = "forge-block-entity-data";

    static final String DATA = RebuiltApiClasses.ROOT + "forge/common/LegacyBlockEntityData";
    static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    static final String EXTENSION = "net/minecraftforge/common/extensions/IForgeBlockEntity";
    static final String GET = "getPersistentData";
    static final String GET_DESC = "()Lnet/minecraft/nbt/CompoundTag;";
    static final String HELPER_GET_DESC = "(Ljava/lang/Object;)Lnet/minecraft/nbt/CompoundTag;";
    static final String LOAD = "loadAdditional";
    static final String LOAD_DESC = "(Lnet/minecraft/world/level/storage/ValueInput;)V";
    static final String SAVE = "saveAdditional";
    static final String SAVE_DESC = "(Lnet/minecraft/world/level/storage/ValueOutput;)V";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    private LegacyBlockEntityDataBridge() {}

    /** Registers the bridge where the Forge host's block entities lack the method; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        // Forge 1.20.1 declared it on the extension interface and the block entity; a host with either keeps it.
        if (!ForgeHostMembers.lacksMethod(BLOCK_ENTITY, GET, GET_DESC, ForgeHostShape.FIRST_CHECKED_VERSION, host)
                || !ForgeHostMembers.lacksMethod(EXTENSION, GET, GET_DESC, ForgeHostShape.FIRST_CHECKED_VERSION, host)) {
            return;
        }
        RebuiltApiClasses.registerTree(t, DATA);
        // A call on a mod's own block entity names that class, so the redirect follows the hierarchy.
        t.registerInheritedMethodRedirect(BLOCK_ENTITY, GET, GET_DESC, DATA, GET, HELPER_GET_DESC);
        t.registerMethodRedirect(EXTENSION, GET, GET_DESC, DATA, GET, HELPER_GET_DESC, true);
        t.enablePostProcess(FLAG);
    }

    /** Post-remap pass: the first mod class under a Minecraft block entity saves and loads the data. */
    public static byte[] apply(byte[] classBytes, BiPredicate<String, String> inheritsFrom) {
        if (classBytes == null) return null;
        // Most classes have a mod parent or none in Minecraft, so the header decides before the code is read.
        ClassReader reader = new ClassReader(classBytes);
        String parent = reader.getSuperName();
        if (parent == null || !parent.startsWith("net/minecraft/")) return classBytes;
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        // Each class whose parent is Minecraft's covers every mod class below it, as Forge's base class did.
        if (!isFirstModBlockEntity(node, inheritsFrom)) return classBytes;
        hook(node, LOAD, LOAD_DESC, "load");
        hook(node, SAVE, SAVE_DESC, "save");
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean isFirstModBlockEntity(ClassNode node, BiPredicate<String, String> inheritsFrom) {
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/")) return false;
        if (node.superName == null || !node.superName.startsWith("net/minecraft/")) return false;
        // A mixin's methods merge into a Minecraft class, which has no data of its own to save.
        if (hasAnnotation(node.invisibleAnnotations) || hasAnnotation(node.visibleAnnotations)) return false;
        return inheritsFrom.test(node.name, BLOCK_ENTITY);
    }

    private static boolean hasAnnotation(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (annotation.desc.equals(MIXIN)) return true;
        }
        return false;
    }

    /**
     * Forge 1.20.1's base class read and wrote the data when a block entity's load or save reached it, so the helper
     * runs right after each call that reaches Minecraft's method. A class without an override gets one that does that.
     */
    private static void hook(ClassNode node, String name, String desc, String helper) {
        if (declared(node, name, desc) == null) {
            // Public, so the override never narrows a parent that made the method public.
            MethodNode added = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
            added.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            added.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            added.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, name, desc, false));
            added.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(added);
        }
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || !call.name.equals(name) || !call.desc.equals(desc) || !call.owner.startsWith("net/minecraft/")
                        || callsHelper(call.getNext())) {
                    continue;
                }
                // [this, value] is kept for the helper, which takes the same two after the parent is done.
                method.instructions.insertBefore(call, new InsnNode(Opcodes.DUP2));
                method.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC, DATA, helper,
                        "(Ljava/lang/Object;" + desc.substring(1), false));
            }
        }
    }

    /** Whether a class transformed twice already has the helper call here. */
    private static boolean callsHelper(AbstractInsnNode next) {
        return next instanceof MethodInsnNode call && call.owner.equals(DATA);
    }

    private static MethodNode declared(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }
}
