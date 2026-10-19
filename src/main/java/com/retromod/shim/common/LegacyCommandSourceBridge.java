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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Keeps the old public {@code CommandSourceStack} constructors and int permission calls working. */
public final class LegacyCommandSourceBridge {

    static final String HELPER = "com/retromod/generated/LegacyCommandSources";

    static final String STACK = "net/minecraft/commands/CommandSourceStack";
    static final String SOURCE = "net/minecraft/commands/CommandSource";
    static final String VEC3 = "net/minecraft/world/phys/Vec3";
    static final String VEC2 = "net/minecraft/world/phys/Vec2";
    static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    static final String PERMISSION_SET = "net/minecraft/server/permissions/PermissionSet";
    static final String LEVEL_PERMISSIONS = "net/minecraft/server/permissions/LevelBasedPermissionSet";
    static final String PERMISSION_LEVEL = "net/minecraft/server/permissions/PermissionLevel";
    static final String COMPONENT = "net/minecraft/network/chat/Component";
    static final String SERVER = "net/minecraft/server/MinecraftServer";
    static final String ENTITY = "net/minecraft/world/entity/Entity";

    // 1.21.11 replaced the int permission level with a PermissionSet, and 26.3 dropped the explicit
    // text and display names. MCreator procedures build a stack this way for every command they
    // run, such as the playsound a food or block plays, so the whole procedure failed with
    // NoSuchMethodError. These are the constructor shapes across those releases.
    private static final String LEADING = "(L" + SOURCE + ";L" + VEC3 + ";L" + VEC2 + ";L" + SERVER_LEVEL + ";";

    /** Up to 1.21.10: an int permission level, then the text name, display name, server and entity. */
    static final String INT_FORM = LEADING + "ILjava/lang/String;L" + COMPONENT + ";L" + SERVER + ";L" + ENTITY + ";)V";
    /** The same with a {@code PermissionSet}, from 1.21.11 to 26.2. */
    static final String SET_FORM = LEADING + "L" + PERMISSION_SET + ";Ljava/lang/String;L" + COMPONENT + ";L"
            + SERVER + ";L" + ENTITY + ";)V";
    /** 26.3: names come from the entity. */
    static final String ENTITY_NAMED = LEADING + "L" + PERMISSION_SET + ";L" + SERVER + ";L" + ENTITY + ";)V";
    /** 26.3: no entity, one constant display name. */
    static final String CONSTANT_NAMED = LEADING + "L" + PERMISSION_SET + ";L" + COMPONENT + ";L" + SERVER + ";)V";

    static final String BY_ID = "(I)L" + PERMISSION_LEVEL + ";";
    static final String FOR_LEVEL = "(L" + PERMISSION_LEVEL + ";)L" + LEVEL_PERMISSIONS + ";";

    /** {@code withPermission(int)} and {@code withMaximumPermission(int)}, removed with the int level. */
    static final String[] WITH_LEVEL = {"withPermission", "withMaximumPermission"};
    static final String WITH_LEVEL_OLD = "(I)L" + STACK + ";";
    static final String WITH_LEVEL_NEW = "(L" + PERMISSION_SET + ";)L" + STACK + ";";
    /** The existing {@code hasPermission(int)} bridge. */
    static final String HAS_PERMISSION_BRIDGE = "com/retromod/shim/fabric/embedded/ItemSafetyShim";

    /** The public constructor shape the host builds a stack with. */
    enum HostShape {
        /** The int form is still there: nothing to bridge. */
        INT_LEVEL,
        /** 1.21.11 to 26.2: a {@code PermissionSet} and both names. */
        PERMISSION_SET_NAMED,
        /** 26.3: the entity or one display name decides the names. */
        ENTITY_NAMED,
        /** None of the above. Nothing is bridged rather than guessing. */
        UNKNOWN
    }

    private LegacyCommandSourceBridge() {}

    /** Registers the bridge, only on a host that lost the old forms. */
    public static void register(RetromodTransformer transformer) {
        HostShape shape = probe();
        if (shape == HostShape.INT_LEVEL || shape == HostShape.UNKNOWN) return;
        registerRedirects(transformer, shape);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, HostShape shape) {
        // Each old constructor goes to a static factory on the helper. The PermissionSet form only
        // needs one on 26.3, which removed it.
        transformer.registerSyntheticClass(HELPER, generateHelper(shape));
        transformer.registerConstructorRedirect(STACK, INT_FORM, HELPER, "create", factoryDesc(INT_FORM));
        if (shape == HostShape.ENTITY_NAMED) {
            transformer.registerConstructorRedirect(STACK, SET_FORM, HELPER, "create", factoryDesc(SET_FORM));
        }
        for (String name : WITH_LEVEL) {
            transformer.registerMethodRedirect(STACK, name, WITH_LEVEL_OLD, HELPER, name, receiverFirst(WITH_LEVEL_OLD), true);
        }
        // hasPermission(int) goes to the bridge the 26.1 shims already register for it, which a
        // 1.21.11 host needs as well.
        transformer.registerMethodRedirect(STACK, "hasPermission", "(I)Z", HAS_PERMISSION_BRIDGE, "hasPermission",
                "(Ljava/lang/Object;I)Z", true);
    }

    /** The host's constructor shape, read from its own classes. */
    static HostShape probe() {
        ClassNode stack = ClassResourceInspector.read(STACK);
        ClassNode permissions = ClassResourceInspector.read(LEVEL_PERMISSIONS);
        // Offline the host classes are not readable, so the host version decides.
        if (stack == null) {
            if (hostBefore("1.21.11")) return HostShape.INT_LEVEL;
            return hostBefore("26.3") ? HostShape.PERMISSION_SET_NAMED : HostShape.ENTITY_NAMED;
        }
        return shapeOf(stack, permissions);
    }

