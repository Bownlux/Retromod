/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.mixin.MixinHandlerResignature.ParamInsert;
import com.retromod.util.SafeClassWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * 1.3.0 track A: the {@code @Inject} arg-insertion re-signature engine (#69). The critical property
 * is that a re-signatured handler still VERIFIES, so these tests actually load the rewritten class
 * through a classloader (a slot-shift bug would surface as a {@code VerifyError} at define time).
 */
class MixinHandlerResignatureTest {

    private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

    private static final class Loader extends ClassLoader {
        Class<?> define(String name, byte[] b) { return defineClass(name, b, 0, b.length); }
    }

    /** A class with an @Inject-shaped handler {@code void handler(String, CIR)} using a body local. */
    private static ClassNode injectShapedClass(String handlerDesc) {
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V17;
        cn.access = ACC_PUBLIC;
        cn.name = "test/Resign";
        cn.superName = "java/lang/Object";

        MethodNode ctor = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.instructions.add(new VarInsnNode(ALOAD, 0));
        ctor.instructions.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        ctor.instructions.add(new InsnNode(RETURN));
        ctor.maxLocals = 1; ctor.maxStack = 1;
        cn.methods.add(ctor);

        MethodNode h = new MethodNode(ACC_PRIVATE, "handler", handlerDesc, null, null);
        // body exercises slot 1 (first captured param) and a body local at slot 3:
        //   local3 = param1; use(local3)
        h.instructions.add(new VarInsnNode(ALOAD, 1));
        h.instructions.add(new VarInsnNode(ASTORE, 3));
        h.instructions.add(new VarInsnNode(ALOAD, 3));
        h.instructions.add(new InsnNode(POP));
        h.instructions.add(new InsnNode(RETURN));
        h.maxLocals = 4; h.maxStack = 1;
        cn.methods.add(h);
        return cn;
    }

    private static MethodNode handler(ClassNode cn) {
        return cn.methods.stream().filter(m -> m.name.equals("handler")).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("#69: inserting a leading param shifts body slots and the result still verifies")
    void insertLeadingParamVerifies() throws Exception {
        ClassNode cn = injectShapedClass("(Ljava/lang/String;" + CIR + ")V");
        MethodNode h = handler(cn);

        boolean applied = MixinHandlerResignature.insertParams(h, List.of(new ParamInsert(0, "Ljava/lang/Integer;")));
        assertTrue(applied, "a captured leading param must be re-signaturable");

        assertEquals("(Ljava/lang/Integer;Ljava/lang/String;" + CIR + ")V", h.desc,
                "the new param is inserted at the front of the captured params");
        // ALOAD/ASTORE slots shifted up by 1 (Integer occupies slot 1 now)
        int[] seen = new int[3]; int n = 0;
        for (var insn : h.instructions.toArray()) if (insn instanceof VarInsnNode v && n < 3) seen[n++] = v.var;
        assertArrayEquals(new int[]{2, 4, 4}, seen, "slot 1 -> 2 (param), slot 3 -> 4 (body local)");

        // The strongest check: recompute frames and actually load the class (VerifyError if wrong).
        SafeClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        Class<?> loaded = new Loader().define("test.Resign", cw.toByteArray());
        assertNotNull(loaded, "the re-signatured class must load and verify");
        Method m = loaded.getDeclaredMethod("handler", Integer.class, String.class,
                Class.forName("org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable"));
        assertEquals(3, m.getParameterCount(), "handler now takes the inserted param + originals");
    }

    @Test
    @DisplayName("Trailing append: a param inserted just before the CallbackInfo shifts only the CI slot and verifies")
    void insertTrailingParamVerifies() throws Exception {
        // handler(String, CIR): one captured param, CallbackInfo at index 1. Append a ResourceKey at
        // index 1 (== cbIndex) - the 26.1 ChunkGenerator.tryGenerateStructure trailing-param shape.
        ClassNode cn = injectShapedClass("(Ljava/lang/String;" + CIR + ")V");
        MethodNode h = handler(cn);

        boolean applied = MixinHandlerResignature.insertParams(h,
                List.of(new ParamInsert(1, "Lnet/minecraft/resources/ResourceKey;")));
        assertTrue(applied, "a trailing append before the CallbackInfo must re-signature");

        assertEquals("(Ljava/lang/String;Lnet/minecraft/resources/ResourceKey;" + CIR + ")V", h.desc,
                "the new param lands just before the CallbackInfo trailer");
        // String stays slot 1; the CI (slot 2) and body local (slot 3) each shift up by one.
        int[] seen = new int[3]; int n = 0;
        for (var insn : h.instructions.toArray()) if (insn instanceof VarInsnNode v && n < 3) seen[n++] = v.var;
        assertArrayEquals(new int[]{1, 4, 4}, seen, "String stays slot 1; body local 3 -> 4");

        SafeClassWriter cw = new SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        assertNotNull(new Loader().define("test.Resign", cw.toByteArray()),
                "the trailing-re-signatured class must load and verify");
    }

    @Test
    @DisplayName("tryGenerateStructure: the registered entry appends ResourceKey at the capture-count index")
    void tryGenerateStructureEntry() {
        // A 1.21.1 @Inject capturing tryGenerateStructure's old 9 params (ending at SectionPos), then CIR.
        String desc = "(Lnet/minecraft/world/level/levelgen/structure/StructureSet$StructureSelectionEntry;"
                + "Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/core/RegistryAccess;"
                + "Lnet/minecraft/world/level/levelgen/RandomState;"
                + "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplateManager;J"
                + "Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/ChunkPos;"
                + "Lnet/minecraft/core/SectionPos;" + CIR + ")V";
        MethodNode h = new MethodNode(ACC_PRIVATE, "betterstrongholds_disableVanillaStrongholds", desc, null, null);
        org.objectweb.asm.tree.AnnotationNode inject =
                new org.objectweb.asm.tree.AnnotationNode(
                        "Lorg/spongepowered/asm/mixin/injection/Inject;");
        inject.values = new java.util.ArrayList<>(List.of("method", List.of("tryGenerateStructure")));
        h.invisibleAnnotations = new java.util.ArrayList<>(List.of(inject));

        List<ParamInsert> ins = MixinHandlerResignature.injectSignatureChange(h);
        assertNotNull(ins, "the tryGenerateStructure signature change must be registered");
        assertEquals(1, ins.size());
        assertEquals(9, ins.get(0).paramIndex(), "ResourceKey is appended right before the CallbackInfo (index 9)");
        assertEquals("Lnet/minecraft/resources/ResourceKey;", ins.get(0).typeDescriptor());

        // and it actually applies (descriptor gains the trailing ResourceKey before the CIR).
        assertTrue(MixinHandlerResignature.insertParams(h, ins));
        assertTrue(h.desc.contains("Lnet/minecraft/core/SectionPos;Lnet/minecraft/resources/ResourceKey;" + CIR),
                "the ResourceKey is inserted after SectionPos and before the CallbackInfoReturnable");
    }

    @Test
    @DisplayName("tryGenerateStructure guard: a same-named handler on another class (wrong first param) is skipped")
    void tryGenerateStructureFirstParamGuard() {
        // Same method name but the first captured param is not StructureSet$StructureSelectionEntry.
        MethodNode h = new MethodNode(ACC_PRIVATE, "handler",
                "(Lnet/minecraft/world/entity/Entity;" + CIR + ")V", null, null);
        org.objectweb.asm.tree.AnnotationNode inject =
                new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
        inject.values = new java.util.ArrayList<>(List.of("method", List.of("tryGenerateStructure")));
        h.invisibleAnnotations = new java.util.ArrayList<>(List.of(inject));
        assertNull(MixinHandlerResignature.injectSignatureChange(h),
                "the first-param guard must skip a same-named method on a different class");
    }

    @Test
    @DisplayName("A handler capturing no target params is left untouched")
    void noCapturedParamsSkipped() {
        ClassNode cn = injectShapedClass("(" + CIR + ")V");
        MethodNode h = handler(cn);
        String before = h.desc;
        assertFalse(MixinHandlerResignature.insertParams(h, List.of(new ParamInsert(0, "Ljava/lang/Integer;"))));
        assertEquals(before, h.desc, "no captured param at the insert index -> no re-signature");
    }

    @Test
    @DisplayName("A non-@Inject shape (no CallbackInfo trailer) is skipped")
    void nonInjectShapeSkipped() {
        ClassNode cn = injectShapedClass("(Ljava/lang/String;Ljava/lang/String;)V");
        MethodNode h = handler(cn);
        assertFalse(MixinHandlerResignature.insertParams(h, List.of(new ParamInsert(0, "Ljava/lang/Integer;"))),
                "without a CallbackInfo trailer it is not a re-signaturable @Inject");
    }

    @Test
    @DisplayName("A captured param carrying a parameter annotation (@Local/@Coerce) is declined")
    @SuppressWarnings("unchecked")
    void paramAnnotationDeclined() {
        // A @Local/@Coerce on a captured param resolves to a specific slot; shifting it would misalign
        // the MixinExtras bridge with a VerifyError that COMPUTE_FRAMES can't catch, so decline instead.
        ClassNode cn = injectShapedClass("(Ljava/lang/String;" + CIR + ")V");
        MethodNode h = handler(cn);
        List<org.objectweb.asm.tree.AnnotationNode>[] pa = new List[2];
        pa[0] = new java.util.ArrayList<>(List.of(
                new org.objectweb.asm.tree.AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;")));
        h.visibleParameterAnnotations = pa;
        assertFalse(MixinHandlerResignature.insertParams(h, List.of(new ParamInsert(0, "Ljava/lang/Integer;"))),
                "a parameter annotation on a captured param must make re-signature decline");
    }

    @Test
    @DisplayName("F1 regression: a @Local captured AFTER CallbackInfo declines (the old cbIndex-bounded guard missed it)")
    @SuppressWarnings("unchecked")
    void localAfterCallbackInfoIsRealigned() {
        // The modern @Inject idiom captures locals AFTER the CallbackInfo trailer:
        // (Entity, CIR, @Local Object). Mixin's LocalVariableDiscriminator matches a capture's type
        // with Type.equals, so an added ServerLevel cannot become a rival for an Object capture.
        // The repair therefore proceeds, and the capture has to move with its own parameter.
        MethodNode h = new MethodNode(ACC_PRIVATE, "handler",
                "(Lnet/minecraft/world/entity/Entity;" + CIR + "Ljava/lang/Object;)V", null, null);
        List<org.objectweb.asm.tree.AnnotationNode>[] pa = new List[3];
        org.objectweb.asm.tree.AnnotationNode local =
                new org.objectweb.asm.tree.AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;");
        pa[2] = new java.util.ArrayList<>(List.of(local));   // @Local on param 2, AFTER the CIR trailer
        h.invisibleParameterAnnotations = pa;
        h.invisibleAnnotableParameterCount = 3;

        assertTrue(MixinHandlerResignature.insertParams(h,
                        List.of(new ParamInsert(0, "Lnet/minecraft/server/level/ServerLevel;"))),
                "a capture of an unrelated type does not block the repair");

        assertEquals("(Lnet/minecraft/server/level/ServerLevel;"
                        + "Lnet/minecraft/world/entity/Entity;" + CIR + "Ljava/lang/Object;)V", h.desc);
        assertNull(h.invisibleParameterAnnotations[2],
                "the capture must not stay pinned to the CallbackInfo");
        assertSame(local, h.invisibleParameterAnnotations[3].get(0),
                "it moves to the parameter it was written on");
        assertEquals(4, h.invisibleAnnotableParameterCount,
                "the annotable count must cover the widened parameter list");
    }

    @Test
    @DisplayName("injectSignatureChange resolves the target selector against the table")
    void tableLookup() {
        // an @Inject targeting doHurtTarget (the #69 target) -> the ServerLevel insert
        MethodNode inj = new MethodNode(ACC_PRIVATE, "h", "(Lnet/minecraft/world/entity/Entity;" + CIR + ")V", null, null);
        AnnotationNodeStub.addInject(inj, "doHurtTarget");
        List<ParamInsert> ins = MixinHandlerResignature.injectSignatureChange(inj);
        assertNotNull(ins, "doHurtTarget is a known signature change");
        assertEquals("Lnet/minecraft/server/level/ServerLevel;", ins.get(0).typeDescriptor());
        assertEquals(0, ins.get(0).paramIndex());

        // an @Inject with an owner-qualified selector still resolves by bare name
        MethodNode inj2 = new MethodNode(ACC_PRIVATE, "h", "()V", null, null);
        AnnotationNodeStub.addInject(inj2, "Lnet/minecraft/world/entity/LivingEntity;doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z");
        assertNotNull(MixinHandlerResignature.injectSignatureChange(inj2), "owner/desc-qualified selector resolves by bare name");

        // an unrelated target -> no change
        MethodNode inj3 = new MethodNode(ACC_PRIVATE, "h", "()V", null, null);
        AnnotationNodeStub.addInject(inj3, "tick");
        assertNull(MixinHandlerResignature.injectSignatureChange(inj3));
    }

    @Test
    @DisplayName("Owner guard: a bare-name match with a wrong Mojang-MC first param is declined")
    void ownerGuardRejectsWrongFirstParam() {
        // actuallyHurt's real first param is DamageSource -> a handler capturing it applies
        MethodNode ok = new MethodNode(ACC_PRIVATE, "h",
                "(Lnet/minecraft/world/damagesource/DamageSource;" + CIR + ")V", null, null);
        AnnotationNodeStub.addInject(ok, "actuallyHurt");
        assertNotNull(MixinHandlerResignature.injectSignatureChange(ok), "correct first param -> applies");

        // a same-named handler capturing a DIFFERENT Mojang-MC type (an unchanged method) -> declined
        MethodNode wrong = new MethodNode(ACC_PRIVATE, "h",
                "(Lnet/minecraft/world/item/ItemStack;" + CIR + ")V", null, null);
        AnnotationNodeStub.addInject(wrong, "actuallyHurt");
        assertNull(MixinHandlerResignature.injectSignatureChange(wrong),
                "wrong Mojang-MC first param -> owner guard declines");

        // an intermediary first param (Fabric pre-remap) can't be checked -> permissive -> applies
        MethodNode intermediary = new MethodNode(ACC_PRIVATE, "h",
                "(Lnet/minecraft/class_1282;" + CIR + ")V", null, null);
        AnnotationNodeStub.addInject(intermediary, "actuallyHurt");
        assertNotNull(MixinHandlerResignature.injectSignatureChange(intermediary),
                "intermediary first param -> permissive (selector already pins the method on Fabric)");

        // a non-MC first param -> permissive -> applies
        MethodNode nonMc = new MethodNode(ACC_PRIVATE, "h",
                "(Ljava/lang/String;" + CIR + ")V", null, null);
        AnnotationNodeStub.addInject(nonMc, "actuallyHurt");
        assertNotNull(MixinHandlerResignature.injectSignatureChange(nonMc), "non-MC first param -> permissive");
    }

    @Test
    @DisplayName("The harvested 1.21.5 ServerLevel-threading entries all resolve to a ServerLevel@0 insert")
    void harvestedEntriesResolve() {
        for (String target : new String[]{
                "actuallyHurt", "isInvulnerableTo", "dropFromLootTable", "dropExperience",
                "triggerOnDeathMobEffects", "spawnAtLocation", "pickUpItem", "wantsToPickUp",
                "equipItemIfPossible", "dropPreservedEquipment", "isPreventingPlayerRest"}) {
            MethodNode m = new MethodNode(ACC_PRIVATE, "h", "()V", null, null);
            AnnotationNodeStub.addInject(m, target);
            List<ParamInsert> ins = MixinHandlerResignature.injectSignatureChange(m);
            assertNotNull(ins, target + " should be a known signature change");
            assertEquals("Lnet/minecraft/server/level/ServerLevel;", ins.get(0).typeDescriptor(), target);
            assertEquals(0, ins.get(0).paramIndex(), target);
        }
    }

    @Test
    @DisplayName("MobEffect handlers gain ServerLevel only with the LivingEntity guard")
    void mobEffectEntriesResolve() {
        for (String target : new String[]{"applyEffectTick", "onMobHurt", "onMobRemoved"}) {
            MethodNode m = new MethodNode(ACC_PRIVATE, "h", "()V", null, null);
            AnnotationNodeStub.addInject(m, target);
            List<ParamInsert> ins = MixinHandlerResignature.injectSignatureChange(m);
            assertNotNull(ins, target + " should be a known signature change");
            assertEquals("Lnet/minecraft/server/level/ServerLevel;", ins.get(0).typeDescriptor(), target);
            assertEquals(0, ins.get(0).paramIndex(), target);
        }
        MethodNode ok = new MethodNode(ACC_PRIVATE, "h",
                "(Lnet/minecraft/world/entity/LivingEntity;ILorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        AnnotationNodeStub.addInject(ok, "applyEffectTick");
        assertNotNull(MixinHandlerResignature.injectSignatureChange(ok),
                "LivingEntity handlers should be updated");
        MethodNode wrong = new MethodNode(ACC_PRIVATE, "h",
                "(Lnet/minecraft/world/item/ItemStack;ILorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        AnnotationNodeStub.addInject(wrong, "applyEffectTick");
        assertNull(MixinHandlerResignature.injectSignatureChange(wrong),
                "same-named handlers with another owner type must stay unchanged");
    }

    @Test
    @DisplayName("rewriteSelectorDescriptor inserts the new param into a desc-qualified selector")
    void selectorDescriptorRewrite() {
        assertEquals("doHurtTarget(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z",
                MixinHandlerResignature.rewriteSelectorDescriptor("doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z"),
                "the ServerLevel is inserted at index 0 of the selector's param list");
        assertEquals("Lnet/minecraft/world/entity/LivingEntity;doHurtTarget(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z",
                MixinHandlerResignature.rewriteSelectorDescriptor("Lnet/minecraft/world/entity/LivingEntity;doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z"),
                "an owner prefix is preserved");
        assertNull(MixinHandlerResignature.rewriteSelectorDescriptor("doHurtTarget"),
                "a bare-name selector is left as-is (resolved by name)");
        assertNull(MixinHandlerResignature.rewriteSelectorDescriptor("tick(Lnet/minecraft/world/entity/Entity;)V"),
                "an unknown target is not rewritten");
    }

    @Test
    @DisplayName("insertParams also rewrites the handler's own desc-qualified @Inject selector")
    void insertParamsRewritesSelector() {
        MethodNode inj = new MethodNode(ACC_PRIVATE, "h",
                "(Lnet/minecraft/world/entity/Entity;" + CIR + ")V", null, null);
        var a = new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
        a.values = new java.util.ArrayList<>(List.of("method",
                new java.util.ArrayList<>(List.of("doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z"))));
        inj.invisibleAnnotations = new java.util.ArrayList<>(List.of(a));
        inj.instructions.add(new InsnNode(RETURN));
        inj.maxLocals = 3; inj.maxStack = 0;

        List<ParamInsert> ins = MixinHandlerResignature.injectSignatureChange(inj);
        assertTrue(MixinHandlerResignature.insertParams(inj, ins));
        assertEquals("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;" + CIR + ")V",
                inj.desc, "the handler captures the inserted ServerLevel as a valid prefix");
        @SuppressWarnings("unchecked")
        List<Object> method = (List<Object>) a.values.get(1);
        assertEquals("doHurtTarget(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z",
                method.get(0), "the selector descriptor is rewritten so Mixin still resolves the target");
    }

    @Test
    @DisplayName("#69: @At(INVOKE, target=old-desc) is drift-rewritten to the new call-site descriptor")
    void atTargetDriftRewritten() {
        // The REAL SweepAttackMixin shape (verified in revampedphantoms-1.1.2): the handler captures
        // nothing; the old-descriptor doHurtTarget CALL SITE in the @At target is what fails to match.
        MethodNode m = new MethodNode(ACC_PRIVATE, "onHurtsTarget", "(" + CIR + ")V", null, null);
        var inject = new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
        var at = new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        at.values = new java.util.ArrayList<>(List.of(
                "value", "INVOKE",
                "target", "Lnet/minecraft/world/entity/monster/Phantom;doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z"));
        inject.values = new java.util.ArrayList<>(List.of(
                "method", new java.util.ArrayList<>(List.of("tick()V")),
                "at", at));
        m.invisibleAnnotations = new java.util.ArrayList<>(List.of(inject));

        assertTrue(MixinHandlerResignature.rewriteAnnotationDrift(m), "the drifted @At target must be rewritten");
        assertEquals("Lnet/minecraft/world/entity/monster/Phantom;doHurtTarget(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z",
                at.values.get(3), "the @At target now matches the 26.2 call site (verified bytecode)");
        // tick()V has no drifted name: untouched
        assertEquals("tick()V", ((List<?>) inject.values.get(1)).get(0));

        // Idempotence: running the pass again must not double-insert the ServerLevel.
        assertFalse(MixinHandlerResignature.rewriteAnnotationDrift(m), "already-new descriptors are left alone");
        assertEquals("Lnet/minecraft/world/entity/monster/Phantom;doHurtTarget(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z",
                at.values.get(3));
    }

    @Test
    @DisplayName("F2/F4: a CAPTURED-param @Inject defers its top-level method= to insertParams (only the @At target moves here)")
    @SuppressWarnings("unchecked")
    void atDriftDefersCapturedParamInjectSelector() {
        // The handler CAPTURES a target param (Entity) before the CallbackInfo, so insertParams will
        // re-signature it and rewrite the desc-qualified method= selector itself (coupled to success).
        // rewriteAnnotationDrift must therefore not rewrite the top-level method= here (if it did and
        // insertParams later declined on a @Local, the fallback would ship a new-form selector on an
        // un-re-signatured handler). The @At injection-point target (independent of the handler) still moves.
        MethodNode m = new MethodNode(ACC_PRIVATE, "h",
                "(Lnet/minecraft/world/entity/Entity;" + CIR + ")V", null, null);
        var inject = new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
        var at = new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        at.values = new java.util.ArrayList<>(List.of(
                "value", "INVOKE",
                "target", "Lnet/minecraft/world/entity/LivingEntity;doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z"));
        inject.values = new java.util.ArrayList<>(List.of(
                "method", new java.util.ArrayList<>(List.of("doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z")),
                "at", at));
        m.invisibleAnnotations = new java.util.ArrayList<>(List.of(inject));

        assertTrue(MixinHandlerResignature.rewriteAnnotationDrift(m),
                "the @At injection-point target is rewritten, so the pass reports a change");
        assertEquals("doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z",
                ((List<?>) inject.values.get(1)).get(0),
                "a captured-param @Inject leaves method= OLD-form here (insertParams owns the coupled rewrite)");
        assertEquals("Lnet/minecraft/world/entity/LivingEntity;doHurtTarget(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z",
                at.values.get(3), "the @At INVOKE target IS rewritten (independent of the handler signature)");
    }

    @Test
    @DisplayName("F4 regression: a ZERO-capture @Inject DOES get its top-level method= rewritten (insertParams would decline)")
    @SuppressWarnings("unchecked")
    void atDriftRewritesZeroCaptureInjectSelector() {
        // The handler captures NOTHING before the CallbackInfo (a valid prefix of any signature), so
        // insertParams declines (nothing to insert) and never rewrites the selector. If
        // rewriteAnnotationDrift also skipped it (the over-broad F2), the desc-qualified method=
        // would stay old-form, resolve 0 targets on 26.x, and the HEAD injection would silently go
        // inert. So the zero-capture case must rewrite the selector here.
        MethodNode m = new MethodNode(ACC_PRIVATE, "h", "(" + CIR + ")V", null, null);
        var inject = new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
        var at = new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        at.values = new java.util.ArrayList<>(List.of("value", "HEAD"));
        inject.values = new java.util.ArrayList<>(List.of(
                "method", new java.util.ArrayList<>(List.of("doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z")),
                "at", at));
        m.invisibleAnnotations = new java.util.ArrayList<>(List.of(inject));

        assertTrue(MixinHandlerResignature.rewriteAnnotationDrift(m), "the drifted method= must be rewritten");
        assertEquals("doHurtTarget(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z",
                ((List<?>) inject.values.get(1)).get(0),
                "a zero-capture @Inject's desc-qualified method= is rewritten to the modern signature");
    }

    @Test
    @DisplayName("@At drift rewrite declines a same-named target with a non-matching old descriptor")
    void atTargetDriftGuarded() {
        MethodNode m = new MethodNode(ACC_PRIVATE, "h", "(" + CIR + ")V", null, null);
        var inject = new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
        var at = new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        // actuallyHurt's known old first param is DamageSource; an (ItemStack,F)V "actuallyHurt"
        // is some other method and must not be rewritten.
        at.values = new java.util.ArrayList<>(List.of(
                "value", "INVOKE",
                "target", "Lsome/mod/Thing;actuallyHurt(Lnet/minecraft/world/item/ItemStack;F)V"));
        inject.values = new java.util.ArrayList<>(List.of("method", "tick()V", "at", at));
        m.invisibleAnnotations = new java.util.ArrayList<>(List.of(inject));
        assertFalse(MixinHandlerResignature.rewriteAnnotationDrift(m),
                "old-signature guard must decline a non-matching descriptor");
    }

    private static final String OLD_CALL =
            "Lnet/minecraft/world/entity/monster/Phantom;doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z";
    private static final String NEW_CALL =
            "Lnet/minecraft/world/entity/monster/Phantom;doHurtTarget(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z";

    /** A handler with a call-mirroring annotation (@Redirect/@WrapOperation) and an @At(INVOKE, target). */
    private static MethodNode callMirrorHandler(String annDesc, String handlerDesc, String atTarget) {
        MethodNode m = new MethodNode(ACC_PRIVATE, "wrap", handlerDesc, null, null);
        var ann = new org.objectweb.asm.tree.AnnotationNode(annDesc);
        var at = new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        at.values = new java.util.ArrayList<>(List.of("value", "INVOKE", "target", atTarget));
        ann.values = new java.util.ArrayList<>(List.of("method", "tick()V", "at", at));
        m.invisibleAnnotations = new java.util.ArrayList<>(List.of(ann));
        m.instructions.add(new InsnNode(ICONST_0));
        m.instructions.add(new InsnNode(IRETURN));
        m.maxLocals = 8; m.maxStack = 1;
        return m;
    }

    private static String atTargetOf(MethodNode m) {
        var ann = m.invisibleAnnotations.get(0);
        for (int i = 0; i + 1 < ann.values.size(); i += 2) {
            if ("at".equals(ann.values.get(i))) {
                var at = (org.objectweb.asm.tree.AnnotationNode) ann.values.get(i + 1);
                for (int j = 0; j + 1 < at.values.size(); j += 2) {
                    if ("target".equals(at.values.get(j))) return (String) at.values.get(j + 1);
                }
            }
        }
        throw new AssertionError("no at.target");
    }

    @Test
    @DisplayName("Drifted @Redirect (virtual call): @At target AND handler re-signatured together")
    void redirectDriftRepairedVirtual() {
        // @Redirect on a virtual call: handler mirrors (receiver, args...) -> insert at index 1
        MethodNode m = callMirrorHandler("Lorg/spongepowered/asm/mixin/injection/Redirect;",
                "(Lnet/minecraft/world/entity/monster/Phantom;Lnet/minecraft/world/entity/Entity;)Z", OLD_CALL);
        List<MixinHandlerResignature.RedirectDrift> drifts = MixinHandlerResignature.detectRedirectDrift(m);
        assertEquals(1, drifts.size(), "the drifted virtual @Redirect must be detected");
        assertTrue(drifts.get(0).apply());
        assertEquals(NEW_CALL, atTargetOf(m), "@At target rewritten to the 26.2 call site");
        assertEquals("(Lnet/minecraft/world/entity/monster/Phantom;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z",
                m.desc, "handler gains the ServerLevel AFTER the captured receiver (index 1)");
    }

    @Test
    @DisplayName("Drifted @WrapOperation (static-shaped): insert at 0, Operation trailer preserved")
    void wrapOperationDriftRepairedStatic() {
        MethodNode m = callMirrorHandler("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
                "(Lnet/minecraft/world/entity/Entity;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Z",
                "Lnet/minecraft/world/entity/LivingEntity;doHurtTarget(Lnet/minecraft/world/entity/Entity;)Z");
        List<MixinHandlerResignature.RedirectDrift> drifts = MixinHandlerResignature.detectRedirectDrift(m);
        assertEquals(1, drifts.size(), "static-shaped @WrapOperation detected (no receiver param)");
        assertTrue(drifts.get(0).apply());
        assertEquals("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Z",
                m.desc, "ServerLevel inserted at 0; the Operation trailer stays last");
    }

    @Test
    @DisplayName("A @Redirect whose handler does not mirror the call is left completely alone")
    void redirectDriftShapeMismatchDeclined() {
        // handler params (String) match neither [receiver, Entity] nor [Entity]
        MethodNode m = callMirrorHandler("Lorg/spongepowered/asm/mixin/injection/Redirect;",
                "(Ljava/lang/String;)Z", OLD_CALL);
        assertTrue(MixinHandlerResignature.detectRedirectDrift(m).isEmpty(), "unprovable shape: no detection");
        assertEquals(OLD_CALL, atTargetOf(m), "and the @At target must NOT be rewritten either");
    }

    @Test
    @DisplayName("rewriteAnnotationDrift never touches a @Redirect's @At target (paired repair owns it)")
    void atDriftSkipsCallMirroringInjectors() {
        MethodNode m = callMirrorHandler("Lorg/spongepowered/asm/mixin/injection/Redirect;",
                "(Lnet/minecraft/world/entity/monster/Phantom;Lnet/minecraft/world/entity/Entity;)Z", OLD_CALL);
        assertFalse(MixinHandlerResignature.rewriteAnnotationDrift(m),
                "rewriting only the @At target of a @Redirect would cause a handler mismatch");
        assertEquals(OLD_CALL, atTargetOf(m));
    }

    @Test
    @DisplayName("Drifted @Overwrite: re-signatured to overwrite the modern method (no soft-fail net exists)")
    void overwriteDriftRepaired() {
        // @Overwrite public boolean doHurtTarget(Entity) - on 26.2 there is no such vanilla method
        // to overwrite -> hard InvalidOverwriteException (require= does not apply to @Overwrite).
        MethodNode m = new MethodNode(ACC_PUBLIC, "doHurtTarget",
                "(Lnet/minecraft/world/entity/Entity;)Z", null, null);
        m.visibleAnnotations = new java.util.ArrayList<>(List.of(
                new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/Overwrite;")));
        m.instructions.add(new VarInsnNode(ALOAD, 1));     // uses the Entity param (slot 1 -> shifts to 2)
        m.instructions.add(new InsnNode(POP));
        m.instructions.add(new InsnNode(ICONST_1));
        m.instructions.add(new InsnNode(IRETURN));
        m.maxLocals = 2; m.maxStack = 1;

        List<MixinHandlerResignature.DriftRepair> drifts = MixinHandlerResignature.detectOverwriteDrift(m);
        assertEquals(1, drifts.size(), "the drifted @Overwrite must be detected");
        assertTrue(drifts.get(0).apply());
        assertEquals("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z",
                m.desc, "the @Overwrite now matches the modern method signature");
        assertEquals(2, ((VarInsnNode) m.instructions.getFirst()).var, "body slot for the Entity shifted 1 -> 2");

        // an @Overwrite with a non-matching first param (a mod's own same-named method) is untouched
        MethodNode other = new MethodNode(ACC_PUBLIC, "doHurtTarget", "(Ljava/lang/String;)Z", null, null);
        other.visibleAnnotations = new java.util.ArrayList<>(List.of(
                new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/Overwrite;")));
        assertTrue(MixinHandlerResignature.detectOverwriteDrift(other).isEmpty(),
                "the first-param owner guard must decline a non-vanilla shape");

        // idempotence: an already-new @Overwrite is untouched
        MethodNode already = new MethodNode(ACC_PUBLIC, "doHurtTarget",
                "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z", null, null);
        already.visibleAnnotations = new java.util.ArrayList<>(List.of(
                new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/Overwrite;")));
        assertTrue(MixinHandlerResignature.detectOverwriteDrift(already).isEmpty(),
                "already-new @Overwrite must not be double-inserted");
    }

    /** Tiny helper to attach an @Inject(method=...) to a method node. */
    private static final class AnnotationNodeStub {
        static void addInject(MethodNode m, String target) {
            var a = new org.objectweb.asm.tree.AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
            a.values = new java.util.ArrayList<>(List.of("method", List.of(target)));
            m.invisibleAnnotations = new java.util.ArrayList<>(List.of(a));
        }
    }
}
