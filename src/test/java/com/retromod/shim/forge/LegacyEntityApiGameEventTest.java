/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.testutil.HostJarLinks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A 1.18.2 mob's {@code gameEvent(event, pos)} posts through the holder form that 1.20.5 and newer take. */
class LegacyEntityApiGameEventTest {

    private static final String ACCESSOR = "net/minecraft/world/level/LevelAccessor";
    private static final String OLD_EVENT = "(Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/world/level/gameevent/GameEvent;Lnet/minecraft/core/BlockPos;)V";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        transformer.clearRedirectsForTesting();
    }

    @Test
    void aHolderHostPostsThroughTheRebuiltLevelCall() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        LegacyEntityApi120Adapter.register(transformer, name -> null);

        byte[] generated = transformer.getSyntheticClasses().get(LegacyEntityApi120Adapter.SYNTH);
        assertEquals(List.of(LegacyEntityApi120Adapter.LEVEL_EVENTS + ".gameEvent(L" + ACCESSOR + ";" + OLD_EVENT.substring(1)),
                gameEventCalls(generated));
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyEntityApi120Adapter.LEVEL_EVENTS),
                "the rebuilt call travels with the helper that names it");
        new ClassReader(generated).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    @Test
    void aBareEventHostKeepsTheLevelCall() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        transformer.clearRedirectsForTesting();
        LegacyEntityApi120Adapter.register(transformer, name -> null);

        assertEquals(List.of(ACCESSOR + ".gameEvent" + OLD_EVENT),
                gameEventCalls(transformer.getSyntheticClasses().get(LegacyEntityApi120Adapter.SYNTH)));
    }

    @Test
    void realHostsTakeHolders() {
        for (String relative : List.of(
                "net/neoforged/minecraft-client-patched/21.11.42/minecraft-client-patched-21.11.42.jar",
                "net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar",
                "net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar")) {
            Path jar = HostJarLinks.prism(relative);
            Assumptions.assumeTrue(jar != null, relative + " present");
            RetromodVersion.TARGET_MC_VERSION = "1.20.1";
            assertTrue(LegacyEntityApi120Adapter.hostTakesGameEventHolders(HostJarLinks.read(List.of(jar), ACCESSOR)),
                    relative + " is read as a holder host whatever the target says");
            List<String> missing = HostJarLinks.unresolvedIn(LegacyEntityApi120Adapter.generate(true), "gameEvent",
                    List.of(jar), "net/minecraft/");
            assertTrue(missing.isEmpty(), relative + " lacks " + missing);
        }
    }

    private static List<String> gameEventCalls(byte[] generated) {
        ClassNode node = new ClassNode();
        new ClassReader(generated).accept(node, 0);
        List<String> calls = new ArrayList<>();
        node.methods.stream().filter(method -> method.name.equals("gameEvent")).forEach(method ->
                method.instructions.forEach(insn -> {
                    if (insn instanceof MethodInsnNode call && call.name.equals("gameEvent")) {
                        calls.add(call.owner + "." + call.name + call.desc);
                    }
                }));
        return calls;
    }
}
