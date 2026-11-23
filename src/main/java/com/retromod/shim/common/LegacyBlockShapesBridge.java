/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.util.Locale;
import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Gives block subclasses the shape constants 1.21.5 dropped or hid, read from the host's own block of that kind. */
public final class LegacyBlockShapesBridge {

    static final String SHAPES = "com/retromod/generated/LegacyBlockShapes";
    static final String GATE = "net/minecraft/world/level/block/FenceGateBlock";
    static final String SHAPE = "Lnet/minecraft/world/phys/shapes/VoxelShape;";
    private static final String STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String DIRECTION = "net/minecraft/core/Direction";
    private static final String SHAPE_DESC = "(L" + DIRECTION + ";ZI)" + SHAPE;

    // Which host shape each old constant was: the outline, the collision box, the support face or the occlusion.
    static final int OUTLINE = 0;
    static final int COLLISION = 1;
    static final int SUPPORT = 2;
    static final int OCCLUSION = 3;

    /** Each old constant, its SRG id, the facing whose axis it is for, whether the gate sits in a wall, and its kind. */
    static final Object[][] CONSTANTS = {
            {"Z_SHAPE", "f_53344_", "NORTH", false, OUTLINE},
            {"X_SHAPE", "f_53345_", "EAST", false, OUTLINE},
            {"Z_SHAPE_LOW", "f_53346_", "NORTH", true, OUTLINE},
            {"X_SHAPE_LOW", "f_53347_", "EAST", true, OUTLINE},
            {"Z_COLLISION_SHAPE", "f_53348_", "NORTH", false, COLLISION},
            {"X_COLLISION_SHAPE", "f_53349_", "EAST", false, COLLISION},
            {"Z_SUPPORT_SHAPE", "f_252518_", "NORTH", false, SUPPORT},
            {"X_SUPPORT_SHAPE", "f_252514_", "EAST", false, SUPPORT},
            {"Z_OCCLUSION_SHAPE", "f_53350_", "NORTH", false, OCCLUSION},
            {"X_OCCLUSION_SHAPE", "f_53351_", "EAST", false, OCCLUSION},
            {"Z_OCCLUSION_SHAPE_LOW", "f_53352_", "NORTH", true, OCCLUSION},
            {"X_OCCLUSION_SHAPE_LOW", "f_53353_", "EAST", true, OCCLUSION}};

    /** Plain constants: the owner, the old name, its SRG id, and the host block whose default state has the shape. */
    static final String[][] DEFAULT_STATE_CONSTANTS = {
            {"net/minecraft/world/level/block/SkullBlock", "SHAPE", "f_56315_", "SKELETON_SKULL"}};

    private LegacyBlockShapesBridge() {}

    public static void register(RetromodTransformer transformer) {
        register(transformer, ClassResourceInspector::read, RetromodVersion.TARGET_MC_VERSION);
    }

    static void register(RetromodTransformer transformer, Function<String, ClassNode> host, String target) {
        if (transformer.getSyntheticClasses().containsKey(SHAPES)) return;
        boolean gates = hostDropped(host.apply(GATE), "Z_SHAPE", target);
        boolean[] plain = new boolean[DEFAULT_STATE_CONSTANTS.length];
        boolean any = gates;
        for (int i = 0; i < plain.length; i++) {
            plain[i] = hostDropped(host.apply(DEFAULT_STATE_CONSTANTS[i][0]), DEFAULT_STATE_CONSTANTS[i][1], target);
            any |= plain[i];
        }
        if (!any) return;
        transformer.registerSyntheticClass(SHAPES, generate());
        // A subclass reads the constants through its own class, so the read names the subclass.
        for (Object[] constant : gates ? CONSTANTS : new Object[0][]) {
            for (String name : new String[] {(String) constant[0], (String) constant[1]}) {
                transformer.registerInheritedStaticFieldRedirect(GATE, name, SHAPE, SHAPES, (String) constant[0], "()" + SHAPE);
            }
        }
        for (int i = 0; i < plain.length; i++) {
            if (!plain[i]) continue;
            String[] constant = DEFAULT_STATE_CONSTANTS[i];
            for (String name : new String[] {constant[1], constant[2]}) {
                transformer.registerInheritedStaticFieldRedirect(constant[0], name, SHAPE, SHAPES, getterName(constant), "()" + SHAPE);
            }
        }
    }

    /**
     * Whether the host's class lacks the constant or keeps it private, where a subclass cannot read it;
     * with no host class to read, from 1.21.5.
     */
    static boolean hostDropped(ClassNode owner, String name, String target) {
        if (owner == null) return target != null && !RetromodVersion.mcVersionExceeds("1.21.5", target);
        for (FieldNode field : owner.fields) {
            if (field.name.equals(name)) return (field.access & ACC_PRIVATE) != 0;
        }
        return true;
    }

    private static String getterName(String[] constant) {
        return constant[0].substring(constant[0].lastIndexOf('/') + 1) + "_" + constant[1];
    }

