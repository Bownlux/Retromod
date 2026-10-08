/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import static org.objectweb.asm.Opcodes.*;

/**
 * Moves the vector, quaternion and matrix classes that Minecraft 1.19.3 deleted from
 * {@code com.mojang.math} onto the JOML classes that replaced them.
 *
 * <p>Every 1.16 to 1.19.2 renderer builds poses with {@code Vector3f.YP.rotationDegrees(f)},
 * {@code new Quaternion(...)} and {@code Matrix4f.multiply}, and every vanilla method that takes
 * or returns one of these now uses the JOML type of the same role. The classes are redirected
 * wholesale, so those vanilla calls link under their new descriptors. JOML's mutators return
 * {@code this} where the old ones returned {@code void}, take its read-only interfaces as
 * arguments, and name a few things differently, so those calls go through generated helpers.
 *
 * <p>Two old behaviors are kept on purpose. {@code new Matrix4f()} and {@code new Matrix3f()}
 * built a zero matrix, where JOML builds the identity. {@code Matrix4f.translate} added to the
 * translation column, where JOML's {@code translate} multiplies by a translation. The old
 * {@code mRC} fields are row-major, JOML's {@code mCR} accessors column-major, so each field is
 * read and written through a helper that swaps the indices.
 */
public final class LegacyMojangMathBridge {

    static final String SYNTH = "com/retromod/generated/LegacyMojangMath";

    private static final String OLD = "com/mojang/math/";
    static final String OLD_V3 = OLD + "Vector3f";
    static final String OLD_V4 = OLD + "Vector4f";
    static final String OLD_Q = OLD + "Quaternion";
    static final String OLD_M3 = OLD + "Matrix3f";
    static final String OLD_M4 = OLD + "Matrix4f";
    private static final String OLD_V3D = OLD + "Vector3d";

    static final String V3 = "org/joml/Vector3f";
    static final String V4 = "org/joml/Vector4f";
    static final String Q = "org/joml/Quaternionf";
    static final String M3 = "org/joml/Matrix3f";
    static final String M4 = "org/joml/Matrix4f";
    private static final String V3C = "Lorg/joml/Vector3fc;";
    private static final String V4C = "Lorg/joml/Vector4fc;";
    private static final String QC = "Lorg/joml/Quaternionfc;";
    private static final String M3C = "Lorg/joml/Matrix3fc;";
    private static final String M4C = "Lorg/joml/Matrix4fc;";
    private static final String VEC3 = "net/minecraft/world/phys/Vec3";

    private static final String[] AXES = {"XN", "XP", "YN", "YP", "ZN", "ZP"};

    private LegacyMojangMathBridge() {
    }

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(SYNTH, generate());

        // Class redirects first: each method redirect below also registers the call shape the
        // class remap leaves behind, and that alias is computed from these redirects.
        transformer.registerClassRedirect(OLD_V3, V3);
        transformer.registerClassRedirect(OLD_V4, V4);
        transformer.registerClassRedirect(OLD_Q, Q);
        transformer.registerClassRedirect(OLD_M3, M3);
        transformer.registerClassRedirect(OLD_M4, M4);
        // Same public x, y, z fields and (DDD) constructor.
        transformer.registerClassRedirect(OLD_V3D, "org/joml/Vector3d");

