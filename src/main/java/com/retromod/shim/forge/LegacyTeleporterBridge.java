/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.function.Predicate;

/**
 * Bridges Forge's {@code ITeleporter} and vanilla's {@code PortalInfo} onto 1.21's
 * {@code DimensionTransition}.
 *
 * <p>1.21 replaced {@code Entity.changeDimension(ServerLevel, ITeleporter)} with
 * {@code changeDimension(DimensionTransition)} and deleted {@code PortalInfo}, which NeoForge
 * followed by deleting {@code ITeleporter}. A Forge mod that moves players between dimensions,
 * such as into its own structure dimension, fails as soon as its teleporter class loads.
 *
 * <p>The teleporter becomes a generated interface whose old methods have the old defaults, so a
 * mod's teleporter keeps overriding them. {@code PortalInfo} becomes a generated class with the
 * same four fields. The old {@code changeDimension} call asks the teleporter for its portal info,
 * or uses the entity's own position when it has none, and moves the entity with a transition built
 * from it. A teleporter's {@code placeEntity} override and its teleport sound are not consulted,
 * because the transition places the entity itself. Applies to the 1.21 and 1.21.1 transition
 * shape only.
 */
final class LegacyTeleporterBridge {

    static final String FORGE_TELEPORTER = "net/minecraftforge/common/util/ITeleporter";
    static final String OLD_PORTAL_INFO = "net/minecraft/world/level/portal/PortalInfo";
    static final String TELEPORTER = "com/retromod/generated/LegacyTeleporter";
    static final String PORTAL_INFO = "com/retromod/generated/LegacyPortalInfo";
    static final String CALLS = "com/retromod/generated/LegacyTeleporterCalls";

    static final String TRANSITION = "net/minecraft/world/level/portal/DimensionTransition";
    private static final String POST = TRANSITION + "$PostDimensionTransition";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String VEC3 = "net/minecraft/world/phys/Vec3";
    private static final String FUNCTION = "java/util/function/Function";

    private static final String L_ENTITY = "L" + ENTITY + ";";
    private static final String L_LEVEL = "L" + SERVER_LEVEL + ";";
    private static final String L_VEC3 = "L" + VEC3 + ";";
    private static final String L_INFO = "L" + PORTAL_INFO + ";";
    private static final String L_TELEPORTER = "L" + TELEPORTER + ";";
    private static final String GET_PORTAL_INFO_DESC = "(" + L_ENTITY + L_LEVEL + "L" + FUNCTION + ";)" + L_INFO;
    private static final String TRANSITION_CTOR = "(" + L_LEVEL + L_VEC3 + L_VEC3 + "FFL" + POST + ";)V";

    private LegacyTeleporterBridge() {}

    static boolean register(RetromodTransformer transformer, Predicate<String> hostHasClass) {
        if (hostHasClass.test(FORGE_TELEPORTER) || hostHasClass.test(OLD_PORTAL_INFO)) return false;
        ClassNode transition = ClassResourceInspector.read(TRANSITION);
        if (transition == null || !hasMethod(transition, "<init>", TRANSITION_CTOR)) return false;
        registerRedirects(transformer);
        return true;
    }