    /** Matches the stack's public constructors against each known release's shape. */
    static HostShape shapeOf(ClassNode stack, ClassNode permissions) {
        if (hasConstructor(stack, INT_FORM)) return HostShape.INT_LEVEL;
        if (permissions == null || !hasMethod(permissions, "forLevel", FOR_LEVEL)) return HostShape.UNKNOWN;
        if (hasPublicConstructor(stack, SET_FORM)) return HostShape.PERMISSION_SET_NAMED;
        if (hasPublicConstructor(stack, ENTITY_NAMED) && hasPublicConstructor(stack, CONSTANT_NAMED)) {
            return HostShape.ENTITY_NAMED;
        }
        return HostShape.UNKNOWN;
    }

    private static boolean hostBefore(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) < 0;
    }

    private static boolean hasConstructor(ClassNode owner, String desc) {
        return hasMethod(owner, "<init>", desc);
    }

    private static boolean hasPublicConstructor(ClassNode owner, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals("<init>") && method.desc.equals(desc)
                    && (method.access & Opcodes.ACC_PUBLIC) != 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    /** An instance method descriptor as a static one that takes the stack first. */
    private static String receiverFirst(String desc) {
        return "(L" + STACK + ";" + desc.substring(1);
    }

    /** A constructor descriptor as a static factory that returns the stack. */
    static String factoryDesc(String constructorDesc) {
        return constructorDesc.substring(0, constructorDesc.length() - 1) + "L" + STACK + ";";
    }

    /** Builds the helper class with a factory for each old constructor and the old permission methods. */
    static byte[] generateHelper(HostShape shape) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);

        // The int level becomes LevelBasedPermissionSet.forLevel(PermissionLevel.byId(level)), the
        // set the host gives an operator of that level. A level out of range is clamped, as the
        // host's own lookup does.
        MethodVisitor fromLevel = begin(cw, factoryDesc(INT_FORM));
        fromLevel.visitVarInsn(Opcodes.ILOAD, 4);
        fromLevel.visitMethodInsn(Opcodes.INVOKESTATIC, PERMISSION_LEVEL, "byId", BY_ID, false);
        fromLevel.visitMethodInsn(Opcodes.INVOKESTATIC, LEVEL_PERMISSIONS, "forLevel", FOR_LEVEL, true);
        // The set replaces the int in slot 4's place; it is kept past the five slots the call uses.
        fromLevel.visitVarInsn(Opcodes.ASTORE, 9);
        emitConstruct(fromLevel, shape, 9);
        finish(fromLevel);

        // On 26.3 the PermissionSet form already has its set in slot 4.
        if (shape == HostShape.ENTITY_NAMED) {
            MethodVisitor fromSet = begin(cw, factoryDesc(SET_FORM));
            emitConstruct(fromSet, shape, 4);
            finish(fromSet);
        }

        // withPermission(int) and withMaximumPermission(int) turn the level into the same set and
        // pass it to their replacements.
        for (String name : WITH_LEVEL) {
            MethodVisitor with = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                    receiverFirst(WITH_LEVEL_OLD), null, null);
            with.visitCode();
            with.visitVarInsn(Opcodes.ALOAD, 0);
            with.visitVarInsn(Opcodes.ILOAD, 1);
            with.visitMethodInsn(Opcodes.INVOKESTATIC, PERMISSION_LEVEL, "byId", BY_ID, false);
            with.visitMethodInsn(Opcodes.INVOKESTATIC, LEVEL_PERMISSIONS, "forLevel", FOR_LEVEL, true);
            with.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STACK, name, WITH_LEVEL_NEW, false);
            with.visitInsn(Opcodes.ARETURN);
            finish(with);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodVisitor begin(ClassWriter cw, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create", desc, null, null);
        mv.visitCode();
        return mv;
    }

    private static void finish(MethodVisitor mv) {
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Builds the stack from the arguments, with the permission set in local {@code permissions}. */
    private static void emitConstruct(MethodVisitor mv, HostShape shape, int permissions) {
        // Locals 5 to 8 are the text name, display name, server and entity. Before 26.3 they pass
        // straight through.
        if (shape == HostShape.PERMISSION_SET_NAMED) {
            newStack(mv, permissions);
            for (int local = 5; local <= 8; local++) mv.visitVarInsn(Opcodes.ALOAD, local);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, STACK, "<init>", SET_FORM, false);
            mv.visitInsn(Opcodes.ARETURN);
            return;
        }
        // On 26.3 a stack with an entity takes the entity's names, which is what old callers passed
        // in nearly every case. A stack without one keeps the display name, and its text name
        // becomes that name's plain string.
        Label noEntity = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 8);
        mv.visitJumpInsn(Opcodes.IFNULL, noEntity);
        newStack(mv, permissions);
        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitVarInsn(Opcodes.ALOAD, 8);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, STACK, "<init>", ENTITY_NAMED, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(noEntity);
        newStack(mv, permissions);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 7);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, STACK, "<init>", CONSTANT_NAMED, false);
        mv.visitInsn(Opcodes.ARETURN);
    }

    /** {@code new CommandSourceStack(source, position, rotation, level, permissions, ...}. */
    private static void newStack(MethodVisitor mv, int permissions) {
        mv.visitTypeInsn(Opcodes.NEW, STACK);
        mv.visitInsn(Opcodes.DUP);
        for (int local = 0; local <= 3; local++) mv.visitVarInsn(Opcodes.ALOAD, local);
        mv.visitVarInsn(Opcodes.ALOAD, permissions);
    }
}
