/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.fabric.Fabric_1_18_2_to_1_19;
import com.retromod.testutil.HostJarLinks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

/** A 1.18.2 animation loader reads its files through the 1.19 resource manager. */
class LegacyResourceApiBridgeTest {

    private static final String MOD = "com/example/AnimationFileLoader";
    private static final String PKG = "net/minecraft/server/packs/resources/";
    private static final String MANAGER = PKG + "ResourceManager";
    private static final String RESOURCE = PKG + "Resource";
    private static final String LOCATION = "Lnet/minecraft/resources/ResourceLocation;";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @BeforeEach
    void setUp() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        transformer.clearRedirectsForTesting();
        LegacyResourceApiBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        transformer.clearRedirectsForTesting();
    }

    @Test
    void oldLoaderOpensListsAndClosesThroughTheNewApi() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "load",
                "(L" + MANAGER + ";" + LOCATION + "Ljava/util/function/Predicate;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn("animations");
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, MANAGER, "listResources",
                "(Ljava/lang/String;Ljava/util/function/Predicate;)Ljava/util/Collection;", true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, MANAGER, "getResource", "(" + LOCATION + ")L" + RESOURCE + ";", true);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, RESOURCE, "getInputStream", "()Ljava/io/InputStream;", true);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, RESOURCE, "close", "()V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        List<String> calls = new ArrayList<>();
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), MOD)).accept(node, 0);
        node.methods.get(0).instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) {
                String owner = call.owner.substring(call.owner.lastIndexOf('/') + 1);
                calls.add(opcode(call.getOpcode()) + " " + owner + "." + call.name + (call.itf ? " itf" : ""));
            }
        });

        assertEquals(List.of(
                "static LegacyResourceApi.listResources",
                "interface ResourceManager.getResourceOrThrow itf",
                "virtual Resource.open",
                "static LegacyResourceApi.close"), calls,
                "the lookup keeps its exception, Resource is called as the class it became, "
                        + "and the listing and the close go through helpers");
    }

    @Test
    void generatedHelpersAreValid() {
        new ClassReader(LegacyResourceApiBridge.generateHelpers())
                .accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
        new ClassReader(LegacyResourceApiBridge.generateFilter())
                .accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    @Test
    void onA263HostTheListingGoesThroughTheSelectorHelper() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        LegacyResourceApiBridge.register(transformer, name -> null);

        assertEquals(List.of("static LegacyResourceSelector.listResources"),
                listingCalls(transformer.getSyntheticClasses().get(LegacyResourceApiBridge.SYNTH)),
                "26.3 lists through a selector, which the 26.3 selector helper wraps the name filter in");
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyResourceSelectorBridge.HELPER),
                "the helper the listing calls is registered with it");
        new ClassReader(LegacyResourceApiBridge.generateHelpers(true))
                .accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
    }

    @Test
    void fabricRegistersTheBridgeOnlyWhereModsCarryMojangNames() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        new Fabric_1_18_2_to_1_19().registerRedirects(transformer);
        RetromodTransformer.MethodTarget close = transformer.getMethodRedirects()
                .get(new RetromodTransformer.MethodKey(RESOURCE, "close", "()V"));
        assertEquals(LegacyResourceApiBridge.SYNTH, close == null ? null : close.owner(),
                "a 1.16.5 or 1.18.2 Fabric mod's resource calls reach the helpers on 26.3");

        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        transformer.clearRedirectsForTesting();
        new Fabric_1_18_2_to_1_19().registerRedirects(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyResourceApiBridge.SYNTH),
                "a pre-26.1 Fabric host runs intermediary names, which these Mojang-named bridges never match");
    }

    @Test
    void realHostsDecideBetweenThePredicateAndTheSelector() {
        assertSelectorHost("com/mojang/minecraft/26.1.2/minecraft-26.1.2-client.jar", false);
        assertSelectorHost("net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar", false);
        assertSelectorHost("com/mojang/minecraft/26.3/minecraft-26.3-client.jar", true);
        assertSelectorHost("net/neoforged/minecraft-client-patched/26.3.0.43-beta/minecraft-client-patched-26.3.0.43-beta.jar",
                true);
    }

    private static void assertSelectorHost(String relative, boolean selector) {
        Path jar = HostJarLinks.prism(relative);
        Assumptions.assumeTrue(jar != null, relative + " present");
        assertEquals(selector, LegacyResourceApiBridge.hostListsBySelector(HostJarLinks.read(List.of(jar), MANAGER)),
                relative);
        // Identifier is the 26.x name the class move gives ResourceLocation in every mod and helper.
        byte[] helpers = LegacyResourceApiBridge.generateHelpers(selector);
        List<String> missing = HostJarLinks.unresolvedIn(helpers, "listResources", List.of(jar), "net/minecraft/server/");
        assertTrue(missing.isEmpty(), relative + " lacks " + missing);
        assertFalse(listingCalls(helpers).contains("interface ResourceManager.listResources itf") && selector,
                "a selector host is never called with a predicate");
    }

    private static List<String> listingCalls(byte[] helpers) {
        ClassNode node = new ClassNode();
        new ClassReader(helpers).accept(node, 0);
        List<String> calls = new ArrayList<>();
        node.methods.stream().filter(method -> method.name.equals("listResources")).forEach(method ->
                method.instructions.forEach(insn -> {
                    if (insn instanceof MethodInsnNode call && call.name.equals("listResources")) {
                        String owner = call.owner.substring(call.owner.lastIndexOf('/') + 1);
                        calls.add(opcode(call.getOpcode()) + " " + owner + "." + call.name + (call.itf ? " itf" : ""));
                    }
                }));
        return calls;
    }

    private static String opcode(int opcode) {
        return switch (opcode) {
            case INVOKESTATIC -> "static";
            case INVOKEINTERFACE -> "interface";
            case INVOKEVIRTUAL -> "virtual";
            default -> "special";
        };
    }
}
