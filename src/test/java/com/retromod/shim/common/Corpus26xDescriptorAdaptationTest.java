/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Corpus-mined 26.x DESCRIPTOR adaptations (top-50 Fabric+NeoForge 1.21.1 linkcheck audit): vanilla
 * methods that still exist on 26.1 but changed a primitive type or lost their static form, wired via
 * {@link RetromodTransformer#registerConvertingRedirect} / {@code registerSingletonStaticRedirect} /
 * {@code registerArgDropMethodRedirect}. Drives the real shim registration and asserts each call site
 * is both retargeted AND gets the right conversion/POP spliced in.
 */
public class Corpus26xDescriptorAdaptationTest {

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Common_1_21_11_to_26_1_ClassMoves.registerCorpus26xDescriptorAdaptations(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    /** Build test/Caller with a single provided body, transform it, and return the method's insns. */
    private List<AbstractInsnNode> transformBody(String methodDesc, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, ACC_PUBLIC, "test/Caller", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "call", methodDesc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = transformer.transformClass(cw.toByteArray(), "test/Caller");
        // Re-readable => COMPUTE_FRAMES in the transformer succeeded (else it would have thrown / fallen back).
        ClassNode cn = new ClassNode();
        assertDoesNotThrow(() -> new ClassReader(out).accept(cn, 0));
        List<AbstractInsnNode> insns = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (!m.name.equals("call")) continue;
            for (AbstractInsnNode i : m.instructions.toArray()) insns.add(i);
        }
        return insns;
    }

    private static MethodInsnNode firstCall(List<AbstractInsnNode> insns, String owner) {
        for (AbstractInsnNode i : insns) if (i instanceof MethodInsnNode mi && mi.owner.equals(owner)) return mi;
        return null;
    }

    private static boolean hasOpcodeBefore(List<AbstractInsnNode> insns, int opcode, MethodInsnNode call) {
        int ci = insns.indexOf(call);
        for (int i = 0; i < ci; i++) if (insns.get(i).getOpcode() == opcode) return true;
        return false;
    }

    private static boolean hasOpcodeAfter(List<AbstractInsnNode> insns, int opcode, MethodInsnNode call) {
        int ci = insns.indexOf(call);
        for (int i = ci + 1; i < insns.size(); i++) if (insns.get(i).getOpcode() == opcode) return true;
        return false;
    }

    @Test
    @DisplayName("Mth.cos(F)F -> cos(D)F with an F2D on the arg")
    void mthCosArgWiden() {
        // static float call(float f) { return Mth.cos(f); }  (we drop the return for simplicity)
        List<AbstractInsnNode> insns = transformBody("(F)V", mv -> {
            mv.visitVarInsn(FLOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/util/Mth", "cos", "(F)F", false);
            mv.visitInsn(POP);
        });
        MethodInsnNode c = firstCall(insns, "net/minecraft/util/Mth");
        assertNotNull(c, "the Mth.cos call must survive");
        assertEquals("(D)F", c.desc, "cos retargeted to the double overload");
        assertTrue(hasOpcodeBefore(insns, F2D, c), "an F2D must be spliced in before the call");
    }

    @Test
    @DisplayName("Window.getGuiScale()D -> ()I with an I2D on the result")
    void windowGuiScaleReturnWiden() {
        List<AbstractInsnNode> insns = transformBody("(Lcom/mojang/blaze3d/platform/Window;)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "com/mojang/blaze3d/platform/Window", "getGuiScale", "()D", false);
            mv.visitInsn(POP2); // double -> the caller's expected 2-slot
        });
        MethodInsnNode c = firstCall(insns, "com/mojang/blaze3d/platform/Window");
        assertNotNull(c);
        assertEquals("()I", c.desc, "getGuiScale retargeted to the int form");
        assertTrue(hasOpcodeAfter(insns, I2D, c), "an I2D must be spliced in after the call");
    }

    @Test
    @DisplayName("SoundManager.play(SoundInstance)V -> ()PlayResult with a trailing POP")
    void soundPlayResultPop() {
        List<AbstractInsnNode> insns = transformBody("(Lnet/minecraft/client/sounds/SoundManager;Lnet/minecraft/client/resources/sounds/SoundInstance;)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/sounds/SoundManager", "play",
                    "(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", false);
        });
        MethodInsnNode c = firstCall(insns, "net/minecraft/client/sounds/SoundManager");
        assertNotNull(c);
        assertTrue(c.desc.endsWith(")Lnet/minecraft/client/sounds/SoundEngine$PlayResult;"),
                "play retargeted to the PlayResult-returning form");
        assertTrue(hasOpcodeAfter(insns, POP, c), "a POP must discard the now-returned PlayResult");
    }

    @Test
    @DisplayName("Vec3.<init>(Vector3f) widened to <init>(Vector3fc), INVOKESPECIAL kept, no cast")
    void vec3JomlCtorWiden() {
        // static void call(Vector3f v) { new Vec3(v); } -- the joml concrete->interface widening.
        List<AbstractInsnNode> insns = transformBody("(Lorg/joml/Vector3f;)V", mv -> {
            mv.visitTypeInsn(NEW, "net/minecraft/world/phys/Vec3");
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/world/phys/Vec3", "<init>",
                    "(Lorg/joml/Vector3f;)V", false);
            mv.visitInsn(POP); // discard the constructed Vec3
        });
        MethodInsnNode c = firstCall(insns, "net/minecraft/world/phys/Vec3");
        assertNotNull(c, "the Vec3 ctor call must survive");
        assertEquals("<init>", c.name, "still a constructor");
        assertEquals(INVOKESPECIAL, c.getOpcode(), "constructor invocation opcode preserved");
        assertEquals("(Lorg/joml/Vector3fc;)V", c.desc, "arg widened to the Vector3fc interface");
        // A Vector3f IS-A Vector3fc, so the upcast needs no checkcast/conversion insn.
        assertFalse(hasOpcodeBefore(insns, CHECKCAST, c), "no checkcast needed for the upcast");
    }

    @Test
    @DisplayName("PoseStack.mulPose(Quaternionf) widens to (Quaternionfc), no cast")
    void poseStackMulPoseWiden() {
        List<AbstractInsnNode> insns = transformBody(
                "(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Quaternionf;)V", mv -> {
            mv.visitVarInsn(ALOAD, 0); // PoseStack receiver
            mv.visitVarInsn(ALOAD, 1); // Quaternionf arg
            mv.visitMethodInsn(INVOKEVIRTUAL, "com/mojang/blaze3d/vertex/PoseStack", "mulPose",
                    "(Lorg/joml/Quaternionf;)V", false);
        });
        MethodInsnNode c = firstCall(insns, "com/mojang/blaze3d/vertex/PoseStack");
        assertNotNull(c, "the mulPose call survives");
        assertEquals("(Lorg/joml/Quaternionfc;)V", c.desc, "arg widened to the Quaternionfc interface");
        assertFalse(hasOpcodeBefore(insns, CHECKCAST, c), "no checkcast needed for the upcast");
    }

    @Test
    @DisplayName("Vec3::new method reference (invokedynamic handle) also widens to Vector3fc")
    void vec3JomlCtorReferenceWiden() {
        // The codec form `VECTOR3F.map(Vec3::new, Vec3::toVector3f)` (jade EntityAccessorImpl):
        // Vec3::new compiles to an invokedynamic whose impl handle is H_NEWINVOKESPECIAL
        // Vec3.<init>(Vector3f)V. The direct-call redirect never sees it, so the handle must be
        // widened separately or it dies NoSuchMethodError at <clinit>.
        org.objectweb.asm.Handle metafactory = new org.objectweb.asm.Handle(H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
                        + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        org.objectweb.asm.Handle vec3New = new org.objectweb.asm.Handle(H_NEWINVOKESPECIAL,
                "net/minecraft/world/phys/Vec3", "<init>", "(Lorg/joml/Vector3f;)V", false);
        List<AbstractInsnNode> insns = transformBody("()V", mv -> {
            mv.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", metafactory,
                    org.objectweb.asm.Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                    vec3New,
                    org.objectweb.asm.Type.getMethodType("(Lorg/joml/Vector3f;)Lnet/minecraft/world/phys/Vec3;"));
            mv.visitInsn(POP); // discard the produced Function
        });
        org.objectweb.asm.tree.InvokeDynamicInsnNode indy = null;
        for (AbstractInsnNode i : insns) {
            if (i instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode idn) { indy = idn; break; }
        }
        assertNotNull(indy, "the invokedynamic survives the transform");
        org.objectweb.asm.Handle impl = (org.objectweb.asm.Handle) indy.bsmArgs[1];
        assertEquals("<init>", impl.getName(), "still a constructor reference");
        assertEquals(H_NEWINVOKESPECIAL, impl.getTag(), "tag stays H_NEWINVOKESPECIAL");
        assertEquals("net/minecraft/world/phys/Vec3", impl.getOwner(), "owner unchanged");
        assertEquals("(Lorg/joml/Vector3fc;)V", impl.getDesc(),
                "the Vec3::new handle's arg widened to the Vector3fc interface");
    }

    @Test
    @DisplayName("new KeyMapping(String,Type,int,String) -> RetroKeyMapping.create factory + CHECKCAST")
    void keyMappingCtorBridge() {
        // A keybind: new KeyMapping(name, type, code, categoryString). The category String became a
        // KeyMapping.Category in 26.x, so the ctor is redirected to the reflective factory.
        List<AbstractInsnNode> insns = transformBody(
                "(Lcom/mojang/blaze3d/platform/InputConstants$Type;)V", mv -> {
            mv.visitTypeInsn(NEW, "net/minecraft/client/KeyMapping");
            mv.visitInsn(DUP);
            mv.visitLdcInsn("key.jade.showoverlay");
            mv.visitVarInsn(ALOAD, 0);                    // InputConstants$Type
            mv.visitIntInsn(BIPUSH, 72);                  // key code
            mv.visitLdcInsn("key.categories.misc");       // category as a String
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/client/KeyMapping", "<init>",
                    "(Ljava/lang/String;Lcom/mojang/blaze3d/platform/InputConstants$Type;ILjava/lang/String;)V",
                    false);
            mv.visitInsn(POP); // discard the constructed KeyMapping
        });
        MethodInsnNode factory = firstCall(insns, "com/retromod/polyfill/minecraft/RetroKeyMapping");
        assertNotNull(factory, "the KeyMapping ctor is redirected to the RetroKeyMapping factory");
        assertEquals("create", factory.name);
        assertEquals(INVOKESTATIC, factory.getOpcode(), "constructor-to-factory is a static call");
        // The factory returns Object, so a CHECKCAST to KeyMapping is appended.
        boolean checkcast = insns.stream().anyMatch(i -> i instanceof TypeInsnNode ti
                && ti.getOpcode() == CHECKCAST && ti.desc.equals("net/minecraft/client/KeyMapping"));
        assertTrue(checkcast, "a CHECKCAST to KeyMapping is appended after the Object-returning factory");
        // The NEW of KeyMapping is gone (the ctor-to-factory rewrite removed it).
        boolean newKeyMapping = insns.stream().anyMatch(i -> i instanceof TypeInsnNode ti
                && ti.getOpcode() == NEW && ti.desc.equals("net/minecraft/client/KeyMapping"));
        assertFalse(newKeyMapping, "the NEW KeyMapping is removed by the ctor->factory rewrite");
    }

    @Test
    @DisplayName("SimpleJsonResourceReloadListener(Gson,String) subclass is rebased onto the synthetic")
    void simpleJsonReloadListenerRebase() {
        // A mod class that extends the removed Gson-based listener and calls super(gson, "dir").
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/MyThemeLoader", null,
                "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ACONST_NULL);          // the Gson (irrelevant to the rebase)
        mv.visitLdcInsn("my_dir");
        mv.visitMethodInsn(INVOKESPECIAL,
                "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener", "<init>",
                "(Lcom/google/gson/Gson;Ljava/lang/String;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = transformer.transformClass(cw.toByteArray(), "test/MyThemeLoader");
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        assertEquals("com/retromod/polyfill/minecraft/RetroSimpleJsonReloadListener", cn.superName,
                "the extends clause is rebased onto the synthesized superclass");
        boolean superCtorRewritten = cn.methods.stream()
                .flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .anyMatch(i -> i instanceof MethodInsnNode mi && mi.getOpcode() == INVOKESPECIAL
                        && mi.name.equals("<init>")
                        && mi.owner.equals("com/retromod/polyfill/minecraft/RetroSimpleJsonReloadListener")
                        && mi.desc.equals("(Lcom/google/gson/Gson;Ljava/lang/String;)V"));
        assertTrue(superCtorRewritten, "the super(gson, dir) call is rewritten to the synthetic's ctor");
        // The synthesized superclass (and its reflective scan helper) are registered as synthetics.
        assertTrue(transformer.getSyntheticClasses()
                .containsKey("com/retromod/polyfill/minecraft/RetroSimpleJsonReloadListener"),
                "the synthesized superclass is registered for embedding");
    }

    @Test
    @DisplayName("RetroReloadScan is fail-safe (no Minecraft -> empty map, never throws)")
    void retroReloadScanFailSafe() {
        Object result = com.retromod.polyfill.minecraft.RetroReloadScan.scan(null, "any_dir", null);
        assertTrue(result instanceof java.util.Map, "always returns a Map");
        assertTrue(((java.util.Map<?, ?>) result).isEmpty(), "no Minecraft on the test classpath -> empty");
    }

    @Test
    @DisplayName("RetroKeyMapping factory is fail-safe (no Minecraft -> null, never throws)")
    void retroKeyMappingFailSafe() {
        // With no Minecraft on the test classpath, the reflective construction can't succeed; the
        // factory returns null (the redirect's CHECKCAST null passes) instead of throwing.
        assertNull(com.retromod.polyfill.minecraft.RetroKeyMapping.create(
                "key.test", null, 72, "key.categories.misc"));
        assertNull(com.retromod.polyfill.minecraft.RetroKeyMapping.createDefault(
                "key.test", 72, "some.mod.category"));
    }

    @Test
    @DisplayName("Util.backgroundExecutor() retargets to TracingExecutor + appends .service() unwrap")
    void utilExecutorReturnUnwrap() {
        // 26.x: Util.backgroundExecutor() returns TracingExecutor (not ExecutorService); unwrap it.
        List<AbstractInsnNode> insns = transformBody("()V", mv -> {
            mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/util/Util", "backgroundExecutor",
                    "()Ljava/util/concurrent/ExecutorService;", false);
            mv.visitInsn(POP); // discard the ExecutorService the caller would use
        });
        MethodInsnNode call = firstCall(insns, "net/minecraft/util/Util");
        assertNotNull(call, "the Util.backgroundExecutor call survives");
        assertEquals("backgroundExecutor", call.name);
        assertEquals("()Lnet/minecraft/TracingExecutor;", call.desc, "retargeted to the TracingExecutor form");
        MethodInsnNode unwrap = firstCall(insns, "net/minecraft/TracingExecutor");
        assertNotNull(unwrap, "a .service() unwrap call is appended");
        assertEquals("service", unwrap.name);
        assertEquals("()Ljava/util/concurrent/ExecutorService;", unwrap.desc, "unwrap yields ExecutorService");
        assertEquals(INVOKEVIRTUAL, unwrap.getOpcode(), "unwrap is an instance call on the wrapper");
        assertTrue(insns.indexOf(call) < insns.indexOf(unwrap), "unwrap comes AFTER the retargeted call");
    }

    @Test
    @DisplayName("CompoundTag.getList(String,int) -> getListOrEmpty(String) dropping the int")
    void compoundTagGetListArgDrop() {
        List<AbstractInsnNode> insns = transformBody("(Lnet/minecraft/nbt/CompoundTag;Ljava/lang/String;)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitInsn(ICONST_5); // the type-hint int (e.g. 10 = compound)
            mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/nbt/CompoundTag", "getList",
                    "(Ljava/lang/String;I)Lnet/minecraft/nbt/ListTag;", false);
            mv.visitInsn(POP);
        });
        MethodInsnNode c = firstCall(insns, "net/minecraft/nbt/CompoundTag");
        assertNotNull(c);
        assertEquals("getListOrEmpty", c.name, "renamed to the type-hint-free getter");
        assertEquals("(Ljava/lang/String;)Lnet/minecraft/nbt/ListTag;", c.desc, "the int param is dropped");
        assertTrue(hasOpcodeBefore(insns, POP, c), "the trailing int must be popped before the call");
    }

    @Test
    @DisplayName("NBT 1.21.5 refactor: contains(String,int)->contains(String) (drop int)")
    void nbtContainsArgDrop() {
        List<AbstractInsnNode> insns = transformBody("(Lnet/minecraft/nbt/CompoundTag;Ljava/lang/String;)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitInsn(ICONST_3); // the tag-type-hint int
            mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/nbt/CompoundTag", "contains",
                    "(Ljava/lang/String;I)Z", false);
            mv.visitInsn(POP);
        });
        MethodInsnNode c = firstCall(insns, "net/minecraft/nbt/CompoundTag");
        assertNotNull(c);
        assertEquals("(Ljava/lang/String;)Z", c.desc, "the type-hint int is dropped");
        assertTrue(hasOpcodeBefore(insns, POP, c), "the trailing int is popped before the call");
    }

    @Test
    @DisplayName("NBT: getCompound->getCompoundOrEmpty (CompoundTag + ListTag); remove()V->remove()Tag+POP")
    void nbtGetterRenamesAndRemove() {
        // CompoundTag.getCompound(String)CompoundTag -> getCompoundOrEmpty
        List<AbstractInsnNode> a = transformBody("(Lnet/minecraft/nbt/CompoundTag;Ljava/lang/String;)V", mv -> {
            mv.visitVarInsn(ALOAD, 0); mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/nbt/CompoundTag", "getCompound",
                    "(Ljava/lang/String;)Lnet/minecraft/nbt/CompoundTag;", false);
            mv.visitInsn(POP);
        });
        assertEquals("getCompoundOrEmpty", firstCall(a, "net/minecraft/nbt/CompoundTag").name);

        // ListTag.getCompound(int)CompoundTag -> getCompoundOrEmpty
        List<AbstractInsnNode> b = transformBody("(Lnet/minecraft/nbt/ListTag;)V", mv -> {
            mv.visitVarInsn(ALOAD, 0); mv.visitInsn(ICONST_0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/nbt/ListTag", "getCompound",
                    "(I)Lnet/minecraft/nbt/CompoundTag;", false);
            mv.visitInsn(POP);
        });
        assertEquals("getCompoundOrEmpty", firstCall(b, "net/minecraft/nbt/ListTag").name);

        // CompoundTag.remove(String)V -> remove(String)Tag + POP
        List<AbstractInsnNode> r = transformBody("(Lnet/minecraft/nbt/CompoundTag;Ljava/lang/String;)V", mv -> {
            mv.visitVarInsn(ALOAD, 0); mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/nbt/CompoundTag", "remove",
                    "(Ljava/lang/String;)V", false);
        });
        MethodInsnNode rc = firstCall(r, "net/minecraft/nbt/CompoundTag");
        assertTrue(rc.desc.endsWith(")Lnet/minecraft/nbt/Tag;"), "remove now returns Tag");
        assertTrue(hasOpcodeAfter(r, POP, rc), "the returned Tag is popped (old call was void)");
    }

    @Test
    @DisplayName("NBT: TagParser.parseTag -> parseCompoundFully (rename)")
    void nbtParseTagRename() {
        List<AbstractInsnNode> insns = transformBody("(Ljava/lang/String;)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/nbt/TagParser", "parseTag",
                    "(Ljava/lang/String;)Lnet/minecraft/nbt/CompoundTag;", false);
            mv.visitInsn(POP);
        });
        assertEquals("parseCompoundFully", firstCall(insns, "net/minecraft/nbt/TagParser").name);
    }

    @Test
    @DisplayName("new ClickEvent(Action,String) -> RetroTextEvents.clickEvent factory + CHECKCAST (ctor bridge)")
    void clickEventCtorBridged() {
        List<AbstractInsnNode> insns = transformBody("()V", mv -> {
            mv.visitTypeInsn(NEW, "net/minecraft/network/chat/ClickEvent");
            mv.visitInsn(DUP);
            mv.visitInsn(ACONST_NULL);                 // the Action (type-only for the test)
            mv.visitLdcInsn("hello");                  // the legacy String value
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/network/chat/ClickEvent", "<init>",
                    "(Lnet/minecraft/network/chat/ClickEvent$Action;Ljava/lang/String;)V", false);
            mv.visitInsn(POP);
        });
        // the direct constructor call is gone
        assertFalse(insns.stream().anyMatch(i -> i instanceof MethodInsnNode mi
                && mi.name.equals("<init>") && mi.owner.equals("net/minecraft/network/chat/ClickEvent")),
                "the removed ClickEvent constructor must not be called");
        MethodInsnNode factory = insns.stream().filter(i -> i instanceof MethodInsnNode mi
                && mi.owner.equals("com/retromod/polyfill/minecraft/RetroTextEvents")).map(i -> (MethodInsnNode) i)
                .findFirst().orElse(null);
        assertNotNull(factory, "rewritten to the RetroTextEvents factory");
        assertEquals("clickEvent", factory.name);
        assertEquals(INVOKESTATIC, factory.getOpcode());
        assertTrue(insns.stream().anyMatch(i -> i instanceof TypeInsnNode ti
                && ti.getOpcode() == CHECKCAST && ti.desc.equals("net/minecraft/network/chat/ClickEvent")),
                "the Object-returning factory result is CHECKCAST back to ClickEvent");
    }

    @Test
    @DisplayName("RetroTextEvents factory fails safe (null) on an unmappable/absent action")
    void textEventFactoryFailSafe() {
        // Non-enum action, null action, and a legacy CUSTOM-like action all yield null (inert), never throw.
        assertNull(com.retromod.polyfill.minecraft.RetroTextEvents.clickEvent(null, "x"));
        assertNull(com.retromod.polyfill.minecraft.RetroTextEvents.clickEvent("not-an-enum", "x"));
        assertNull(com.retromod.polyfill.minecraft.RetroTextEvents.clickEvent(java.time.DayOfWeek.MONDAY, "x"),
                "an enum whose name isn't a ClickEvent action maps to null, not a crash");
        assertNull(com.retromod.polyfill.minecraft.RetroTextEvents.hoverEvent(null, new Object()));
    }

    @Test
    @DisplayName("Screen.hasControlDown/Shift/Alt (static) -> Minecraft.getInstance().hasX() (instance)")
    void screenModifierKeysSingleton() {
        for (String m : new String[]{"hasControlDown", "hasShiftDown", "hasAltDown"}) {
            List<AbstractInsnNode> insns = transformBody("()V", mv -> {
                mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/client/gui/screens/Screen", m, "()Z", false);
                mv.visitInsn(POP);
            });
            // No more static call on Screen.
            assertNull(firstCall(insns, "net/minecraft/client/gui/screens/Screen"),
                    m + ": the static Screen call must be gone");
            List<MethodInsnNode> mc = new ArrayList<>();
            for (AbstractInsnNode i : insns)
                if (i instanceof MethodInsnNode mi && mi.owner.equals("net/minecraft/client/Minecraft")) mc.add(mi);
            assertEquals(2, mc.size(), m + ": expect getInstance() + the instance call");
            assertEquals("getInstance", mc.get(0).name);
            assertEquals(INVOKESTATIC, mc.get(0).getOpcode());
            assertEquals(m, mc.get(1).name);
            assertEquals(INVOKEVIRTUAL, mc.get(1).getOpcode());
        }
    }
}
