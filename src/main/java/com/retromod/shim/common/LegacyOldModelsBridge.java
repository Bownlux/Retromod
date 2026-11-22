/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/** Moves old models off HierarchicalModel and AgeableListModel, which 1.21.2 removed, onto rebuilt bases. */
public final class LegacyOldModelsBridge {

    public static final String FLAG = "legacy-old-models";
    static final String HIERARCHICAL = "net/minecraft/client/model/HierarchicalModel";
    static final String AGEABLE = "net/minecraft/client/model/AgeableListModel";
    static final String HIERARCHICAL_BASE = RebuiltApiClasses.ROOT + "minecraft/client/model/LegacyHierarchicalModel";
    static final String AGEABLE_BASE = RebuiltApiClasses.ROOT + "minecraft/client/model/LegacyAgeableListModel";
    static final String ROOT_DESC = "()Lnet/minecraft/client/model/geom/ModelPart;";
    static final String DRAW_DESC = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V";
    static final String OLD_DRAW_DESC = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V";
    static final String MODEL = "net/minecraft/client/model/Model";
    static final String DRAW_HELPER = "com/retromod/generated/LegacyModelDraw";
    // The bases under their Mojang, intermediary and rebuilt names, since a mod's own classes are read as shipped.
    private static final Set<String> HIERARCHICAL_NAMES = Set.of(HIERARCHICAL, "net/minecraft/class_5597", HIERARCHICAL_BASE);
    private static final Set<String> AGEABLE_NAMES = Set.of(AGEABLE, "net/minecraft/class_4592", AGEABLE_BASE);

    private LegacyOldModelsBridge() {}

    /** Registers the rebuilt bases where the host has neither old one, as 1.21.11 and 26.x have not. */
    public static void register(RetromodTransformer t) {
        register(t, ClassResourceInspector::read);
    }

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        boolean hierarchical = host.apply(HIERARCHICAL) == null;
        boolean ageable = host.apply(AGEABLE) == null;
        if (hierarchical) {
            RebuiltApiClasses.registerTree(t, HIERARCHICAL_BASE);
            t.registerClassRedirect(HIERARCHICAL, HIERARCHICAL_BASE);
        }
        if (ageable) {
            RebuiltApiClasses.registerTree(t, AGEABLE_BASE);
            t.registerClassRedirect(AGEABLE, AGEABLE_BASE);
        }
        if (hierarchical || ageable) t.enablePostProcess(FLAG);
        registerFourArgumentDraw(t, host.apply(MODEL));
    }

    /** 26.2 dropped the old draw without a color; an old call draws with white, as that overload did. */
    static void registerFourArgumentDraw(RetromodTransformer t, ClassNode model) {
        boolean dropped = model == null
                ? com.retromod.core.RetromodVersion.compareMcVersions(com.retromod.core.RetromodVersion.TARGET_MC_VERSION, "26.2") >= 0
                : model.methods.stream().noneMatch(m -> m.name.equals("renderToBuffer") && m.desc.equals(OLD_DRAW_DESC));
        if (!dropped || t.getSyntheticClasses().containsKey(DRAW_HELPER)) return;
        t.registerSyntheticClass(DRAW_HELPER, generateDrawHelper());
        // Any model type names it, so a call through a mod's own model type takes the helper too.
        t.registerInheritedMethodRedirect(MODEL, "renderToBuffer", OLD_DRAW_DESC, DRAW_HELPER, "renderToBuffer",
                "(L" + MODEL + ";" + OLD_DRAW_DESC.substring(1));
    }

    /** {@code static renderToBuffer(model, pose, buffer, light, overlay)}: the five-argument draw with white. */
    static byte[] generateDrawHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_FINAL
                | org.objectweb.asm.Opcodes.ACC_SUPER, DRAW_HELPER, null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor m = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "renderToBuffer", "(L" + MODEL + ";" + OLD_DRAW_DESC.substring(1), null, null);
        m.visitCode();
        for (int slot = 0; slot < 3; slot++) m.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, slot);
        m.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, 3);
        m.visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, 4);
        m.visitInsn(org.objectweb.asm.Opcodes.ICONST_M1);
        m.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, MODEL, "renderToBuffer", DRAW_DESC, false);
        m.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Renames an old model's own root() and renderToBuffer overrides, which the host made final, so the class loads.
     * Returns the original array when the class is not an old model.
     */
    public static byte[] renameFinalOverrides(byte[] classBytes, Function<String, byte[]> jarClasses) {
        ClassReader reader = new ClassReader(classBytes);
        boolean hierarchical = extendsAny(reader.getSuperName(), HIERARCHICAL_NAMES, jarClasses);
        boolean ageable = !hierarchical && extendsAny(reader.getSuperName(), AGEABLE_NAMES, jarClasses);
        boolean callsRoot = contains(classBytes, ROOT_DESC);
        if (!hierarchical && !ageable && !callsRoot) return classBytes;
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        boolean changed = callsRoot && renameRootCalls(node, hierarchical, jarClasses);
        if (!hierarchical && !ageable) return changed ? write(node) : classBytes;
        for (MethodNode method : node.methods) {
            if (hierarchical && method.name.equals("root") && method.desc.equals(ROOT_DESC)) {
                method.name = "legacyRoot";
                changed = true;
            } else if (method.name.equals("renderToBuffer") && method.desc.equals(DRAW_DESC)) {
                // The host draws the model's parts itself; the base reports once that this draw is skipped.
                method.name = "legacyRenderToBuffer";
                changed = true;
            }
        }
        return changed ? write(node) : classBytes;
    }

    /** Points an old call of root() on a hierarchical model at the model's own root, since the host's root() is final. */
    private static boolean renameRootCalls(ClassNode node, boolean selfHierarchical, Function<String, byte[]> jarClasses) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !call.name.equals("root") || !call.desc.equals(ROOT_DESC)) continue;
                boolean family = call.owner.equals(node.name) ? selfHierarchical
                        : HIERARCHICAL_NAMES.contains(call.owner) || extendsAny(superOf(call.owner, jarClasses), HIERARCHICAL_NAMES, jarClasses);
                if (!family) continue;
                call.name = "legacyRoot";
                changed = true;
            }
        }
        return changed;
    }

    private static String superOf(String owner, Function<String, byte[]> jarClasses) {
        byte[] bytes = jarClasses == null ? null : jarClasses.apply(owner);
        return bytes == null ? null : new ClassReader(bytes).getSuperName();
    }

    private static boolean contains(byte[] bytes, String text) {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + needle.length <= bytes.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Whether {@code superName}, or a class above it in the mod's jar, is one of {@code bases}. */
    private static boolean extendsAny(String superName, Set<String> bases, Function<String, byte[]> jarClasses) {
        Set<String> seen = new HashSet<>();
        String current = superName;
        while (current != null && seen.add(current)) {
            if (bases.contains(current)) return true;
            byte[] bytes = jarClasses == null ? null : jarClasses.apply(current);
            if (bytes == null) return false;
            current = new ClassReader(bytes).getSuperName();
        }
        return false;
    }
}