    static void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(PORTAL_INFO, generatePortalInfo());
        transformer.registerSyntheticClass(TELEPORTER, generateTeleporter());
        transformer.registerSyntheticClass(CALLS, generateCalls());
        transformer.registerClassRedirect(OLD_PORTAL_INFO, PORTAL_INFO);
        transformer.registerClassRedirect(FORGE_TELEPORTER, TELEPORTER);
        String oldDesc = "(" + L_LEVEL + "L" + FORGE_TELEPORTER + ";)" + L_ENTITY;
        String helperDesc = "(" + L_ENTITY + L_LEVEL + L_TELEPORTER + ")" + L_ENTITY;
        // javac names the receiver's static type, so the player form needs its own key.
        transformer.registerInheritedMethodRedirect(ENTITY, "changeDimension", oldDesc, CALLS, "changeDimension", helperDesc);
        transformer.registerMethodRedirect(SERVER_PLAYER, "changeDimension", oldDesc,
                CALLS, "changeDimension", helperDesc, true);
    }

    /** {@code PortalInfo(Vec3 pos, Vec3 speed, float yRot, float xRot)} with its public fields. */
    static byte[] generatePortalInfo() {
        ClassWriter cw = newWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, PORTAL_INFO, null, "java/lang/Object", null);
        String[][] fields = {{"pos", L_VEC3}, {"speed", L_VEC3}, {"yRot", "F"}, {"xRot", "F"}};
        for (String[] field : fields) {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, field[0], field[1], null, null).visitEnd();
        }
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + L_VEC3 + L_VEC3 + "FF)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        int[] slots = {1, 2, 3, 4};
        int[] loads = {Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.FLOAD, Opcodes.FLOAD};
        for (int i = 0; i < fields.length; i++) {
            ctor.visitVarInsn(Opcodes.ALOAD, 0);
            ctor.visitVarInsn(loads[i], slots[i]);
            ctor.visitFieldInsn(Opcodes.PUTFIELD, PORTAL_INFO, fields[i][0], fields[i][1]);
        }
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code ITeleporter} with Forge's defaults: keep the default placement and portal info. */
    static byte[] generateTeleporter() {
        ClassWriter cw = newWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                TELEPORTER, null, "java/lang/Object", null);

        MethodVisitor place = cw.visitMethod(Opcodes.ACC_PUBLIC, "placeEntity",
                "(" + L_ENTITY + L_LEVEL + L_LEVEL + "FL" + FUNCTION + ";)" + L_ENTITY, null, null);
        place.visitCode();
        place.visitVarInsn(Opcodes.ALOAD, 5);
        place.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
        place.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        place.visitTypeInsn(Opcodes.CHECKCAST, ENTITY);
        place.visitInsn(Opcodes.ARETURN);
        place.visitMaxs(0, 0);
        place.visitEnd();

        MethodVisitor info = cw.visitMethod(Opcodes.ACC_PUBLIC, "getPortalInfo", GET_PORTAL_INFO_DESC, null, null);
        info.visitCode();
        info.visitVarInsn(Opcodes.ALOAD, 3);
        info.visitVarInsn(Opcodes.ALOAD, 2);
        info.visitMethodInsn(Opcodes.INVOKEINTERFACE, FUNCTION, "apply", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        info.visitTypeInsn(Opcodes.CHECKCAST, PORTAL_INFO);
        info.visitInsn(Opcodes.ARETURN);
        info.visitMaxs(0, 0);
        info.visitEnd();

        MethodVisitor vanilla = cw.visitMethod(Opcodes.ACC_PUBLIC, "isVanilla", "()Z", null, null);
        vanilla.visitCode();
        vanilla.visitInsn(Opcodes.ICONST_0);
        vanilla.visitInsn(Opcodes.IRETURN);
        vanilla.visitMaxs(0, 0);
        vanilla.visitEnd();

        MethodVisitor sound = cw.visitMethod(Opcodes.ACC_PUBLIC, "playTeleportSound",
                "(L" + SERVER_PLAYER + ";" + L_LEVEL + L_LEVEL + ")Z", null, null);
        sound.visitCode();
        sound.visitInsn(Opcodes.ICONST_0);
        sound.visitInsn(Opcodes.IRETURN);
        sound.visitMaxs(0, 0);
        sound.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code changeDimension(entity, level, teleporter)}: the teleporter's portal info, asked with a
     * default that keeps the entity where it is, becomes a transition with no follow-up action.
     */
    static byte[] generateCalls() {
        ClassWriter cw = newWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, CALLS, null,
                "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "changeDimension",
                "(" + L_ENTITY + L_LEVEL + L_TELEPORTER + ")" + L_ENTITY, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CALLS, "stayInPlace", "(" + L_ENTITY + ")L" + FUNCTION + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, TELEPORTER, "getPortalInfo", GET_PORTAL_INFO_DESC, true);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        Label found = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitJumpInsn(Opcodes.IFNONNULL, found);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(found);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.NEW, TRANSITION);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        for (String[] field : new String[][] {{"pos", L_VEC3}, {"speed", L_VEC3}, {"yRot", "F"}, {"xRot", "F"}}) {
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitFieldInsn(Opcodes.GETFIELD, PORTAL_INFO, field[0], field[1]);
        }
        mv.visitFieldInsn(Opcodes.GETSTATIC, TRANSITION, "DO_NOTHING", "L" + POST + ";");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TRANSITION, "<init>", TRANSITION_CTOR, false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "changeDimension",
                "(L" + TRANSITION + ";)" + L_ENTITY, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // Forge's default portal info: the entity's own position, motion, and rotation.
        MethodVisitor stay = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "stayInPlace",
                "(" + L_ENTITY + ")L" + FUNCTION + ";", null, null);
        stay.visitCode();
        stay.visitVarInsn(Opcodes.ALOAD, 0);
        stay.visitInvokeDynamicInsn("apply", "(" + L_ENTITY + ")L" + FUNCTION + ";",
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                org.objectweb.asm.Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, CALLS, "infoOf",
                        "(" + L_ENTITY + "Ljava/lang/Object;)" + L_INFO, false),
                org.objectweb.asm.Type.getType("(Ljava/lang/Object;)" + L_INFO));
        stay.visitInsn(Opcodes.ARETURN);
        stay.visitMaxs(0, 0);
        stay.visitEnd();

        MethodVisitor infoOf = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "infoOf",
                "(" + L_ENTITY + "Ljava/lang/Object;)" + L_INFO, null, null);
        infoOf.visitCode();
        infoOf.visitTypeInsn(Opcodes.NEW, PORTAL_INFO);
        infoOf.visitInsn(Opcodes.DUP);
        infoOf.visitVarInsn(Opcodes.ALOAD, 0);
        infoOf.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "position", "()" + L_VEC3, false);
        infoOf.visitVarInsn(Opcodes.ALOAD, 0);
        infoOf.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "getDeltaMovement", "()" + L_VEC3, false);
        infoOf.visitVarInsn(Opcodes.ALOAD, 0);
        infoOf.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "getYRot", "()F", false);
        infoOf.visitVarInsn(Opcodes.ALOAD, 0);
        infoOf.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "getXRot", "()F", false);
        infoOf.visitMethodInsn(Opcodes.INVOKESPECIAL, PORTAL_INFO, "<init>", "(" + L_VEC3 + L_VEC3 + "FF)V", false);
        infoOf.visitInsn(Opcodes.ARETURN);
        infoOf.visitMaxs(0, 0);
        infoOf.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }
}
