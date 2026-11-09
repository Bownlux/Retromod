/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Old RenderLevelStageEvent listeners point at the event rebuilt inside the mod, on Forge hosts that removed it only. */
class LegacyForgeRenderBridgeTest {

    static final String OLD_EVENT = LegacyForgeRenderBridge.OLD_EVENT;
    static final String OLD_STAGE = LegacyForgeRenderBridge.OLD_STAGE;
    static final String EVENT = LegacyForgeRenderBridge.EVENT;
    static final String STAGE = EVENT + "$Stage";
    static final String OLD_SUBSCRIBE = "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    static final String LEGACY_SUBSCRIBE = "L" + RebuiltApiClasses.ROOT + "forge/eventbus/LegacySubscribe;";
    static final String POSE_STACK = "com/mojang/blaze3d/vertex/PoseStack";
    static final String MATRIX = "org/joml/Matrix4f";
    static final String LISTENER = "mod/StageListener";
    static final String LEVEL_STAGES = LegacyForgeRenderBridge.LEVEL_STAGES;
    static final String BOOTSTRAP = LegacyClientRenderBootstrapInjector.BOOTSTRAP;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The Forge 26.2 shim rebuilds the stage event and its install, which carries on to Forge 26.3")
    void forgeHostsFrom262TakeTheBridge(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        new Forge_26_1_to_26_2().registerRedirects(transformer);

        Map<String, String> redirects = transformer.getClassRedirects();
        assertEquals(EVENT, redirects.get(OLD_EVENT));
        assertEquals(STAGE, redirects.get(OLD_STAGE));
        assertEquals(EVENT + "$RegisterStageEvent", redirects.get(LegacyForgeRenderBridge.OLD_REGISTER_STAGE));
        Set<String> synthetics = transformer.getSyntheticClasses().keySet();
        for (String rebuilt : List.of(EVENT, STAGE, LEVEL_STAGES, BOOTSTRAP, LegacyForgeRenderBridge.ROOT + "LegacyLevelStagePass")) {
            assertTrue(synthetics.contains(rebuilt), rebuilt + " is registered for embedding");
        }
    }

