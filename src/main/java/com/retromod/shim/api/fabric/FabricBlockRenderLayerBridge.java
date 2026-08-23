/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soft-fail stand-in for Fabric's removed {@code BlockRenderLayerMap}. 26.1 resolves render
 * layers from the block model, so mods that called {@code INSTANCE.putBlock(block, layer)}
 * crash with {@code NoClassDefFoundError}. We inject an inert interface plus impl and let the
 * class redirect in {@code mojang-class-moves-26.1.tsv} (26.1-gated) point at it.
 *
 * <p>Synthetics aren't re-remapped, so the stub descriptors use 26.1 Mojang types directly,
 * including {@code RenderType} at its moved path {@code client/renderer/rendertype/RenderType}.
 * The {@code put*} calls become no-ops: a block needing CUTOUT/TRANSLUCENT renders solid, but
 * the mod loads.
 */
public class FabricBlockRenderLayerBridge implements AuxiliaryVersionShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String SELF = "com/retromod/generated/legacyfabric/BlockRenderLayerMap";
    private static final String IMPL = "com/retromod/generated/legacyfabric/BlockRenderLayerMapImpl";
    private static final String L_SELF = "L" + SELF + ";";

    private static final String L_BLOCK = "Lnet/minecraft/world/level/block/Block;";
    private static final String L_RENDERTYPE = "Lnet/minecraft/client/renderer/rendertype/RenderType;";
    private static final String L_ITEM = "Lnet/minecraft/world/item/Item;";
    private static final String L_FLUID = "Lnet/minecraft/world/level/material/Fluid;";

    @Override public String getShimName() { return "Fabric BlockRenderLayerMap Soft-Fail"; }
    @Override public String getSourceVersion() { return "0.50.0"; }
    @Override public String getTargetVersion() { return "0.100.0"; }
    @Override public String getModLoaderType() { return "fabric"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(SELF, generateInterface());
        transformer.registerSyntheticClass(IMPL, generateImpl());
        LOGGER.debug("[Retromod] BlockRenderLayerMap bridge - injected no-op stand-in");
    }

    private static byte[] generateInterface() {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                SELF, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "INSTANCE", L_SELF, null, null).visitEnd();

        abstractMethod(cw, "putBlock",  "(" + L_BLOCK + L_RENDERTYPE + ")V");
        abstractMethod(cw, "putBlocks", "(" + L_RENDERTYPE + "[" + L_BLOCK + ")V");
        abstractMethod(cw, "putItem",   "(" + L_ITEM + L_RENDERTYPE + ")V");
        abstractMethod(cw, "putItems",  "(" + L_RENDERTYPE + "[" + L_ITEM + ")V");
        abstractMethod(cw, "putFluid",  "(" + L_FLUID + L_RENDERTYPE + ")V");
        abstractMethod(cw, "putFluids", "(" + L_RENDERTYPE + "[" + L_FLUID + ")V");

        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, IMPL);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, IMPL, "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, SELF, "INSTANCE", L_SELF);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateImpl() {
        ClassWriter cw = newClassWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                IMPL, null, "java/lang/Object", new String[]{SELF});
        emitDefaultCtor(cw);
        noopVoid(cw, "putBlock",  "(" + L_BLOCK + L_RENDERTYPE + ")V");
        noopVoid(cw, "putBlocks", "(" + L_RENDERTYPE + "[" + L_BLOCK + ")V");
        noopVoid(cw, "putItem",   "(" + L_ITEM + L_RENDERTYPE + ")V");
        noopVoid(cw, "putItems",  "(" + L_RENDERTYPE + "[" + L_ITEM + ")V");
        noopVoid(cw, "putFluid",  "(" + L_FLUID + L_RENDERTYPE + ")V");
        noopVoid(cw, "putFluids", "(" + L_RENDERTYPE + "[" + L_FLUID + ")V");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassWriter newClassWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String t1, String t2) {
                try { return super.getCommonSuperClass(t1, t2); }
                catch (Throwable t) { return "java/lang/Object"; }
            }
        };
    }

    private static void abstractMethod(ClassWriter cw, String name, String desc) {
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, desc, null, null).visitEnd();
    }

    private static void emitDefaultCtor(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ALOAD, 0);
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void noopVoid(ClassWriter cw, String name, String desc) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
