/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.api.fabric.ForgeConfigApiPortV5Shim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Forge Config API Port dropped its {@code neoforge.v4} API for {@code v5} at Minecraft 1.21.5, so a
 * 1.21.1 mod died in {@code onInitialize} with {@code NoClassDefFoundError} naming
 * {@code NeoForgeConfigRegistry} (#249, Sophisticated Core and Backpacks on 26.1.2).
 */
class ForgeConfigApiPortV5ShimTest {

    private static final String V4 = "fuzs/forgeconfigapiport/fabric/api/neoforge/v4/";
    private static final String V5 = "fuzs/forgeconfigapiport/fabric/api/v5/";
    private static final String MOD_CONFIG = "net/neoforged/fml/config/ModConfig";
    private static final String REGISTER_DESC = "(Ljava/lang/String;L" + MOD_CONFIG
            + "$Type;Lnet/neoforged/fml/config/IConfigSpec;)L" + MOD_CONFIG + ";";

    @AfterEach
    void clearRedirects() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void appliesOnlyWhereThePortShipsV5() {
        ForgeConfigApiPortV5Shim shim = new ForgeConfigApiPortV5Shim();
        for (String host : List.of("1.20.1", "1.21.1", "1.21.4")) {
            assertFalse(ShimRegistry.isAvailableOnHost(shim, host),
                    host + " ships the v4 API, so nothing may be rewritten");
        }
        for (String host : List.of("1.21.5", "1.21.11", "26.1.2", "26.2")) {
            assertTrue(ShimRegistry.isAvailableOnHost(shim, host),
                    host + " ships only the v5 API, so the bridge must apply");
        }
        assertEquals("fabric", shim.getModLoaderType(), "the port is a Fabric library");
    }

    @Test
    void sameShapeEventTypesMoveOntoV5() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new ForgeConfigApiPortV5Shim().registerRedirects(transformer);

        assertEquals(V5 + "ModConfigEvents$Reloading",
                transformer.getClassRedirects().get(V4 + "NeoForgeModConfigEvents$Reloading"),
                "the listener type Sophisticated Core registers must land on v5");
        assertEquals(V5 + "ModConfigEvents",
                transformer.getClassRedirects().get(V4 + "NeoForgeModConfigEvents"));
        assertEquals(V5 + "client/ConfigScreenFactoryRegistry",
                transformer.getClassRedirects().get(V4 + "client/ConfigScreenFactoryRegistry"));
    }

    @Test
    void registryStandInKeepsTheV4ReturnValueAndForwardsToTheTracker() {
        ClassNode registry = read(ForgeConfigApiPortV5Shim.generateRegistry());
        assertTrue((registry.access & ACC_INTERFACE) != 0,
                "mods call register with INVOKEINTERFACE, so the stand-in must stay an interface");
        assertTrue(registry.fields.stream().anyMatch(f -> f.name.equals("INSTANCE")
                        && (f.access & ACC_STATIC) != 0),
                "mods read the registry through its static INSTANCE");
        assertTrue(registry.methods.stream().anyMatch(m -> m.name.equals("register")
                        && m.desc.equals(REGISTER_DESC)),
                "register must keep the v4 descriptor, ModConfig return included");

        ClassNode impl = read(ForgeConfigApiPortV5Shim.generateRegistryImpl());
        MethodNode register = impl.methods.stream()
                .filter(m -> m.name.equals("register") && m.desc.equals(REGISTER_DESC))
                .findFirst().orElseThrow();
        MethodInsnNode forward = null;
        List<Integer> loads = new ArrayList<>();
        for (AbstractInsnNode insn : register.instructions) {
            if (insn instanceof VarInsnNode var && var.getOpcode() == ALOAD) loads.add(var.var);
            if (insn instanceof MethodInsnNode call) forward = call;
        }
        assertNotNull(forward, "register must forward somewhere");
        assertEquals("net/neoforged/fml/config/ConfigTracker", forward.owner);
        assertEquals("registerConfig", forward.name,
                "the v4 implementation called ConfigTracker.registerConfig, and so must the bridge");
        assertEquals(List.of(2, 3, 1), loads,
                "register(modId, type, spec) becomes registerConfig(type, spec, modId)");
    }

    @Test
    void aV4RegistrationIsRewrittenOntoTheStandIn() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new ForgeConfigApiPortV5Shim().registerRedirects(transformer);

        ClassNode out = read(transformer.transformClass(sophisticatedStyleInit(),
                "test/mod/ModInit.class"));
        boolean readsStandIn = false;
        boolean callsStandIn = false;
        for (MethodNode method : out.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field) {
                    assertFalse(field.owner.startsWith(V4), "a v4 field read survived");
                    readsStandIn |= field.owner.equals(ForgeConfigApiPortV5Shim.REGISTRY);
                }
                if (insn instanceof MethodInsnNode call) {
                    assertFalse(call.owner.startsWith(V4), "a v4 call survived: " + call.owner);
                    callsStandIn |= call.owner.equals(ForgeConfigApiPortV5Shim.REGISTRY)
                            && call.desc.equals(REGISTER_DESC) && call.itf;
                }
            }
        }
        assertTrue(readsStandIn, "INSTANCE must be read from the stand-in");
        assertTrue(callsStandIn, "register must stay an interface call on the stand-in");
        assertTrue(transformer.getSyntheticClasses().containsKey(ForgeConfigApiPortV5Shim.REGISTRY)
                        && transformer.getSyntheticClasses()
                        .containsKey(ForgeConfigApiPortV5Shim.REGISTRY_IMPL),
                "both stand-in classes must be registered for embedding");
    }

    /** The registration Sophisticated Core makes in {@code onInitialize}. */
    private static byte[] sophisticatedStyleInit() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/ModInit", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "init",
                "(Lnet/neoforged/fml/config/IConfigSpec;)V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, V4 + "NeoForgeConfigRegistry", "INSTANCE",
                "L" + V4 + "NeoForgeConfigRegistry;");
        mv.visitLdcInsn("testmod");
        mv.visitFieldInsn(GETSTATIC, MOD_CONFIG + "$Type", "COMMON", "L" + MOD_CONFIG + "$Type;");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, V4 + "NeoForgeConfigRegistry", "register",
                REGISTER_DESC, true);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
