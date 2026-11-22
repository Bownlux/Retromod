/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Old sprite constructors and sprite calls reach the 26.x shapes with a stack the verifier accepts. */
class LegacySpriteBridgeTest {

    private static final String SPRITE = LegacySpriteBridge.SPRITE;
    private static final String CONTENTS = LegacySpriteBridge.CONTENTS;

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an access widener that opened the old sprite constructor opens the one with padding instead")
    void accessWidenerFollowsTheConstructor() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacySpriteBridge.registerSpriteConstructor(t);
        // Entity Texture Features' Fabric widener, as built for 1.21.1.
        String widener = "accessWidener\tv2\tintermediary\n"
                + "accessible\tmethod\tnet/minecraft/class_1058\t<init>\t(Lnet/minecraft/class_2960;Lnet/minecraft/class_7764;IIII)V\n";

        String out = com.retromod.core.AccessWidenerRemapper.remapToOfficial(widener,
                com.retromod.mapping.IntermediaryToMojangMapper.getInstance());

        assertTrue(out.contains("accessible\tmethod\t" + LegacySpriteBridge.SPRITE + "\t<init>\t" + LegacySpriteBridge.NEW_SPRITE_CTOR),
                "the entry must name the constructor the host has: " + out);
    }

    @Test
    @DisplayName("an access transformer that opened the old sprite constructor opens the one with padding instead")
    void accessTransformerFollowsTheConstructor() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        t.registerClassRedirect("net/minecraft/resources/ResourceLocation", "net/minecraft/resources/Identifier");
        LegacySpriteBridge.registerSpriteConstructor(t);
        // Entity Texture Features' and Fusion's NeoForge transformer line, as built for 1.21.1.
        String at = "public net.minecraft.client.renderer.texture.TextureAtlasSprite <init>(Lnet/minecraft/resources/ResourceLocation;"
                + "Lnet/minecraft/client/renderer/texture/SpriteContents;IIII)V # sprite\n"
                + "public net.minecraft.client.renderer.texture.SpriteContents$FrameInfo <init>(II)V\n";

        String out = com.retromod.core.ForgeModTransformer.prepareAccessTransformer(at, "26.3");

        assertTrue(out.contains("public net.minecraft.client.renderer.texture.TextureAtlasSprite <init>"
                + LegacySpriteBridge.NEW_SPRITE_CTOR + " # sprite"), "the entry must name the constructor the host has: " + out);
        assertTrue(out.contains("SpriteContents$FrameInfo <init>(II)V"), "a constructor no change applies to stays as written");
    }

    @Test
    @DisplayName("a sprite subclass's old super call gains the padding argument")
    void spriteSuperCallGainsPadding() throws AnalyzerException {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacySpriteBridge.registerSpriteConstructor(t);

        byte[] out = t.transformClass(spriteSubclass(), "test/sprite/OldSprite");

        assertTrue(calls(out).contains(SPRITE + ".<init>" + LegacySpriteBridge.NEW_SPRITE_CTOR), calls(out).toString());
        verify(out);
    }

    @Test
    @DisplayName("new SpriteContents(id, size, image, metadata) splits the metadata into the three new arguments")
    void contentsConstructorSplitsMetadata() throws AnalyzerException {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacySpriteBridge.registerContentsConstructor(t);

        byte[] out = t.transformClass(newContents(), "test/sprite/OldContents");
        List<String> calls = calls(out);

        String sprites = LegacySpriteBridge.SPRITES;
        assertEquals(List.of(sprites + ".animation(" + LegacySpriteBridge.METADATA + ")Ljava/util/Optional;",
                sprites + ".texture(" + LegacySpriteBridge.METADATA + ")Ljava/util/Optional;",
                sprites + ".additionalSections(" + LegacySpriteBridge.METADATA + ")Ljava/util/List;",
                CONTENTS + ".<init>" + LegacySpriteBridge.NEW_CONTENTS_CTOR), calls);
        verify(out);
    }

    @Test
    @DisplayName("getTexture, setSprite and spriteBake go to the rebuilt helpers")
    void spriteCallsAreRedirected() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacySpriteBridge.registerMissingTexture(t);
        LegacySpriteBridge.registerQuadBaking(t);
        LegacySpriteBridge.registerEmitters(t);

        List<String> calls = calls(t.transformClass(spriteCalls(), "test/sprite/Calls"));

        assertTrue(calls.contains(LegacySpriteBridge.SPRITES + ".missingTexture()" + LegacySpriteBridge.DYNAMIC_TEXTURE), calls.toString());
        assertTrue(calls.contains(LegacySpriteBridge.QUAD_BAKING_HELPER + ".setSprite(L" + LegacySpriteBridge.QUAD_BAKING + ";L"
                + SPRITE + ";)V"), calls.toString());
        String emitter = "L" + LegacySpriteBridge.QUAD_EMITTER + ";";
        assertTrue(calls.contains(LegacySpriteBridge.EMITTERS + ".spriteBake(" + emitter + "L" + SPRITE + ";I)" + emitter),
                calls.toString());
        assertTrue(calls.contains(LegacySpriteBridge.EMITTERS + ".spriteBake(" + emitter + "IL" + SPRITE + ";I)" + emitter),
                calls.toString());
    }

    @Test
    @DisplayName("each part registers only where the host has the new shape")
    void hostGates() {
        Map<String, ClassNode> modern = Map.of(
                SPRITE, withMethods("<init>", LegacySpriteBridge.NEW_SPRITE_CTOR),
                CONTENTS, withMethods("<init>", LegacySpriteBridge.NEW_CONTENTS_CTOR),
                LegacySpriteBridge.MISSING, withMethods("getLocation", "()Lnet/minecraft/resources/Identifier;"),
                LegacySpriteBridge.MATERIAL_BAKED, new ClassNode());
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacySpriteBridge.register(t, modern::get, true);
        List<String> calls = calls(t.transformClass(spriteCalls(), "test/sprite/Calls"));
        assertTrue(calls.stream().noneMatch(call -> call.startsWith(LegacySpriteBridge.MISSING + ".")), calls.toString());

        assertFalse(LegacySpriteBridge.replaced(withMethods("<init>", LegacySpriteBridge.OLD_SPRITE_CTOR),
                LegacySpriteBridge.OLD_SPRITE_CTOR, LegacySpriteBridge.NEW_SPRITE_CTOR, true), "a host with the old constructor keeps it");
        assertFalse(LegacySpriteBridge.quadBakingTakesMaterial(withMethods("setSprite", "(L" + SPRITE + ";)V")));
        assertFalse(LegacySpriteBridge.emitterTakesMaterial(withMethods("spriteBake", "()V")));
    }

    private static byte[] spriteSubclass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/sprite/OldSprite", null, SPRITE, null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + LegacySpriteBridge.ID + "L" + CONTENTS + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        for (int i = 0; i < 4; i++) mv.visitIntInsn(Opcodes.BIPUSH, 16);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, SPRITE, "<init>", LegacySpriteBridge.OLD_SPRITE_CTOR, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(8, 3);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] newContents() {
        return method("test/sprite/OldContents", mv -> {
            mv.visitTypeInsn(Opcodes.NEW, CONTENTS);
            mv.visitInsn(Opcodes.DUP);
            for (int i = 0; i < 4; i++) mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, CONTENTS, "<init>", LegacySpriteBridge.OLD_CONTENTS_CTOR, false);
            mv.visitInsn(Opcodes.POP);
        });
    }

    private static byte[] spriteCalls() {
        String sprite = "L" + SPRITE + ";";
        String emitter = LegacySpriteBridge.QUAD_EMITTER;
        return method("test/sprite/Calls", mv -> {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, LegacySpriteBridge.MISSING, "getTexture", "()" + LegacySpriteBridge.DYNAMIC_TEXTURE, false);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LegacySpriteBridge.QUAD_BAKING, "setSprite", "(" + sprite + ")V", false);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, emitter, "spriteBake", "(" + sprite + "I)L" + emitter + ";", true);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, emitter, "spriteBake", "(I" + sprite + "I)L" + emitter + ";", true);
            mv.visitInsn(Opcodes.POP);
        });
    }

    private static byte[] method(String name, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(10, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode withMethods(String name, String desc) {
        ClassNode node = new ClassNode();
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null));
        return node;
    }

    /** Runs ASM's basic verifier over every method, which fails on a wrong stack depth or argument kind. */
    private static void verify(byte[] bytes) throws AnalyzerException {
        ClassNode node = node(bytes);
        for (MethodNode method : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
    }

    private static List<String> calls(byte[] bytes) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node(bytes).methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
