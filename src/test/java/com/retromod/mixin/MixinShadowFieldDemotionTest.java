/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.mixin;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transform-level guarantee for the YUNG's API worldgen repair: the {@code @Shadow @Final}
 * {@code NoiseChunk.noiseSettings} field (deleted from vanilla in the 1.21.5 worldgen
 * refactor) is demoted to a {@code @Unique} mixin field and seeded from the still-present
 * constructor parameter the mixin's {@code <init>} handler captures. Host-independent:
 * asserts on the emitted bytecode, no Minecraft on the classpath.
 *
 * @see MixinShadowFieldDemotion
 */
class MixinShadowFieldDemotionTest {

    private static final String MIXIN = "com/yungnickyoung/minecraft/yungsapi/mixin/NoiseChunkMixin";
    private static final String FIELD = "noiseSettings";
    private static final String FIELD_DESC = "Lnet/minecraft/world/level/levelgen/NoiseSettings;";
    private static final String HANDLER = "yungsapi_attachNoiseChunkToBeardifier";
    // The real 5.1.6 handler descriptor: an @Inject into NoiseChunk.<init> capturing every
    // ctor param. NoiseSettings is the 5th argument, so it lands at local slot 5.
    private static final String HANDLER_DESC =
            "(ILnet/minecraft/world/level/levelgen/RandomState;II"
            + "Lnet/minecraft/world/level/levelgen/NoiseSettings;"
            + "Lnet/minecraft/world/level/levelgen/DensityFunctions$BeardifierOrMarker;"
            + "Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;"
            + "Lnet/minecraft/world/level/levelgen/Aquifer$FluidPicker;"
            + "Lnet/minecraft/world/level/levelgen/blending/Blender;"
            + "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
    private static final int NOISE_SETTINGS_SLOT = 5;

    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String FINAL = "Lorg/spongepowered/asm/mixin/Final;";
    private static final String UNIQUE = "Lorg/spongepowered/asm/mixin/Unique;";

    private String savedVersion;

    @AfterEach
    void restoreVersion() {
        if (savedVersion != null) {
            RetromodVersion.TARGET_MC_VERSION = savedVersion;
            savedVersion = null;
        }
        MixinBlocklist.resetForTesting();
    }

