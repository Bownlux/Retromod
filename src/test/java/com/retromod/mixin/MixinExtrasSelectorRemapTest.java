/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.3.0 track B, first slice (#50 Revamped Phantoms): MixinExtras injector annotations
 * (@ModifyReturnValue / @ModifyExpressionValue / @WrapOperation / ...) were never dispatched through
 * the mixin selector-remap, so a renamed target left their {@code method=} pointing at the old name
 * and the injection failed with "Scanned 0 target(s)" (the mod ships no refMap). They are now routed
 * through the same remap as core injectors.
 *
 * <p>Safety invariant: the {@code require=0} soft-fail net is added to a MixinExtras handler only when
 * a selector was actually rewritten, so a working MixinExtras mixin is left byte-identical.
 */
class MixinExtrasSelectorRemapTest {

    private static final String MODIFY_RETURN_VALUE = "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";

    /** A mixin class with one handler carrying {@code annDesc(method = {methodTarget})}. */
    private static byte[] mixin(String annDesc, String methodTarget, String mixinTarget) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mixin/PhantomMixin", null, "java/lang/Object", null);
        // class-level @Mixin(value = { mixinTarget }) - invisible, as real mixins are
        AnnotationVisitor ma = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor mav = ma.visitArray("value");
        mav.visit(null, Type.getObjectType(mixinTarget));
        mav.visitEnd();
        ma.visitEnd();
        // handler method with the injector annotation
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler", "()V", null, null);
        AnnotationVisitor av = mv.visitAnnotation(annDesc, false);
        AnnotationVisitor arr = av.visitArray("method");
        arr.visit(null, methodTarget);
        arr.visitEnd();
        av.visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static AnnotationNode handlerAnnotation(byte[] classBytes, String annDesc) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        MethodNode m = cn.methods.stream().filter(x -> x.name.equals("handler")).findFirst().orElseThrow();
        for (List<AnnotationNode> anns : List.of(
                m.visibleAnnotations != null ? m.visibleAnnotations : List.<AnnotationNode>of(),
                m.invisibleAnnotations != null ? m.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode a : anns) if (annDesc.equals(a.desc)) return a;
        }
        throw new AssertionError("annotation not found: " + annDesc);
    }

    /** value of a named annotation key, or null. */
    private static Object annVal(AnnotationNode a, String key) {
        if (a.values == null) return null;
        for (int i = 0; i < a.values.size(); i += 2) if (key.equals(a.values.get(i))) return a.values.get(i + 1);
        return null;
    }

    @Test
    @DisplayName("#50: @ModifyReturnValue method= is remapped and gets a require=0 soft-fail net")
    void modifyReturnValueSelectorRemapped() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        // an obfuscated (globally-unique) name so the bare-name mixin redirect activates
        t.registerMethodRedirect(
                "net/minecraft/world/entity/monster/Phantom", "method_18377", "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;",
                "net/minecraft/world/entity/monster/Phantom", "getDefaultDimensions", "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;");
        MixinCompatibilityTransformer mt = new MixinCompatibilityTransformer(t);

        byte[] out = mt.transformMixinClass(
                mixin(MODIFY_RETURN_VALUE, "method_18377", "net/minecraft/world/entity/monster/Phantom"));
        AnnotationNode a = handlerAnnotation(out, MODIFY_RETURN_VALUE);

        Object method = annVal(a, "method");
        assertInstanceOf(List.class, method, "method= should still be an array");
        assertEquals("getDefaultDimensions", ((List<?>) method).get(0),
                "the MixinExtras selector must be remapped to the host name");
        assertEquals(0, ((Number) annVal(a, "require")).intValue(),
                "a rewritten MixinExtras injector gets require=0 so it soft-fails if the target still moves");
    }

    @Test
    @DisplayName("A working MixinExtras handler (no redirect) is left byte-identical")
    void unrewrittenMixinExtrasHandlerByteIdentical() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();  // no redirects registered
        MixinCompatibilityTransformer mt = new MixinCompatibilityTransformer(t);

        byte[] in = mixin(MODIFY_RETURN_VALUE, "method_99999", "net/minecraft/world/entity/monster/Phantom");
        byte[] out = mt.transformMixinClass(in);
        assertArrayEquals(in, out, "an untouched MixinExtras mixin must not be rewritten (no require=0 injected)");

        AnnotationNode a = handlerAnnotation(out, MODIFY_RETURN_VALUE);
        assertNull(annVal(a, "require"), "no require=0 should be added when nothing was translated");
    }

    @Test
    @DisplayName("Core @Inject still gets require=0 unconditionally (existing soft-fail net preserved)")
    void coreInjectStillUnconditionalRequire() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();  // no redirect for the target
        MixinCompatibilityTransformer mt = new MixinCompatibilityTransformer(t);

        byte[] out = mt.transformMixinClass(
                mixin(INJECT, "method_99999", "net/minecraft/world/entity/monster/Phantom"));
        AnnotationNode a = handlerAnnotation(out, INJECT);
        assertEquals(0, ((Number) annVal(a, "require")).intValue(),
                "core @Inject keeps its unconditional require=0 net, unchanged by the MixinExtras dispatch");
    }

    private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

    /** A @Mixin class with one @Inject(method=target) handler capturing (String, CIR). */
    private static byte[] injectMixinTargeting(String target) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mixin/ResignWiring", null, "java/lang/Object", null);
        AnnotationVisitor ma = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor mav = ma.visitArray("value");
        mav.visit(null, Type.getObjectType("java/lang/Object"));
        mav.visitEnd();
        ma.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler", "(Ljava/lang/String;" + CIR + ")V", null, null);
        AnnotationVisitor iv = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
        AnnotationVisitor arr = iv.visitArray("method");
        arr.visit(null, target);
        arr.visitEnd();
        iv.visitEnd();
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 4);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("Track A: a signature-changed @Inject target re-signatures the handler end-to-end")
    void injectHandlerResignaturedThroughTransform() {
        // register a test signature change with a resolvable insert type so COMPUTE_FRAMES is deterministic
        MixinHandlerResignature.register("retromodTestResignTarget",
                new MixinHandlerResignature.ParamInsert(0, "Ljava/lang/Integer;"));
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        MixinCompatibilityTransformer mt = new MixinCompatibilityTransformer(t);

        String saved = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        try {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "26.2";   // re-signature is gated to 1.21.5+
            byte[] out = mt.transformMixinClass(injectMixinTargeting("retromodTestResignTarget"));
            ClassNode cn = new ClassNode();
            new ClassReader(out).accept(cn, 0);
            MethodNode h = cn.methods.stream().filter(m -> m.name.equals("handler")).findFirst().orElseThrow();
            assertEquals("(Ljava/lang/Integer;Ljava/lang/String;" + CIR + ")V", h.desc,
                    "transformMixinClass must re-signature the handler for a signature-changed target");
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = saved;
        }
    }

    @Test
    @DisplayName("#50 owner-alias: a @ModifyExpressionValue @At target FlyingMob.getDefaultDimensions re-owners to Mob")
    void phantomOwnerAliasNeoForge() {
        String saved = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        try {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "26.2";
            RetromodTransformer t = RetromodTransformer.getInstance();
            t.clearRedirectsForTesting();
            // exactly the redirect the NeoForge/Forge 1.21.11->26.1 shim registers
            t.registerMethodRedirect(
                    "net/minecraft/world/entity/FlyingMob", "getDefaultDimensions",
                    "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;",
                    "net/minecraft/world/entity/Mob", "getDefaultDimensions",
                    "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;");
            MixinCompatibilityTransformer mt = new MixinCompatibilityTransformer(t);
            // Use the NeoForge/Forge entry point (stripBlocklistedHandlers): Revamped Phantoms is a
            // NeoForge mod, and this path previously did not apply the selector remap, so the #50
            // owner-alias was dead on its actual loader (the review's dominant finding). It now runs
            // the full pipeline.
            byte[] out = mt.stripBlocklistedHandlers(modifyExprValueMixin(
                    "Lnet/minecraft/world/entity/FlyingMob;getDefaultDimensions(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;"));
            assertEquals("Lnet/minecraft/world/entity/Mob;getDefaultDimensions(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;",
                    mevAtTargetOf(out),
                    "the deleted FlyingMob owner must re-own to Mob on the NeoForge/Forge path too (#50)");
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = saved;
        }
    }

    @Test
    @DisplayName("#50 owner-alias (Fabric): intermediary class_1307.method_55694 -> Mojang Mob.getDefaultDimensions")
    void phantomOwnerAliasFabric() {
        String saved = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        try {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "26.2";
            RetromodTransformer t = RetromodTransformer.getInstance();
            t.clearRedirectsForTesting();
            // exactly the redirect the Fabric 1.21.11->26.1 shim registers (intermediary key -> Mojang)
            t.registerMethodRedirect(
                    "net/minecraft/class_1307", "method_55694",
                    "(Lnet/minecraft/class_4050;)Lnet/minecraft/class_4048;",
                    "net/minecraft/world/entity/Mob", "getDefaultDimensions",
                    "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;");
            MixinCompatibilityTransformer mt = new MixinCompatibilityTransformer(t);
            byte[] out = mt.transformMixinClass(modifyExprValueMixin(
                    "Lnet/minecraft/class_1307;method_55694(Lnet/minecraft/class_4050;)Lnet/minecraft/class_4048;"));
            assertEquals("Lnet/minecraft/world/entity/Mob;getDefaultDimensions(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;",
                    mevAtTargetOf(out), "the intermediary FlyingMob call re-owners straight to Mojang Mob");
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = saved;
        }
    }

    /** A @Mixin class with one @ModifyExpressionValue(method=..., at=@At(INVOKE, target=atTarget)) handler (#50 shape). */
    private static byte[] modifyExprValueMixin(String atTarget) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mixin/MevDrift", null, "java/lang/Object", null);
        AnnotationVisitor ma = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor mav = ma.visitArray("value");
        mav.visit(null, Type.getObjectType("java/lang/Object"));
        mav.visitEnd();
        ma.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler",
                "(Lnet/minecraft/world/entity/EntityDimensions;)Lnet/minecraft/world/entity/EntityDimensions;", null, null);
        AnnotationVisitor iv = mv.visitAnnotation(
                "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;", false);
        AnnotationVisitor arr = iv.visitArray("method");
        arr.visit(null, "getDefaultDimensions(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;");
        arr.visitEnd();
        AnnotationVisitor atArr = iv.visitArray("at");
        AnnotationVisitor at = atArr.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "INVOKE");
        at.visit("target", atTarget);
        at.visitEnd();
        atArr.visitEnd();
        iv.visitEnd();
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String mevAtTargetOf(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        MethodNode h = cn.methods.stream().filter(m -> m.name.equals("handler")).findFirst().orElseThrow();
        for (List<AnnotationNode> anns : List.of(
                h.visibleAnnotations != null ? h.visibleAnnotations : List.<AnnotationNode>of(),
                h.invisibleAnnotations != null ? h.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode a : anns) {
                if (!"Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;".equals(a.desc) || a.values == null) continue;
                for (int i = 0; i + 1 < a.values.size(); i += 2) {
                    if (!"at".equals(a.values.get(i))) continue;
                    Object v = a.values.get(i + 1);
                    AnnotationNode at = v instanceof List<?> l ? (AnnotationNode) l.get(0) : (AnnotationNode) v;
                    for (int j = 0; j + 1 < at.values.size(); j += 2) {
                        if ("target".equals(at.values.get(j))) return (String) at.values.get(j + 1);
                    }
                }
            }
        }
        throw new AssertionError("no @ModifyExpressionValue @At target found");
    }

    /** A @Mixin class with one @Inject(method="tick()V", at=@At(INVOKE, target=atTarget)) handler. */
    private static byte[] atTargetMixin(String atTarget) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mixin/AtDrift", null, "java/lang/Object", null);
        AnnotationVisitor ma = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor mav = ma.visitArray("value");
        mav.visit(null, Type.getObjectType("java/lang/Object"));
        mav.visitEnd();
        ma.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        AnnotationVisitor iv = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
        AnnotationVisitor arr = iv.visitArray("method");
        arr.visit(null, "tick()V");
        arr.visitEnd();
        AnnotationVisitor atArr = iv.visitArray("at");
        AnnotationVisitor at = atArr.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "INVOKE");
        at.visit("target", atTarget);
        at.visitEnd();
        atArr.visitEnd();
        iv.visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String atTargetOf(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        MethodNode h = cn.methods.stream().filter(m -> m.name.equals("handler")).findFirst().orElseThrow();
        for (List<AnnotationNode> anns : List.of(
                h.visibleAnnotations != null ? h.visibleAnnotations : List.<AnnotationNode>of(),
                h.invisibleAnnotations != null ? h.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode a : anns) {
                if (!"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(a.desc) || a.values == null) continue;
                for (int i = 0; i + 1 < a.values.size(); i += 2) {
                    if (!"at".equals(a.values.get(i))) continue;
                    Object v = a.values.get(i + 1);
                    AnnotationNode at = v instanceof List<?> l ? (AnnotationNode) l.get(0) : (AnnotationNode) v;
                    for (int j = 0; j + 1 < at.values.size(); j += 2) {
                        if ("target".equals(at.values.get(j))) return (String) at.values.get(j + 1);
                    }
                }
            }
        }
        throw new AssertionError("no @At target found");
    }

    @Test
    @DisplayName("#69 end-to-end (Fabric path): intermediary @At target -> redirect -> drift rewrite")
    void atTargetRedirectThenDrift() {
        String saved = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        try {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "26.2";
            RetromodTransformer t = RetromodTransformer.getInstance();
            t.clearRedirectsForTesting();
            // the intermediary->Mojang redirect the 26.x harvest registers (still the OLD descriptor)
            t.registerMethodRedirect(
                    "net/minecraft/class_1593", "method_6121", "(Lnet/minecraft/class_1297;)Z",
                    "net/minecraft/world/entity/monster/Phantom", "doHurtTarget", "(Lnet/minecraft/world/entity/Entity;)Z");
            MixinCompatibilityTransformer mt = new MixinCompatibilityTransformer(t);

            byte[] out = mt.transformMixinClass(atTargetMixin(
                    "Lnet/minecraft/class_1593;method_6121(Lnet/minecraft/class_1297;)Z"));
            assertEquals("Lnet/minecraft/world/entity/monster/Phantom;doHurtTarget(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z",
                    atTargetOf(out),
                    "redirect must land on the Mojang OLD desc, then the drift rewrite must produce the NEW desc");
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = saved;
        }
    }

    @Test
    @DisplayName("#69 end-to-end (NeoForge path): Mojang old-desc @At target drift-rewritten in stripBlocklistedHandlers")
    void atTargetDriftOnStripPath() {
        String saved = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        try {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "26.2";
            RetromodTransformer t = RetromodTransformer.getInstance();
            t.clearRedirectsForTesting();
            MixinCompatibilityTransformer mt = new MixinCompatibilityTransformer(t);

            byte[] out = mt.stripBlocklistedHandlers(atTargetMixin(
                    "Lnet/minecraft/world/entity/monster/Phantom;doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z"));
            assertEquals("Lnet/minecraft/world/entity/monster/Phantom;doHurtTarget(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z",
                    atTargetOf(out), "the NeoForge/Forge path must drift-rewrite @At targets too (RP is a NeoForge mod)");
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = saved;
        }
    }

    @Test
    @DisplayName("#69 bonus: on the mod's NATIVE (pre-1.21.5) host the @At target is left untouched")
    void atTargetUntouchedOnNativeHost() {
        // The retired blocklist strip was unconditional, so it used to neutralize SweepAttackMixin
        // even on 1.21.1 where the mixin works natively. The drift rewrite is version-gated instead.
        String saved = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        try {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "1.21.1";
            RetromodTransformer t = RetromodTransformer.getInstance();
            t.clearRedirectsForTesting();
            MixinCompatibilityTransformer mt = new MixinCompatibilityTransformer(t);
            String oldTarget = "Lnet/minecraft/world/entity/monster/Phantom;doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z";
            byte[] out = mt.stripBlocklistedHandlers(atTargetMixin(oldTarget));
            assertEquals(oldTarget, atTargetOf(out), "on a pre-1.21.5 host the old descriptor is CORRECT; no rewrite");
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = saved;
        }
    }

    @Test
    @DisplayName("Track A: re-signature is gated to 1.21.5+ hosts (unchanged on an older host)")
    void resignatureVersionGated() {
        String saved = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        try {
            RetromodTransformer t = RetromodTransformer.getInstance();
            t.clearRedirectsForTesting();
            MixinCompatibilityTransformer mt = new MixinCompatibilityTransformer(t);
            byte[] in = injectMixinTargeting("actuallyHurt");   // handler(String, CIR) @Inject actuallyHurt

            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "1.20.1";
            assertEquals("(Ljava/lang/String;" + CIR + ")V", handlerDesc(mt.transformMixinClass(in)),
                    "pre-1.21.5 host: the working handler is NOT re-signatured");

            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "26.2";
            assertEquals("(Lnet/minecraft/server/level/ServerLevel;Ljava/lang/String;" + CIR + ")V",
                    handlerDesc(mt.transformMixinClass(in)),
                    "1.21.5+ host: the handler gains the leading ServerLevel");
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = saved;
        }
    }

    private static String handlerDesc(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);
        return cn.methods.stream().filter(m -> m.name.equals("handler")).findFirst().orElseThrow().desc;
    }

    @Test
    @DisplayName("Track A: the NeoForge/Forge strip path re-signatures the handler symmetrically")
    void injectHandlerResignaturedThroughStripPath() {
        // The re-signature must not be Fabric-only (cf. CLAUDE.md pitfall #15: the blocklist strip
        // was accidentally Fabric-only until beta.10). A vanilla signature change is loader-independent.
        MixinHandlerResignature.register("retromodTestResignTarget",
                new MixinHandlerResignature.ParamInsert(0, "Ljava/lang/Integer;"));
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        MixinCompatibilityTransformer mt = new MixinCompatibilityTransformer(t);

        String saved = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        try {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "26.2";   // re-signature is gated to 1.21.5+
            byte[] out = mt.stripBlocklistedHandlers(injectMixinTargeting("retromodTestResignTarget"));
            ClassNode cn = new ClassNode();
            new ClassReader(out).accept(cn, 0);
            MethodNode h = cn.methods.stream().filter(m -> m.name.equals("handler")).findFirst().orElseThrow();
            assertEquals("(Ljava/lang/Integer;Ljava/lang/String;" + CIR + ")V", h.desc,
                    "the NeoForge/Forge strip path must re-signature a signature-changed @Inject handler too");
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = saved;
        }
    }
}
