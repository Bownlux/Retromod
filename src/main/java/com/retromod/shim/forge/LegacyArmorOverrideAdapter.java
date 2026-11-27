/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.shim.common.OldRenderShapes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Gives an old mod's armor extension and item the hooks a Forge-family 26.x host calls, each answered by its old override. */
public final class LegacyArmorOverrideAdapter {

    /** Post-process flags, one per host family and hook kind, set by {@link LegacyArmorHooksBridge}. */
    public static final String NEO_ARMOR = "legacy-armor-hooks-neoforge";
    public static final String FORGE_ARMOR = "legacy-armor-hooks-forge";
    public static final String NEO_DISGUISE = "legacy-disguise-neoforge";
    public static final String FORGE_DISGUISE = "legacy-disguise-forge";

    static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    static final String SLOT = "Lnet/minecraft/world/entity/EquipmentSlot;";
    static final String LIVING = "Lnet/minecraft/world/entity/LivingEntity;";
    static final String HUMANOID = "Lnet/minecraft/client/model/HumanoidModel;";
    static final String MODEL = "Lnet/minecraft/client/model/Model;";
    static final String ID = "Lnet/minecraft/resources/Identifier;";
    static final String PLAYER = "Lnet/minecraft/world/entity/player/Player;";
    static final String LAYER_TYPE = "Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;";
    static final String LAYER = "Lnet/minecraft/client/resources/model/EquipmentClientInfo$Layer;";
    /** Forge 1.20.1's entity-taking model hooks, the same in NeoForge 21.1. */
    static final String OLD_HUMANOID = "(" + LIVING + STACK + SLOT + HUMANOID + ")" + HUMANOID;
    static final String OLD_GENERIC = "(" + LIVING + STACK + SLOT + HUMANOID + ")" + MODEL;

    private static final String NEO_EXT = "net/neoforged/neoforge/client/extensions/common/IClientItemExtensions";
    private static final String FORGE_EXT = "net/minecraftforge/client/extensions/common/IClientItemExtensions";
    private static final String NEW_TEXTURE = "(" + STACK + LAYER_TYPE + LAYER + ID + ")" + ID;
    // The old hook names, one of which a class must name before it is parsed.
    private static final List<String> OLD_NAMES = List.of("getHumanoidArmorModel", "getGenericArmorModel", "getArmorTexture",
            "getArmorLayerTintColor", "isEnderMask");

    private LegacyArmorOverrideAdapter() {}

    /** Whether any of the flags is on, so the class pass can skip parsing otherwise. */
    public static boolean enabled(Predicate<String> flags) {
        return flags.test(NEO_ARMOR) || flags.test(FORGE_ARMOR) || flags.test(NEO_DISGUISE) || flags.test(FORGE_DISGUISE);
    }