    /** One cached getter per constant, each asking the host's oak fence gate in the matching state. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SHAPES, null, "java/lang/Object", null);
        for (Object[] constant : CONSTANTS) {
            String name = (String) constant[0];
            String cache = name.toLowerCase(Locale.ROOT);
            cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_VOLATILE, cache, SHAPE, null, null).visitEnd();
            // Asked on first read, after the game's blocks exist; the shape never changes, so a race only repeats the work.
            MethodVisitor getter = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "()" + SHAPE, null, null);
            getter.visitCode();
            Label known = new Label();
            getter.visitFieldInsn(GETSTATIC, SHAPES, cache, SHAPE);
            getter.visitJumpInsn(IFNONNULL, known);
            getter.visitFieldInsn(GETSTATIC, DIRECTION, (String) constant[2], "L" + DIRECTION + ";");
            getter.visitInsn((Boolean) constant[3] ? ICONST_1 : ICONST_0);
            getter.visitIntInsn(BIPUSH, (Integer) constant[4]);
            getter.visitMethodInsn(INVOKESTATIC, SHAPES, "shape", SHAPE_DESC, false);
            getter.visitFieldInsn(PUTSTATIC, SHAPES, cache, SHAPE);
            getter.visitLabel(known);
            getter.visitFieldInsn(GETSTATIC, SHAPES, cache, SHAPE);
            getter.visitInsn(ARETURN);
            getter.visitMaxs(0, 0);
            getter.visitEnd();
        }
        emitShape(cw);
        // Plain constants: the default state's outline, asked once on first read.
        for (String[] constant : DEFAULT_STATE_CONSTANTS) {
            String name = getterName(constant);
            String cache = name.toLowerCase(Locale.ROOT);
            cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_VOLATILE, cache, SHAPE, null, null).visitEnd();
            MethodVisitor getter = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "()" + SHAPE, null, null);
            getter.visitCode();
            Label known = new Label();
            getter.visitFieldInsn(GETSTATIC, SHAPES, cache, SHAPE);
            getter.visitJumpInsn(IFNONNULL, known);
            getter.visitFieldInsn(GETSTATIC, "net/minecraft/world/level/block/Blocks", constant[3], "Lnet/minecraft/world/level/block/Block;");
            getter.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/block/Block", "defaultBlockState", "()L" + STATE + ";", false);
            getter.visitFieldInsn(GETSTATIC, "net/minecraft/world/level/EmptyBlockGetter", "INSTANCE", "Lnet/minecraft/world/level/EmptyBlockGetter;");
            getter.visitFieldInsn(GETSTATIC, "net/minecraft/core/BlockPos", "ZERO", "Lnet/minecraft/core/BlockPos;");
            getter.visitMethodInsn(INVOKEVIRTUAL, STATE, "getShape", "(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)" + SHAPE, false);
            getter.visitFieldInsn(PUTSTATIC, SHAPES, cache, SHAPE);
            getter.visitLabel(known);
            getter.visitFieldInsn(GETSTATIC, SHAPES, cache, SHAPE);
            getter.visitInsn(ARETURN);
            getter.visitMaxs(0, 0);
            getter.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code shape(facing, inWall, kind)}: the closed oak fence gate's shape of that kind, as the host computes it. */
    private static void emitShape(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "shape", SHAPE_DESC, null, null);
        m.visitCode();
        String property = "Lnet/minecraft/world/level/block/state/properties/";
        String setValue = "(Lnet/minecraft/world/level/block/state/properties/Property;Ljava/lang/Comparable;)Ljava/lang/Object;";
        m.visitFieldInsn(GETSTATIC, "net/minecraft/world/level/block/Blocks", "OAK_FENCE_GATE", "Lnet/minecraft/world/level/block/Block;");
        m.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/block/Block", "defaultBlockState", "()L" + STATE + ";", false);
        m.visitFieldInsn(GETSTATIC, GATE, "FACING", property + "EnumProperty;");
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, STATE, "setValue", setValue, false);
        m.visitTypeInsn(CHECKCAST, STATE);
        m.visitFieldInsn(GETSTATIC, GATE, "IN_WALL", property + "BooleanProperty;");
        m.visitVarInsn(ILOAD, 1);
        m.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, STATE, "setValue", setValue, false);
        m.visitTypeInsn(CHECKCAST, STATE);
        m.visitFieldInsn(GETSTATIC, GATE, "OPEN", property + "BooleanProperty;");
        m.visitFieldInsn(GETSTATIC, "java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;");
        m.visitMethodInsn(INVOKEVIRTUAL, STATE, "setValue", setValue, false);
        m.visitTypeInsn(CHECKCAST, STATE);
        m.visitVarInsn(ASTORE, 3);
        String world = "(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)" + SHAPE;
        String[] names = {"getShape", "getCollisionShape", "getBlockSupportShape"};
        for (int kind = OUTLINE; kind <= SUPPORT; kind++) {
            Label next = new Label();
            m.visitVarInsn(ILOAD, 2);
            m.visitIntInsn(BIPUSH, kind);
            m.visitJumpInsn(IF_ICMPNE, next);
            m.visitVarInsn(ALOAD, 3);
            m.visitFieldInsn(GETSTATIC, "net/minecraft/world/level/EmptyBlockGetter", "INSTANCE", "Lnet/minecraft/world/level/EmptyBlockGetter;");
            m.visitFieldInsn(GETSTATIC, "net/minecraft/core/BlockPos", "ZERO", "Lnet/minecraft/core/BlockPos;");
            m.visitMethodInsn(INVOKEVIRTUAL, STATE, names[kind], world, false);
            m.visitInsn(ARETURN);
            m.visitLabel(next);
        }
        m.visitVarInsn(ALOAD, 3);
        m.visitMethodInsn(INVOKEVIRTUAL, STATE, "getOcclusionShape", "()" + SHAPE, false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
