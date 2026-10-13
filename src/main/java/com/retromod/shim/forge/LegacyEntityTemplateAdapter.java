/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Two calls that MCreator's entity and procedure templates make in nearly every Forge 1.20.1 mod.
 *
 * <p>{@code Commands.performPrefixedCommand} and {@code performCommand} returned the command's
 * result until 1.20.3 and return nothing since, so a template that ran a command failed with
 * {@code NoSuchMethodError} the first time it fired. The call now goes to the host method and
 * answers 0, the result of a command that reported nothing.
 *
 * <p>{@code LivingEntity.dropExperience()}, which the death template calls on the entity itself,
 * takes the attacker since 1.21 and the server level too since 26.1. The entity gains a private
 * method that supplies them, as the game does: its last attacker, and on 26.1 and newer its level
 * when that is a server level. On a client nothing is dropped, as before. It is protected, so the
 * call can only be rebuilt inside the mod's own class.
 */
public final class LegacyEntityTemplateAdapter implements Opcodes {

    public static final String FLAG = "legacy-entity-templates";
    static final String COMMANDS_HELPER = "com/retromod/generated/LegacyCommandResults";
    private static final String COMMANDS = "net/minecraft/commands/Commands";
    private static final String SOURCE = "Lnet/minecraft/commands/CommandSourceStack;";
    private static final String PARSE = "Lcom/mojang/brigadier/ParseResults;";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String ENTITY = "Lnet/minecraft/world/entity/Entity;";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    static final String DROP_BRIDGE = "retromod$dropExperience";

    /** The host's {@code dropExperience} descriptor, or null when it still takes no arguments. */
    private static volatile String hostDropExperience;

    private LegacyEntityTemplateAdapter() {}

    public static void register(RetromodTransformer transformer) {
        register(transformer, ClassResourceInspector.read(COMMANDS), ClassResourceInspector.read(LIVING));
    }

    static void register(RetromodTransformer transformer, ClassNode commands, ClassNode living) {
        if (returnsVoid(commands, "performPrefixedCommand")) {
            transformer.registerSyntheticClass(COMMANDS_HELPER, commandResults());
            transformer.registerMethodRedirect(COMMANDS, "performPrefixedCommand", "(" + SOURCE + "Ljava/lang/String;)I",
                    COMMANDS_HELPER, "performPrefixedCommand", "(L" + COMMANDS + ";" + SOURCE + "Ljava/lang/String;)I", true);
            transformer.registerMethodRedirect(COMMANDS, "performCommand", "(" + PARSE + "Ljava/lang/String;)I",
                    COMMANDS_HELPER, "performCommand", "(L" + COMMANDS + ";" + PARSE + "Ljava/lang/String;)I", true);
        }
        String drop = dropExperienceShape(living);
        if (drop != null) {
            hostDropExperience = drop;
            transformer.enablePostProcess(FLAG);
        }
    }

    /** Rebuilds a mod entity's own {@code dropExperience()} calls on the host's shape. */
    public static byte[] apply(byte[] classBytes) {
        String drop = hostDropExperience;
        if (drop == null || !new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("dropExperience")) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean called = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.equals("dropExperience") && call.desc.equals("()V")
                        && call.owner.equals(node.name) && call.getOpcode() != INVOKESTATIC) {
                    call.setOpcode(INVOKESPECIAL);
                    call.name = DROP_BRIDGE;
                    called = true;
                }
            }
        }
        if (!called) return classBytes;
        node.methods.add(dropBridge(node.name, drop));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode dropBridge(String owner, String drop) {
        MethodNode bridge = new MethodNode(ACC_PRIVATE | ACC_SYNTHETIC, DROP_BRIDGE, "()V", null, null);
        InsnList code = bridge.instructions;
        LabelNode done = new LabelNode();
        boolean needsLevel = drop.startsWith("(L" + SERVER_LEVEL + ";");
        if (needsLevel) {
            code.add(new VarInsnNode(ALOAD, 0));
            code.add(new MethodInsnNode(INVOKEVIRTUAL, owner, "level", "()Lnet/minecraft/world/level/Level;", false));
            code.add(new TypeInsnNode(INSTANCEOF, SERVER_LEVEL));
            code.add(new JumpInsnNode(IFEQ, done));
        }
        code.add(new VarInsnNode(ALOAD, 0));
        if (needsLevel) {
            code.add(new VarInsnNode(ALOAD, 0));
            code.add(new MethodInsnNode(INVOKEVIRTUAL, owner, "level", "()Lnet/minecraft/world/level/Level;", false));
            code.add(new TypeInsnNode(CHECKCAST, SERVER_LEVEL));
        }
        code.add(new VarInsnNode(ALOAD, 0));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, owner, "getLastHurtByMob", "()L" + LIVING + ";", false));
        code.add(new MethodInsnNode(INVOKEVIRTUAL, owner, "dropExperience", drop, false));
        code.add(done);
        code.add(new InsnNode(RETURN));
        return bridge;
    }

    private static boolean returnsVoid(ClassNode owner, String method) {
        return owner != null && owner.methods.stream().anyMatch(m -> m.name.equals(method) && m.desc.endsWith(")V"))
                && owner.methods.stream().noneMatch(m -> m.name.equals(method) && m.desc.endsWith(")I"));
    }

    private static String dropExperienceShape(ClassNode living) {
        if (living == null) return null;
        boolean old = living.methods.stream().anyMatch(m -> m.name.equals("dropExperience") && m.desc.equals("()V"));
        if (old) return null;
        for (String shape : new String[] {"(L" + SERVER_LEVEL + ";" + ENTITY + ")V", "(" + ENTITY + ")V"}) {
            if (living.methods.stream().anyMatch(m -> m.name.equals("dropExperience") && m.desc.equals(shape))) return shape;
        }
        return null;
    }

    /** {@code performPrefixedCommand} and {@code performCommand} that run the host's and answer 0. */
    static byte[] commandResults() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, COMMANDS_HELPER, null, "java/lang/Object", null);
        for (String[] command : new String[][] {{"performPrefixedCommand", SOURCE + "Ljava/lang/String;"},
                {"performCommand", PARSE + "Ljava/lang/String;"}}) {
            var mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, command[0], "(L" + COMMANDS + ";" + command[1] + ")I", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKEVIRTUAL, COMMANDS, command[0], "(" + command[1] + ")V", false);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