    /** Synthetic NoiseChunkMixin: bare @Mixin, a @Shadow @Final noiseSettings field, and the handler. */
    private static byte[] syntheticMixin() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MIXIN, null, "java/lang/Object", null);
        cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitEnd();

        FieldVisitor fv = cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, FIELD, FIELD_DESC, null, null);
        fv.visitAnnotation(SHADOW, false).visitEnd();
        fv.visitAnnotation(FINAL, false).visitEnd();
        fv.visitEnd();

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor h = cw.visitMethod(Opcodes.ACC_PRIVATE, HANDLER, HANDLER_DESC, null, null);
        h.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false).visitEnd();
        h.visitCode();
        h.visitInsn(Opcodes.RETURN);
        h.visitMaxs(0, 0);
        h.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        return cn;
    }

    private static boolean fieldHas(ClassNode cn, String desc) {
        FieldNode f = field(cn);
        return annotationPresent(f.visibleAnnotations, desc) || annotationPresent(f.invisibleAnnotations, desc);
    }

    private static FieldNode field(ClassNode cn) {
        for (FieldNode f : cn.fields) {
            if (f.name.equals(FIELD) && f.desc.equals(FIELD_DESC)) return f;
        }
        return null;
    }

    private static boolean annotationPresent(List<AnnotationNode> list, String desc) {
        if (list == null) return false;
        for (AnnotationNode a : list) if (desc.equals(a.desc)) return true;
        return false;
    }

    /** Whether the handler body begins with {@code this.noiseSettings = <slot>} (the seed store). */
    private static boolean handlerSeedsField(ClassNode cn, int expectedSlot) {
        MethodNode h = null;
        for (MethodNode m : cn.methods) if (m.name.equals(HANDLER)) h = m;
        if (h == null) return false;
        for (AbstractInsnNode insn = h.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode fi
                    && fi.getOpcode() == Opcodes.PUTFIELD
                    && fi.owner.equals(MIXIN) && fi.name.equals(FIELD) && fi.desc.equals(FIELD_DESC)) {
                // the two insns before the PUTFIELD must be ALOAD 0, ALOAD <slot>
                AbstractInsnNode load = fi.getPrevious();
                AbstractInsnNode self = load != null ? load.getPrevious() : null;
                return load instanceof VarInsnNode lv && lv.getOpcode() == Opcodes.ALOAD && lv.var == expectedSlot
                        && self instanceof VarInsnNode sv && sv.getOpcode() == Opcodes.ALOAD && sv.var == 0;
            }
        }
        return false;
    }

    @Test
    @DisplayName("apply(): @Shadow @Final noiseSettings becomes @Unique and is seeded from the ctor param")
    void demotesAndSeeds() {
        ClassNode cn = read(syntheticMixin());
        assertTrue(fieldHas(cn, SHADOW), "precondition: field starts @Shadow");

        assertTrue(MixinShadowFieldDemotion.apply(cn), "the rule must fire for NoiseChunkMixin");

        assertFalse(fieldHas(cn, SHADOW), "@Shadow must be removed (the target field no longer exists)");
        assertFalse(fieldHas(cn, FINAL), "@Final must be removed");
        assertTrue(fieldHas(cn, UNIQUE), "the field must become @Unique (mixin-owned)");
        assertEquals(0, field(cn).access & Opcodes.ACC_FINAL, "ACC_FINAL must be cleared (we write it post-ctor)");
        assertTrue(handlerSeedsField(cn, NOISE_SETTINGS_SLOT),
                "the handler must begin with this.noiseSettings = <NoiseSettings ctor param at slot 5>");
    }

    @Test
    @DisplayName("apply() is idempotent: a second pass finds no @Shadow and does nothing")
    void idempotent() {
        ClassNode cn = read(syntheticMixin());
        assertTrue(MixinShadowFieldDemotion.apply(cn));
        assertFalse(MixinShadowFieldDemotion.apply(cn), "already demoted, must not seed a second store");
    }

    @Test
    @DisplayName("Wired: the post-remap pass demotes on a 1.21.5+ host, and re-emits valid bytecode")
    void wiredOnModernHost() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        var t = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());

        // The rule matches a Mojang field descriptor, so it only runs once the remap is done.
        byte[] out = t.applyLegacyMemberBridges(syntheticMixin());
        // must be verifiable: reading it back (ClassReader) would throw on malformed bytecode
        ClassNode cn = read(out);
        assertFalse(fieldHas(cn, SHADOW), "on a 26.2 host the field is demoted");
        assertTrue(fieldHas(cn, UNIQUE), "and marked @Unique");
        assertTrue(handlerSeedsField(cn, NOISE_SETTINGS_SLOT), "and seeded from the captured ctor param");
    }

    @Test
    @DisplayName("Gate: on a pre-1.21.5 host the field still resolves, so it is left @Shadow")
    void gatedOffOnOldHost() {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        var t = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());

        ClassNode cn = read(t.applyLegacyMemberBridges(syntheticMixin()));
        assertTrue(fieldHas(cn, SHADOW), "pre-1.21.5: noiseSettings still exists on NoiseChunk, keep the @Shadow");
        assertFalse(fieldHas(cn, UNIQUE), "no demotion on an old host");
    }

    @Test
    @DisplayName("Real jar: the shipped YungsApi 5.1.6 NoiseChunkMixin is repaired byte-for-byte")
    void realJar() throws Exception {
        Path jar = Path.of("test-jars-mixin/YungsApi-1.21.1-NeoForge-5.1.6.jar");
        Assumptions.assumeTrue(Files.isRegularFile(jar), "yungsapi test fixture present");
        byte[] original;
        try (ZipFile zf = new ZipFile(jar.toFile());
             InputStream in = zf.getInputStream(zf.getEntry(MIXIN + ".class"))) {
            original = in.readAllBytes();
        }
        ClassNode before = read(original);
        assertTrue(fieldHas(before, SHADOW), "the shipped mixin shadows noiseSettings");

        ClassNode cn = read(original);
        assertTrue(MixinShadowFieldDemotion.apply(cn), "the rule must fire for the real class");
        assertFalse(fieldHas(cn, SHADOW), "real jar: @Shadow removed");
        assertTrue(fieldHas(cn, UNIQUE), "real jar: @Unique added");
        assertTrue(handlerSeedsField(cn, NOISE_SETTINGS_SLOT),
                "real jar: handler seeds noiseSettings from the NoiseSettings ctor param at slot 5");
    }

    @Test
    @DisplayName("Blocklist: NoiseChunkMixin strip RETIRED (repaired); BeardifierMixin stays stripped (headless-confirmed unsafe)")
    void blocklistState() {
        MixinBlocklist.resetForTesting();
        // NoiseChunkMixin: repaired by the shadow-field demotion, verified safe on a 26.2 server.
        assertFalse(MixinBlocklist.isFullStrip("com/yungnickyoung/minecraft/yungsapi/mixin/NoiseChunkMixin"),
                "NoiseChunkMixin is repaired by the shadow-field demotion - no longer stripped");
        // BeardifierMixin: applies but its ACTIVE enhanced terrain adaptation throws
        // 'Parent chunk missing' on 26.2 (A/B verified headless). Stays whole-class stripped.
        assertTrue(MixinBlocklist.isFullStrip("com/yungnickyoung/minecraft/yungsapi/mixin/BeardifierMixin"),
                "BeardifierMixin breaks 26.2 chunk gen when active - stays whole-class stripped");
        // The dead companion @Accessor interfaces stay neutralized (zero .class refs, unbindable targets).
        assertTrue(MixinBlocklist.isFullStrip("com/yungnickyoung/minecraft/yungsapi/mixin/accessor/BeardifierAccessor"),
                "the dead BeardifierAccessor stays stripped");
        assertTrue(MixinBlocklist.isFullStrip("com/yungnickyoung/minecraft/yungsapi/mixin/accessor/NoiseChunkAccessor"),
                "the dead NoiseChunkAccessor stays stripped");
    }
}
