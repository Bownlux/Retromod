/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticLibraries;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #302: a Forge 1.20.1 Farmer's Delight addon builds its knife with damage and speed, which the
 * library's NeoForge 1.21 knife no longer takes.
 */
class LibraryToolConstructorTest {

    private static final String TIER = "Lnet/minecraft/world/item/Tier;";
    private static final String PROPS = "Lnet/minecraft/world/item/Item$Properties;";
    private static final String KNIFE = LegacyToolItemBridge.FARMERS_DELIGHT_KNIFE;
    private static final String OLD_KNIFE = "(" + TIER + "FF" + PROPS + ")V";
    private static final String NEW_KNIFE = "(" + TIER + PROPS + ")V";
    private static final String ADDON_ITEMS = "net/count/example/ModItems";

    private RetromodTransformer transformer;
    private String host;

    @BeforeEach
    void setUp() {
        host = RetromodVersion.TARGET_MC_VERSION;
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = host;
        McReflect.setForceNeoForge(false);
        transformer.clearLinkedLibraries();
        transformer.clearRedirectsForTesting();
    }

    @Test
    void anAddonKnifeWithDamageAndSpeedGoesThroughTheFactoryOnNeoForge() {
        registerOn("1.21.1", true);

        List<String> calls = calls(transformAddon(addonKnifeFactory()), "knife");

        assertTrue(calls.contains(LegacyToolItemBridge.HELPER + ".newKnifeItem(" + TIER + "FF" + PROPS + ")L" + KNIFE + ";"),
                "the 1.20.1 knife constructor must go through the factory: " + calls);
        assertFalse(calls.contains(KNIFE + ".<init>" + OLD_KNIFE), "the removed constructor must not be called");
    }

    @Test
    void theKnifeFactoryFoldsDamageAndSpeedIntoTheItemAttributes() throws Exception {
        registerOn("1.21.1", true);

        ClassNode helper = node(transformer.getSyntheticClasses().get(LegacyToolItemBridge.HELPER));
        List<String> calls = calls(helper, "newKnifeItem");

        assertEquals(List.of(
                "net/minecraft/world/item/DiggerItem.createAttributes(" + TIER + "FF)Lnet/minecraft/world/item/component/ItemAttributeModifiers;",
                "net/minecraft/world/item/Item$Properties.attributes(Lnet/minecraft/world/item/component/ItemAttributeModifiers;)" + PROPS,
                KNIFE + ".<init>" + NEW_KNIFE), calls);
        for (MethodNode method : helper.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
        }
    }

    @Test
    void anAddonKnifeSubclassCallsTheLibrarysCurrentConstructor() {
        registerOn("1.21.1", true);

        List<String> calls = calls(transformAddon(addonKnifeSubclass()), "<init>");

        assertTrue(calls.contains(LegacyToolItemBridge.HELPER + ".stashKnifeItem" + OLD_KNIFE),
                "the 1.20.1 arguments are converted before the super call: " + calls);
        assertTrue(calls.contains(KNIFE + ".<init>" + NEW_KNIFE), "the super call uses the 1.21 constructor: " + calls);
    }

    @Test
    void aForgeHostLeavesTheKnifeAlone() {
        registerOn("1.21.1", false);

        List<String> calls = calls(transformAddon(addonKnifeFactory()), "knife");

        assertTrue(calls.contains(KNIFE + ".<init>" + OLD_KNIFE), "Forge has no 1.21 Farmer's Delight: " + calls);
    }

    @Test
    void aNeoForgeHostBeforeTheToolChangeLeavesTheKnifeAlone() {
        registerOn("1.20.1", true);

        List<String> calls = calls(transformAddon(addonKnifeFactory()), "knife");

        assertTrue(calls.contains(KNIFE + ".<init>" + OLD_KNIFE), "Farmer's Delight 1.20.1 is native here: " + calls);
    }