    @Test
    @DisplayName("NeoForge, other loaders, Forge 1.21.11 and 26.1, and a Forge host that still has the event keep the old names")
    void otherHostsAreRefused() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        LegacyForgeRenderBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(OLD_EVENT), "NeoForge keeps its own RenderLevelStageEvent");

        McReflect.setForceNeoForge(false);
        LegacyForgeRenderBridge.register(transformer);
        assertNull(transformer.getClassRedirects().get(OLD_EVENT), "a Fabric or Quilt run takes no Forge bridge");

        McReflect.setForceForge(true);
        for (String older : List.of("1.21.11", "26.1")) {
            RetromodVersion.TARGET_MC_VERSION = older;
            LegacyForgeRenderBridge.register(transformer);
            assertNull(transformer.getClassRedirects().get(OLD_EVENT), "Forge " + older + " still fires the stage event");
        }

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        LegacyForgeRenderBridge.register(transformer, host(ForgeHostShape.ANCHOR, LegacyForgeRenderBridge.ADD_FRAME_PASS, OLD_EVENT));
        assertNull(transformer.getClassRedirects().get(OLD_EVENT), "a host that still has RenderLevelStageEvent keeps it");
        LegacyForgeRenderBridge.register(transformer, host(ForgeHostShape.ANCHOR));
        assertNull(transformer.getClassRedirects().get(OLD_EVENT), "a host with neither event has nothing to draw through");
        LegacyForgeRenderBridge.register(transformer, host(ForgeHostShape.ANCHOR, LegacyForgeRenderBridge.ADD_FRAME_PASS));
        assertEquals(EVENT, transformer.getClassRedirects().get(OLD_EVENT), "a host with frame passes and no stage event");
    }

    @Test
    @DisplayName("A Forge 1.20.1 listener takes the rebuilt event, its Stage constants and Retromod's legacy dispatch")
    void oldListenerTakesTheRebuiltEvent() {
        register("1.20.1");
        ClassNode out = node(transformer.transformClass(listenerClass(), LISTENER + ".class"));

        MethodNode listener = method(out, "onStage");
        assertEquals("(L" + EVENT + ";)V", listener.desc);
        assertEquals(List.of(LEGACY_SUBSCRIBE), annotations(listener),
                "the listener keeps Forge 1.20.1's priority and order rules in Retromod's dispatch");
        assertEquals(List.of(EVENT + ".getStage()L" + STAGE + ";", EVENT + ".getPoseStack()L" + POSE_STACK + ";",
                EVENT + ".getProjectionMatrix()L" + MATRIX + ";"), calls(listener));
        assertEquals(List.of(STAGE + ".AFTER_PARTICLES:L" + STAGE + ";"), fields(listener));
    }

    @Test
    @DisplayName("Forge 1.21's getPoseStack() returning a Matrix4f reads the rebuilt event's model-view matrix")
    void forge121PoseStackIsTheModelViewMatrix() {
        register("1.21.1");
        ClassNode out = node(transformer.transformClass(modelViewCaller(), "mod/ModelViewCaller.class"));
        assertEquals(List.of(EVENT + ".getStage()L" + STAGE + ";", EVENT + ".getModelViewMatrix()L" + MATRIX + ";"),
                calls(method(out, "onStage")));
    }

    @Test
    @DisplayName("A mod built for Forge 26.2 or newer keeps the class and member names the redirects are limited to")
    void modsBuiltFor262KeepTheNames() {
        register("1.21.1");
        assertEquals("(L" + EVENT + ";)V", method(node(transformer.transformClass(modelViewCaller(),
                "mod/ModelViewCaller.class")), "onStage").desc, "an older mod in the same run takes the rebuilt event");
        transformer.setModSourceVersion("26.2");
        ClassNode out = node(transformer.transformClass(modelViewCaller(), "mod/ModelViewCaller.class"));
        assertEquals(List.of(OLD_EVENT + ".getStage()L" + OLD_STAGE + ";", OLD_EVENT + ".getPoseStack()L" + MATRIX + ";"),
                calls(method(out, "onStage")));
        assertEquals("(L" + OLD_EVENT + ";)V", method(out, "onStage").desc);
    }

    @Test
    @DisplayName("Each @Mod constructor of a jar that names the stage event installs the rebuilt client rendering first")
    void constructorsInstallTheLevelStages() {
        register("1.20.1");
        byte[] mod = ModConstructorHooksTest.modClass("mod/StageMod", "stagemod", LegacyAutoSubscribeBridgeTest.OBJECT,
                (writer, owner) -> { });
        ModConstructorHooks.JarHooks hooks = ModConstructorHooks.forJar(transformer, List.of(mod, listenerClass()));
        assertNotNull(hooks);
        MethodNode constructor = ModConstructorHooksTest.constructor(node(hooks.apply(mod)), "()V");
        List<String> calls = new ArrayList<>();
        boolean afterSuper = false;
        for (AbstractInsnNode insn : constructor.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.name.equals("<init>")) afterSuper = true;
            else if (afterSuper && call.getOpcode() == INVOKESTATIC) calls.add(call.owner + "." + call.name + call.desc);
        }
        assertEquals(Set.of(BOOTSTRAP + ".install()V", LEVEL_STAGES + ".install()V"), Set.copyOf(calls));
        assertEquals(2, calls.size(), "each install is called once, right after the super call: " + calls);

        // A jar that never names the event gets no install, so a server mod keeps its constructor as it was.
        assertNull(LegacyClientRenderBootstrapInjector.plan(List.of(new LegacyClientRenderBootstrapInjector.Part("level-stages",
                LegacyForgeRenderBridge.REBUILT.keySet(), LEVEL_STAGES)), Map.of("mod/StageMod", mod)));
    }

    @Test
    @DisplayName("The rebuilt events keep Forge 1.20.1's shape: a legacy game event, a mod-bus register event, and the eleven stages in order")
    void rebuiltEventShape() {
        register("1.20.1");
        Map<String, byte[]> synthetics = transformer.getSyntheticClasses();
        ClassNode event = node(synthetics.get(EVENT));
        assertEquals(RebuiltApiClasses.ROOT + "forge/eventbus/LegacyEvent", event.superName);
        assertTrue(event.interfaces.contains("net/minecraftforge/eventbus/api/event/InheritableEvent"));
        assertTrue(event.fields.stream().noneMatch(field -> field.name.equals("BUS")),
                "a game-bus legacy event finds its bus through Retromod's dispatch, not a static BUS");

        ClassNode register = node(synthetics.get(EVENT + "$RegisterStageEvent"));
        assertTrue(register.interfaces.contains("net/minecraftforge/fml/event/IModBusEvent"),
                "RegisterStageEvent stays a mod-bus event, which a mod's bus group accepts");

        List<String> stages = new ArrayList<>();
        for (FieldNode field : node(synthetics.get(STAGE)).fields) {
            if ((field.access & ACC_STATIC) != 0 && field.desc.equals("L" + STAGE + ";")) stages.add(field.name);
        }
        assertEquals(List.of("AFTER_SKY", "AFTER_SOLID_BLOCKS", "AFTER_CUTOUT_MIPPED_BLOCKS_BLOCKS", "AFTER_CUTOUT_BLOCKS",
                "AFTER_ENTITIES", "AFTER_BLOCK_ENTITIES", "AFTER_TRANSLUCENT_BLOCKS", "AFTER_TRIPWIRE_BLOCKS", "AFTER_PARTICLES",
                "AFTER_WEATHER", "AFTER_LEVEL"), stages);
    }

    @Test
    @DisplayName("The Stage class names no render type while it loads, since an old static initializer can name a stage early")
    void stagesLoadWithoutRenderTypes() {
        register("1.20.1");
        MethodNode clinit = method(node(transformer.getSyntheticClasses().get(STAGE)), "<clinit>");
        for (AbstractInsnNode insn : clinit.instructions) {
            if (insn instanceof MethodInsnNode call) {
                assertFalse(call.owner.startsWith("net/minecraft/"), "the stages' static initializer calls " + call.owner);
            }
        }
    }

    // ---- helpers ----

    private void register(String modVersion) {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        new Forge_26_1_to_26_2().registerRedirects(transformer);
        transformer.setModSourceVersion(modVersion);
    }

    /** A Forge host that has exactly these classes. */
    static Function<String, ClassNode> host(String... classes) {
        Set<String> present = Set.of(classes);
        return name -> present.contains(name) ? new ClassNode() : null;
    }

    static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
    }

    private static List<String> annotations(MethodNode method) {
        List<String> found = new ArrayList<>();
        if (method.visibleAnnotations != null) {
            for (AnnotationNode annotation : method.visibleAnnotations) found.add(annotation.desc);
        }
        return found;
    }

    private static List<String> calls(MethodNode method) {
        List<String> found = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) found.add(call.owner + "." + call.name + call.desc);
        }
        return found;
    }

    private static List<String> fields(MethodNode method) {
        List<String> found = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field) found.add(field.owner + "." + field.name + ":" + field.desc);
        }
        return found;
    }

    /**
     * Forge 1.20.1's usual listener: {@code @SubscribeEvent static void onStage(RenderLevelStageEvent event)}, which reads
     * the PoseStack and projection at AFTER_PARTICLES.
     */
    static byte[] listenerClass() {
        return listenerClass(LISTENER);
    }

    static byte[] listenerClass(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onStage", "(L" + OLD_EVENT + ";)V", null, null);
        AnnotationVisitor subscribe = mv.visitAnnotation(OLD_SUBSCRIBE, true);
        subscribe.visitEnd();
        mv.visitCode();
        Label done = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_EVENT, "getStage", "()L" + OLD_STAGE + ";", false);
        mv.visitFieldInsn(GETSTATIC, OLD_STAGE, "AFTER_PARTICLES", "L" + OLD_STAGE + ";");
        mv.visitJumpInsn(IF_ACMPNE, done);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_EVENT, "getPoseStack", "()L" + POSE_STACK + ";", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_EVENT, "getProjectionMatrix", "()L" + MATRIX + ";", false);
        mv.visitInsn(POP);
        mv.visitLabel(done);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Forge 1.21.1's listener shape, as Carry On and Shoulder Surfing call it: {@code event.getPoseStack()} as a Matrix4f. */
    static byte[] modelViewCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/ModelViewCaller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onStage", "(L" + OLD_EVENT + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_EVENT, "getStage", "()L" + OLD_STAGE + ";", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, OLD_EVENT, "getPoseStack", Type.getMethodDescriptor(Type.getObjectType(MATRIX)), false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
