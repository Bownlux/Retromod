/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyMobLootBridge.Mode;
import com.retromod.shim.common.LegacyMobLootBridge.Names;
import com.retromod.shim.common.LegacyMobLootBridge.Shape;
import com.retromod.util.RetromodLog;
import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

/** Makes the host drop the table an old mob names with its own getDefaultLootTable(), which hosts from 1.20.5 never ask for. */
public final class LegacyMobLootAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    private LegacyMobLootAdapter() {}

    /** The class with the host's own loot table member added where it is an old entity that names its table; else the same array. */
    public static byte[] apply(byte[] classBytes, Set<String> flags, String modVersion) {
        if (classBytes == null || modVersion != null && RetromodVersion.compareMcVersions(modVersion, LegacyMobLootBridge.FIRST_KEYED) >= 0) {
            return classBytes;
        }
        Shape shape = Shape.among(flags);
        if (shape == null) return classBytes;
        return apply(classBytes, shape, RetromodTransformer::currentJarClassInheritsFrom);
    }

    static byte[] apply(byte[] classBytes, Shape shape, BiPredicate<String, String> inheritsFrom) {
        Names n = shape.names();
        // Most classes name neither old member, so the bytes decide before the class is read.
        if (!mentionsOldMember(classBytes, n)) return classBytes;
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            if (!adapt(node, shape, inheritsFrom)) return classBytes;
            SafeClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException failure) {
            return classBytes;
        }
    }

    private static boolean adapt(ClassNode node, Shape shape, BiPredicate<String, String> inheritsFrom) {
        Names n = shape.names();
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/") || node.superName == null
                || OldRenderShapes.isMixin(node)) {
            return false;
        }
        // A mob overrode getDefaultLootTable(); any other living entity overrode getLootTable(). Both answered an id.
        boolean mob = inheritsFrom.test(node.superName, n.mob());
        boolean living = mob || inheritsFrom.test(node.superName, n.living());
        // Only Mob had getDefaultLootTable() in 1.20.1, so where no host classes can be read (an offline run) the name
        // alone proves a mob. A Fabric mob's own on 26.x carries getLootTable, which any entity could have, so it needs the proof.
        if (!living && declares(node, n.mobOnlyOld(), "()" + n.idDesc())) mob = living = true;
        if (!living) {
            noteUnprovenParents(node, n, inheritsFrom);
            return false;
        }
        String old = declared(node, mob ? n.mobOld() : n.livingOld(), "()" + n.idDesc());
        if (old == null) return false;

        MethodNode added = switch (shape.mode()) {
            case KEY_DEFAULT -> keyedTable(node, n, mob ? n.keyDefault() : n.livingKeyGetter(), mob, old);
            case TABLE_DROP, OWN_DROP -> mob ? deathDrop(node, shape, old) : optionalTable(node, n, old);
        };
        if (added == null) return false;
        node.methods.add(added);
        answerBaseCalls(node, n);
        return true;
    }

    // 1.20.5 to 1.21.1: getDefaultLootTable() (or a non-mob's getLootTable()) answering a key, which the host's own reads use.
    private static MethodNode keyedTable(ClassNode node, Names n, String name, boolean mob, String old) {
        String desc = "()" + n.keyDesc();
        if (declares(node, name, desc)) return null;
        MethodNode method = new MethodNode(mob ? Opcodes.ACC_PROTECTED : Opcodes.ACC_PUBLIC, name, desc, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, old, "()" + n.idDesc(), false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LegacyMobLootBridge.HELPER, "defaultLootTableKey",
                "(" + n.idDesc() + ")" + n.keyDesc(), false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    // From 1.21.2 a non-mob's getLootTable() is an optional key the host reads for its drops and /loot.
    private static MethodNode optionalTable(ClassNode node, Names n, String old) {
        String desc = "()L" + LegacyMobLootBridge.OPTIONAL + ";";
        if (declares(node, n.tableGetter(), desc)) return null;
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, n.tableGetter(), desc, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, old, "()" + n.idDesc(), false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LegacyMobLootBridge.HELPER, "ownLootTable",
                "(" + n.idDesc() + ")" + desc.substring(2), false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        return method;
    }

    /**
     * From 1.21.2 a mob's getLootTable() is final, so its death drop asks for the old table instead:
     * {@code if (getLootTable() == getType().getDefaultLootTable()) drop(old table) else super.dropFromLootTable(...)}.
     */
    private static MethodNode deathDrop(ClassNode node, Shape shape, String old) {
        Names n = shape.names();
        if (declares(node, n.drop(), n.dropDesc())) return null;
        MethodNode method = new MethodNode(Opcodes.ACC_PROTECTED, n.drop(), n.dropDesc(), null, null);
        InsnList code = method.instructions;
        // A table set on this mob (a spawner's or a command's) keeps the host's own drop; the type's default is one shared value.
        LabelNode custom = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, n.tableGetter(), "()L" + LegacyMobLootBridge.OPTIONAL + ";", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, n.getType(), "()L" + n.entityType() + ";", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, n.entityType(), n.typeTable(), "()L" + LegacyMobLootBridge.OPTIONAL + ";", false));
        // Compared by value: a mixin that rebuilds the type's Optional must not hide the mob's own table.
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LegacyMobLootBridge.OPTIONAL, "equals", "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, custom));
        loadDropArgs(code);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, old, "()" + n.idDesc(), false));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LegacyMobLootBridge.HELPER, "defaultLootTableKey",
                "(" + n.idDesc() + ")" + n.keyDesc(), false));
        if (shape.mode() == Mode.TABLE_DROP) {
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, n.tableDrop(), n.tableDropDesc(), false));
        } else {
            // The last player to hurt the mob, a field until 1.21.4 and a getter from 1.21.5, read here where it is visible.
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            String player = "L" + n.player() + ";";
            if (shape.playerGetter()) {
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, n.playerGetter(), "()" + player, false));
            } else {
                code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, n.playerField(), player));
            }
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LegacyMobLootBridge.DROPS, "drop",
                    "(L" + n.living() + ";L" + n.level() + ";L" + n.source() + ";Z" + n.keyDesc() + player + ")V", false));
        }
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(custom);
        // The locals at the join are the parameters, as on entry.
        code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        loadDropArgs(code);
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, n.drop(), n.dropDesc(), false));
        code.add(new InsnNode(Opcodes.RETURN));
        return method;
    }

    private static void loadDropArgs(InsnList code) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
    }

    /** {@code super.getDefaultLootTable()} on a Minecraft class answers the entity type's table, as the 1.20.1 base did. */
    private static void answerBaseCalls(ClassNode node, Names n) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || !call.owner.startsWith("net/minecraft/") || !call.desc.equals("()" + n.idDesc())
                        || !(n.mobOld().contains(call.name) || n.livingOld().contains(call.name))) {
                    continue;
                }
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, LegacyMobLootBridge.HELPER,
                        "mobDefaultLootTable", "(Ljava/lang/Object;)" + n.idDesc(), false));
            }
        }
    }

    // A Fabric mob's getDefaultLootTable() reads as getLootTable on 26.x, so a class whose parents cannot be read is left alone.
    private static void noteUnprovenParents(ClassNode node, Names n, BiPredicate<String, String> inheritsFrom) {
        String old = declared(node, n.livingOld(), "()" + n.idDesc());
        if (old == null || inheritsFrom.test(node.superName, "java/lang/Object")) return;
        LOGGER.debug(RetromodLog.DETAIL, "Left {} without its old loot table: it declares {}(), but its parent classes "
                + "could not be read to prove it is an entity", node.name, old);
    }

    private static boolean mentionsOldMember(byte[] classBytes, Names n) {
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        for (String name : n.mobOld()) {
            if (text.contains(name)) return true;
        }
        for (String name : n.livingOld()) {
            if (text.contains(name)) return true;
        }
        return false;
    }

    private static String declared(ClassNode node, List<String> names, String desc) {
        for (String name : names) {
            if (declares(node, name, desc)) return name;
        }
        return null;
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc) && (m.access & Opcodes.ACC_STATIC) == 0);
    }
}
