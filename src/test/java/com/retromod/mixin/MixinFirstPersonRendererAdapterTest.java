/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hold My Items' first-person renderer mixin on 26.2 and the 26.3 split (#281/#312). */
class MixinFirstPersonRendererAdapterTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String MINECRAFT = "net/minecraft/client/Minecraft";
    private static final String BLOCK_INTERFACE = "test/access/AlternateBlockRenderer";
    private static final String OLD = MixinFirstPersonRendererAdapter.OLD_RENDERER;
    private static final String SPLIT = MixinFirstPersonRendererAdapter.RENDERER;
    private static final String STACK_STATE = "net/minecraft/client/renderer/item/ItemStackRenderState";
    private static final String RESOLVER = "net/minecraft/client/renderer/item/ItemModelResolver";
    private static final String HANDS_STATE =
            "net/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState";

    @TempDir
    Path tempDir;

    @Test
    void routesAHeldBlockThroughTheItemModelWhenTheBlockRendererIsGone() throws IOException {
        FuzzyMethodResolver host = host(false);
        byte[] adapted = MixinFirstPersonRendererAdapter.adapt(heldBlockMixin(OLD), host);
        ClassNode node = read(adapted);
        MethodNode render = method(node, "render");

        assertFalse(calls(render, MINECRAFT, "getBlockRenderer"),
                "Minecraft.getBlockRenderer() no longer exists on 26.1 and newer");
        assertTrue(calls(render, node.name, "retromod$renderHeldBlock"),
                "the held block must reach the mixin-owned fallback");
        MethodNode helper = method(node, "retromod$renderHeldBlock");
        assertTrue(calls(helper, RESOLVER, "updateForTopItem") && calls(helper, STACK_STATE, "submit"),
                "the fallback submits the block's item model");
    }

    @Test
    void raisesTheHeldBlockLightByItsEmissionWhenTheHostCanComputeIt() throws IOException {
        ClassNode withEmission = read(MixinFirstPersonRendererAdapter.adapt(heldBlockMixin(OLD), host(false, true)));
        MethodNode lit = method(withEmission, "retromod$renderHeldBlock");
        assertTrue(calls(lit, "net/minecraft/world/level/block/state/BlockState", "getLightEmission")
                        && calls(lit, "net/minecraft/util/LightCoordsUtil", "lightCoordsWithEmission"),
                "a held glowstone must keep its own light, as the removed emissive block call did");

        ClassNode without = read(MixinFirstPersonRendererAdapter.adapt(heldBlockMixin(OLD), host(false)));
        assertFalse(calls(method(without, "retromod$renderHeldBlock"),
                        "net/minecraft/util/LightCoordsUtil", "lightCoordsWithEmission"),
                "a host without the light helper must not get a call it cannot link");
    }

    @Test
    void movesTheArmRedirectOntoTheSplitRendererWithItsRenderStates() throws IOException {
        FuzzyMethodResolver host = host(true);
        byte[] adapted = MixinFirstPersonRendererAdapter.adapt(armRedirectMixin(), host);
        ClassNode node = read(adapted);

        assertEquals(Type.getObjectType(SPLIT), ((List<?>) value(node.invisibleAnnotations.get(0), "value")).get(0),
                "the mixin must target FirstPersonHandsAndItemsRenderer");
        MethodNode handler = method(node, "renderOverhaul");
        assertEquals("(L" + SPLIT + ";" + MixinFirstPersonRendererAdapter.NEW_ARM.substring(1), handler.desc,
                "the redirect handler receives the two render states the new arm call takes");
        AnnotationNode redirect = handler.visibleAnnotations.get(0);
        assertEquals(List.of("submitHandsWithItems" + MixinFirstPersonRendererAdapter.NEW_HANDS),
                value(redirect, "method"));
        assertTrue(calls(handler, node.name, "renderOverhaul$retromodLegacy"),
                "the original body still runs, with the local player in place of the removed one");
        MethodNode playerArm = node.methods.stream()
                .filter(m -> m.name.equals("renderPlayerArm") && !hasShadow(m)).findFirst().orElse(null);
        assertNotNull(playerArm, "the old renderPlayerArm shadow becomes a bridge");
        assertTrue(calls(playerArm, node.name, "renderPlayerArm"),
                "the bridge forwards to the host's renderPlayerArm with the kept player state");
    }

    @Test
    void leavesTheMixinOnItsOldTargetWhenTheHostStillHasIt() throws IOException {
        FuzzyMethodResolver host = host(false);
        ClassNode node = read(MixinFirstPersonRendererAdapter.adapt(armRedirectMixin(), host));

        assertEquals(Type.getObjectType(OLD), ((List<?>) value(node.invisibleAnnotations.get(0), "value")).get(0),
                "26.2 still has ItemInHandRenderer, so no retarget may happen");
    }

    @Test
    void removedTargetCheckLeavesItemInHandRendererMixinsForTheSplitRetarget() {
        var compatibility = new MixinCompatibilityTransformer(com.retromod.core.RetromodTransformer.getInstance());
        ClassNode mixin = read(armRedirectMixin());

        String onSplitHost = compatibility.mixinTargetsRemovedClass(mixin,
                name -> !name.equals(OLD.replace('/', '.')));
        String withoutSuccessor = compatibility.mixinTargetsRemovedClass(mixin,
                name -> !name.equals(OLD.replace('/', '.')) && !name.equals(SPLIT.replace('/', '.')));

        assertEquals(null, onSplitHost,
                "on 26.3 the mixin must survive until the post-remap step moves it to the split renderer");
        assertEquals(OLD, withoutSuccessor, "without the successor the old rule still disables it");
    }

    @Test
    void removedTargetCheckFollowsAClassMoveAfterTheIntermediaryName() {
        com.retromod.core.RetromodTransformer transformer = com.retromod.core.RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        String intermediary = "net/minecraft/class_759$class_5773";
        String mojang = OLD + "$HandRenderSelection";
        String moved = HANDS_STATE + "$HandRenderSelection";
        try {
            transformer.registerClassRedirect(intermediary, mojang);
            transformer.registerClassRedirect(mojang, moved);
            var compatibility = new MixinCompatibilityTransformer(transformer);
            ClassNode mixin = read(mixin(intermediary, c -> {}));

            String flagged = compatibility.mixinTargetsRemovedClass(mixin,
                    name -> !name.equals(intermediary.replace('/', '.')) && !name.equals(mojang.replace('/', '.')));

            assertEquals(null, flagged, "the enum moved into the 26.3 render state and still exists");
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    @Test
    void removedTargetCheckKeepsAMixinWhoseIntermediateNameIsOnTheHost() {
        com.retromod.core.RetromodTransformer transformer = com.retromod.core.RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        String intermediary = "net/minecraft/class_1234";
        String present = "net/minecraft/client/Present";
        String later = "net/minecraft/client/LaterMove";
        try {
            transformer.registerClassRedirect(intermediary, present);
            transformer.registerClassRedirect(present, later);
            var compatibility = new MixinCompatibilityTransformer(transformer);
            ClassNode mixin = read(mixin(intermediary, c -> {}));

            String flagged = compatibility.mixinTargetsRemovedClass(mixin,
                    name -> name.equals(present.replace('/', '.'))
                            || name.equals("net.minecraft.world.level.block.Blocks"));

            assertEquals(null, flagged,
                    "following the chain further must never strip a mixin the first hop kept");
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    @Test
    void disablesADeferredMixinWhenNoHostIndexCanMoveIt() {
        byte[] adapted = MixinFirstPersonRendererAdapter.adapt(armRedirectMixin(), null,
                name -> !name.equals(OLD.replace('/', '.')));

        assertEquals(List.of("retromod/stripped/RendererMixin"),
                value(read(adapted).invisibleAnnotations.get(0), "targets"),
                "the removed-target check deferred it, so without an index it must still be disabled");
    }

    @Test
    void disablesAnItemInHandRendererMixinItCannotMove() throws IOException {
        FuzzyMethodResolver host = host(true);
        byte[] unrecognized = mixin(OLD, c -> {
            MethodVisitor shadow = c.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "tick", "()V", null, null);
            shadow.visitAnnotation(SHADOW, true).visitEnd();
            shadow.visitEnd();
        });

        ClassNode node = read(MixinFirstPersonRendererAdapter.adapt(unrecognized, host));

        assertEquals(List.of("retromod/stripped/RendererMixin"), value(node.invisibleAnnotations.get(0), "targets"),
                "a mixin left on the removed class would fail Mixin's target lookup");
    }

    // ---- fixtures ------------------------------------------------------------------------

    private FuzzyMethodResolver host(boolean split) throws IOException {
        return host(split, false);
    }

    private FuzzyMethodResolver host(boolean split, boolean lightHelper) throws IOException {
        Path jar = tempDir.resolve((split ? "split" : "old") + (lightHelper ? "-light" : "") + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            if (lightHelper) {
                put(out, hostClass("net/minecraft/util/LightCoordsUtil",
                        c -> abstractMethod(c, "lightCoordsWithEmission", "(II)I")));
            }
            put(out, hostClass(MINECRAFT, c -> abstractMethod(c, "getItemModelResolver", "()L" + RESOLVER + ";")));
            put(out, hostClass(RESOLVER, c -> abstractMethod(c, "updateForTopItem",
                    "(L" + STACK_STATE + ";Lnet/minecraft/world/item/ItemStack;"
                            + "Lnet/minecraft/world/item/ItemDisplayContext;Lnet/minecraft/world/level/Level;"
                            + "Lnet/minecraft/world/entity/ItemOwner;I)V")));
            put(out, hostClass(STACK_STATE, c -> abstractMethod(c, "submit",
                    "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V")));
            if (split) {
                put(out, hostClass(SPLIT, c -> {
                    abstractMethod(c, "submitHandsWithItems", MixinFirstPersonRendererAdapter.NEW_HANDS);
                    abstractMethod(c, "submitArmWithItem", MixinFirstPersonRendererAdapter.NEW_ARM);
                    abstractMethod(c, "renderPlayerArm", "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;IFF"
                            + "Lnet/minecraft/world/entity/HumanoidArm;"
                            + "Lnet/minecraft/client/renderer/state/level/PlayerRenderState;)V");
                    abstractMethod(c, "renderMap", "(Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
                            + "Lnet/minecraft/world/item/ItemStack;ZL" + HANDS_STATE + ";)V");
                }));
                put(out, hostClass(HANDS_STATE, c -> c.visitField(Opcodes.ACC_PUBLIC, "mainHandItem",
                        "Lnet/minecraft/world/item/ItemStack;", null, null).visitEnd()));
            } else {
                put(out, hostClass(OLD, c -> abstractMethod(c, "submitArmWithItem",
                        MixinFirstPersonRendererAdapter.OLD_ARM)));
            }
        }
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(jar);
        return resolver;
    }

    private static byte[] heldBlockMixin(String target) {
        return mixin(target, c -> {
            c.visitField(Opcodes.ACC_PRIVATE, "minecraft", "L" + MINECRAFT + ";", null, null).visitEnd();
            MethodVisitor render = c.visitMethod(Opcodes.ACC_PRIVATE, "render",
                    "(Lnet/minecraft/world/level/block/state/BlockState;Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;I"
                            + "Lnet/minecraft/client/multiplayer/ClientLevel;"
                            + "Lnet/minecraft/client/player/AbstractClientPlayer;)V", null, null);
            render.visitCode();
            render.visitVarInsn(Opcodes.ALOAD, 0);
            render.visitFieldInsn(Opcodes.GETFIELD, "test/mixin/RendererMixin", "minecraft", "L" + MINECRAFT + ";");
            render.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MINECRAFT, "getBlockRenderer",
                    "()Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;", false);
            render.visitTypeInsn(Opcodes.CHECKCAST, BLOCK_INTERFACE);
            for (int slot = 1; slot <= 6; slot++) {
                render.visitVarInsn(slot == 4 ? Opcodes.ILOAD : Opcodes.ALOAD, slot);
            }
            render.visitMethodInsn(Opcodes.INVOKEINTERFACE, BLOCK_INTERFACE, "renderSingleBlockWithEmission",
                    MixinFirstPersonRendererAdapter.HELD_BLOCK_DESC, true);
            render.visitInsn(Opcodes.RETURN);
            render.visitMaxs(7, 7);
            render.visitEnd();
        });
    }

    private static byte[] armRedirectMixin() {
        return mixin(OLD, c -> {
            MethodVisitor shadow = c.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "renderPlayerArm",
                    "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IFF"
                            + "Lnet/minecraft/world/entity/HumanoidArm;)V", null, null);
            shadow.visitAnnotation(SHADOW, true).visitEnd();
            shadow.visitEnd();

            MethodVisitor handler = c.visitMethod(Opcodes.ACC_PRIVATE, "renderOverhaul",
                    "(L" + OLD + ";" + MixinFirstPersonRendererAdapter.OLD_ARM.substring(1), null, null);
            AnnotationVisitor redirect = handler.visitAnnotation(REDIRECT, true);
            AnnotationVisitor methods = redirect.visitArray("method");
            methods.visit(null, "submitHandsWithItems" + MixinFirstPersonRendererAdapter.OLD_HANDS);
            methods.visitEnd();
            AnnotationVisitor at = redirect.visitAnnotation("at", AT);
            at.visit("value", "INVOKE");
            at.visit("target", "L" + OLD + ";submitArmWithItem" + MixinFirstPersonRendererAdapter.OLD_ARM);
            at.visitEnd();
            redirect.visitEnd();
            handler.visitCode();
            handler.visitInsn(Opcodes.RETURN);
            handler.visitMaxs(0, 13);
            handler.visitEnd();
        });
    }

    private static byte[] mixin(String target, Consumer<ClassWriter> members) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "test/mixin/RendererMixin", null,
                "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = mixin.visitArray("value");
        value.visit(null, Type.getObjectType(target));
        value.visitEnd();
        mixin.visitEnd();
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] hostClass(String name, Consumer<ClassWriter> members) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", null);
        members.accept(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void abstractMethod(ClassWriter writer, String name, String desc) {
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, desc, null, null).visitEnd();
    }

    private static void put(JarOutputStream out, byte[] classBytes) throws IOException {
        out.putNextEntry(new JarEntry(new ClassReader(classBytes).getClassName() + ".class"));
        out.write(classBytes);
        out.closeEntry();
    }

    private static ClassNode read(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no method " + name));
    }

    private static boolean calls(MethodNode method, String owner, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasShadow(MethodNode method) {
        return method.visibleAnnotations != null
                && method.visibleAnnotations.stream().anyMatch(a -> a.desc.equals(SHADOW));
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        }
        throw new AssertionError("no " + key + " on " + annotation.desc);
    }
}
