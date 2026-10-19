/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * 26.3 removed {@code RenderTypes.entityGlint()}. {@code patternedShieldGlint()} builds the same
 * render type: the glint pipeline, the item glint texture and the entity glint texturing, with no
 * layering, plus 26.3's forced solid model phase for glints. A custom layer that draws an
 * enchantment shine with it keeps working.
 */
class GlintRenderTypes263Test {

    private static final String RENDER_TYPES = "net/minecraft/client/renderer/rendertype/RenderTypes";
    private static final String GETTER = "()Lnet/minecraft/client/renderer/rendertype/RenderType;";
    private static final String BUILDER = "net/minecraft/client/renderer/rendertype/RenderSetup$RenderSetupBuilder";
    private static final String CALLER = "test/mod/GlintLayer";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("entityGlint() becomes patternedShieldGlint() on 26.3")
    void entityGlintIsRenamed() {
        Mc26_2To26_3CoreMoves.register(transformer);
        assertEquals(List.of(RENDER_TYPES + ".patternedShieldGlint" + GETTER),
                calls(callingGetter(RENDER_TYPES, "entityGlint", GETTER)));
    }

    @Test
    @DisplayName("a mod built before RenderTypes existed reaches the 26.3 glints through the owner move")
    void oldRenderTypeGlintsReachTwentySixThree() {
        String oldRenderType = "net/minecraft/client/renderer/RenderType";
        String oldGetter = "()L" + oldRenderType + ";";
        transformer.registerClassRedirect(oldRenderType, "net/minecraft/client/renderer/rendertype/RenderType");
        Common_1_21_11_to_26_1_ClassMoves.registerRenderApiBridges26x(transformer);
        Mc26_2To26_3CoreMoves.register(transformer);

        assertEquals(List.of(RENDER_TYPES + ".patternedShieldGlint" + GETTER),
                calls(callingGetter(oldRenderType, "entityGlint", oldGetter)));
        assertEquals(List.of(RENDER_TYPES + ".trimmedArmorGlint" + GETTER),
                calls(callingGetter(oldRenderType, "armorEntityGlint", oldGetter)));
    }

    @Test
    @DisplayName("the real 26.1.2 jar: RenderTypes has the four glint getters and RenderType none")
    void glintGettersMovedToRenderTypes() throws Exception {
        Path v2612 = clientJar("26.1.2");
        Assumptions.assumeTrue(v2612 != null, "the 26.1.2 client jar is present");
        ClassNode renderTypes = renderTypes(v2612);
        ClassNode renderType;
        try (ZipFile zip = new ZipFile(v2612.toFile())) {
            renderType = new ClassNode();
            new ClassReader(zip.getInputStream(zip.getEntry("net/minecraft/client/renderer/rendertype/RenderType.class"))
                    .readAllBytes()).accept(renderType, ClassReader.SKIP_CODE);
        }
        for (String glint : new String[] {"glint", "glintTranslucent", "entityGlint", "armorEntityGlint"}) {
            assertTrue(declares(renderTypes, glint), "RenderTypes." + glint);
            assertFalse(declares(renderType, glint), "RenderType." + glint + " would still be the host's own");
        }
    }

    /** {@code static Object get() { return owner.name(); }} */
    private static byte[] callingGetter(String owner, String name, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, CALLER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "get", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, owner, name, desc, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private List<String> calls(byte[] caller) {
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(caller, CALLER + ".class")).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    @Test
    @DisplayName("the real jars: each successor is its old render type plus the forced solid model phase")
    void successorsMatchTheOldRenderTypes() throws Exception {
        Path v262 = clientJar("26.2");
        Path v263 = clientJar("26.3");
        Assumptions.assumeTrue(v262 != null && v263 != null, "the 26.2 and 26.3 client jars are present");

        ClassNode old = renderTypes(v262);
        ClassNode current = renderTypes(v263);
        assertTrue(declares(old, "entityGlint") && !declares(old, "patternedShieldGlint"), "26.2 shape");
        assertTrue(!declares(current, "entityGlint") && declares(current, "patternedShieldGlint"), "26.3 shape");

        assertEquals(recipe(old, "entity_glint"), withoutForcedSolidPhase(recipe(current, "patterned_shield_glint")));
        assertEquals(recipe(old, "armor_entity_glint"), withoutForcedSolidPhase(recipe(current, "trimmed_armor_glint")));
        assertTrue(recipe(current, "patterned_shield_glint").contains("withForcedSolidModelPhase"),
                "the one difference is the phase 26.3 draws every glint in");
    }

    /**
     * How {@code RenderTypes} builds the render type named {@code name}: the constants it reads
     * and the builder steps it takes, from the name to the field that stores the result.
     */
    private static List<String> recipe(ClassNode renderTypes, String name) {
        for (MethodNode method : renderTypes.methods) {
            if (!method.name.equals("<clinit>")) continue;
            List<String> recipe = null;
            for (AbstractInsnNode insn : method.instructions) {
                if (recipe == null) {
                    if (insn instanceof LdcInsnNode ldc && name.equals(ldc.cst)) recipe = new ArrayList<>();
                    continue;
                }
                if (insn instanceof FieldInsnNode field) {
                    if (field.getOpcode() == PUTSTATIC) return recipe;
                    recipe.add(field.owner + "." + field.name);
                } else if (insn instanceof LdcInsnNode ldc) {
                    recipe.add("\"" + ldc.cst + "\"");
                } else if (insn instanceof MethodInsnNode call && call.owner.equals(BUILDER)
                        && !call.name.equals("createRenderSetup")) {
                    recipe.add(call.name);
                }
            }
        }
        throw new AssertionError("no render type named " + name);
    }

    private static List<String> withoutForcedSolidPhase(List<String> recipe) {
        List<String> copy = new ArrayList<>(recipe);
        copy.remove("withForcedSolidModelPhase");
        return copy;
    }

    private static boolean declares(ClassNode renderTypes, String name) {
        return renderTypes.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(GETTER));
    }

    private static ClassNode renderTypes(Path jar) throws Exception {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(zip.getEntry(RENDER_TYPES + ".class")).readAllBytes()).accept(node, 0);
            return node;
        }
    }

    private static Path clientJar(String version) {
        Path jar = Path.of(System.getProperty("user.home", ""),
                "Library/Application Support/PrismLauncher/libraries/com/mojang/minecraft",
                version, "minecraft-" + version + "-client.jar");
        return Files.isRegularFile(jar) ? jar : null;
    }
}
