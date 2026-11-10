/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.retromod.polyfill.minecraft.rendering.RenderingPolyfill;
import com.retromod.util.McReflect;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Old block-layer getters, GUI draws and sound ids reach members a 26.x host has, as the game registers them. */
class LegacyRenderCallHostTest {

    private static final String RENDER_TYPES = "net/minecraft/client/renderer/rendertype/RenderTypes";
    private static final String MOVED_TYPE = "Lnet/minecraft/client/renderer/rendertype/RenderType;";
    private static final String POSE = "Lcom/mojang/blaze3d/vertex/PoseStack;";
    private static final String GUI_COMPONENT = "net/minecraft/client/gui/GuiComponent";
    private static final String POSE_GUI = "com/retromod/shim/common/embedded/LegacyPoseGui";
    private static final String FIXTURE = "fixture/OldCalls";

    // The tokens 26.1.2, 26.2 and 26.3 have (javap): only the moving-block types survive.
    private static final Map<String, String> GETTER_TOKENS = Map.of(
            "solid", "solidMovingBlock", "cutout", "cutoutMovingBlock",
            "cutoutMipped", "cutoutMovingBlock", "translucent", "translucentMovingBlock");
    // Fabric mods name the getters by intermediary id.
    private static final Map<String, String> FABRIC_GETTERS = Map.of(
            "solid", "method_23577", "cutout", "method_23581",
            "cutoutMipped", "method_23579", "translucent", "method_23583");

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedHost;

    @BeforeEach
    void saveHost() {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedHost;
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(false);
        transformer.clearRedirectsForTesting();
    }