        registerConstructors(transformer);
        registerVectorCalls(transformer);
        registerQuaternionCalls(transformer);
        registerMatrixCalls(transformer);
        registerFields(transformer);
    }

    private static String d(String internalName) {
        return "L" + internalName + ";";
    }

    private static void registerConstructors(RetromodTransformer t) {
        // Only the old owner is keyed: a newer mod's new org.joml.Matrix4f() must stay the identity.
        t.registerConstructorRedirect(OLD_V3, "(" + d(VEC3) + ")V", SYNTH, "v3FromVec3",
                "(" + d(VEC3) + ")" + d(V3));
        t.registerConstructorRedirect(OLD_V3, "(" + d(OLD_V4) + ")V", SYNTH, "v3FromV4",
                "(" + d(V4) + ")" + d(V3));
        t.registerConstructorRedirect(OLD_V4, "(" + d(OLD_V3) + ")V", SYNTH, "v4FromV3",
                "(" + d(V3) + ")" + d(V4));
        t.registerConstructorRedirect(OLD_Q, "(" + d(OLD_Q) + ")V", SYNTH, "qCopy",
                "(" + d(Q) + ")" + d(Q));
        t.registerConstructorRedirect(OLD_Q, "(" + d(OLD_V3) + "FZ)V", SYNTH, "qAxisAngle",
                "(" + d(V3) + "FZ)" + d(Q));
        t.registerConstructorRedirect(OLD_Q, "(FFFZ)V", SYNTH, "qEuler", "(FFFZ)" + d(Q));
        t.registerConstructorRedirect(OLD_M4, "()V", SYNTH, "m4Zero", "()" + d(M4));
        t.registerConstructorRedirect(OLD_M4, "(" + d(OLD_M4) + ")V", SYNTH, "m4Copy",
                "(" + d(M4) + ")" + d(M4));
        t.registerConstructorRedirect(OLD_M4, "(" + d(OLD_Q) + ")V", SYNTH, "m4FromQ",
                "(" + d(Q) + ")" + d(M4));
        t.registerConstructorRedirect(OLD_M3, "()V", SYNTH, "m3Zero", "()" + d(M3));
        t.registerConstructorRedirect(OLD_M3, "(" + d(OLD_M3) + ")V", SYNTH, "m3Copy",
                "(" + d(M3) + ")" + d(M3));
        t.registerConstructorRedirect(OLD_M3, "(" + d(OLD_M4) + ")V", SYNTH, "m3FromM4",
                "(" + d(M4) + ")" + d(M3));
        t.registerConstructorRedirect(OLD_M3, "(" + d(OLD_Q) + ")V", SYNTH, "m3FromQ",
                "(" + d(Q) + ")" + d(M3));
    }

    /** An instance call on {@code oldOwner} that now goes to a static helper taking the receiver. */
    private static void helper(RetromodTransformer t, String oldOwner, String name, String oldDesc,
            String newOwner, String helperName) {
        String newDesc = "(" + d(newOwner) + jomlTypes(oldDesc.substring(1));
        t.registerMethodRedirect(oldOwner, name, oldDesc, SYNTH, helperName, newDesc, true);
    }

    private static String jomlTypes(String descriptor) {
        return descriptor.replace(d(OLD_V3), d(V3)).replace(d(OLD_V4), d(V4))
                .replace(d(OLD_Q), d(Q)).replace(d(OLD_M3), d(M3)).replace(d(OLD_M4), d(M4));
    }

    private static void registerVectorCalls(RetromodTransformer t) {
        helper(t, OLD_V3, "set", "(FFF)V", V3, "v3Set");
        helper(t, OLD_V3, "load", "(" + d(OLD_V3) + ")V", V3, "v3Load");
        helper(t, OLD_V3, "setX", "(F)V", V3, "v3SetX");
        helper(t, OLD_V3, "setY", "(F)V", V3, "v3SetY");
        helper(t, OLD_V3, "setZ", "(F)V", V3, "v3SetZ");
        helper(t, OLD_V3, "mul", "(F)V", V3, "v3Scale");
        helper(t, OLD_V3, "mul", "(FFF)V", V3, "v3Mul");
        helper(t, OLD_V3, "add", "(FFF)V", V3, "v3AddXyz");
        helper(t, OLD_V3, "add", "(" + d(OLD_V3) + ")V", V3, "v3Add");
        helper(t, OLD_V3, "sub", "(" + d(OLD_V3) + ")V", V3, "v3Sub");
        helper(t, OLD_V3, "dot", "(" + d(OLD_V3) + ")F", V3, "v3Dot");
        helper(t, OLD_V3, "cross", "(" + d(OLD_V3) + ")V", V3, "v3Cross");
        helper(t, OLD_V3, "normalize", "()Z", V3, "v3Normalize");
        helper(t, OLD_V3, "transform", "(" + d(OLD_M3) + ")V", V3, "v3TransformM3");
        helper(t, OLD_V3, "transform", "(" + d(OLD_Q) + ")V", V3, "v3TransformQ");
        helper(t, OLD_V3, "lerp", "(" + d(OLD_V3) + "F)V", V3, "v3Lerp");
        helper(t, OLD_V3, "copy", "()" + d(OLD_V3), V3, "v3Copy");
        helper(t, OLD_V3, "rotation", "(F)" + d(OLD_Q), V3, "v3Rotation");
        helper(t, OLD_V3, "rotationDegrees", "(F)" + d(OLD_Q), V3, "v3RotationDegrees");

        helper(t, OLD_V4, "set", "(FFFF)V", V4, "v4Set");
        helper(t, OLD_V4, "setX", "(F)V", V4, "v4SetX");
        helper(t, OLD_V4, "setY", "(F)V", V4, "v4SetY");
        helper(t, OLD_V4, "setZ", "(F)V", V4, "v4SetZ");
        helper(t, OLD_V4, "setW", "(F)V", V4, "v4SetW");
        helper(t, OLD_V4, "mul", "(" + d(OLD_V3) + ")V", V4, "v4MulXyz");
        helper(t, OLD_V4, "dot", "(" + d(OLD_V4) + ")F", V4, "v4Dot");
        helper(t, OLD_V4, "normalize", "()Z", V4, "v4Normalize");
        helper(t, OLD_V4, "transform", "(" + d(OLD_M4) + ")V", V4, "v4TransformM4");
        helper(t, OLD_V4, "transform", "(" + d(OLD_Q) + ")V", V4, "v4TransformQ");
        helper(t, OLD_V4, "perspectiveDivide", "()V", V4, "v4PerspectiveDivide");
    }

    private static void registerQuaternionCalls(RetromodTransformer t) {
        // i, j, k and r are JOML's x, y, z and w, in the same order.
        String[][] parts = {{"i", "x"}, {"j", "y"}, {"k", "z"}, {"r", "w"}};
        for (String[] part : parts) {
            t.registerMethodRedirect(OLD_Q, part[0], "()F", Q, part[1], "()F");
        }
        helper(t, OLD_Q, "mul", "(" + d(OLD_Q) + ")V", Q, "qMul");
        helper(t, OLD_Q, "mul", "(F)V", Q, "qScale");
        helper(t, OLD_Q, "set", "(FFFF)V", Q, "qSet");
        helper(t, OLD_Q, "conj", "()V", Q, "qConj");
        helper(t, OLD_Q, "normalize", "()V", Q, "qNormalize");
        helper(t, OLD_Q, "copy", "()" + d(OLD_Q), Q, "qCopy");
    }

    private static void registerMatrixCalls(RetromodTransformer t) {
        helper(t, OLD_M4, "load", "(" + d(OLD_M4) + ")V", M4, "m4Load");
        helper(t, OLD_M4, "set", "(" + d(OLD_M4) + ")V", M4, "m4Load");
        helper(t, OLD_M4, "setIdentity", "()V", M4, "m4Identity");
        helper(t, OLD_M4, "multiply", "(" + d(OLD_M4) + ")V", M4, "m4Mul");
        helper(t, OLD_M4, "multiplyBackward", "(" + d(OLD_M4) + ")V", M4, "m4MulLocal");
        helper(t, OLD_M4, "multiply", "(" + d(OLD_Q) + ")V", M4, "m4Rotate");
        helper(t, OLD_M4, "multiply", "(F)V", M4, "m4Scale");
        helper(t, OLD_M4, "multiplyWithTranslation", "(FFF)V", M4, "m4MulTranslation");
        helper(t, OLD_M4, "translate", "(" + d(OLD_V3) + ")V", M4, "m4AddTranslation");
        helper(t, OLD_M4, "setTranslation", "(FFF)V", M4, "m4SetTranslation");
        helper(t, OLD_M4, "add", "(" + d(OLD_M4) + ")V", M4, "m4Add");
        helper(t, OLD_M4, "invert", "()Z", M4, "m4Invert");
        helper(t, OLD_M4, "transpose", "()V", M4, "m4Transpose");
        helper(t, OLD_M4, "copy", "()" + d(OLD_M4), M4, "m4Copy");
        t.registerMethodRedirect(OLD_M4, "createScaleMatrix", "(FFF)" + d(OLD_M4),
                SYNTH, "m4ScaleMatrix", "(FFF)" + d(M4));
        t.registerMethodRedirect(OLD_M4, "createTranslateMatrix", "(FFF)" + d(OLD_M4),
                SYNTH, "m4TranslateMatrix", "(FFF)" + d(M4));

        helper(t, OLD_M3, "load", "(" + d(OLD_M3) + ")V", M3, "m3Load");
        helper(t, OLD_M3, "set", "(" + d(OLD_M3) + ")V", M3, "m3Load");
        helper(t, OLD_M3, "setIdentity", "()V", M3, "m3Identity");
        helper(t, OLD_M3, "mul", "(" + d(OLD_M3) + ")V", M3, "m3Mul");
        helper(t, OLD_M3, "mul", "(" + d(OLD_Q) + ")V", M3, "m3Rotate");
        helper(t, OLD_M3, "mul", "(F)V", M3, "m3Scale");
        helper(t, OLD_M3, "transpose", "()V", M3, "m3Transpose");
        helper(t, OLD_M3, "invert", "()Z", M3, "m3Invert");
        helper(t, OLD_M3, "determinant", "()F", M3, "m3Determinant");
        helper(t, OLD_M3, "copy", "()" + d(OLD_M3), M3, "m3Copy");
        t.registerMethodRedirect(OLD_M3, "createScaleMatrix", "(FFF)" + d(OLD_M3),
                SYNTH, "m3ScaleMatrix", "(FFF)" + d(M3));
    }

    private static void registerFields(RetromodTransformer t) {
        // Field accesses are matched after the class remap, so the JOML owner is the one seen.
        // JOML keeps its matrix fields private and has no axis or ONE constants, so no access
        // from a mod built against JOML can match these keys.
        for (String owner : new String[]{OLD_V3, V3}) {
            for (String axis : AXES) {
                t.registerFieldRedirect(owner, axis, SYNTH, axis);
            }
        }
        for (String owner : new String[]{OLD_Q, Q}) {
            t.registerFieldRedirect(owner, "ONE", SYNTH, "ONE");
        }
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                matrixField(t, OLD_M4, M4, row, column);
                if (row < 3 && column < 3) {
                    matrixField(t, OLD_M3, M3, row, column);
                }
            }
        }
    }

    private static void matrixField(RetromodTransformer t, String oldOwner, String owner,
            int row, int column) {
        String field = "m" + row + column;
        String prefix = owner.equals(M4) ? "m4" : "m3";
        for (String keyOwner : new String[]{oldOwner, owner}) {
            t.registerFieldStaticBridge(keyOwner, field, SYNTH,
                    prefix + "Get" + row + column, "(" + d(owner) + ")F",
                    prefix + "Set" + row + column, "(" + d(owner) + "F)V");
        }
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SYNTH, null, "java/lang/Object", null);
        new Generator(cw).run();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Writes the helper bodies. Each one mirrors the 1.18.2 method it replaces. */
    private static final class Generator {
        private final ClassWriter cw;

        Generator(ClassWriter cw) {
            this.cw = cw;
        }

        void run() {
            constants();
            vectors();
            quaternions();
            matrices();
        }

        private MethodVisitor begin(String name, String desc) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
            mv.visitCode();
            return mv;
        }

        private static void end(MethodVisitor mv) {
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private static void loadArgs(MethodVisitor mv, String desc) {
            int slot = 0;
            for (Type arg : Type.getArgumentTypes(desc)) {
                mv.visitVarInsn(arg.getOpcode(ILOAD), slot);
                slot += arg.getSize();
            }
        }

        /**
         * A helper that loads every argument, calls {@code owner.name(callDesc)} on the first,
         * drops JOML's returned {@code this}, and returns nothing.
         */
        private void mutator(String name, String desc, String owner, String target, String callDesc) {
            MethodVisitor mv = begin(name, desc);
            loadArgs(mv, desc);
            mv.visitMethodInsn(INVOKEVIRTUAL, owner, target, callDesc, false);
            if (Type.getReturnType(callDesc).getSort() != Type.VOID) {
                mv.visitInsn(Type.getReturnType(callDesc).getSize() == 2 ? POP2 : POP);
            }
            mv.visitInsn(RETURN);
            end(mv);
        }

        /** A helper that returns what the JOML call returns. */
        private void value(String name, String desc, String owner, String target, String callDesc) {
            MethodVisitor mv = begin(name, desc);
            loadArgs(mv, desc);
            mv.visitMethodInsn(INVOKEVIRTUAL, owner, target, callDesc, false);
            mv.visitInsn(Type.getReturnType(desc).getOpcode(IRETURN));
            end(mv);
        }

        /** A helper that builds {@code new owner(ctorDesc)} from its arguments. */
        private void construct(String name, String desc, String owner, String ctorDesc) {
            MethodVisitor mv = begin(name, desc);
            mv.visitTypeInsn(NEW, owner);
            mv.visitInsn(DUP);
            loadArgs(mv, desc);
            mv.visitMethodInsn(INVOKESPECIAL, owner, "<init>", ctorDesc, false);
            mv.visitInsn(ARETURN);
            end(mv);
        }

        private void putFloat(String name, String owner, String field) {
            MethodVisitor mv = begin(name, "(" + d(owner) + "F)V");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(FLOAD, 1);
            mv.visitFieldInsn(PUTFIELD, owner, field, "F");
            mv.visitInsn(RETURN);
            end(mv);
        }

        private void constants() {
            float[][] axes = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
            for (String axis : AXES) {
                cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, axis, d(V3), null, null).visitEnd();
            }
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "ONE", d(Q), null, null).visitEnd();
            MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitCode();
            for (int i = 0; i < AXES.length; i++) {
                clinit.visitTypeInsn(NEW, V3);
                clinit.visitInsn(DUP);
                for (float component : axes[i]) {
                    clinit.visitLdcInsn(component);
                }
                clinit.visitMethodInsn(INVOKESPECIAL, V3, "<init>", "(FFF)V", false);
                clinit.visitFieldInsn(PUTSTATIC, SYNTH, AXES[i], d(V3));
            }
            clinit.visitTypeInsn(NEW, Q);
            clinit.visitInsn(DUP);
            clinit.visitMethodInsn(INVOKESPECIAL, Q, "<init>", "()V", false);
            clinit.visitFieldInsn(PUTSTATIC, SYNTH, "ONE", d(Q));
            clinit.visitInsn(RETURN);
            end(clinit);
        }

        private void vectors() {
            String v3 = d(V3);
            String v4 = d(V4);
            MethodVisitor fromVec3 = begin("v3FromVec3", "(" + d(VEC3) + ")" + v3);
            fromVec3.visitTypeInsn(NEW, V3);
            fromVec3.visitInsn(DUP);
            for (String axis : new String[]{"x", "y", "z"}) {
                fromVec3.visitVarInsn(ALOAD, 0);
                fromVec3.visitFieldInsn(GETFIELD, VEC3, axis, "D");
                fromVec3.visitInsn(D2F);
            }
            fromVec3.visitMethodInsn(INVOKESPECIAL, V3, "<init>", "(FFF)V", false);
            fromVec3.visitInsn(ARETURN);
            end(fromVec3);

            MethodVisitor fromV4 = begin("v3FromV4", "(" + v4 + ")" + v3);
            fromV4.visitTypeInsn(NEW, V3);
            fromV4.visitInsn(DUP);
            for (String axis : new String[]{"x", "y", "z"}) {
                fromV4.visitVarInsn(ALOAD, 0);
                fromV4.visitFieldInsn(GETFIELD, V4, axis, "F");
            }
            fromV4.visitMethodInsn(INVOKESPECIAL, V3, "<init>", "(FFF)V", false);
            fromV4.visitInsn(ARETURN);
            end(fromV4);

            // The old Vector4f(Vector3f) set w to 1.
            MethodVisitor fromV3 = begin("v4FromV3", "(" + v3 + ")" + v4);
            fromV3.visitTypeInsn(NEW, V4);
            fromV3.visitInsn(DUP);
            fromV3.visitVarInsn(ALOAD, 0);
            fromV3.visitInsn(FCONST_1);
            fromV3.visitMethodInsn(INVOKESPECIAL, V4, "<init>", "(" + V3C + "F)V", false);
            fromV3.visitInsn(ARETURN);
            end(fromV3);

            mutator("v3Set", "(" + v3 + "FFF)V", V3, "set", "(FFF)" + v3);
            mutator("v3Load", "(" + v3 + v3 + ")V", V3, "set", "(" + V3C + ")" + v3);
            putFloat("v3SetX", V3, "x");
            putFloat("v3SetY", V3, "y");
            putFloat("v3SetZ", V3, "z");
            mutator("v3Scale", "(" + v3 + "F)V", V3, "mul", "(F)" + v3);
            mutator("v3Mul", "(" + v3 + "FFF)V", V3, "mul", "(FFF)" + v3);
            mutator("v3AddXyz", "(" + v3 + "FFF)V", V3, "add", "(FFF)" + v3);
            mutator("v3Add", "(" + v3 + v3 + ")V", V3, "add", "(" + V3C + ")" + v3);
            mutator("v3Sub", "(" + v3 + v3 + ")V", V3, "sub", "(" + V3C + ")" + v3);
            value("v3Dot", "(" + v3 + v3 + ")F", V3, "dot", "(" + V3C + ")F");
            mutator("v3Cross", "(" + v3 + v3 + ")V", V3, "cross", "(" + V3C + ")" + v3);
            normalizeIfLong("v3Normalize", V3);
            // The old transform(m) computed m * v, which is JOML's mul(m).
            mutator("v3TransformM3", "(" + v3 + d(M3) + ")V", V3, "mul", "(" + M3C + ")" + v3);
            mutator("v3TransformQ", "(" + v3 + d(Q) + ")V", V3, "rotate", "(" + QC + ")" + v3);
            mutator("v3Lerp", "(" + v3 + v3 + "F)V", V3, "lerp", "(" + V3C + "F)" + v3);
            construct("v3Copy", "(" + v3 + ")" + v3, V3, "(" + V3C + ")V");
            rotation("v3Rotation", false);
            rotation("v3RotationDegrees", true);

            mutator("v4Set", "(" + v4 + "FFFF)V", V4, "set", "(FFFF)" + v4);
            putFloat("v4SetX", V4, "x");
            putFloat("v4SetY", V4, "y");
            putFloat("v4SetZ", V4, "z");
            putFloat("v4SetW", V4, "w");
            MethodVisitor mulXyz = begin("v4MulXyz", "(" + v4 + v3 + ")V");
            mulXyz.visitVarInsn(ALOAD, 0);
            for (String axis : new String[]{"x", "y", "z"}) {
                mulXyz.visitVarInsn(ALOAD, 1);
                mulXyz.visitFieldInsn(GETFIELD, V3, axis, "F");
            }
            mulXyz.visitInsn(FCONST_1);
            mulXyz.visitMethodInsn(INVOKEVIRTUAL, V4, "mul", "(FFFF)" + v4, false);
            mulXyz.visitInsn(POP);
            mulXyz.visitInsn(RETURN);
            end(mulXyz);
            value("v4Dot", "(" + v4 + v4 + ")F", V4, "dot", "(" + V4C + ")F");
            normalizeIfLong("v4Normalize", V4);
            mutator("v4TransformM4", "(" + v4 + d(M4) + ")V", V4, "mul", "(" + M4C + ")" + v4);
            mutator("v4TransformQ", "(" + v4 + d(Q) + ")V", V4, "rotate", "(" + QC + ")" + v4);
            MethodVisitor divide = begin("v4PerspectiveDivide", "(" + v4 + ")V");
            divide.visitVarInsn(ALOAD, 0);
            divide.visitInsn(FCONST_1);
            divide.visitVarInsn(ALOAD, 0);
            divide.visitFieldInsn(GETFIELD, V4, "w", "F");
            divide.visitInsn(FDIV);
            divide.visitMethodInsn(INVOKEVIRTUAL, V4, "mul", "(F)" + v4, false);
            divide.visitInsn(POP);
            divide.visitInsn(RETURN);
            end(divide);
        }

        /** The old normalize refused a vector shorter than sqrt(1e-5) and reported it. */
        private void normalizeIfLong(String name, String owner) {
            MethodVisitor mv = begin(name, "(" + d(owner) + ")Z");
            Label tooShort = new Label();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, owner, "lengthSquared", "()F", false);
            mv.visitInsn(F2D);
            mv.visitLdcInsn(1.0E-5D);
            mv.visitInsn(DCMPG);
            mv.visitJumpInsn(IFLT, tooShort);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, owner, "normalize", "()" + d(owner), false);
            mv.visitInsn(POP);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IRETURN);
            mv.visitLabel(tooShort);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
            end(mv);
        }

        private void rotation(String name, boolean degrees) {
            MethodVisitor mv = begin(name, "(" + d(V3) + "F)" + d(Q));
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(FLOAD, 1);
            mv.visitInsn(degrees ? ICONST_1 : ICONST_0);
            mv.visitMethodInsn(INVOKESTATIC, SYNTH, "qAxisAngle", "(" + d(V3) + "FZ)" + d(Q), false);
            mv.visitInsn(ARETURN);
            end(mv);
        }

        private static void sinCos(MethodVisitor mv, int angleSlot, boolean sin) {
            mv.visitVarInsn(FLOAD, angleSlot);
            mv.visitInsn(F2D);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", sin ? "sin" : "cos", "(D)D", false);
            mv.visitInsn(D2F);
        }

        private static void toRadiansIf(MethodVisitor mv, int flagSlot, int... angleSlots) {
            Label radians = new Label();
            mv.visitVarInsn(ILOAD, flagSlot);
            mv.visitJumpInsn(IFEQ, radians);
            for (int slot : angleSlots) {
                mv.visitVarInsn(FLOAD, slot);
                mv.visitLdcInsn((float) Math.PI / 180F);
                mv.visitInsn(FMUL);
                mv.visitVarInsn(FSTORE, slot);
            }
            mv.visitLabel(radians);
        }

        private void quaternions() {
            String q = d(Q);
            construct("qCopy", "(" + q + ")" + q, Q, "(" + QC + ")V");

            // Quaternion(axis, angle, degrees): the axis is used as given, without normalizing.
            MethodVisitor axisAngle = begin("qAxisAngle", "(" + d(V3) + "FZ)" + q);
            toRadiansIf(axisAngle, 2, 1);
            axisAngle.visitVarInsn(FLOAD, 1);
            axisAngle.visitLdcInsn(2.0F);
            axisAngle.visitInsn(FDIV);
            axisAngle.visitVarInsn(FSTORE, 1);
            sinCos(axisAngle, 1, true);
            axisAngle.visitVarInsn(FSTORE, 3);
            axisAngle.visitTypeInsn(NEW, Q);
            axisAngle.visitInsn(DUP);
            for (String axis : new String[]{"x", "y", "z"}) {
                axisAngle.visitVarInsn(ALOAD, 0);
                axisAngle.visitFieldInsn(GETFIELD, V3, axis, "F");
                axisAngle.visitVarInsn(FLOAD, 3);
                axisAngle.visitInsn(FMUL);
            }
            sinCos(axisAngle, 1, false);
            axisAngle.visitMethodInsn(INVOKESPECIAL, Q, "<init>", "(FFFF)V", false);
            axisAngle.visitInsn(ARETURN);
            end(axisAngle);

            // Quaternion(x, y, z, degrees), in the old constructor's own component order.
            MethodVisitor euler = begin("qEuler", "(FFFZ)" + q);
            toRadiansIf(euler, 3, 0, 1, 2);
            for (int axis = 0; axis < 3; axis++) {
                euler.visitVarInsn(FLOAD, axis);
                euler.visitLdcInsn(0.5F);
                euler.visitInsn(FMUL);
                euler.visitVarInsn(FSTORE, axis);
            }
            // Slots 4 to 9 hold sin x, cos x, sin y, cos y, sin z, cos z.
            for (int axis = 0; axis < 3; axis++) {
                sinCos(euler, axis, true);
                euler.visitVarInsn(FSTORE, 4 + axis * 2);
                sinCos(euler, axis, false);
                euler.visitVarInsn(FSTORE, 5 + axis * 2);
            }
            euler.visitTypeInsn(NEW, Q);
            euler.visitInsn(DUP);
            // i = sx*cy*cz + cx*sy*sz, j = cx*sy*cz - sx*cy*sz,
            // k = sx*sy*cz + cx*cy*sz, r = cx*cy*cz - sx*sy*sz
            int[][] terms = {{4, 7, 9, 5, 6, 8}, {5, 6, 9, 4, 7, 8}, {4, 6, 9, 5, 7, 8}, {5, 7, 9, 4, 6, 8}};
            boolean[] plus = {true, false, true, false};
            for (int i = 0; i < terms.length; i++) {
                for (int half = 0; half < 2; half++) {
                    euler.visitVarInsn(FLOAD, terms[i][half * 3]);
                    euler.visitVarInsn(FLOAD, terms[i][half * 3 + 1]);
                    euler.visitInsn(FMUL);
                    euler.visitVarInsn(FLOAD, terms[i][half * 3 + 2]);
                    euler.visitInsn(FMUL);
                }
                euler.visitInsn(plus[i] ? FADD : FSUB);
            }
            euler.visitMethodInsn(INVOKESPECIAL, Q, "<init>", "(FFFF)V", false);
            euler.visitInsn(ARETURN);
            end(euler);

            mutator("qMul", "(" + q + q + ")V", Q, "mul", "(" + QC + ")" + q);
            mutator("qScale", "(" + q + "F)V", Q, "mul", "(F)" + q);
            mutator("qSet", "(" + q + "FFFF)V", Q, "set", "(FFFF)" + q);
            mutator("qConj", "(" + q + ")V", Q, "conjugate", "()" + q);
            mutator("qNormalize", "(" + q + ")V", Q, "normalize", "()" + q);
        }

        private void matrices() {
            String m4 = d(M4);
            String m3 = d(M3);
            MethodVisitor zero4 = begin("m4Zero", "()" + m4);
            zero4.visitTypeInsn(NEW, M4);
            zero4.visitInsn(DUP);
            zero4.visitMethodInsn(INVOKESPECIAL, M4, "<init>", "()V", false);
            zero4.visitMethodInsn(INVOKEVIRTUAL, M4, "zero", "()" + m4, false);
            zero4.visitInsn(ARETURN);
            end(zero4);
            construct("m4Copy", "(" + m4 + ")" + m4, M4, "(" + M4C + ")V");
            fromQuaternion("m4FromQ", M4);
            mutator("m4Load", "(" + m4 + m4 + ")V", M4, "set", "(" + M4C + ")" + m4);
            mutator("m4Identity", "(" + m4 + ")V", M4, "identity", "()" + m4);
            mutator("m4Mul", "(" + m4 + m4 + ")V", M4, "mul", "(" + M4C + ")" + m4);
            mutator("m4MulLocal", "(" + m4 + m4 + ")V", M4, "mulLocal", "(" + M4C + ")" + m4);
            mutator("m4Rotate", "(" + m4 + d(Q) + ")V", M4, "rotate", "(" + QC + ")" + m4);
            mutator("m4MulTranslation", "(" + m4 + "FFF)V", M4, "translate", "(FFF)" + m4);
            mutator("m4Add", "(" + m4 + m4 + ")V", M4, "add", "(" + M4C + ")" + m4);
            mutator("m4Transpose", "(" + m4 + ")V", M4, "transpose", "()" + m4);
            scaleAll("m4Scale", M4, 16);
            scaleAll("m3Scale", M3, 9);

            // The old translate(v) added v to the translation column, not a multiplication.
            MethodVisitor translate = begin("m4AddTranslation", "(" + m4 + d(V3) + ")V");
            String[] columns = {"x", "y", "z"};
            for (int row = 0; row < 3; row++) {
                translate.visitVarInsn(ALOAD, 0);
                translate.visitVarInsn(ALOAD, 0);
                translate.visitMethodInsn(INVOKEVIRTUAL, M4, "m3" + row, "()F", false);
                translate.visitVarInsn(ALOAD, 1);
                translate.visitFieldInsn(GETFIELD, V3, columns[row], "F");
                translate.visitInsn(FADD);
                translate.visitMethodInsn(INVOKEVIRTUAL, M4, "m3" + row, "(F)" + m4, false);
                translate.visitInsn(POP);
            }
            translate.visitInsn(RETURN);
            end(translate);

            MethodVisitor setTranslation = begin("m4SetTranslation", "(" + m4 + "FFF)V");
            for (int row = 0; row < 3; row++) {
                setTranslation.visitVarInsn(ALOAD, 0);
                setTranslation.visitVarInsn(FLOAD, row + 1);
                setTranslation.visitMethodInsn(INVOKEVIRTUAL, M4, "m3" + row, "(F)" + m4, false);
                setTranslation.visitInsn(POP);
            }
            setTranslation.visitInsn(RETURN);
            end(setTranslation);

            invertIfRegular("m4Invert", M4);
            MethodVisitor scale4 = begin("m4ScaleMatrix", "(FFF)" + m4);
            scale4.visitTypeInsn(NEW, M4);
            scale4.visitInsn(DUP);
            scale4.visitMethodInsn(INVOKESPECIAL, M4, "<init>", "()V", false);
            loadArgs(scale4, "(FFF)V");
            scale4.visitMethodInsn(INVOKEVIRTUAL, M4, "scaling", "(FFF)" + m4, false);
            scale4.visitInsn(ARETURN);
            end(scale4);
            MethodVisitor translation = begin("m4TranslateMatrix", "(FFF)" + m4);
            translation.visitTypeInsn(NEW, M4);
            translation.visitInsn(DUP);
            translation.visitMethodInsn(INVOKESPECIAL, M4, "<init>", "()V", false);
            loadArgs(translation, "(FFF)V");
            translation.visitMethodInsn(INVOKEVIRTUAL, M4, "translation", "(FFF)" + m4, false);
            translation.visitInsn(ARETURN);
            end(translation);

            MethodVisitor zero3 = begin("m3Zero", "()" + m3);
            zero3.visitTypeInsn(NEW, M3);
            zero3.visitInsn(DUP);
            zero3.visitMethodInsn(INVOKESPECIAL, M3, "<init>", "()V", false);
            zero3.visitMethodInsn(INVOKEVIRTUAL, M3, "zero", "()" + m3, false);
            zero3.visitInsn(ARETURN);
            end(zero3);
            construct("m3Copy", "(" + m3 + ")" + m3, M3, "(" + M3C + ")V");
            construct("m3FromM4", "(" + m4 + ")" + m3, M3, "(" + M4C + ")V");
            fromQuaternion("m3FromQ", M3);
            mutator("m3Load", "(" + m3 + m3 + ")V", M3, "set", "(" + M3C + ")" + m3);
            mutator("m3Identity", "(" + m3 + ")V", M3, "identity", "()" + m3);
            mutator("m3Mul", "(" + m3 + m3 + ")V", M3, "mul", "(" + M3C + ")" + m3);
            mutator("m3Rotate", "(" + m3 + d(Q) + ")V", M3, "rotate", "(" + QC + ")" + m3);
            mutator("m3Transpose", "(" + m3 + ")V", M3, "transpose", "()" + m3);
            value("m3Determinant", "(" + m3 + ")F", M3, "determinant", "()F");
            invertIfRegular("m3Invert", M3);
            MethodVisitor scale3 = begin("m3ScaleMatrix", "(FFF)" + m3);
            scale3.visitTypeInsn(NEW, M3);
            scale3.visitInsn(DUP);
            scale3.visitMethodInsn(INVOKESPECIAL, M3, "<init>", "()V", false);
            loadArgs(scale3, "(FFF)V");
            scale3.visitMethodInsn(INVOKEVIRTUAL, M3, "scaling", "(FFF)" + m3, false);
            scale3.visitInsn(ARETURN);
            end(scale3);

            for (int row = 0; row < 4; row++) {
                for (int column = 0; column < 4; column++) {
                    element("m4", M4, row, column);
                    if (row < 3 && column < 3) {
                        element("m3", M3, row, column);
                    }
                }
            }
        }

        private void fromQuaternion(String name, String owner) {
            MethodVisitor mv = begin(name, "(" + d(Q) + ")" + d(owner));
            mv.visitTypeInsn(NEW, owner);
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, owner, "<init>", "()V", false);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, owner, "rotation", "(" + QC + ")" + d(owner), false);
            mv.visitInsn(ARETURN);
            end(mv);
        }

        /** The old multiply(float) scaled every element, translation included. */
        private void scaleAll(String name, String owner, int elements) {
            MethodVisitor mv = begin(name, "(" + d(owner) + "F)V");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(elements);
            mv.visitIntInsn(NEWARRAY, T_FLOAT);
            mv.visitMethodInsn(INVOKEVIRTUAL, owner, "get", "([F)[F", false);
            mv.visitVarInsn(ASTORE, 2);
            Label loop = new Label();
            Label done = new Label();
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, 3);
            mv.visitLabel(loop);
            mv.visitVarInsn(ILOAD, 3);
            mv.visitLdcInsn(elements);
            mv.visitJumpInsn(IF_ICMPGE, done);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ILOAD, 3);
            mv.visitInsn(DUP2);
            mv.visitInsn(FALOAD);
            mv.visitVarInsn(FLOAD, 1);
            mv.visitInsn(FMUL);
            mv.visitInsn(FASTORE);
            mv.visitIincInsn(3, 1);
            mv.visitJumpInsn(GOTO, loop);
            mv.visitLabel(done);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKEVIRTUAL, owner, "set", "([F)" + d(owner), false);
            mv.visitInsn(POP);
            mv.visitInsn(RETURN);
            end(mv);
        }

        /** The old invert left a singular matrix in place and returned false. */
        private void invertIfRegular(String name, String owner) {
            MethodVisitor mv = begin(name, "(" + d(owner) + ")Z");
            Label singular = new Label();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, owner, "determinant", "()F", false);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(F)F", false);
            mv.visitLdcInsn(1.0E-6F);
            mv.visitInsn(FCMPL);
            mv.visitJumpInsn(IFLE, singular);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, owner, "invert", "()" + d(owner), false);
            mv.visitInsn(POP);
            mv.visitInsn(ICONST_1);
            mv.visitInsn(IRETURN);
            mv.visitLabel(singular);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(IRETURN);
            end(mv);
        }

        /** Old field mRC is row R, column C. JOML's accessor mCR names the column first. */
        private void element(String prefix, String owner, int row, int column) {
            String accessor = "m" + column + row;
            MethodVisitor get = begin(prefix + "Get" + row + column, "(" + d(owner) + ")F");
            get.visitVarInsn(ALOAD, 0);
            get.visitMethodInsn(INVOKEVIRTUAL, owner, accessor, "()F", false);
            get.visitInsn(FRETURN);
            end(get);
            MethodVisitor set = begin(prefix + "Set" + row + column, "(" + d(owner) + "F)V");
            set.visitVarInsn(ALOAD, 0);
            set.visitVarInsn(FLOAD, 1);
            set.visitMethodInsn(INVOKEVIRTUAL, owner, accessor, "(F)" + d(owner), false);
            set.visitInsn(POP);
            set.visitInsn(RETURN);
            end(set);
        }
    }
}