    /** Post-remap pass; {@code classes} reads only the jar's and its libraries' classes for the parents. Returns the original array when nothing changes. */
    public static byte[] apply(byte[] classBytes, Predicate<String> flags, Function<String, byte[]> classes) {
        if (classBytes == null || !namesAny(classBytes)) return classBytes;
        ClassReader reader = new ClassReader(classBytes);
        if ((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0) return classBytes;
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        // A mixin's members land on its target, which keeps the host's own hooks.
        if (OldRenderShapes.isMixin(node)) return classBytes;
        int before = node.methods.size();
        if (flags.test(NEO_ARMOR)) addNeoForgeArmor(node, classes);
        if (flags.test(FORGE_ARMOR)) addForgeArmor(node, classes);
        if (flags.test(NEO_DISGUISE)) addDisguise(node, "isGazeDisguise", LIVING);
        if (flags.test(FORGE_DISGUISE)) addDisguise(node, "isMonsterDisguise", "Lnet/minecraft/world/entity/monster/Monster;");
        if (node.methods.size() == before) return classBytes;
        // The new methods carry their own frames, so the class's other methods keep theirs as they are.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    // ---- extensions ----

    /** NeoForge 26.x's item-and-layer hooks, each passing {@code this} to {@code LegacyArmorHooks}. */
    private static void addNeoForgeArmor(ClassNode node, Function<String, byte[]> classes) {
        boolean humanoid = declares(node, "getHumanoidArmorModel", OLD_HUMANOID);
        boolean generic = declares(node, "getGenericArmorModel", OLD_GENERIC);
        boolean texture = declaresOldLayer(node, "getArmorTexture", "(" + STACK + "Lnet/minecraft/world/entity/Entity;" + SLOT, "Z" + ID + ")" + ID);
        boolean tint = declaresOldLayer(node, "getArmorLayerTintColor", "(" + STACK + LIVING, "II)I");
        if (!humanoid && !generic && !texture && !tint) return;
        Parent parent = parent(node, NEO_EXT, classes);
        if (parent == null) return;
        String hooks = LegacyArmorHooksBridge.NEO_HOOKS;
        String newModel = "(" + STACK + LAYER_TYPE + MODEL + ")" + MODEL;
        if (humanoid) bridge(node, parent, "getHumanoidArmorModel", newModel, hooks, "humanoidModel", NEO_EXT);
        if (generic) bridge(node, parent, "getGenericArmorModel", newModel, hooks, "genericModel", NEO_EXT);
        // An extension with old model hooks usually belongs to an item that chose its texture in code, which this asks too,
        // unless a parent answers the texture itself.
        if (texture || (humanoid || generic) && !parent.answersTexture()) {
            bridge(node, parent, "getArmorTexture", NEW_TEXTURE, hooks, "armorTexture", NEO_EXT);
        }
        if (tint) bridge(node, parent, "getArmorLayerTintColor", "(" + STACK + LAYER + "II)I", hooks, "tintColor", NEO_EXT);
    }

    /** Forge 26.2's render-state hooks, each passing {@code this} to {@code LegacyForgeArmorHooks}. */
    private static void addForgeArmor(ClassNode node, Function<String, byte[]> classes) {
        boolean humanoid = declares(node, "getHumanoidArmorModel", OLD_HUMANOID);
        boolean generic = declares(node, "getGenericArmorModel", OLD_GENERIC);
        if (!humanoid && !generic) return;
        Parent parent = parent(node, FORGE_EXT, classes);
        if (parent == null) return;
        String hooks = LegacyArmorHooksBridge.FORGE_HOOKS;
        if (humanoid) {
            bridge(node, parent, "getHumanoidArmorModel", "(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;" + STACK + SLOT
                    + HUMANOID + ")" + HUMANOID, hooks, "humanoidModel", FORGE_EXT);
        }
        if (generic) {
            bridge(node, parent, "getGenericArmorModel", "(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;" + STACK + SLOT
                    + HUMANOID + ")" + MODEL, hooks, "genericModel", FORGE_EXT);
        }
    }

    /**
     * {@code name(desc)}, unless the class declares it: {@code helper(this, args...)} when it answers, else the inherited hook,
     * so a parent's own answer or the host's default still runs where the old override gives none.
     */
    private static void bridge(ClassNode node, Parent parent, String name, String desc, String owner, String helper, String ext) {
        if (declaresAny(node, name, desc)) return;
        Type result = Type.getReturnType(desc);
        boolean primitive = result.getSort() == Type.INT;
        // The helper answers a boxed int, and null for no answer.
        String answer = primitive ? "java/lang/Integer" : result.getInternalName();
        Type[] arguments = Type.getArgumentTypes(desc);
        int local = 1;
        for (Type argument : arguments) local += argument.getSize();
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, name, desc, null, null);
        Label inherited = new Label();
        method.visitCode();
        loadThisAndArguments(method, arguments);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, helper, "(L" + ext + ";" + desc.substring(1, desc.indexOf(')') + 1) + "L" + answer + ";", false);
        method.visitVarInsn(Opcodes.ASTORE, local);
        method.visitVarInsn(Opcodes.ALOAD, local);
        method.visitJumpInsn(Opcodes.IFNULL, inherited);
        method.visitVarInsn(Opcodes.ALOAD, local);
        if (primitive) method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, answer, "intValue", "()I", false);
        method.visitInsn(result.getOpcode(Opcodes.IRETURN));
        method.visitLabel(inherited);
        if ((node.version & 0xFFFF) >= Opcodes.V1_6) method.visitFrame(Opcodes.F_APPEND, 1, new Object[] {answer}, 0, null);
        loadThisAndArguments(method, arguments);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, parent.owner(), name, desc, parent.isInterface());
        method.visitInsn(result.getOpcode(Opcodes.IRETURN));
        method.visitMaxs(0, 0);
        method.visitEnd();
        node.methods.add(method);
    }

    private static void loadThisAndArguments(MethodNode method, Type[] arguments) {
        method.visitVarInsn(Opcodes.ALOAD, 0);
        int slot = 1;
        for (Type argument : arguments) {
            method.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
            slot += argument.getSize();
        }
    }

    /** Where an added hook's inherited call goes, and whether a parent already answers the new armor texture. */
    private record Parent(String owner, boolean isInterface, boolean answersTexture) {}

    /** What a walk up from some parents found: the extension itself, and a class that declares the new texture hook. */
    private record Walk(boolean reachesExt, boolean answersTexture) {}

    /**
     * The superclass when a parent the jar or its libraries ship implements the extension, else the extension when the class
     * lists it, else the class's own interface that extends it, else null. A parent nobody ships counts as not implementing
     * it, so the game and the CLI choose the same target.
     */
    private static Parent parent(ClassNode node, String ext, Function<String, byte[]> classes) {
        Walk superclass = walk(classes, ext, node.superName == null ? List.of() : List.of(node.superName));
        if (superclass.reachesExt()) return new Parent(node.superName, false, superclass.answersTexture());
        // INVOKESPECIAL can name only a direct interface, and only from a Java 8 or newer class file.
        if ((node.version & 0xFFFF) < Opcodes.V1_8) return null;
        if (node.interfaces.contains(ext)) return new Parent(ext, true, superclass.answersTexture());
        for (String own : node.interfaces) {
            if (walk(classes, ext, List.of(own)).reachesExt()) return new Parent(own, true, superclass.answersTexture());
        }
        return null;
    }

    // Walks the readable parents from start; the host's classes and anything else unreadable do not reach the extension.
    private static Walk walk(Function<String, byte[]> classes, String ext, List<String> start) {
        boolean reachesExt = false;
        boolean answersTexture = false;
        Set<String> seen = new HashSet<>();
        List<String> pending = new ArrayList<>(start);
        while (!pending.isEmpty() && seen.size() < 64) {
            String current = pending.remove(pending.size() - 1);
            if (!seen.add(current) || current.equals("java/lang/Object")) continue;
            if (current.equals(ext)) {
                reachesExt = true;
                continue;
            }
            ClassNode type = read(classes, current);
            if (type == null) continue;
            if ((type.access & Opcodes.ACC_INTERFACE) == 0 && declares(type, "getArmorTexture", NEW_TEXTURE)) answersTexture = true;
            if (type.superName != null) pending.add(type.superName);
            pending.addAll(type.interfaces);
        }
        return new Walk(reachesExt, answersTexture);
    }

    // A cheap scan of the raw bytes, so a class that names none of the old hooks is not parsed.
    private static boolean namesAny(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        for (String name : OLD_NAMES) {
            if (text.contains(name)) return true;
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

    // ---- items ----

    /** The host's disguise hook on an item that overrides the old {@code isEnderMask}: the old answer, else the inherited check. */
    private static void addDisguise(ClassNode node, String name, String target) {
        if (node.superName == null || node.superName.equals("java/lang/Object")) return;
        String desc = "(" + STACK + PLAYER + target + ")Z";
        if (!declaresEnderMask(node) || declaresAny(node, name, desc)) return;
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, name, desc, null, null);
        Label inherited = new Label();
        method.visitCode();
        for (int slot = 0; slot < 4; slot++) method.visitVarInsn(Opcodes.ALOAD, slot);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, LegacyArmorHooksBridge.DISGUISES, "oldAnswer",
                "(Ljava/lang/Object;" + STACK + PLAYER + "Ljava/lang/Object;)I", false);
        method.visitVarInsn(Opcodes.ISTORE, 4);
        method.visitVarInsn(Opcodes.ILOAD, 4);
        method.visitJumpInsn(Opcodes.IFLT, inherited);
        method.visitVarInsn(Opcodes.ILOAD, 4);
        method.visitInsn(Opcodes.IRETURN);
        // No old answer: another mob than an enderman, or an override that failed.
        method.visitLabel(inherited);
        if ((node.version & 0xFFFF) >= Opcodes.V1_6) method.visitFrame(Opcodes.F_APPEND, 1, new Object[] {Opcodes.INTEGER}, 0, null);
        for (int slot = 0; slot < 4; slot++) method.visitVarInsn(Opcodes.ALOAD, slot);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, node.superName, name, desc, false);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        node.methods.add(method);
    }

    // 26.3 renamed EnderMan to Enderman, so either class can follow the stack and player.
    private static boolean declaresEnderMask(ClassNode node) {
        for (String enderman : List.of("EnderMan", "Enderman")) {
            if (declares(node, "isEnderMask", "(" + STACK + PLAYER + "Lnet/minecraft/world/entity/monster/" + enderman + ";)Z")) return true;
        }
        return false;
    }

    // ---- shapes ----

    private static boolean declares(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc) && isConcreteInstance(method)) return true;
        }
        return false;
    }

    /** A NeoForge 21.1 hook whose old armor layer parameter sits between {@code head} and {@code tail}, as each mod's own copy. */
    private static boolean declaresOldLayer(ClassNode node, String name, String head, String tail) {
        for (MethodNode method : node.methods) {
            if (!method.name.equals(name) || !isConcreteInstance(method) || !method.desc.startsWith(head + "L") || !method.desc.endsWith(tail)) continue;
            String middle = method.desc.substring(head.length(), method.desc.length() - tail.length());
            if (middle.endsWith("LegacyArmorLayer;") && middle.indexOf(';') == middle.length() - 1) return true;
        }
        return false;
    }

    private static boolean declaresAny(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(desc));
    }

    private static boolean isConcreteInstance(MethodNode method) {
        return (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0;
    }
}
