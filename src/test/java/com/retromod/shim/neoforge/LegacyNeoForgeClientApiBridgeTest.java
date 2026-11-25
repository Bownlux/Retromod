/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.ModConstructorHooks;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old mods' removed NeoForge client events, fluid reads, fluid stack tag and spawn event constructor resolve on NeoForge 26.x. */
public class LegacyNeoForgeClientApiBridgeTest {

    static final String NEO = "net/neoforged/neoforge/";
    static final String FORGE = "net/minecraftforge/";
    static final String ROOT = "com/retromod/legacyapi/neoforge/";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void neoForgeTarget() {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("On NeoForge 26.3 listeners of the removed shader and container screen events name rebuilt events, and the anger event its new name")
    void removedEventsResolve() {
        register("26.3", name -> null);
        assertEquals(ROOT + "client/event/LegacyRegisterShadersEvent", listenerType(NEO + "client/event/RegisterShadersEvent"));
        assertEquals(ROOT + "client/event/LegacyRegisterShadersEvent", listenerType(FORGE + "client/event/RegisterShadersEvent"));
        assertEquals(ROOT + "client/event/LegacyContainerScreenEvent$Render$Foreground",
                listenerType(NEO + "client/event/ContainerScreenEvent$Render$Foreground"));
        assertEquals(NEO + "event/entity/living/EndermanAngerEvent", listenerType(FORGE + "event/entity/living/EnderManAngerEvent"),
                "26.3 renamed the anger event and still posts it");
        assertTrue(transformer.getSyntheticClasses().containsKey(ROOT + "client/event/LegacyNeoForgeClientEvents"), "the installer is embedded");
    }

    @Test
    @DisplayName("NeoForge 26.1 and 26.2 keep their own container screen and anger events, and nothing registers on a Forge target")
    void earlierHostsKeepWhatTheyHave() {
        register("26.2", name -> name.endsWith("ContainerScreenEvent") || name.endsWith("EnderManAngerEvent")
                || name.endsWith("common/NeoForge") ? new ClassNode() : null);
        assertEquals(NEO + "client/event/ContainerScreenEvent$Render", listenerType(NEO + "client/event/ContainerScreenEvent$Render"));
        assertEquals(ROOT + "client/event/LegacyRegisterShadersEvent", listenerType(NEO + "client/event/RegisterShadersEvent"));
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        register("26.3", name -> null);
        assertEquals(NEO + "client/event/RegisterShadersEvent", listenerType(NEO + "client/event/RegisterShadersEvent"));
    }

    @Test
    @DisplayName("Old fluid texture and tint reads, fluid stack tag calls, player render reads and getModels go to rebuilt helpers")
    void oldCallsGoToHelpers() {
        register("26.3", name -> null);
        String ext = NEO + "client/extensions/common/IClientFluidTypeExtensions";
        assertEquals(ROOT + "client/extensions/LegacyFluidTypeReads.getStillTexture(L" + ext + ";)Lnet/minecraft/resources/Identifier;",
                call(INVOKEINTERFACE, ext, "getStillTexture", "()Lnet/minecraft/resources/Identifier;"));
        assertEquals(ROOT + "client/extensions/LegacyFluidTypeReads.getTintColor(L" + ext + ";L" + NEO + "fluids/FluidStack;)I",
                call(INVOKEINTERFACE, FORGE + "client/extensions/common/IClientFluidTypeExtensions", "getTintColor", "(L" + NEO + "fluids/FluidStack;)I"));
        String draw = "(Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
                + "Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/block/state/BlockState;)Z";
        assertTrue(call(INVOKESPECIAL, ext, "renderFluid", draw).startsWith(ROOT + "client/extensions/LegacyFluidTypeReads.renderFluid("),
                "an override's super call of the removed fluid draw answers the old default");
        assertEquals(ROOT + "common/LegacyFluidStackTags.getOrCreateTag(L" + NEO + "fluids/FluidStack;)Lnet/minecraft/nbt/CompoundTag;",
                call(INVOKEVIRTUAL, NEO + "fluids/FluidStack", "getOrCreateTag", "()Lnet/minecraft/nbt/CompoundTag;"));
        assertEquals(ROOT + "client/model/LegacyNeoForgeModelMaps.getModels(L" + NEO + "client/event/ModelEvent$ModifyBakingResult;)Ljava/util/Map;",
                call(INVOKEVIRTUAL, NEO + "client/event/ModelEvent$ModifyBakingResult", "getModels", "()Ljava/util/Map;"));
        assertEquals(LegacyNeoForgeClientApiBridge.NO_PLAYER_RENDERER + ".getRenderer(Ljava/lang/Object;)L"
                + LegacyNeoForgeClientApiBridge.PLAYER_RENDERER + ";", call(INVOKEVIRTUAL, NEO + "client/event/RenderPlayerEvent$Pre",
                "getRenderer", "()L" + LegacyNeoForgeClientApiBridge.PLAYER_RENDERER + ";"));
    }

    @Test
    @DisplayName("The old spawn finalize constructor with a spawn tag and spawner logic builds NeoForge's event through a factory")
    void oldFinalizeConstructor() {
        register("26.3", name -> null);
        String desc = LegacyNeoForgeClientApiBridge.oldFinalize();
        assertEquals(ROOT + "event/LegacyFinalizeSpawn.create" + desc.substring(0, desc.length() - 1)
                + "L" + LegacyNeoForgeClientApiBridge.NEO_FINALIZE + ";", call(INVOKESPECIAL, LegacyNeoForgeClientApiBridge.NEO_FINALIZE, "<init>", desc));
    }

    @Test
    @DisplayName("The install call goes only into jars that name a removed client event or the old fluid extension, nested names included")
    void installPlan() {
        assertNotNull(LegacyNeoForgeClientApiBridge.plan(Map.of("a", bytes(NEO + "client/event/ContainerScreenEvent$Render$Background")), Map.of()));
        assertNotNull(LegacyNeoForgeClientApiBridge.plan(Map.of("a", bytes("L" + FORGE + "client/event/RegisterShadersEvent;")), Map.of()));
        assertNull(LegacyNeoForgeClientApiBridge.plan(Map.of("a", bytes(NEO + "client/event/ContainerScreenEventHelper")), Map.of()));
        ModConstructorHooks.JarPlan plan = LegacyNeoForgeClientApiBridge.plan(
                Map.of("a", bytes(NEO + "client/extensions/common/IClientFluidTypeExtensions")), Map.of());
        assertEquals(LegacyNeoForgeClientApiBridge.INSTALLER, plan.callsFor("mod/Main", "mod").get(0).owner());
    }

    @Test
    @DisplayName("The PlayerRenderer helper answers null to a caller, without the renamed class ever loading")
    void noPlayerRendererLoads() throws Exception {
        String helper = LegacyNeoForgeClientApiBridge.NO_PLAYER_RENDERER;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/AsksRenderer", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "ask", "()Ljava/lang/Object;", null, null);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKESTATIC, helper, "getRenderer", "(Ljava/lang/Object;)L" + LegacyNeoForgeClientApiBridge.PLAYER_RENDERER + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        cw.visitEnd();
        Map<String, byte[]> classes = Map.of(helper.replace('/', '.'), LegacyNeoForgeClientApiBridge.noPlayerRenderer(),
                "mod.AsksRenderer", cw.toByteArray());
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        assertNull(loader.loadClass("mod.AsksRenderer").getMethod("ask").invoke(null));
    }

    private void register(String host, Function<String, ClassNode> classes) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        LegacyNeoForgeClientApiBridge.register(transformer, classes);
        transformer.setModSourceVersion("1.20.1");
    }

