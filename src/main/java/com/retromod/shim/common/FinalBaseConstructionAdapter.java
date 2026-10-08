/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Keeps a mod loadable when one of its classes extends a Minecraft class that a later version
 * made final, by moving the failure from class loading to the moment the mod builds that object.
 *
 * <p>The JVM refuses to define a subclass of a final class. That is not limited to the subclass:
 * a method that returns {@code new Subclass(...)} as the base type makes the verifier load the
 * subclass to prove the return is legal, so the factory class fails to load too. KubeJS's
 * enchantment builder is one: its {@code createObject()} returns a {@code BasicEnchantment}, so
 * the whole KubeJS plugin setup stopped on {@code IncompatibleClassChangeError} although no script
 * defined an enchantment.
 *
 * <p>Each {@code return new Subclass(...)} becomes a call to a private method of the same class
 * that throws an {@code UnsupportedOperationException} naming the subclass and the reason. The
 * subclass is then never loaded unless the mod really builds one. This restores loading, not the
 * object: since 1.21 enchantments are data pack entries, and no code-defined subclass can exist.
 *
 * <p>Only the shape {@code NEW, DUP, arguments, INVOKESPECIAL, ARETURN} with no branch inside the
 * arguments is rewritten, in a method whose declared return type is the final base or
 * {@code Object}. Any other use of the subclass would need the subclass type on the stack, and
 * is left for the JVM to report.
 */
