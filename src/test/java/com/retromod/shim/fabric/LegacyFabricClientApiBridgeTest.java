/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Function;

import static com.retromod.shim.neoforge.LegacyNeoForgeClientApiBridgeTest.call;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Fabric API's fluid render handlers, FabricBakedModel, core shader callback and buffer menu types on Fabric 26.x. */
class LegacyFabricClientApiBridgeTest {

    private static final String FLUID = "com/retromod/legacyapi/fabric/client/render/fluid/";
    private static final String AT = "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/material/FluidState;)";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("On 26.3 the old fluid registry and simple handler are rebuilt, and a handler's old reads ask it first, then the fluid model")
    void fluidHandlers() {
        register("26.3", name -> null);
        assertEquals(FLUID + "LegacyFluidRenderHandlerRegistry.INSTANCE:L" + FLUID + "LegacyFluidRenderHandlerRegistry;",
                field(LegacyFabricClientApiBridge.OLD_REGISTRY, "INSTANCE", "L" + LegacyFabricClientApiBridge.OLD_REGISTRY + ";"));
        assertEquals(FLUID + "LegacySimpleFluidRenderHandler.<init>(Lnet/minecraft/resources/Identifier;Lnet/minecraft/resources/Identifier;I)V",
                call(INVOKESPECIAL, LegacyFabricClientApiBridge.OLD_SIMPLE, "<init>",
                        "(Lnet/minecraft/resources/Identifier;Lnet/minecraft/resources/Identifier;I)V", transformer));
        assertEquals(FLUID + "LegacyFluidRenderHandlers.getFluidColor(L" + LegacyFabricClientApiBridge.HANDLER + ";" + AT.substring(1) + "I",
                call(INVOKEINTERFACE, LegacyFabricClientApiBridge.HANDLER, "getFluidColor", AT + "I", transformer));
    }

    @Test
    @DisplayName("FabricBakedModel is an interface whose old methods have defaults, so a model class that implements it loads")
    void bakedModel() {
        register("26.3", name -> null);
        ClassNode model = node(transformer.getSyntheticClasses().get(LegacyFabricClientApiBridge.BAKED));
        assertTrue((model.access & ACC_INTERFACE) != 0);
        for (MethodNode method : model.methods) assertEquals(0, method.access & ACC_ABSTRACT, method.name + " has a default");
        assertEquals(3, model.methods.size());
    }

    @Test
    @DisplayName("The core shader context's register takes 26.x's vertex format, and the 1.20.1 menu type constructor gets a codec")
    void shadersAndMenus() {
        register("26.3", name -> null);
        String context = LegacyFabricClientApiBridge.SHADERS + "$RegistrationContext";
        assertEquals(context + ".register(Lnet/minecraft/resources/Identifier;Ljava/lang/Object;Ljava/util/function/Consumer;)V",
                call(INVOKEINTERFACE, LegacyFabricClientApiBridge.OLD_SHADERS + "$RegistrationContext", "register",
                        "(Lnet/minecraft/resources/Identifier;Lcom/mojang/renderpearl/api/vertex/VertexFormat;Ljava/util/function/Consumer;)V", transformer));
        String factory = Pre1_20_5ScreenHandlerBridge.MOJANG_NAMES.get(Pre1_20_5ScreenHandlerBridge.FACTORY);
        assertEquals(factory + ".create(L" + Pre1_20_5ScreenHandlerBridge.MENU_FACTORY + ";)L" + Pre1_20_5ScreenHandlerBridge.MENU_TYPE + ";",
                call(INVOKESPECIAL, Pre1_20_5ScreenHandlerBridge.MENU_TYPE, "<init>", "(L" + Pre1_20_5ScreenHandlerBridge.MENU_FACTORY + ";)V", transformer));
        assertEquals(Pre1_20_5ScreenHandlerBridge.MENU_TYPE + ".<init>(L" + Pre1_20_5ScreenHandlerBridge.MENU_FACTORY
                + ";Lnet/minecraft/network/codec/StreamCodec;)V", subclassSuperCall(), "a mod's own menu type subclass gets the codec too");
        // The generated classes carry 26.x names only, so nothing of intermediary is left to resolve.
        for (String generated : new String[]{factory, Pre1_20_5ScreenHandlerBridge.MOJANG_NAMES.get(Pre1_20_5ScreenHandlerBridge.CODEC),
                Pre1_20_5ScreenHandlerBridge.MOJANG_NAMES.get(Pre1_20_5ScreenHandlerBridge.LEGACY_MENU_PROVIDER)}) {
            String text = new String(transformer.getSyntheticClasses().get(generated), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(text.contains("class_") || text.contains("method_"), generated + " has no intermediary names");
        }
    }

    @Test
    @DisplayName("A 1.21.11 host and a 26.x Fabric API that still has a class take nothing for it")
    void olderHostsAndPresentClasses() {
        register("1.21.11", name -> null);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyFabricClientApiBridge.BAKED));
        register("26.3", name -> name.equals(LegacyFabricClientApiBridge.OLD_BAKED) ? new ClassNode() : null);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyFabricClientApiBridge.BAKED));
        assertTrue(transformer.getSyntheticClasses().containsKey(FLUID + "LegacyFluidRenderHandlerRegistry"));
    }

    private void register(String host, Function<String, ClassNode> classes) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        LegacyFabricClientApiBridge.register(transformer, classes);
        transformer.setModSourceVersion("1.20.1");
    }

    // The static field read {@code owner.name} becomes, as {@code owner.name:desc}.
    private String field(String owner, String name, String desc) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Reads", null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read", "()Ljava/lang/Object;", null, null);
        mv.visitFieldInsn(GETSTATIC, owner, name, desc);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        cw.visitEnd();
        ClassNode node = node(transformer.transformClass(cw.toByteArray(), "mod/Reads.class"));
        var insn = (org.objectweb.asm.tree.FieldInsnNode) node.methods.stream().filter(m -> m.name.equals("read")).findFirst()
                .orElseThrow().instructions.getFirst();
        return insn.owner + "." + insn.name + ":" + insn.desc;
    }

    // A mod's menu type subclass whose constructor calls super(factory), and the super call it ends with.
    private String subclassSuperCall() {
        String type = Pre1_20_5ScreenHandlerBridge.MENU_TYPE, factory = Pre1_20_5ScreenHandlerBridge.MENU_FACTORY;
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/MenuType", null, type, null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + factory + ";)V", null, null);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESPECIAL, type, "<init>", "(L" + factory + ";)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        cw.visitEnd();
        for (var insn : node(transformer.transformClass(cw.toByteArray(), "mod/MenuType.class")).methods.get(0).instructions) {
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.owner.equals(type)) return call.owner + "." + call.name + call.desc;
        }
        return null;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