    static Stream<Arguments> modernHosts() {
        List<Arguments> cases = new ArrayList<>();
        for (String loader : List.of("fabric", "forge", "neoforge")) {
            for (String host : List.of("26.1", "26.2", "26.3")) cases.add(Arguments.of(loader, host));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("modernHosts")
    void blockLayerGettersReachAHostToken(String loader, String host) {
        registerAsTheGame(loader, host);
        for (var getter : GETTER_TOKENS.entrySet()) {
            MethodInsnNode call = onlyCall(transform(getterCall(loader, getter.getKey())));
            assertEquals(RENDER_TYPES + "." + getter.getValue() + "()" + MOVED_TYPE,
                    call.owner + "." + call.name + call.desc,
                    "RenderType." + getter.getKey() + "() must return the host's render type, not null");
        }
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("modernHosts")
    void poseStackDrawsReachTheExtractorBridge(String loader, String host) {
        registerAsTheGame(loader, host);
        MethodInsnNode blit = onlyCall(transform(guiCall("blit", 6)));
        MethodInsnNode fill = onlyCall(transform(guiCall("fill", 5)));

        assertEquals(POSE_GUI + ".blitInstance", blit.owner + "." + blit.name);
        assertEquals(POSE_GUI + ".fill", fill.owner + "." + fill.name);
    }

    @ParameterizedTest(name = "{0} on {1}")
    @MethodSource("modernHosts")
    void soundIdReadsTheRecordComponent(String loader, String host) {
        registerAsTheGame(loader, host);
        // A Fabric mod names the getter by intermediary id; Forge and NeoForge ones by its 1.20 name.
        byte[] call = "fabric".equals(loader)
                ? virtualCall("net/minecraft/class_3414", "method_14833", "()Lnet/minecraft/class_2960;")
                : virtualCall("net/minecraft/sounds/SoundEvent", "getLocation",
                        "()Lnet/minecraft/resources/ResourceLocation;");
        MethodInsnNode read = onlyCall(transform(call));

        assertEquals("net/minecraft/sounds/SoundEvent.location()Lnet/minecraft/resources/Identifier;",
                read.owner + "." + read.name + read.desc, "26.x SoundEvent has location(), not getLocation()");
    }

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({
            "forge, 1.21.5, location", "neoforge, 1.21.5, location",
            "forge, 1.21.1, getLocation", "neoforge, 1.21.1, getLocation"})
    void soundIdFollowsTheHostBefore26(String loader, String host, String expected) {
        registerAsTheGame(loader, host);
        MethodInsnNode read = onlyCall(transform(virtualCall("net/minecraft/sounds/SoundEvent",
                "getLocation", "()Lnet/minecraft/resources/ResourceLocation;")));

        // SoundEvent became a record in 1.21.2; a 1.21.1 host still has getLocation().
        assertEquals("net/minecraft/sounds/SoundEvent." + expected
                        + "()Lnet/minecraft/resources/ResourceLocation;",
                read.owner + "." + read.name + read.desc);
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    void fabricScreenEventKeepsThe26xRename(String host) {
        registerAsTheGame("fabric", host);
        MethodInsnNode call = onlyCall(transform(fixture(mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents",
                    "beforeRender", "(Lnet/minecraft/class_437;)Lnet/fabricmc/fabric/api/event/Event;", false);
        })));

        // FabricApiShim's Yarn-keyed redirect also lands on this call shape; its shim method takes Object.
        assertEquals("net/fabricmc/fabric/api/client/screen/v1/ScreenEvents.beforeExtract"
                        + "(Lnet/minecraft/client/gui/screens/Screen;)Lnet/fabricmc/fabric/api/event/Event;",
                call.owner + "." + call.name + call.desc);
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3"})
    void fabricPlacementOverrideKeepsGetPositions(String host) {
        registerAsTheGame("fabric", host);
        // method_14452 names both PlacementModifier.getPositions and a count(random, pos) method.
        String getPositions = "(Lnet/minecraft/class_5444;Lnet/minecraft/class_5819;Lnet/minecraft/class_2338;)"
                + "Ljava/util/stream/Stream;";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, FIXTURE, null, "net/minecraft/class_6797", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "method_14452", getPositions, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(transform(writer.toByteArray())).accept(node, 0);
        List<String> names = new ArrayList<>();
        for (MethodNode method : node.methods) names.add(method.name);
        assertEquals(List.of("getPositions"), names,
                "an override renamed to count no longer overrides getPositions (AbstractMethodError)");
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"1.21.11", "26.1", "26.2", "26.3"})
    void theRenderingPolyfillLeavesModernDrawsToTheirBridges(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        new RenderingPolyfill().registerPolyfills(transformer);
        var redirects = transformer.getMethodRedirects();
        boolean before26 = RetromodVersion.mcVersionExceeds("26.1", host);

        // A 1.21.11 host keeps the 1.20 bridge; 26.1 and newer leave these calls to LegacyPoseGui.
        assertEquals(before26, redirects.containsKey(new RetromodTransformer.MethodKey(
                GUI_COMPONENT, "blit", "(" + POSE + "IIIIII)V")));
        assertEquals(before26, redirects.containsKey(new RetromodTransformer.MethodKey(
                GUI_COMPONENT, "fill", "(" + POSE + "IIIII)V")));
        // The getters never go to the shim, whose RenderTypes.solid() no host has.
        for (String getter : GETTER_TOKENS.keySet()) {
            assertFalse(redirects.containsKey(new RetromodTransformer.MethodKey(
                    "net/minecraft/client/renderer/RenderType", getter,
                    "()Lnet/minecraft/client/renderer/RenderType;")), getter);
        }
    }

    // ---- helpers ----

    /** What the loader's entry point registers before it transforms a mod. */
    private void registerAsTheGame(String loader, String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        McReflect.setForceForge("forge".equals(loader));
        McReflect.setForceNeoForge("neoforge".equals(loader));
        transformer.clearRedirectsForTesting();
        switch (loader) {
            case "fabric" -> RetromodPreLaunch.registerShimsForTransform(host);
            case "forge" -> RetromodForge.registerTransformRedirects(transformer);
            default -> RetromodNeoForge.registerTransformRedirects(transformer);
        }
    }

    private static byte[] getterCall(String loader, String getter) {
        boolean fabric = "fabric".equals(loader);
        String owner = fabric ? "net/minecraft/class_1921" : "net/minecraft/client/renderer/RenderType";
        String name = fabric ? FABRIC_GETTERS.get(getter) : getter;
        return fixture(mv -> mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, "()L" + owner + ";", false));
    }

    /** {@code component.name(pose, 0, ...)} with {@code ints} int arguments, as a pre-1.20 screen calls it. */
    private static byte[] guiCall(String name, int ints) {
        String desc = "(" + POSE + "I".repeat(ints) + ")V";
        return fixture(mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitInsn(Opcodes.ACONST_NULL);
            for (int i = 0; i < ints; i++) mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GUI_COMPONENT, name, desc, false);
        });
    }

    private static byte[] virtualCall(String owner, String name, String desc) {
        return fixture(mv -> {
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, desc, false);
        });
    }

    /** A class whose one method makes the given call and drops its result. */
    private static byte[] fixture(java.util.function.Consumer<MethodVisitor> call) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, FIXTURE, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        call.accept(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] transform(byte[] classBytes) {
        return transformer.transformClass(classBytes, FIXTURE);
    }

    private static MethodInsnNode onlyCall(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        List<MethodInsnNode> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call);
            }
        }
        assertTrue(calls.size() == 1, "expected one call, found " + calls.size());
        return calls.get(0);
    }
}
