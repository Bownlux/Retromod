/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 (#48): the {@code CompoundTag -> ValueOutput/ValueInput} save-data adapter. The synthesized
 * handler must (a) forward to the renamed original body and (b) VERIFY, so the write/read tests load
 * the rewritten class through a stub-generating loader (a bad forwarding shim would be a VerifyError).
 */
class MixinValueIoAdapterTest {

    private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String COMPOUND_TAG = "net/minecraft/nbt/CompoundTag";
    private static final String VALUE_OUTPUT = "net/minecraft/world/level/storage/ValueOutput";
    private static final String VALUE_INPUT = "net/minecraft/world/level/storage/ValueInput";

    /** Generates a trivial stub for any class the parent can't resolve (the absent MC types). */
    private static final class StubLoader extends ClassLoader {
        StubLoader() { super(MixinValueIoAdapterTest.class.getClassLoader()); }
        Class<?> define(String name, byte[] b) { return defineClass(name, b, 0, b.length); }
        @Override protected Class<?> findClass(String name) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 1);
            mv.visitEnd();
            cw.visitEnd();
            byte[] b = cw.toByteArray();
            return defineClass(name, b, 0, b.length);
        }
    }

    /** A {@code @Mixin} class with one {@code @Inject(method=selector)} handler of the given descriptor. */
    private static byte[] saveDataMixin(String selector, String handlerDesc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mixin/SaveMixin", null, "java/lang/Object", null);
        AnnotationVisitor ma = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor mav = ma.visitArray("value");
        mav.visit(null, Type.getObjectType("java/lang/Object"));
        mav.visitEnd();
        ma.visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "onSave", handlerDesc, null, null);
        AnnotationVisitor iv = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
        AnnotationVisitor arr = iv.visitArray("method");
        arr.visit(null, selector);
        arr.visitEnd();
        iv.visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);          // no-op body: exercises the forwarding shim, not the logic
        mv.visitMaxs(0, Type.getArgumentTypes(handlerDesc).length + 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode parse(byte[] b) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        return cn;
    }

    private static MethodNode method(ClassNode cn, String name, String desc) {
        return cn.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
    }

    /** Run collect+apply and re-emit with COMPUTE_FRAMES, then LOAD to prove it verifies. */
    private static ClassNode adaptAndVerify(byte[] in) throws Exception {
        ClassNode cn = parse(in);
        List<MixinValueIoAdapter.Target> targets = MixinValueIoAdapter.collect(cn);
        assertFalse(targets.isEmpty(), "a save-data CompoundTag @Inject must be identified");
        int applied = MixinValueIoAdapter.apply(cn, targets);
        assertEquals(targets.size(), applied, "every identified target must be adapted");
        com.retromod.util.SafeClassWriter cw = new com.retromod.util.SafeClassWriter(ClassWriter.COMPUTE_FRAMES);
        cn.accept(cw);
        byte[] out = cw.toByteArray();
        Class<?> loaded = new StubLoader().define("test.mixin.SaveMixin", out);
        assertNotNull(loaded, "the adapted mixin class must load and verify");
        return parse(out);
    }

    @Test
    @DisplayName("#48: a write handler (CompoundTag) is wrapped for ValueOutput and verifies")
    void writeHandlerAdapted() throws Exception {
        ClassNode out = adaptAndVerify(saveDataMixin("addAdditionalSaveData", "(L" + COMPOUND_TAG + ";" + CI + ")V"));

        // renamed helper keeps the CompoundTag signature and has no @Inject
        MethodNode helper = method(out, "onSave$retromod$vio", "(L" + COMPOUND_TAG + ";" + CI + ")V");
        assertNotNull(helper, "the original body is renamed to a private helper");
        assertNull(injectOf(helper), "the helper carries no injector annotation");

        // new handler takes ValueOutput and carries the @Inject
        MethodNode nh = method(out, "onSave", "(L" + VALUE_OUTPUT + ";" + CI + ")V");
        assertNotNull(nh, "a new ValueOutput handler is synthesized");
        assertNotNull(injectOf(nh), "the @Inject moved to the new handler");

        // body bridges through ValueIoBridge.outputTag, CHECKCASTs to CompoundTag, calls the helper
        assertTrue(hasBridgeCall(nh, "outputTag"), "write handler bridges through outputTag");
        assertTrue(hasCheckcast(nh, COMPOUND_TAG), "the bridged result is cast to CompoundTag");
        assertTrue(callsHelper(nh, "onSave$retromod$vio"), "the new handler calls the renamed body");
    }

    @Test
    @DisplayName("#48: a read handler (CompoundTag) is wrapped for ValueInput and verifies")
    void readHandlerAdapted() throws Exception {
        ClassNode out = adaptAndVerify(saveDataMixin("readAdditionalSaveData", "(L" + COMPOUND_TAG + ";" + CI + ")V"));
        assertNotNull(method(out, "onSave$retromod$vio", "(L" + COMPOUND_TAG + ";" + CI + ")V"));
        MethodNode nh = method(out, "onSave", "(L" + VALUE_INPUT + ";" + CI + ")V");
        assertNotNull(nh, "a new ValueInput handler is synthesized");
        assertTrue(hasBridgeCall(nh, "inputTag"), "read handler bridges through inputTag");
    }

    @Test
    @DisplayName("#48: a handler capturing a trailing local forwards it and still verifies")
    void extraCapturedLocalForwarded() throws Exception {
        // (CompoundTag, CallbackInfo, int local) -> forward the int at its slot
        ClassNode out = adaptAndVerify(saveDataMixin("saveAdditional", "(L" + COMPOUND_TAG + ";" + CI + "I)V"));
        assertNotNull(method(out, "onSave", "(L" + VALUE_OUTPUT + ";" + CI + "I)V"),
                "the new handler keeps the trailing captured local");
        assertNotNull(method(out, "onSave$retromod$vio", "(L" + COMPOUND_TAG + ";" + CI + "I)V"));
    }

    @Test
    @DisplayName("A handler that captures no CompoundTag is declined")
    void declineNoCompoundTag() {
        // only CallbackInfo captured -> nothing to bridge
        assertTrue(MixinValueIoAdapter.collect(parse(saveDataMixin("addAdditionalSaveData", "(" + CI + ")V"))).isEmpty());
    }

    @Test
    @DisplayName("A non-save-data target is declined")
    void declineNonSaveTarget() {
        assertTrue(MixinValueIoAdapter.collect(parse(saveDataMixin("tick", "(L" + COMPOUND_TAG + ";" + CI + ")V"))).isEmpty());
    }

    @Test
    @DisplayName("A desc-qualified method= selector's CompoundTag param is rewritten to the ValueIO type")
    void selectorDescRewritten() {
        String sel = "addAdditionalSaveData(L" + COMPOUND_TAG + ";)V";
        ClassNode cn = parse(saveDataMixin(sel, "(L" + COMPOUND_TAG + ";" + CI + ")V"));
        MixinValueIoAdapter.apply(cn, MixinValueIoAdapter.collect(cn));
        MethodNode nh = method(cn, "onSave", "(L" + VALUE_OUTPUT + ";" + CI + ")V");
        Object m = injectMethodValue(injectOf(nh));
        assertEquals("addAdditionalSaveData(L" + VALUE_OUTPUT + ";)V", ((List<?>) m).get(0),
                "the selector descriptor's CompoundTag is rewritten to ValueOutput");
    }

    @Test
    @DisplayName("The strip fallback removes the identified handler (soft-fail path)")
    void stripFallback() {
        String handlerDesc = "(L" + COMPOUND_TAG + ";" + CI + ")V";
        byte[] in = saveDataMixin("addAdditionalSaveData", handlerDesc);
        // strip keys are name+descriptor (identity-safe against overloads)
        byte[] stripped = MixinValueIoAdapter.stripTargetsFrom(in, java.util.Set.of("onSave" + handlerDesc));
        assertNull(method(parse(stripped), "onSave", handlerDesc),
                "the broken handler is stripped on the fallback path");
    }

    /** A @Mixin with N handlers, each: name, descriptor, selectors[], and an optional CompoundTag @Local slot. */
    private static byte[] mixin(HandlerSpec... handlers) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mixin/M", null, "java/lang/Object", null);
        AnnotationVisitor ma = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor mav = ma.visitArray("value");
        mav.visit(null, Type.getObjectType("java/lang/Object"));
        mav.visitEnd(); ma.visitEnd();
        for (HandlerSpec h : handlers) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, h.name, h.desc, null, null);
            AnnotationVisitor iv = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
            AnnotationVisitor arr = iv.visitArray("method");
            for (String s : h.selectors) arr.visit(null, s);
            arr.visitEnd(); iv.visitEnd();
            if (h.localParamIdx >= 0) {
                AnnotationVisitor pa = mv.visitParameterAnnotation(h.localParamIdx,
                        "Lcom/llamalad7/mixinextras/sugar/Local;", false);
                pa.visitEnd();
            }
            mv.visitCode(); mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, Type.getArgumentTypes(h.desc).length + 1); mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private record HandlerSpec(String name, String desc, String[] selectors, int localParamIdx) {}

    @Test
    @DisplayName("A handler shared across a write AND a read save target is declined by collect but stripped as unrepairable")
    void dualDirectionHandlerNotAdaptedButStripped() {
        byte[] in = mixin(new HandlerSpec("onIo", "(L" + COMPOUND_TAG + ";" + CI + ")V",
                new String[]{"addAdditionalSaveData", "readAdditionalSaveData"}, -1));
        ClassNode cn = parse(in);
        var targets = MixinValueIoAdapter.collect(cn);
        assertTrue(targets.isEmpty(), "a dual-direction (write+read) handler must NOT be adapted (its one "
                + "CompoundTag param cannot become both ValueOutput and ValueInput)");
        var unrepairable = MixinValueIoAdapter.collectUnrepairable(cn, targets);
        assertEquals(1, unrepairable.size(), "it is definitionally broken on a ValueIO host, so it is stripped");
    }

    @Test
    @DisplayName("A modern handler (ValueOutput param0) that captures a CompoundTag @Local is NOT stripped")
    void modernHandlerWithCompoundTagLocalNotStripped() {
        // h(ValueOutput out, CallbackInfo ci, @Local CompoundTag scratch) - valid on a ValueIO host.
        byte[] in = mixin(new HandlerSpec("onSave", "(L" + VALUE_OUTPUT + ";" + CI + "L" + COMPOUND_TAG + ";)V",
                new String[]{"addAdditionalSaveData"}, 2));
        ClassNode cn = parse(in);
        var targets = MixinValueIoAdapter.collect(cn);
        assertTrue(targets.isEmpty(), "param0 is ValueOutput, not CompoundTag: nothing to adapt");
        var unrepairable = MixinValueIoAdapter.collectUnrepairable(cn, targets);
        assertTrue(unrepairable.isEmpty(), "the CompoundTag is an @Local AFTER the CallbackInfo, not a stale "
                + "target capture; the handler is valid and must not be stripped");
    }

    @Test
    @DisplayName("The strip fallback (name+desc keyed) leaves an unrelated overload with the same name intact")
    void stripFallbackKeepsUnrelatedOverload() {
        String saveDesc = "(L" + COMPOUND_TAG + ";" + CI + ")V";
        String tickDesc = "(D" + CI + ")V";
        byte[] in = mixin(
                new HandlerSpec("onSave", saveDesc, new String[]{"addAdditionalSaveData"}, -1),
                new HandlerSpec("onSave", tickDesc, new String[]{"tick"}, -1)); // unrelated overload, same name
        byte[] stripped = MixinValueIoAdapter.stripTargetsFrom(in, java.util.Set.of("onSave" + saveDesc));
        assertNull(method(parse(stripped), "onSave", saveDesc), "the save-data handler is stripped");
        assertNotNull(method(parse(stripped), "onSave", tickDesc),
                "the unrelated same-named overload must survive (name+desc keyed strip)");
    }

    @Test
    @DisplayName("The ValueIO gate is host >= 1.21.5 (when the refactor landed)")
    void versionGate() {
        assertTrue(com.retromod.core.RetromodVersion.compareMcVersions("26.2", "1.21.5") >= 0, "26.x has ValueIO");
        assertTrue(com.retromod.core.RetromodVersion.compareMcVersions("1.21.11", "1.21.5") >= 0, "1.21.11 has ValueIO");
        assertTrue(com.retromod.core.RetromodVersion.compareMcVersions("1.21.1", "1.21.5") < 0, "1.21.1 does not");
        assertTrue(com.retromod.core.RetromodVersion.compareMcVersions("unknown", "1.21.5") < 0, "unknown declines");
    }

    @Test
    @DisplayName("adaptValueIoHandlers post-remap entry point: adapts on a 26.x host, no-ops below 1.21.5")
    void adaptEntryPointGated() {
        String saved = RetromodVersion.TARGET_MC_VERSION;
        try {
            MixinCompatibilityTransformer mt = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());
            byte[] in = saveDataMixin("addAdditionalSaveData", "(L" + COMPOUND_TAG + ";" + CI + ")V");

            RetromodVersion.TARGET_MC_VERSION = "1.21.1";               // pre-ValueIO host
            assertSame(in, mt.adaptValueIoHandlers(in), "below 1.21.5 the pass is a no-op (input returned)");

            RetromodVersion.TARGET_MC_VERSION = "26.2";                 // ValueIO host
            ClassNode out = parse(mt.adaptValueIoHandlers(in));
            assertNotNull(method(out, "onSave", "(L" + VALUE_OUTPUT + ";" + CI + ")V"),
                    "the entry point adapts the handler on 26.x");
            assertNotNull(method(out, "onSave$retromod$vio", "(L" + COMPOUND_TAG + ";" + CI + ")V"));

            ClassNode pipeline = parse(mt.applyPostRemapRepairs(in));
            assertNotNull(method(pipeline, "onSave", "(L" + VALUE_OUTPUT + ";" + CI + ")V"),
                    "the shared post-remap pipeline must include the ValueIO adapter");
        } finally {
            RetromodVersion.TARGET_MC_VERSION = saved;
        }
    }

    @Test
    @DisplayName("#48 review fix: adapting a save-data handler self-registers the ValueIoBridge synthetic")
    void adapterSelfRegistersBridge() {
        // Regression for the confirmed HIGH finding: the Forge entry point never registered the
        // bridge, so its per-mod embed was skipped -> NoClassDefFoundError at save/load. Registration
        // is now coupled to the adaptation itself, so no loader path can miss it.
        String saved = RetromodVersion.TARGET_MC_VERSION;
        try {
            RetromodTransformer t = RetromodTransformer.getInstance();
            RetromodVersion.TARGET_MC_VERSION = "26.2";
            MixinCompatibilityTransformer mt = new MixinCompatibilityTransformer(t);
            mt.adaptValueIoHandlers(saveDataMixin("addAdditionalSaveData", "(L" + COMPOUND_TAG + ";" + CI + ")V"));
            assertTrue(t.getSyntheticClasses().containsKey("com/retromod/mixin/runtime/ValueIoBridge"),
                    "adapting must register ValueIoBridge so the Forge/NeoForge embedder relocates it");
        } finally {
            RetromodVersion.TARGET_MC_VERSION = saved;
        }
    }

    @Test
    @DisplayName("Repair-or-strip: an unrepairable save-data CompoundTag handler is stripped, an adaptable one wrapped")
    void repairOrStrip() {
        String saved = RetromodVersion.TARGET_MC_VERSION;
        try {
            RetromodVersion.TARGET_MC_VERSION = "26.2";
            MixinCompatibilityTransformer mt = new MixinCompatibilityTransformer(RetromodTransformer.getInstance());

            // one class with BOTH: an adaptable handler and an unrepairable one (2 captured params
            // before CallbackInfo: not the strict shape, but still definitionally broken)
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mixin/RepairOrStrip", null, "java/lang/Object", null);
            AnnotationVisitor ma = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
            AnnotationVisitor mav = ma.visitArray("value");
            mav.visit(null, Type.getObjectType("java/lang/Object"));
            mav.visitEnd();
            ma.visitEnd();
            for (String[] h : new String[][]{
                    {"good", "(L" + COMPOUND_TAG + ";" + CI + ")V"},
                    {"bad", "(L" + COMPOUND_TAG + ";Ljava/lang/String;" + CI + ")V"}}) {
                MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, h[0], h[1], null, null);
                AnnotationVisitor iv = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
                AnnotationVisitor arr = iv.visitArray("method");
                arr.visit(null, "addAdditionalSaveData");
                arr.visitEnd();
                iv.visitEnd();
                mv.visitCode();
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, Type.getArgumentTypes(h[1]).length + 1);
                mv.visitEnd();
            }
            cw.visitEnd();

            ClassNode out = parse(mt.adaptValueIoHandlers(cw.toByteArray()));
            assertNotNull(method(out, "good", "(L" + VALUE_OUTPUT + ";" + CI + ")V"),
                    "the strict-shape handler is ADAPTED (repair)");
            assertNotNull(method(out, "good$retromod$vio", "(L" + COMPOUND_TAG + ";" + CI + ")V"));
            assertTrue(out.methods.stream().noneMatch(m -> m.name.equals("bad")),
                    "the unrepairable handler is STRIPPED (soft-fail), never left broken");
        } finally {
            RetromodVersion.TARGET_MC_VERSION = saved;
        }
    }

    // ---- assertion helpers ----

    private static AnnotationNode injectOf(MethodNode m) {
        for (List<AnnotationNode> anns : List.of(
                m.visibleAnnotations != null ? m.visibleAnnotations : List.<AnnotationNode>of(),
                m.invisibleAnnotations != null ? m.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode a : anns) if ("Lorg/spongepowered/asm/mixin/injection/Inject;".equals(a.desc)) return a;
        }
        return null;
    }

    private static Object injectMethodValue(AnnotationNode inject) {
        for (int i = 0; inject.values != null && i + 1 < inject.values.size(); i += 2) {
            if ("method".equals(inject.values.get(i))) return inject.values.get(i + 1);
        }
        return null;
    }

    private static boolean hasBridgeCall(MethodNode m, String bridgeMethod) {
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof MethodInsnNode mi
                    && "com/retromod/mixin/runtime/ValueIoBridge".equals(mi.owner)
                    && bridgeMethod.equals(mi.name)) return true;
        }
        return false;
    }

    private static boolean hasCheckcast(MethodNode m, String type) {
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.CHECKCAST && type.equals(ti.desc)) return true;
        }
        return false;
    }

    private static boolean callsHelper(MethodNode m, String helperName) {
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof MethodInsnNode mi && helperName.equals(mi.name)) return true;
        }
        return false;
    }
}