public final class FinalBaseConstructionAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-final-bases");

    /**
     * A Minecraft class that mods used to extend and that a later version made final.
     *
     * @param owner      internal name of the now-final class
     * @param finalSince first Minecraft version where it is final
     * @param reason     why no subclass can be built, for the exception message
     */
    record FinalBase(String owner, String finalSince, String reason) {}

    private static final String ENCHANTMENT_REASON =
            "Minecraft 1.21 made Enchantment a final record and defines enchantments in data packs";

    /**
     * Mojang names serve NeoForge, Forge and Fabric on 26.1 and newer. The intermediary row serves
     * Fabric hosts from 1.21 through 1.21.11.
     */
    static final List<FinalBase> FINAL_BASES = List.of(
            new FinalBase("net/minecraft/world/item/enchantment/Enchantment", "1.21", ENCHANTMENT_REASON),
            new FinalBase("net/minecraft/class_1887", "1.21", ENCHANTMENT_REASON));

    static final String THROWER_PREFIX = "retromod$cannotConstruct$";

    private FinalBaseConstructionAdapter() {}

    /**
     * Why {@code owner} cannot be extended on {@code hostVersion}, when it is a base this adapter
     * handles, or null. The superclass report uses it so it does not call such a mod unloadable.
     */
    public static String finalBaseReason(String owner, String hostVersion) {
        if (owner == null || hostVersion == null) return null;
        for (FinalBase base : FINAL_BASES) {
            if (base.owner().equals(owner)
                    && RetromodVersion.compareMcVersions(hostVersion, base.finalSince()) >= 0) {
                return base.reason();
            }
        }
        return null;
    }

    /** Returns the original array when no construction of a final-base subclass is present. */
    public static byte[] apply(byte[] classBytes) {
        return apply(classBytes, RetromodVersion.TARGET_MC_VERSION,
                RetromodTransformer::currentJarClassInheritsFrom);
    }

    /**
     * Rewrites every qualifying construction for the bases already final on {@code hostVersion}.
     *
     * @param inheritsFrom proves that a jar-local class extends a Minecraft owner
     */
    static byte[] apply(byte[] classBytes, String hostVersion, BiPredicate<String, String> inheritsFrom) {
        if (classBytes == null || hostVersion == null) return classBytes;
        List<FinalBase> active = new ArrayList<>();
        for (FinalBase base : FINAL_BASES) {
            if (RetromodVersion.compareMcVersions(hostVersion, base.finalSince()) >= 0) active.add(base);
        }
        if (active.isEmpty()) return classBytes;
        try {
            ClassReader reader = new ClassReader(classBytes);
            if ((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0
                    || reader.getClassName().startsWith("net/minecraft/")) {
                return classBytes;
            }
            ClassNode node = new ClassNode();
            reader.accept(node, 0);

            List<MethodNode> throwers = new ArrayList<>();
            for (MethodNode method : node.methods) {
                rewriteConstructions(node, method, active, inheritsFrom, throwers);
            }
            if (throwers.isEmpty()) return classBytes;
            node.methods.addAll(throwers);

            // The rewrite only removes NEW and DUP before a straight-line argument list and turns
            // the constructor call into a static call, so no stack map frame changes.
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            LOGGER.info("{} builds {} object(s) whose Minecraft base is now final; building one "
                    + "now reports why instead of stopping the class from loading", node.name, throwers.size());
            return writer.toByteArray();
        } catch (Throwable t) {
            LOGGER.debug("Final-base construction repair skipped ({}); class left unchanged", t.toString());
            return classBytes;
        }
    }

    private static void rewriteConstructions(ClassNode owner, MethodNode method, List<FinalBase> bases,
            BiPredicate<String, String> inheritsFrom, List<MethodNode> throwers) {
        String returned = Type.getReturnType(method.desc).getInternalName();
        List<TypeInsnNode> sites = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() == Opcodes.NEW) sites.add((TypeInsnNode) insn);
        }
        for (TypeInsnNode newInsn : sites) {
            String created = newInsn.desc;
            if (created.startsWith("net/minecraft/") || created.startsWith("java/")) continue;
            FinalBase base = finalBaseOf(created, returned, bases, inheritsFrom);
            if (base == null) continue;
            MethodInsnNode init = straightLineConstructorCall(newInsn, created);
            AbstractInsnNode after = init == null ? null : nextReal(init);
            if (after == null || after.getOpcode() != Opcodes.ARETURN) continue;

            MethodNode thrower = thrower(owner, throwers.size(), created, init.desc, base);
            method.instructions.remove(nextReal(newInsn));
            method.instructions.remove(newInsn);
            method.instructions.set(init, new MethodInsnNode(Opcodes.INVOKESTATIC, owner.name,
                    thrower.name, thrower.desc, false));
            throwers.add(thrower);
        }
    }

    /** The final base {@code created} extends, when the method hands the object out as that base. */
    private static FinalBase finalBaseOf(String created, String returned, List<FinalBase> bases,
            BiPredicate<String, String> inheritsFrom) {
        for (FinalBase base : bases) {
            boolean returnsBase = returned.equals(base.owner()) || returned.equals("java/lang/Object");
            if (returnsBase && inheritsFrom.test(created, base.owner())) return base;
        }
        return null;
    }

    /**
     * The constructor call that consumes {@code newInsn}, when the arguments between them are a
     * straight line: no label, so no frame can hold the uninitialized object, and no other NEW.
     */
    private static MethodInsnNode straightLineConstructorCall(AbstractInsnNode newInsn, String created) {
        AbstractInsnNode dup = nextReal(newInsn);
        if (dup == null || dup.getOpcode() != Opcodes.DUP) return null;
        for (AbstractInsnNode insn = dup.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode || insn.getOpcode() == Opcodes.NEW) return null;
            if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
                MethodInsnNode call = (MethodInsnNode) insn;
                return call.owner.equals(created) && call.name.equals("<init>") ? call : null;
            }
            if (insn.getType() == AbstractInsnNode.JUMP_INSN || insn.getType() == AbstractInsnNode.FRAME) return null;
        }
        return null;
    }

    /**
     * The next instruction after line numbers and labels. A stack map frame is returned as it is,
     * so it never passes for the expected opcode: a frame would record the subclass type.
     */
    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next instanceof LineNumberNode || next instanceof LabelNode) next = next.getNext();
        return next;
    }

    /** {@code private static Base retromod$cannotConstruct$N(args) { throw new UOE(message); }} */
    private static MethodNode thrower(ClassNode owner, int index, String created, String initDesc, FinalBase base) {
        String desc = Type.getMethodDescriptor(Type.getObjectType(base.owner()), Type.getArgumentTypes(initDesc));
        MethodNode thrower = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                THROWER_PREFIX + index, desc, null, null);
        InsnList body = thrower.instructions;
        body.add(new TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException"));
        body.add(new InsnNode(Opcodes.DUP));
        body.add(new LdcInsnNode(created.replace('/', '.') + " extends " + base.owner().replace('/', '.')
                + ", which is final on this Minecraft version, so it cannot be built. "
                + base.reason() + ". Remove whatever defines it, such as a script, or use a version "
                + "of the mod made for this Minecraft version."));
        body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException",
                "<init>", "(Ljava/lang/String;)V", false));
        body.add(new InsnNode(Opcodes.ATHROW));
        return thrower;
    }
}
