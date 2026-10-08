/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Lets Forge 1.20.1 entity types that set a custom client factory build on NeoForge 1.21.
 *
 * <p>Forge's {@code EntityType.Builder.setCustomClientFactory(BiFunction<PlayMessages.SpawnEntity,
 * Level, T>)} picked the class a client creates for a spawned entity. NeoForge removed it with
 * {@code PlayMessages}, and a client now creates the entity through the type's ordinary factory.
 * The call is dropped, so the builder chain continues. The factory lambda still names
 * {@code PlayMessages.SpawnEntity} in its signature, and linking the lambda loads that class even
 * though it is never called, so an empty stand-in takes the name.
 */
public final class LegacyEntityClientFactoryBridge {

    static final String SPAWN_ENTITY = "net/minecraftforge/network/PlayMessages$SpawnEntity";
    static final String HELPER = "com/retromod/generated/LegacyEntityClientFactory";
    private static final String L_BUILDER = "Lnet/minecraft/world/entity/EntityType$Builder;";
    private static final String BUILDER = "net/minecraft/world/entity/EntityType$Builder";
    static final String HELPER_DESC = "(" + L_BUILDER + "Ljava/util/function/BiFunction;)" + L_BUILDER;

    private LegacyEntityClientFactoryBridge() {}

    /** Called by {@link ForgeNeoForgeApiBridge} on a NeoForge host. */
    public static void register(RetromodTransformer transformer) {
        if (ClassResourceInspector.exists(SPAWN_ENTITY)) return;
        transformer.registerSyntheticClass(SPAWN_ENTITY, generateSpawnEntityStandIn());
        transformer.registerSyntheticClass(HELPER, generateHelper());
        transformer.registerMethodRedirect(BUILDER, "setCustomClientFactory",
                "(Ljava/util/function/BiFunction;)" + L_BUILDER, HELPER, "setCustomClientFactory",
                HELPER_DESC, true);
    }

    /** An empty class: only the lambda's signature names it, and nothing constructs it. */
    static byte[] generateSpawnEntityStandIn() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, SPAWN_ENTITY, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static Builder setCustomClientFactory(Builder builder, BiFunction ignored)}. */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        MethodVisitor drop = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "setCustomClientFactory",
                HELPER_DESC, null, null);
        drop.visitCode();
        drop.visitVarInsn(ALOAD, 0);
        drop.visitInsn(ARETURN);
        drop.visitMaxs(1, 2);
        drop.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
