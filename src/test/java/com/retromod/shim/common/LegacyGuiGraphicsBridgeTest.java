/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

/** 1.21.1 GuiGraphics calls that 1.21.2 and 1.21.6 removed, on hosts before and after each change. */
class LegacyGuiGraphicsBridgeTest {

    private static final String GUI = "net/minecraft/client/gui/GuiGraphics";
    private static final String EXTRACTOR = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String HELPER = "com/retromod/polyfill/minecraft/LegacyGuiGraphics";
    private static final String RL = "Lnet/minecraft/resources/ResourceLocation;";
    private static final String ID = "Lnet/minecraft/resources/Identifier;";
    private static final String BUFFER_SOURCE = "net/minecraft/client/renderer/MultiBufferSource$BufferSource";
    private static final String OBJECTS = "Ljava/lang/Object;Ljava/lang/Object;";

    /** Each old blit by name and argument tail, and the helper method that draws it now. */
    private static final String[][] BLITS = {
            {"blit", "IIFFIIII", "blit"},
            {"blit", "IIIIII", "blitTile"},
            {"blit", "IIIFFIIII", "blitAtDepth"},
            {"blit", "IIIIFFIIII", "blitScaled"},
            {"blitSprite", "IIII", "blitSprite"}};

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousHost;
    }

    @ParameterizedTest
    @ValueSource(strings = {"neoforge", "forge"})
    void from1212EveryOldBlitDrawsThroughTheHelper(String loader) {
        host(loader, "1.21.4");

        for (String[] blit : BLITS) {
            MethodInsnNode call = onlyCall(probe(m -> blitCall(m, GUI, blit[0], RL, blit[1])));
            assertEquals(HELPER + "." + blit[2] + "(" + OBJECTS + blit[1] + ")V", describe(call),
                    blit[0] + blit[1] + " on " + loader + " 1.21.4");
            assertEquals(INVOKESTATIC, call.getOpcode(), "the receiver becomes the first argument");
        }
        assertTrue(transformer.getSyntheticClasses().containsKey(HELPER), "the helper ships with the mod");
    }

    @Test
    void theHelperEmbedsAsOneSelfContainedClass() {
        host("neoforge", "1.21.4");
        byte[] helper = transformer.getSyntheticClasses().get(HELPER);

        // The embedder copies only registered synthetics, so a nested or sibling class would dangle.
        Set<String> retromodTypes = new java.util.TreeSet<>();
        new ClassReader(helper).accept(new org.objectweb.asm.commons.ClassRemapper(new ClassNode(),
                new org.objectweb.asm.commons.Remapper() {
                    @Override
                    public String map(String internalName) {
                        if (internalName.startsWith("com/retromod/")) retromodTypes.add(internalName);
                        return internalName;
                    }
                }), 0);
        assertEquals(Set.of(HELPER), retromodTypes);
    }

    @Test
    void aHostBefore1212KeepsEveryOldCall() {
        host("neoforge", "1.21.1");

        assertEquals(GUI + ".blit(" + RL + "IIFFIIII)V",
                describe(onlyCall(probe(m -> blitCall(m, GUI, "blit", RL, "IIFFIIII")))));
        assertEquals(GUI + ".setColor(FFFF)V", describe(onlyCall(probe(this::setColorCall))));
        assertEquals(GUI + ".bufferSource()L" + BUFFER_SOURCE + ";",
                describe(onlyCall(probe(m -> bufferSourceCall(m, GUI)))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"neoforge", "forge"})
    void setColorTintsThroughTheHelperFrom1212(String loader) {
        host(loader, "1.21.2");

        assertEquals(HELPER + ".setColor(Ljava/lang/Object;FFFF)V", describe(onlyCall(probe(this::setColorCall))));
    }

    @Test
    void bufferSourceComesFromTheHelperOnlyUntil1216() {
        host("neoforge", "1.21.5");
        MethodNode run = probe(m -> bufferSourceCall(m, GUI));
        MethodInsnNode call = onlyCall(run);
        assertEquals(HELPER + ".bufferSource(Ljava/lang/Object;)Ljava/lang/Object;", describe(call));
        assertEquals(BUFFER_SOURCE, ((TypeInsnNode) call.getNext()).desc,
                "the caller still gets the buffer source type it stores");

        host("neoforge", "1.21.6");
        assertEquals(GUI + ".bufferSource()L" + BUFFER_SOURCE + ";",
                describe(onlyCall(probe(m -> bufferSourceCall(m, GUI)))),
                "1.21.6 queues GUI drawing, so no buffer source can stand in");
    }

    @ParameterizedTest
    @ValueSource(strings = {"neoforge", "forge"})
    void flushIsDroppedFrom1216(String loader) {
        host(loader, "1.21.5");
        assertEquals(GUI + ".flush()V", describe(onlyCall(probe(m -> flushCall(m, GUI)))),
                "1.21.5 still has flush");

        host(loader, "1.21.6");
        assertEquals(List.of(), calls(probe(m -> flushCall(m, GUI))), "1.21.6 has nothing to flush");
    }

    @Test
    void identifierSpellingOn12111ReachesTheSameHelper() {
        host("neoforge", "1.21.11");

        MethodInsnNode call = onlyCall(probe(m -> blitCall(m, GUI, "blit", RL, "IIFFIIII")));
        assertEquals(HELPER + ".blit(" + OBJECTS + "IIFFIIII)V", describe(call));
    }

    @ParameterizedTest
    @ValueSource(strings = {"neoforge", "forge"})
    void onTwentySixThreeTheOldCallsReachTheHelperUnderTheExtractorName(String loader) {
        host(loader, "26.3");

        for (String[] blit : BLITS) {
            MethodInsnNode call = onlyCall(probe(m -> blitCall(m, GUI, blit[0], RL, blit[1])));
            assertEquals(HELPER + "." + blit[2] + "(" + OBJECTS + blit[1] + ")V", describe(call),
                    blit[0] + blit[1] + " on " + loader + " 26.3");
        }
        assertEquals(HELPER + ".setColor(Ljava/lang/Object;FFFF)V", describe(onlyCall(probe(this::setColorCall))));
        assertEquals(List.of(), calls(probe(m -> flushCall(m, GUI))));
    }

    @Test
    void fabricOnTwentySixThreeBridgesTheRemappedCalls() {
        host("fabric", "26.3");

        // A Fabric mod reaches the shims with its intermediary names already mapped to 26.x ones.
        MethodInsnNode call = onlyCall(probe(m -> blitCall(m, EXTRACTOR, "blit", ID, "IIFFIIII")));
        assertEquals(HELPER + ".blit(" + OBJECTS + "IIFFIIII)V", describe(call));
        assertEquals(List.of(), calls(probe(m -> flushCall(m, EXTRACTOR))));
    }

    /** Registers every shim the loader's entry point would register on this host. */
    private void host(String loader, String version) {
        RetromodVersion.TARGET_MC_VERSION = version;
        transformer.clearRedirectsForTesting();
        Set<String> types = switch (loader) {
            case "neoforge" -> Set.of("neoforge", "forge", "common");
            case "forge" -> Set.of("forge", "common");
            default -> Set.of("fabric", "common");
        };
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, version)) {
                ShimRegistration.register(shim, transformer);
            }
        }
    }

    private static void blitCall(MethodVisitor m, String owner, String name, String id, String ints) {
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(ACONST_NULL);
        for (char type : ints.toCharArray()) m.visitInsn(type == 'F' ? FCONST_0 : ICONST_0);
        m.visitMethodInsn(INVOKEVIRTUAL, owner, name, "(" + id + ints + ")V", false);
    }

    private void setColorCall(MethodVisitor m) {
        m.visitVarInsn(ALOAD, 0);
        for (int i = 0; i < 4; i++) m.visitInsn(FCONST_1);
        m.visitMethodInsn(INVOKEVIRTUAL, GUI, "setColor", "(FFFF)V", false);
    }

    private static void bufferSourceCall(MethodVisitor m, String owner) {
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, owner, "bufferSource", "()L" + BUFFER_SOURCE + ";", false);
        m.visitVarInsn(ASTORE, 1);
    }

    private static void flushCall(MethodVisitor m, String owner) {
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, owner, "flush", "()V", false);
    }

    /** A mod class whose {@code run(GuiGraphics)} holds the body, after the transform. */
    private MethodNode probe(Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "probe/OldScreen", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(L" + GUI + ";)V", null, null);
        m.visitCode();
        body.accept(m);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "probe/OldScreen.class")).accept(node, 0);
        return node.methods.stream().filter(method -> method.name.equals("run")).findFirst().orElseThrow();
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        return calls;
    }

    private static MethodInsnNode onlyCall(MethodNode method) {
        List<MethodInsnNode> calls = calls(method);
        assertEquals(1, calls.size(), "expected one call, found "
                + calls.stream().map(LegacyGuiGraphicsBridgeTest::describe).toList());
        return calls.get(0);
    }

    private static String describe(MethodInsnNode call) {
        return call.owner + "." + call.name + call.desc;
    }
}