    @Test
    void theLibrarysOwnKnivesKeepTheirConstructorWhenItIsTranslated() {
        registerOn("1.21.1", true);
        byte[] items = addonKnifeFactory();

        byte[] out;
        try (var scope = transformer.pushJarClassBytesProvider(
                Map.of(ADDON_ITEMS, items, KNIFE, knifeClass(OLD_KNIFE))::get)) {
            out = transformer.transformClass(items, ADDON_ITEMS);
        }

        assertTrue(calls(out, "knife").contains(KNIFE + ".<init>" + OLD_KNIFE),
                "a jar that ships the knife class builds it as that class still allows");
    }

    @Test
    void anAddonLinkedToATranslatedLibraryKeepsTheOldConstructor(@TempDir Path dir) throws IOException {
        registerOn("1.21.1", true);
        transformer.setLinkedLibraries(library(dir, knifeClass(OLD_KNIFE)));

        List<String> calls = calls(transformAddon(addonKnifeFactory()), "knife");

        assertTrue(calls.contains(KNIFE + ".<init>" + OLD_KNIFE),
                "a translated Farmer's Delight 1.20.1 still has the old knife: " + calls);
    }

    @Test
    void anAddonLinkedToTheLibrarysNeoForgeBuildUsesTheFactory(@TempDir Path dir) throws IOException {
        registerOn("1.21.1", true);
        transformer.setLinkedLibraries(library(dir, knifeClass(NEW_KNIFE)));

        List<String> calls = calls(transformAddon(addonKnifeFactory()), "knife");

        assertTrue(calls.contains(LegacyToolItemBridge.HELPER + ".newKnifeItem(" + TIER + "FF" + PROPS + ")L" + KNIFE + ";"),
                "the installed 1.21.1 knife only takes a tier and properties: " + calls);
    }

    private void registerOn(String hostVersion, boolean neoForge) {
        RetromodVersion.TARGET_MC_VERSION = hostVersion;
        McReflect.setForceNeoForge(neoForge);
        LegacyToolItemBridge.register(transformer);
    }

    /** Transforms one addon class with the addon jar's class list in scope, as every real path has. */
    private byte[] transformAddon(byte[] bytes) {
        String name = new ClassReader(bytes).getClassName();
        try (var scope = transformer.pushJarClassBytesProvider(Map.of(name, bytes)::get)) {
            return transformer.transformClass(bytes, name);
        }
    }

    private static SyntheticLibraries library(Path dir, byte[] knife) throws IOException {
        Path jar = dir.resolve("FarmersDelight.jar");
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file)) {
            out.putNextEntry(new JarEntry(KNIFE + ".class"));
            out.write(knife);
            out.closeEntry();
        }
        SyntheticLibraries libraries = SyntheticLibraries.create();
        libraries.add(jar);
        return libraries;
    }

    /** {@code new KnifeItem(tier, 0.5F, -2.0F, properties)}, as the addon's item registration writes it. */
    private static byte[] addonKnifeFactory() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, ADDON_ITEMS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "knife",
                "(" + TIER + PROPS + ")Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, KNIFE);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn(0.5f);
        mv.visitLdcInsn(-2.0f);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, KNIFE, "<init>", OLD_KNIFE, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] addonKnifeSubclass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/count/example/StorageKnife", null, KNIFE, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + TIER + PROPS + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitLdcInsn(1.0f);
        mv.visitLdcInsn(-2.0f);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, KNIFE, "<init>", OLD_KNIFE, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The library's knife class with one constructor, which is all the guard reads. */
    private static byte[] knifeClass(String constructor) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, KNIFE, null, "net/minecraft/world/item/DiggerItem", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", constructor, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes, String methodName) {
        return calls(node(bytes), methodName);
    }

    private static List<String> calls(ClassNode node, String methodName) {
        List<String> calls = new ArrayList<>();
        MethodNode method = node.methods.stream().filter(m -> m.name.equals(methodName)).findFirst().orElseThrow();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