    private String listenerType(String event) {
        MethodNode listener = transformed(cw -> {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "on", "(L" + event + ";)V", null, null);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 1);
        });
        return Type.getArgumentTypes(listener.desc)[0].getInternalName();
    }

    /** The one call {@code owner.name desc} becomes, as {@code owner.name desc}. */
    public static String call(int opcode, String owner, String name, String desc, RetromodTransformer transformer) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Uses", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "use", "()V", null, null);
        mv.visitCode();
        boolean constructor = name.equals("<init>");
        if (constructor) {
            mv.visitTypeInsn(NEW, owner);
            mv.visitInsn(DUP);
        } else if (opcode != INVOKESTATIC) {
            mv.visitInsn(ACONST_NULL);
        }
        for (Type arg : Type.getArgumentTypes(desc)) pushDefault(mv, arg);
        mv.visitMethodInsn(opcode, owner, name, desc, opcode == INVOKEINTERFACE || (opcode == INVOKESPECIAL && !constructor));
        Type result = constructor ? Type.getObjectType(owner) : Type.getReturnType(desc);
        if (result.getSize() > 0) mv.visitInsn(result.getSize() == 2 ? POP2 : POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "mod/Uses.class")).accept(node, 0);
        for (AbstractInsnNode insn : node.methods.stream().filter(m -> m.name.equals("use")).findFirst().orElseThrow().instructions) {
            if (insn instanceof MethodInsnNode m) return m.owner + "." + m.name + m.desc;
        }
        return null;
    }

    private String call(int opcode, String owner, String name, String desc) {
        return call(opcode, owner, name, desc, transformer);
    }

    static void pushDefault(MethodVisitor mv, Type type) {
        switch (type.getSort()) {
            case Type.OBJECT, Type.ARRAY -> mv.visitInsn(ACONST_NULL);
            case Type.DOUBLE -> mv.visitInsn(DCONST_0);
            case Type.FLOAT -> mv.visitInsn(FCONST_0);
            case Type.LONG -> mv.visitInsn(LCONST_0);
            default -> mv.visitInsn(ICONST_0);
        }
    }

    private MethodNode transformed(java.util.function.Consumer<ClassWriter> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/Listener", null, "java/lang/Object", null);
        body.accept(cw);
        cw.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "mod/Listener.class")).accept(node, 0);
        return node.methods.get(0);
    }

    private static byte[] bytes(String text) {
        return ("\u0001" + text + "\u0001").getBytes(StandardCharsets.ISO_8859_1);
    }
}
