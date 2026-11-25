/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;

import java.util.List;
import java.util.function.Function;

import static com.retromod.shim.forge.LoaderApiHostJars.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 1.20.1 loader APIs that Forge 26.2 kept with another shape. */
class ForgeOn262LoaderApiTest {

    private static final String TRACKER = LegacyConfigTrackerBridge.TRACKER;
    private static final String L_TRACKER = LegacyConfigTrackerBridge.L_TRACKER;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();
    private RetromodTransformer transformer;

    @BeforeEach
    void forge262() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Calls on ConfigTracker.INSTANCE become the static calls, and a newer mod's static call is left alone")
    void configTrackerSingleton() {
        LegacyConfigTrackerBridge.register(transformer, name -> null);
        byte[] old = transformer.transformClass(mod("mod/Configs", "()Ljava/lang/Object;", mv -> {
            mv.visitFieldInsn(GETSTATIC, TRACKER, "INSTANCE", L_TRACKER);
            mv.visitMethodInsn(INVOKEVIRTUAL, TRACKER, "configSets", "()Ljava/util/Map;", false);
            mv.visitInsn(ARETURN);
        }), "mod/Configs");
        assertEquals(List.of(LegacyConfigTrackerBridge.HELPER + ".instance()" + L_TRACKER,
                LegacyConfigTrackerBridge.HELPER + ".configSets(Ljava/lang/Object;)Ljava/util/Map;"), refs(old));
        byte[] newer = transformer.transformClass(mod("mod/NewConfigs", "()Ljava/lang/Object;", mv -> {
            mv.visitMethodInsn(INVOKESTATIC, TRACKER, "configSets", "()Ljava/util/Map;", false);
            mv.visitInsn(ARETURN);
        }), "mod/NewConfigs");
        assertEquals(List.of(TRACKER + ".configSets()Ljava/util/Map;"), refs(newer));
        assertTrue(refs(transformer.getSyntheticClasses().get(LegacyConfigTrackerBridge.HELPER))
                .contains(TRACKER + ".loadConfigs(Lnet/minecraftforge/fml/config/ModConfig$Type;Ljava/nio/file/Path;)V"));
    }

    @Test
    @DisplayName("Fall distance, field of view and anvil cost calls convert the number type the host changed")
    void primitiveDrift() {
        LegacyForgeEventMembersBridge.register(transformer);
        String fall = "net/minecraftforge/event/entity/living/LivingFallEvent";
        String fov = "net/minecraftforge/client/event/ViewportEvent$ComputeFov";
        byte[] out = transformer.transformClass(mod("mod/Events", "(L" + fall + ";L" + fov + ";)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, fall, "getDistance", "()F", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, fall, "setDistance", "(F)V", false);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, fov, "getFOV", "()D", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, fov, "setFOV", "(D)V", false);
            mv.visitInsn(RETURN);
        }), "mod/Events");
        assertEquals(List.of(fall + ".getDistance()D", fall + ".setDistance(D)V", fov + ".getFOV()F", fov + ".setFOV(F)V"), refs(out));
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);
        List<Integer> conversions = new java.util.ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions) {
            if (insn instanceof InsnNode plain && plain.getOpcode() >= I2L && plain.getOpcode() <= I2S) conversions.add(plain.getOpcode());
        }
        assertEquals(List.of(D2F, F2D, F2D, D2F), conversions);
    }

    @Test
    @DisplayName("ForgeHooks.getBurnTime goes to the rebuilt read on the world's fuel values")
    void burnTime() {
        LegacyForgeHooksBridge.registerForgeBurnTime(transformer);
        byte[] out = transformer.transformClass(mod("mod/Fuel", "(Lnet/minecraft/world/item/ItemStack;)I", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESTATIC, LegacyForgeHooksBridge.FORGE_HOOKS, "getBurnTime", LegacyForgeHooksBridge.BURN_TIME_DESC, false);
            mv.visitInsn(IRETURN);
        }), "mod/Fuel");
        assertEquals(List.of(LegacyForgeHooksBridge.FORGE_BURN_TIMES + ".getBurnTime" + LegacyForgeHooksBridge.BURN_TIME_DESC), refs(out));
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyForgeHooksBridge.FORGE_BURN_TIMES));

        // 26.3 has no FuelValues for the rebuilt read to ask, so nothing is registered there.
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        LegacyForgeHooksBridge.registerForgeBurnTime(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyForgeHooksBridge.FORGE_BURN_TIMES));
    }

    @Test
    @DisplayName("Against the Forge 26.2 jars: the old members are gone and every target has the shape the bridges call")
    void shapesMatchTheRealJars() {
        Assumptions.assumeTrue(present(FORGE_262, FORGE_262_CLIENT, FORGE_262_FMLCORE), "the Forge 26.2 jars are not in the local Prism library");
        Function<String, ClassNode> host = host(FORGE_262, FORGE_262_CLIENT, FORGE_262_FMLCORE);
        ClassNode tracker = host.apply(TRACKER);
        assertTrue(tracker.fields.stream().noneMatch(f -> f.name.equals("INSTANCE")));
        for (String[] method : LegacyConfigTrackerBridge.METHODS) {
            assertTrue(tracker.methods.stream().anyMatch(m -> m.name.equals(method[0]) && m.desc.equals(method[1])
                    && (m.access & ACC_STATIC) != 0), "static " + method[0]);
        }
        for (String[] drift : LegacyForgeEventMembersBridge.PRIMITIVE_DRIFT) {
            ClassNode owner = host.apply(drift[0]);
            assertTrue(owner.methods.stream().noneMatch(m -> m.name.equals(drift[1]) && m.desc.equals(drift[2])), "gone: " + drift[1] + drift[2]);
            assertTrue(owner.methods.stream().anyMatch(m -> m.name.equals(drift[1]) && m.desc.equals(drift[3])), "host: " + drift[1] + drift[3]);
        }
        assertTrue(host.apply(LegacyForgeHooksBridge.FORGE_HOOKS).methods.stream().noneMatch(m -> m.name.equals("getBurnTime")));
        assertTrue(host.apply("net/minecraft/world/level/block/entity/FuelValues").methods.stream()
                .anyMatch(m -> m.name.equals("burnDuration") && m.desc.equals(LegacyForgeHooksBridge.BURN_TIME_DESC)));
    }
}
