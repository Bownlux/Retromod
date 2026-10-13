/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Map;

/**
 * Gives an old living entity renderer the two methods 26.1 made it provide. 26.1 renders from a
 * state object, so {@code createRenderState()} and {@code getTextureLocation(state)} became abstract,
 * and a pre-26.1 mob renderer, such as an MCreator biped on {@code HumanoidMobRenderer}, failed with
 * {@code AbstractMethodError} the first time its mob was drawn. The renderer gets the state its
 * vanilla base expects and a texture from its old {@code getTextureLocation(entity)}.
 *
 * <p>Only concrete mod classes that extend one of the vanilla bases directly and declare neither
 * method are changed. What the old renderer drew itself, through its own {@code render} override or
 * a custom model's old methods, is not wired into 26.1's renderer.
 */
public final class LegacyLivingRendererAdapter {

    public static final String TEXTURES = "com/retromod/shim/common/embedded/LegacyRendererTextures";
    private static final String STATES = "net/minecraft/client/renderer/entity/state/";
    private static final String RENDERERS = "net/minecraft/client/renderer/entity/";
    /** Each vanilla base and the state its own extraction expects. */
    private static final Map<String, String> BASES = Map.of(
            RENDERERS + "HumanoidMobRenderer", STATES + "HumanoidRenderState",
            RENDERERS + "MobRenderer", STATES + "LivingEntityRenderState",
            RENDERERS + "LivingEntityRenderer", STATES + "LivingEntityRenderState");
    private static final String CREATE_DESC = "()L" + STATES + "EntityRenderState;";
    private static final String TEXTURE_DESC = "(L" + STATES + "LivingEntityRenderState;)Lnet/minecraft/resources/Identifier;";

    private LegacyLivingRendererAdapter() {}

    /** Returns the original array when the class is outside the match. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION)) return classBytes;
        ClassReader reader = new ClassReader(classBytes);
        String state = BASES.get(reader.getSuperName());
        if (state == null || reader.getClassName().startsWith("net/minecraft/")
                || (reader.getAccess() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        boolean hasState = node.methods.stream().anyMatch(m -> m.name.equals("createRenderState")
                && Type.getArgumentTypes(m.desc).length == 0);
        boolean hasTexture = node.methods.stream().anyMatch(m -> m.name.equals("getTextureLocation")
                && m.desc.contains("RenderState"));
        if (hasState && hasTexture) return classBytes;

        if (!hasState) {
            MethodNode create = new MethodNode(Opcodes.ACC_PUBLIC, "createRenderState", CREATE_DESC, null, null);
            create.instructions.add(new TypeInsnNode(Opcodes.NEW, state));
            create.instructions.add(new InsnNode(Opcodes.DUP));
            create.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, state, "<init>", "()V", false));
            create.instructions.add(new InsnNode(Opcodes.ARETURN));
            node.methods.add(create);
        }
        if (!hasTexture) {
            MethodNode texture = new MethodNode(Opcodes.ACC_PUBLIC, "getTextureLocation", TEXTURE_DESC, null, null);
            texture.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            texture.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TEXTURES, "texture",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            texture.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/resources/Identifier"));
            texture.instructions.add(new InsnNode(Opcodes.ARETURN));
            node.methods.add(texture);
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }
}
