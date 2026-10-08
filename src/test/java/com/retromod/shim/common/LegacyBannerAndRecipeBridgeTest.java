/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

/** 1.18.2 banner patterns (1.19.3 to 1.20.4 hosts) and special crafting recipes (1.19.3 to 1.20.1). */
class LegacyBannerAndRecipeBridgeTest {

    private static final String PATTERN = "net/minecraft/world/level/block/entity/BannerPattern";
    private static final String CREATE_DESC =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)L" + PATTERN + ";";
    private static final String CRAFTING = "net/minecraft/world/item/crafting/";
    private static final String LOCATION = "Lnet/minecraft/resources/ResourceLocation;";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @TempDir
    Path modRoot;

    @BeforeEach
    void setUp() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        transformer.clearRedirectsForTesting();
        LegacyBannerPatternBridge.register(transformer);
        LegacyCraftingRecipeBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        transformer.clearRedirectsForTesting();
    }

    @Test
    void bannerPatternIsRegisteredAndItsItemTagIsWritten() throws Exception {
        byte[] patterns = staticInit("com/example/ModBannerPatterns", mv -> {
            pattern(mv, "dragon_crest", true);
            pattern(mv, "plain_stripe", false);
        });
        byte[] transformed = transformer.transformClass(patterns, "com/example/ModBannerPatterns");
        assertEquals(List.of("LegacyBannerPatterns.create", "LegacyBannerPatterns.create"), calls(transformed));

        Path file = modRoot.resolve("com/example/ModBannerPatterns.class");
        Files.createDirectories(file.getParent());
        Files.write(file, transformed);
        assertEquals(2, LegacyBannerPatternBridge.writeTags(modRoot));
        assertTrue(Files.readString(modRoot.resolve(
                "data/dragons/tags/banner_pattern/pattern_item/dragon_crest.json")).contains("\"dragons:dragon_crest\""),
                "the pattern item's tag holds its pattern");
        String noItem = Files.readString(modRoot.resolve("data/minecraft/tags/banner_pattern/no_item_required.json"));
        assertTrue(noItem.contains("\"replace\": false") && noItem.contains("\"dragons:plain_stripe\""),
                "a pattern that never had an item stays available without one");
    }

    @Test
    void simpleSerializerIsBuiltFromAnIdOnlyFactory() {
        String old = CRAFTING + "SimpleRecipeSerializer";
        byte[] recipes = staticInit("com/example/ModRecipes", mv -> {
            mv.visitTypeInsn(NEW, old);
            mv.visitInsn(DUP);
            mv.visitInsn(ACONST_NULL);
            mv.visitMethodInsn(INVOKESPECIAL, old, "<init>", "(Ljava/util/function/Function;)V", false);
            mv.visitInsn(POP);
        });
        assertEquals(List.of("LegacyRecipes.simpleSerializer"),
                calls(transformer.transformClass(recipes, "com/example/ModRecipes")));
    }

    @Test
    void oldAssembleOverrideImplementsTheRegistryAwareMethod() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "com/example/ArrowRecipe", null, CRAFTING + "CustomRecipe", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "assemble",
                "(Lnet/minecraft/world/inventory/CraftingContainer;)Lnet/minecraft/world/item/ItemStack;", null, null);
        mv.visitCode();
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] adapted = LegacyCraftingRecipeBridge.apply(cw.toByteArray(), transformer);
        new ClassReader(adapted).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
        MethodNode bridge = node(adapted).methods.stream()
                .filter(m -> m.desc.equals(LegacyCraftingRecipeBridge.NEW_ASSEMBLE)).findFirst().orElseThrow();
        MethodInsnNode forward = (MethodInsnNode) bridge.instructions.get(3);
        assertEquals("assemble", forward.name);
        assertEquals("(Lnet/minecraft/world/inventory/CraftingContainer;)Lnet/minecraft/world/item/ItemStack;",
                forward.desc, "the new method forwards to the mod's own override");

        byte[] again = LegacyCraftingRecipeBridge.apply(adapted, transformer);
        assertEquals(1, node(again).methods.stream()
                .filter(m -> m.desc.equals(LegacyCraftingRecipeBridge.NEW_ASSEMBLE)).count(),
                "a class that already has the new method is left alone");
    }

    @Test
    void hostsOutsideTheRegistryEraAreNotBridged() {
        assertFalse(LegacyBannerPatternBridge.appliesTo("1.19.2"));
        assertFalse(LegacyBannerPatternBridge.appliesTo("1.20.5"));
        assertTrue(LegacyCraftingRecipeBridge.appliesTo("1.20.1"));
        assertTrue(LegacyBannerPatternBridge.appliesTo("1.20.4"));
        assertFalse(LegacyCraftingRecipeBridge.appliesTo("1.20.2"),
                "1.20.2 builds special recipes from a category alone, so the id-taking factory would not link");
        assertFalse(LegacyCraftingRecipeBridge.appliesTo("1.21.1"));
    }

    @Test
    void generatedHelpersAreValid() {
        for (byte[] generated : List.of(LegacyBannerPatternBridge.generate(),
                LegacyCraftingRecipeBridge.generateHelpers(), LegacyCraftingRecipeBridge.generateFactory())) {
            new ClassReader(generated).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
        }
    }

    private static void pattern(MethodVisitor mv, String name, boolean hasItem) {
        mv.visitLdcInsn(name.toUpperCase());
        mv.visitLdcInsn("dragons:" + name);
        mv.visitLdcInsn("dragons:" + name);
        mv.visitInsn(hasItem ? ICONST_1 : ICONST_0);
        mv.visitMethodInsn(INVOKESTATIC, PATTERN, "create", CREATE_DESC, false);
        mv.visitInsn(POP);
    }

    private static byte[] staticInit(String name, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> calls(byte[] classBytes) {
        List<String> calls = new ArrayList<>();
        node(classBytes).methods.get(0).instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) {
                calls.add(call.owner.substring(call.owner.lastIndexOf('/') + 1) + "." + call.name);
            }
        });
        return calls;
    }
}
