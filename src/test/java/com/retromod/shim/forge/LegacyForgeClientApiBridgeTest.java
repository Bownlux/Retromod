/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Function;

import static com.retromod.shim.neoforge.LegacyNeoForgeClientApiBridgeTest.call;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Forge 1.20.1's shader, player render and anger events, positional fluid reads and spawn constructor on Forge 26.2 and 26.3. */
class LegacyForgeClientApiBridgeTest {

    private static final String OLD = "net/minecraftforge/";
    private static final String ROOT = "com/retromod/legacyapi/forge/";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
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
    @DisplayName("Offline on Forge 26.2 the removed events are rebuilt and registerShader takes Forge's inert shader stand-in")
    void removedEventsAreRebuilt() {
        register("26.2", name -> null);
        assertTrue(transformer.getSyntheticClasses().containsKey(ROOT + "client/event/RegisterShadersEvent"));
        assertTrue(transformer.getSyntheticClasses().containsKey(ROOT + "client/event/RenderPlayerEvent$Pre"));
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyForgeClientApiBridge.INSTALLER), "the event poster is embedded");
        assertEquals(ROOT + "client/event/RegisterShadersEvent.registerShader(L" + ROOT + "client/event/RegisterShadersEvent;"
                + "Ljava/lang/Object;Ljava/util/function/Consumer;)V", call(INVOKEVIRTUAL, OLD + "client/event/RegisterShadersEvent",
                "registerShader", "(L" + LegacyForgeClientApiBridge.LEGACY_SHADER + ";Ljava/util/function/Consumer;)V", transformer));
        assertEquals(ROOT + "client/LegacyForgeFluidTypeReads.getTintColor(L" + LegacyForgeClientApiBridge.FLUID_EXT + ";Lnet/minecraft/world/level/"
                + "material/FluidState;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I",
                call(INVOKEINTERFACE, LegacyForgeClientApiBridge.FLUID_EXT, "getTintColor", "(Lnet/minecraft/world/level/material/FluidState;"
                        + "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I", transformer));
        String spawn = LegacyForgeClientApiBridge.oldFinalize();
        assertTrue(call(INVOKESPECIAL, LegacyForgeClientApiBridge.FINALIZE, "<init>", spawn, transformer)
                .startsWith(ROOT + "event/LegacyFinalizeSpawn.create"), "the old constructor goes to the factory");
    }

    @Test
    @DisplayName("The anger event names EnderMan on Forge 26.2 and Enderman on 26.3, where Minecraft renamed the class")
    void angerEventFollowsTheEndermanRename() {
        register("26.2", name -> null);
        assertEquals("()Lnet/minecraft/world/entity/monster/EnderMan;", getEntityDesc());
        register("26.3", name -> null);
        assertEquals("()Lnet/minecraft/world/entity/monster/Enderman;", getEntityDesc());
        ClassNode event = node(transformer.getSyntheticClasses().get(LegacyForgeClientApiBridge.ANGER));
        assertEquals(LegacyForgeClientApiBridge.LIVING_EVENT, event.superName, "old listeners keep the legacy cancel rules");
    }

    @Test
    @DisplayName("A Forge host that still has the events, and every NeoForge target, take none of it")
    void hostsWithTheEventsKeepThem() {
        register("26.2", name -> name.startsWith(OLD) ? new ClassNode() : null);
        assertFalse(transformer.getSyntheticClasses().containsKey(ROOT + "client/event/RegisterShadersEvent"));
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        register("26.2", name -> null);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyForgeClientApiBridge.ANGER));
    }

    @Test
    @DisplayName("ForgeHooks.getBurnTime reads the fuel component on Forge 26.3 and the fuel values on 26.2")
    void burnTimeFollowsTheHost() {
        String desc = LegacyForgeHooksBridge.BURN_TIME_DESC;
        for (String[] host : new String[][]{{"26.3", LegacyForgeHooksBridge.COMPONENT_BURN_TIMES}, {"26.2", LegacyForgeHooksBridge.FORGE_BURN_TIMES}}) {
            RetromodVersion.TARGET_MC_VERSION = host[0];
            transformer.clearRedirectsForTesting();
            LegacyForgeHooksBridge.registerForgeBurnTime(transformer);
            transformer.setModSourceVersion("1.20.1");
            assertEquals(host[1] + ".getBurnTime" + desc, call(INVOKESTATIC, LegacyForgeHooksBridge.FORGE_HOOKS, "getBurnTime", desc, transformer), host[0]);
            assertTrue(transformer.getSyntheticClasses().containsKey(host[1]), "the rebuilt read is embedded on " + host[0]);
        }
    }

    private void register(String host, Function<String, ClassNode> classes) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        LegacyForgeClientApiBridge.register(transformer, classes);
        transformer.setModSourceVersion("1.20.1");
    }

    private String getEntityDesc() {
        return node(transformer.getSyntheticClasses().get(LegacyForgeClientApiBridge.ANGER)).methods.stream()
                .filter(m -> m.name.equals("getEntity")).findFirst().orElseThrow().desc;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
