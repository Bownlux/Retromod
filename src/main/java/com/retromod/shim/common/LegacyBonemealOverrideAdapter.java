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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Gives a block written before 26.3 the bonemeal methods 26.3 calls, which take a bonemeal source, and passes one on its old calls. */
public final class LegacyBonemealOverrideAdapter {

    /** Post-process flag: the host's bonemeal methods take a {@code BonemealSource}. */
    public static final String FLAG = "legacy-bonemeal-source";

    static final String BONEMEALABLE = "net/minecraft/world/level/block/BonemealableBlock";
    static final String SOURCE = "net/minecraft/world/level/block/BonemealSource";
    /** The release that added the source. */
    static final String VERSION = "26.3";
    // Each method's descriptor up to 26.2; 26.3 adds the source as the last parameter.
    static final Map<String, String> OLD = Map.of(
            "isValidBonemealTarget", "(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;)Z",
            "isBonemealSuccess", "(Lnet/minecraft/world/level/Level;Lnet/minecraft/util/RandomSource;"
                    + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z",
            "performBonemeal", "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/util/RandomSource;"
                    + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V");
    // An old call is made as a player's bone meal, the source vanilla's own bone meal passes.
    static final String CALL_SOURCE = "INTERACTION";

    private LegacyBonemealOverrideAdapter() {}

    /** Turns the pass on where the host's bonemeal methods take a source; offline, the target decides. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        if (hostTakesSource(host.apply(BONEMEALABLE))) t.enablePostProcess(FLAG);
    }

    static boolean hostTakesSource(ClassNode bonemealable) {
        if (bonemealable == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, VERSION) >= 0;
        return bonemealable.methods.stream().anyMatch(m -> m.name.equals("performBonemeal")
                && m.desc.equals(withSource(OLD.get("performBonemeal"))));
    }

    /**
     * Post-remap pass; {@code modVersion} is null when the mod declares none, and {@code classes} reads a class of the
     * mod, a library or the host by name, as the remap leaves it. Returns the original array when nothing changes.
     */
    public static byte[] apply(byte[] classBytes, String modVersion, Function<String, byte[]> classes) {
        if (classBytes == null || !namesAny(classBytes)) return classBytes;
        // A mod built for 26.3 already writes the new methods and passes its own source.
        if (modVersion != null && RetromodVersion.compareMcVersions(modVersion, VERSION) >= 0) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = passSourceOnOldCalls(node, classes);
        // A mixin's members are merged into its target, so only the mod's own classes get the new methods.
        if (!OldRenderShapes.isMixin(node)) changed |= addNewOverrides(node);
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    // Each old method the class declares gets the new one, which drops the source and calls it.
    private static boolean addNewOverrides(ClassNode node) {
        boolean isInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
        List<MethodNode> added = new ArrayList<>();
        for (MethodNode method : node.methods) {
            String old = OLD.get(method.name);
            if (old == null || !old.equals(method.desc) || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0
                    || declares(node, method.name, withSource(old))) {
                continue;
            }
            added.add(newOverride(node.name, isInterface, method.name, old));
        }
        node.methods.addAll(added);
        return !added.isEmpty();
    }

    /** {@code name(args..., source)}, answered by the class's own {@code name(args...)}. */
    private static MethodNode newOverride(String owner, boolean isInterface, String name, String old) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, name, withSource(old), null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        Type[] arguments = Type.getArgumentTypes(old);
        for (int i = 0; i < arguments.length; i++) method.instructions.add(new VarInsnNode(Opcodes.ALOAD, i + 1));
        // Virtual, so a subclass that overrides the old method is still asked.
        method.instructions.add(new MethodInsnNode(isInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
                owner, name, old, isInterface));
        method.instructions.add(new InsnNode(Type.getReturnType(old).getOpcode(Opcodes.IRETURN)));
        return method;
    }

    // A call to an old method the mod does not declare itself would fail, so it passes a source to the new one.
    private static boolean passSourceOnOldCalls(ClassNode node, Function<String, byte[]> classes) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() == Opcodes.INVOKESTATIC) continue;
                String old = OLD.get(call.name);
                if (old == null || !old.equals(call.desc) || declaresOld(node, call.owner, call.name, classes)) continue;
                method.instructions.insertBefore(call, new FieldInsnNode(Opcodes.GETSTATIC, SOURCE, CALL_SOURCE, "L" + SOURCE + ";"));
                call.desc = withSource(old);
                changed = true;
            }
        }
        return changed;
    }

    /** Whether {@code owner} or a type above it that the mod or a library ships declares the old method. */
    private static boolean declaresOld(ClassNode self, String owner, String name, Function<String, byte[]> classes) {
        Set<String> seen = new HashSet<>();
        List<String> pending = new ArrayList<>(List.of(owner));
        while (!pending.isEmpty() && seen.size() < 64) {
            String current = pending.remove(pending.size() - 1);
            // The host no longer declares the old methods, so its classes are not read.
            if (!seen.add(current) || current.startsWith("net/minecraft/") || current.startsWith("java/")) continue;
            ClassNode type = current.equals(self.name) ? self : read(classes, current);
            if (type == null) continue;
            if (declares(type, name, OLD.get(name))) return true;
            if (type.superName != null) pending.add(type.superName);
            pending.addAll(type.interfaces);
        }
        return false;
    }

    private static ClassNode read(Function<String, byte[]> classes, String name) {
        byte[] bytes = classes == null ? null : classes.apply(name);
        if (bytes == null) return null;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        return node;
    }

    static String withSource(String old) {
        return old.replace(")", "L" + SOURCE + ";)");
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    // A cheap scan of the raw bytes, so a class that names none of the methods is not parsed.
    private static boolean namesAny(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        for (String name : OLD.keySet()) {
            if (text.contains(name)) return true;
        }
        return false;
    }
}
