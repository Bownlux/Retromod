/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyClientRenderBridge.TextDraw;
import com.retromod.shim.fabric.Fabric_1_19_3_to_1_19_4;
import com.retromod.testutil.HostJarLinks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generated see-through text draw calls the draw each host has, or none where 26.2 removed it. */
class LegacyClientRenderTextDrawTest {

    private static final String FONT = "net/minecraft/client/gui/Font";
    private static final String OLD_DRAW = "(Ljava/lang/String;FFIZLorg/joml/Matrix4f;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;ZII)I";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        transformer.clearRedirectsForTesting();
    }

    @Test
    void offlineTargetsPickTheDrawOfTheirVersion() {
        assertEquals(TextDraw.RETURNS_END_X, drawFor("1.20.1"));
        assertEquals(TextDraw.VOID, drawFor("1.21.6"));
        assertEquals(TextDraw.VOID_WIDE_MATRIX, drawFor("26.1"));
        assertEquals(TextDraw.NONE, drawFor("26.2"));
        assertEquals(TextDraw.NONE, drawFor("26.3"));
    }

    @Test
    void eachShapeCallsItsHostDrawAndOnlyTheVoidOnesMeasureTheText() {
        for (TextDraw draw : TextDraw.values()) {
            byte[] generated = LegacyClientRenderBridge.generate(draw);
            new ClassReader(generated).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
            List<String> calls = calls(generated, "(L" + FONT + ";" + OLD_DRAW.substring(1));
            String hostDraw = draw.descriptor("Ljava/lang/String;");
            assertEquals(hostDraw != null, calls.contains("drawInBatch" + hostDraw), draw + ": " + calls);
            assertEquals(draw != TextDraw.RETURNS_END_X, calls.contains("stringWidth(Ljava/lang/String;)F"),
                    draw + " works out the end x only when the host draw does not return it: " + calls);
        }
        assertNull(TextDraw.NONE.descriptor("Ljava/lang/String;"), "26.2 has no draw to call");
    }

    @Test
    void theStringDrawWithABidirectionalFlagAnswersThroughTheSameDraw() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        LegacyClientRenderBridge.register(transformer, name -> null);
        for (String matrix : List.of("Lorg/joml/Matrix4f;", "Lcom/mojang/math/Matrix4f;")) {
            String oldBidi = "(Ljava/lang/String;FFIZ" + matrix + "Lnet/minecraft/client/renderer/MultiBufferSource;ZIIZ)I";
            RetromodTransformer.MethodTarget target = transformer.getMethodRedirects()
                    .get(new RetromodTransformer.MethodKey(FONT, "drawInBatch", oldBidi));
            assertEquals(LegacyClientRenderBridge.SYNTH + ".drawInBatch" + LegacyClientRenderBridge.BIDI_DRAW,
                    target == null ? null : target.owner() + "." + target.name() + target.desc(), matrix);
        }

        byte[] generated = transformer.getSyntheticClasses().get(LegacyClientRenderBridge.SYNTH);
        new ClassReader(generated).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
        assertEquals(List.of("drawInBatch(L" + FONT + ";" + OLD_DRAW.substring(1)),
                calls(generated, LegacyClientRenderBridge.BIDI_DRAW),
                "FancyMenu 1.18.2's bidirectional string draw drops the flag and takes the plain draw");
    }

    @Test
    void realHostsGetTheirOwnDrawAndEveryCallLinks() {
        checkHost("net/neoforged/minecraft-client-patched/21.11.42/minecraft-client-patched-21.11.42.jar", "1.21.11",
                TextDraw.VOID);
        checkHost("com/mojang/minecraft/26.1.2/minecraft-26.1.2-client.jar", "26.1.2", TextDraw.VOID_WIDE_MATRIX);
        checkHost("com/mojang/minecraft/26.2/minecraft-26.2-client.jar", "26.2", TextDraw.NONE);
        checkHost("com/mojang/minecraft/26.3/minecraft-26.3-client.jar", "26.3", TextDraw.NONE);
        checkHost("net/minecraftforge/forge/26.2-65.0.0/forge-26.2-65.0.0-client.jar", "26.2", TextDraw.NONE);
    }

    @Test
    void fabricRegistersTheBridgeOnlyWhereModsCarryMojangNames() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        new Fabric_1_19_3_to_1_19_4().registerRedirects(transformer);
        RetromodTransformer.MethodTarget target = transformer.getMethodRedirects()
                .get(new RetromodTransformer.MethodKey(FONT, "drawInBatch", OLD_DRAW));
        assertEquals(LegacyClientRenderBridge.SYNTH, target == null ? null : target.owner(),
                "a 1.16.5 to 1.19.2 Fabric mod's see-through draw reaches the generated helper on 26.3");

        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        transformer.clearRedirectsForTesting();
        new Fabric_1_19_3_to_1_19_4().registerRedirects(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyClientRenderBridge.SYNTH),
                "a pre-26.1 Fabric host runs intermediary names, which these Mojang-named bridges never match");
    }

    private static TextDraw drawFor(String target) {
        RetromodVersion.TARGET_MC_VERSION = target;
        return LegacyClientRenderBridge.textDraw(null);
    }

    private void checkHost(String relative, String version, TextDraw expected) {
        Path jar = HostJarLinks.prism(relative);
        Assumptions.assumeTrue(jar != null, relative + " present");
        RetromodVersion.TARGET_MC_VERSION = version;
        ClassNode font = HostJarLinks.read(List.of(jar), FONT);
        TextDraw draw = LegacyClientRenderBridge.textDraw(font);
        assertEquals(expected, draw, relative);
        List<String> missing = HostJarLinks.unresolved(LegacyClientRenderBridge.generate(draw), List.of(jar),
                "net/minecraft/");
        assertTrue(missing.isEmpty(), relative + " lacks " + missing);
    }

    private static List<String> calls(byte[] generated, String methodDesc) {
        ClassNode node = new ClassNode();
        new ClassReader(generated).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!method.name.equals("drawInBatch") || !method.desc.equals(methodDesc)) continue;
            method.instructions.forEach(insn -> {
                if (insn instanceof MethodInsnNode call) calls.add(call.name + call.desc);
            });
        }
        return calls;
    }
}
