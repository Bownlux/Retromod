/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyVillagerBridge.ProfessionShape;
import com.retromod.shim.forge.Forge_1_20_to_NeoForge_1_21;
import com.retromod.testutil.HostJarLinks;
import com.retromod.util.McReflect;
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

/** A 1.18.2 mod's profession is built with the constructor its host has, and its registry check is the host's. */
class LegacyVillagerHostShapeTest {

    private static final String PROFESSION = "net/minecraft/world/entity/npc/villager/VillagerProfession";
    private static final String REGISTRY_OBJECT = "net/minecraftforge/registries/RegistryObject";
    private static final String DEFERRED_HOLDER = "net/neoforged/neoforge/registries/DeferredHolder";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;
    private final boolean previousNeoForge = McReflect.isForceNeoForge();
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        McReflect.setForceNeoForge(previousNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @Test
    void offlineTargetsPickTheConstructorOfTheirVersion() {
        assertEquals(ProfessionShape.STRING_NAME, shapeFor("1.20.1"));
        assertEquals(ProfessionShape.COMPONENT_NAME, shapeFor("1.21.5"));
        assertEquals(ProfessionShape.COMPONENT_NAME_TRADE_SETS, shapeFor("26.1"));
        assertEquals(ProfessionShape.COMPONENT_NAME_TRADE_SETS, shapeFor("26.3"));
    }

    @Test
    void eachShapeBuildsTheProfessionWithItsOwnConstructor() {
        for (ProfessionShape shape : ProfessionShape.values()) {
            byte[] generated = LegacyVillagerBridge.generate(shape, false);
            new ClassReader(generated).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
            List<String> calls = calls(generated, "profession");
            assertTrue(calls.contains("<init>" + shape.constructor), shape + ": " + calls);
            assertEquals(shape != ProfessionShape.STRING_NAME, calls.contains("translatableWithFallback"
                    + "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;"),
                    shape + " turns the old name into a display name only where the host wants one");
        }
    }

    @Test
    void aNeoForgeHostAsksTheDeferredHolderWhetherItIsBound() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        McReflect.setForceNeoForge(true);
        assertTrue(LegacyVillagerBridge.registryObjectsAreDeferredHolders());
        assertTrue(calls(LegacyVillagerBridge.generate(), "poiOrPending").contains("isBound()Z"),
                "RegistryObject becomes DeferredHolder on NeoForge 1.21 and newer, which has isBound");

        McReflect.setForceNeoForge(false);
        assertTrue(calls(LegacyVillagerBridge.generate(), "poiOrPending").contains("isPresent()Z"),
                "Forge keeps its RegistryObject and isPresent");
    }

    @Test
    void aForgeModsIsPresentCallBecomesIsBoundOnNeoForge() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        McReflect.setForceNeoForge(true);
        transformer.clearRedirectsForTesting();
        new Forge_1_20_to_NeoForge_1_21().registerRedirects(transformer);
        for (String owner : List.of(REGISTRY_OBJECT, DEFERRED_HOLDER)) {
            RetromodTransformer.MethodTarget target = transformer.getMethodRedirects()
                    .get(new RetromodTransformer.MethodKey(owner, "isPresent", "()Z"));
            assertEquals(DEFERRED_HOLDER + ".isBound()Z", target.owner() + "." + target.name() + target.desc(), owner);
        }
    }

    @Test
    void realHostsHaveTheConstructorTheShapeNames() {
        checkHost("net/neoforged/minecraft-client-patched/21.11.42/minecraft-client-patched-21.11.42.jar",
                ProfessionShape.COMPONENT_NAME);
        checkHost("net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar",
                ProfessionShape.COMPONENT_NAME_TRADE_SETS);
        checkHost("net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar",
                ProfessionShape.COMPONENT_NAME_TRADE_SETS);
    }

    private static ProfessionShape shapeFor(String target) {
        RetromodVersion.TARGET_MC_VERSION = target;
        return LegacyVillagerBridge.professionShape(null);
    }

    private static void checkHost(String relative, ProfessionShape expected) {
        Path jar = HostJarLinks.prism(relative);
        Assumptions.assumeTrue(jar != null, relative + " present");
        Path fastutil = HostJarLinks.prism("it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar");
        Assumptions.assumeTrue(fastutil != null, "fastutil 8.5.18 present");
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        assertEquals(expected, LegacyVillagerBridge.professionShape(HostJarLinks.read(List.of(jar), PROFESSION)), relative);
        // The generated class names the pre-1.21.11 package until the embed applies the class move, so read it there.
        byte[] generated = LegacyVillagerBridge.generate(expected, false);
        List<String> missing = HostJarLinks.unresolvedIn(generated, "profession", List.of(jar), "net/minecraft/network/");
        assertTrue(missing.isEmpty(), relative + " lacks " + missing);
        assertTrue(HostJarLinks.read(List.of(jar), PROFESSION).methods.stream()
                .anyMatch(method -> method.name.equals("<init>") && method.desc.equals(expected.constructor)), relative);
        if (expected == ProfessionShape.COMPONENT_NAME_TRADE_SETS) {
            assertTrue(HostJarLinks.unresolvedIn(generated, "profession", List.of(fastutil), "it/unimi/").isEmpty());
        }
    }

    private static List<String> calls(byte[] generated, String methodName) {
        ClassNode node = new ClassNode();
        new ClassReader(generated).accept(node, 0);
        List<String> calls = new ArrayList<>();
        node.methods.stream().filter(method -> method.name.equals(methodName)).forEach(method ->
                method.instructions.forEach(insn -> {
                    if (insn instanceof MethodInsnNode call) calls.add(call.name + call.desc);
                }));
        return calls;
    }
}
