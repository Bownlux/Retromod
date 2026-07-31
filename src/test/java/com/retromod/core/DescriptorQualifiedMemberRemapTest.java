package com.retromod.core;

import org.junit.jupiter.api.*;
import org.objectweb.asm.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the descriptor-qualified fallback in the intermediary member-name
 * remap (Retromod task #91, from the Sinytra Connector analysis, backlog item #8).
 *
 * <p>The flat name-only maps in {@link RetromodTransformer} are last-writer-wins for any
 * intermediary short-name the offline harvest could not make globally unique. That silently
 * mis-renames one of the colliding call sites. These tests register two entries sharing a
 * short-name but differing by descriptor and assert each call site resolves to its own target
 * (not last-writer-wins), while unambiguous names still take the fast name-only path unchanged.
 */
public class DescriptorQualifiedMemberRemapTest {

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Ambiguous intermediary method name resolves per descriptor, not last-writer-wins")
    void testAmbiguousMethodResolvesByDescriptor() {
        // Same short-name method_9999, two distinct descriptors -> two distinct Mojang names.
        String voidDesc = "()V";
        String intDesc = "(I)Z";
        transformer.registerIntermediaryMemberMapping("method_9999", voidDesc, "tick", false);
        transformer.registerIntermediaryMemberMapping("method_9999", intDesc, "isReady", false);

        byte[] original = createTwoCallClass(
                "test/desc/Caller",
                "method_9999", voidDesc,
                "method_9999", intDesc);
        byte[] transformed = transformer.transformClass(original, "test/desc/Caller");

        Map<String, String> callsByDesc = methodCallNamesByDescriptor(transformed);

        // Each call site must land on the name matching ITS descriptor, not a single winner.
        assertEquals("tick", callsByDesc.get(voidDesc),
                "()V call site should remap to tick");
        assertEquals("isReady", callsByDesc.get(intDesc),
                "(I)Z call site should remap to isReady");

        // The intermediary short-name must not survive on either site.
        assertFalse(callsByDesc.containsValue("method_9999"),
                "no call site should keep the intermediary short-name");
    }

    @Test
    @DisplayName("Unambiguous intermediary method name still remaps by name only (unchanged behavior)")
    void testUnambiguousMethodStillRemapsByName() {
        // method_1111 is unambiguous (flat legacy API). Register an UNRELATED ambiguous member
        // via the new by-descriptor API too, so the by-desc/ambiguous maps are non-empty and we
        // prove the fast name-only path still fires for method_1111 alongside them.
        Map<String, String> methods = new HashMap<>();
        methods.put("method_1111", "getName");
        transformer.registerIntermediaryNameMappings(methods, Collections.emptyMap());
        transformer.registerIntermediaryMemberMapping("method_7777", "()V", "start", false);
        transformer.registerIntermediaryMemberMapping("method_7777", "(I)V", "stop", false);

        byte[] original = createSingleCallClass(
                "test/desc/CallerU", "method_1111", "()Ljava/lang/String;");
        byte[] transformed = transformer.transformClass(original, "test/desc/CallerU");

        Map<String, String> callsByDesc = methodCallNamesByDescriptor(transformed);
        assertEquals("getName", callsByDesc.get("()Ljava/lang/String;"),
                "unambiguous name must remap on the fast name-only path");
    }

    @Test
    @DisplayName("A descriptor with no harvested variant falls through to the flat map")
    void testUnknownDescriptorFallsBackToFlatMap() {
        // method_2222 is ambiguous, but the caller uses a THIRD descriptor we never harvested.
        // The fallback map has no entry for it, so we must fall through to the flat map rather
        // than dropping the rename entirely.
        transformer.registerIntermediaryMemberMapping("method_2222", "()V", "start", false);
        transformer.registerIntermediaryMemberMapping("method_2222", "(I)V", "stop", false);

        byte[] original = createSingleCallClass(
                "test/desc/CallerF", "method_2222", "(Z)V");
        byte[] transformed = transformer.transformClass(original, "test/desc/CallerF");

        Map<String, String> callsByDesc = methodCallNamesByDescriptor(transformed);
        // The flat map is FIRST-writer-wins (registerIntermediaryMemberMapping uses
        // putIfAbsent), so method_2222's flat entry holds "start" (registered first). The
        // point is the unharvested descriptor still falls back to the flat rename rather
        // than keeping the raw intermediary name or throwing.
        assertEquals("start", callsByDesc.get("(Z)V"),
                "unknown descriptor falls back to the first-writer-wins flat rename");
    }

    @Test
    @DisplayName("Ambiguous intermediary field name resolves per descriptor")
    void testAmbiguousFieldResolvesByDescriptor() {
        String intType = "I";
        String boolType = "Z";
        transformer.registerIntermediaryMemberMapping("field_8888", intType, "count", true);
        transformer.registerIntermediaryMemberMapping("field_8888", boolType, "flag", true);

        byte[] original = createTwoFieldAccessClass(
                "test/desc/FieldCaller",
                "field_8888", intType,
                "field_8888", boolType);
        byte[] transformed = transformer.transformClass(original, "test/desc/FieldCaller");

        Map<String, String> fieldsByDesc = fieldAccessNamesByDescriptor(transformed);
        assertEquals("count", fieldsByDesc.get(intType),
                "I field access should remap to count");
        assertEquals("flag", fieldsByDesc.get(boolType),
                "Z field access should remap to flag");
    }

    // Bytecode builders

    /** Class whose one method makes two INVOKESTATIC calls (same owner, given name/desc pairs). */
    private byte[] createTwoCallClass(String className,
                                      String name1, String desc1,
                                      String name2, String desc2) {
        ClassWriter cw = new ClassWriter(0); // no COMPUTE_FRAMES: callees are off-classpath
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        emitStaticCall(mv, "test/desc/Target", name1, desc1);
        emitStaticCall(mv, "test/desc/Target", name2, desc2);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private byte[] createSingleCallClass(String className, String name, String desc) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        emitStaticCall(mv, "test/desc/Target", name, desc);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Pushes the args a descriptor needs, calls it, then pops any return value. */
    private void emitStaticCall(MethodVisitor mv, String owner, String name, String desc) {
        for (Type arg : Type.getArgumentTypes(desc)) {
            pushDefault(mv, arg);
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, desc, false);
        Type ret = Type.getReturnType(desc);
        if (ret.getSort() != Type.VOID) {
            mv.visitInsn(ret.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
        }
    }

    private void pushDefault(MethodVisitor mv, Type t) {
        switch (t.getSort()) {
            case Type.LONG -> mv.visitInsn(Opcodes.LCONST_0);
            case Type.FLOAT -> mv.visitInsn(Opcodes.FCONST_0);
            case Type.DOUBLE -> mv.visitInsn(Opcodes.DCONST_0);
            case Type.OBJECT, Type.ARRAY -> mv.visitInsn(Opcodes.ACONST_NULL);
            default -> mv.visitInsn(Opcodes.ICONST_0); // boolean/byte/char/short/int
        }
    }

    /** Class that GETSTATICs two fields off the same owner with the given name/type pairs. */
    private byte[] createTwoFieldAccessClass(String className,
                                             String name1, String type1,
                                             String name2, String type2) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, "test/desc/Target", name1, type1);
        mv.visitInsn(Type.getType(type1).getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "test/desc/Target", name2, type2);
        mv.visitInsn(Type.getType(type2).getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // Result readers

    /** Maps each INVOKESTATIC call's descriptor to the (possibly remapped) method name. */
    private Map<String, String> methodCallNamesByDescriptor(byte[] classBytes) {
        Map<String, String> out = new HashMap<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int a, String mn, String md, String sg, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String o, String n, String d, boolean itf) {
                        out.put(d, n);
                    }
                };
            }
        }, 0);
        return out;
    }

    /** Maps each field access's descriptor (type) to the (possibly remapped) field name. */
    private Map<String, String> fieldAccessNamesByDescriptor(byte[] classBytes) {
        Map<String, String> out = new HashMap<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int a, String mn, String md, String sg, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int op, String o, String n, String d) {
                        out.put(d, n);
                    }
                };
            }
        }, 0);
        return out;
    }
}
