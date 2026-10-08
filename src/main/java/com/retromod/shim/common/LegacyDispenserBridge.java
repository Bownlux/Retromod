/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges the dispenser API that 1.20.2 and 1.21 reshaped, for Mojang-named mods built for 1.20.1
 * (Forge 1.20.1 mods after the SRG remap) on a 1.21.x host.
 *
 * <p>1.20.2 turned the {@code BlockSource} interface into the {@code dispenser.BlockSource}
 * record, whose getters drop the {@code get} prefix. 1.21 removed
 * {@code AbstractProjectileDispenseBehavior}, so a mod's projectile behavior fails to load with
 * {@code NoClassDefFoundError} when the mod registers it, which is usually during setup.
 *
 * <p>The interface and its getters are renamed onto the record. The removed base is supplied as a
 * generated class with the 1.20.1 behavior: it asks the subclass for the projectile, shoots it
 * from the dispenser face with the subclass's power and uncertainty, and plays the launch sound.
 * The {@code Position} view of the old interface, {@code x()}, {@code y()} and {@code z()}, is
 * not bridged. 26.1 hosts are left to the 26.1 class moves and rebase.
 */
public final class LegacyDispenserBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String OLD_SOURCE = "net/minecraft/core/BlockSource";
    static final String SOURCE = "net/minecraft/core/dispenser/BlockSource";
    static final String OLD_PROJECTILE_BASE = "net/minecraft/core/dispenser/AbstractProjectileDispenseBehavior";
    static final String PROJECTILE_BASE = "com/retromod/generated/LegacyProjectileDispenseBehavior";
    static final String DEFAULT_BEHAVIOR = "net/minecraft/core/dispenser/DefaultDispenseItemBehavior";

    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String POSITION = "net/minecraft/core/Position";
    private static final String STACK = "net/minecraft/world/item/ItemStack";
    private static final String PROJECTILE = "net/minecraft/world/entity/projectile/Projectile";
    private static final String DISPENSER = "net/minecraft/world/level/block/DispenserBlock";
    private static final String STATE = "net/minecraft/world/level/block/state/BlockState";
    private static final String DIRECTION = "net/minecraft/core/Direction";
    private static final String BLOCK_POS = "net/minecraft/core/BlockPos";

    static final String GET_PROJECTILE_DESC = "(L" + LEVEL + ";L" + POSITION + ";L" + STACK + ";)L" + PROJECTILE + ";";

    static final String DIRECTION_PROPERTY = "Lnet/minecraft/world/level/block/state/properties/DirectionProperty;";
    static final String ENUM_PROPERTY = "Lnet/minecraft/world/level/block/state/properties/EnumProperty;";

    /** Old getter, its return type, and the record accessor that replaced it. */
    static final String[][] GETTERS = {
            {"getLevel", "L" + SERVER_LEVEL + ";", "level", "L" + SERVER_LEVEL + ";"},
            {"getPos", "L" + BLOCK_POS + ";", "pos", "L" + BLOCK_POS + ";"},
            {"getBlockState", "L" + STATE + ";", "state", "L" + STATE + ";"},
            {"getEntity", "Lnet/minecraft/world/level/block/entity/BlockEntity;", "blockEntity",
                    "Lnet/minecraft/world/level/block/entity/DispenserBlockEntity;"},
    };

    private LegacyDispenserBridge() {}

    public static void register(RetromodTransformer transformer) {
        if (!hostHasRecordSource()) return;
        boolean projectileBaseRemoved = !ClassResourceInspector.exists(OLD_PROJECTILE_BASE)
                && (ClassResourceInspector.exists(DEFAULT_BEHAVIOR)
                        || RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21") >= 0);
        registerRedirects(transformer, projectileBaseRemoved);
        LOGGER.info("Bridged the pre-1.20.2 dispenser BlockSource{}",
                projectileBaseRemoved ? " and AbstractProjectileDispenseBehavior" : "");
    }

    /**
     * The host's own classes decide, below 26.1. Offline, where they are not readable, a target
     * from 1.20.2 up to 26.1 does.
     */
    static boolean hostHasRecordSource() {
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0) return false;
        ClassNode source = ClassResourceInspector.read(SOURCE);
        if (source == null && !ClassResourceInspector.exists(OLD_SOURCE)) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20.2") >= 0;
        }
        return source != null && !ClassResourceInspector.exists(OLD_SOURCE)
                && source.methods.stream().anyMatch(m -> m.name.equals("level"));
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, boolean projectileBaseRemoved) {
        transformer.registerClassRedirect(OLD_SOURCE, SOURCE);
        // The record is a class, so the mod's interface calls must become virtual calls.
        transformer.registerClassOwner(SOURCE);
        for (String[] getter : GETTERS) {
            transformer.registerMethodRedirect(OLD_SOURCE, getter[0], "()" + getter[1],
                    SOURCE, getter[2], "()" + getter[3]);
        }
        if (projectileBaseRemoved) {
            transformer.registerSyntheticClass(PROJECTILE_BASE, generateProjectileBase(hostFacingDesc()));
            transformer.registerClassRedirect(OLD_PROJECTILE_BASE, PROJECTILE_BASE);
        }
    }

    /**
     * The type of {@code DispenserBlock.FACING} on this host. 1.21.2 removed {@code DirectionProperty}
     * for {@code EnumProperty<Direction>}, and a field read with the wrong type fails with
     * {@code NoSuchFieldError} the first time a dispenser fires. Offline, the version decides.
     */
    static String hostFacingDesc() {
        ClassNode dispenser = ClassResourceInspector.read(DISPENSER);
        if (dispenser != null) {
            for (org.objectweb.asm.tree.FieldNode field : dispenser.fields) {
                if (field.name.equals("FACING")) return field.desc;
            }
        }
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.2") >= 0
                ? ENUM_PROPERTY : DIRECTION_PROPERTY;
    }

    static byte[] generateProjectileBase(String facingDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER,
                PROJECTILE_BASE, null, DEFAULT_BEHAVIOR, null);

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, DEFAULT_BEHAVIOR, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        emitExecute(cw, facingDesc);
        emitPlaySound(cw);

        cw.visitMethod(Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT, "getProjectile", GET_PROJECTILE_DESC,
                null, null).visitEnd();
        emitFloat(cw, "getUncertainty", 6.0f);
        emitFloat(cw, "getPower", 1.1f);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The 1.20.1 body: build the projectile, shoot it out of the dispenser face, use up one item. */
    private static void emitExecute(ClassWriter cw, String facingDesc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "execute",
                "(L" + SOURCE + ";L" + STACK + ";)L" + STACK + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SOURCE, "level", "()L" + SERVER_LEVEL + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, DISPENSER, "getDispensePosition",
                "(L" + SOURCE + ";)L" + POSITION + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SOURCE, "state", "()L" + STATE + ";", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, DISPENSER, "FACING", facingDesc);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STATE, "getValue",
                "(Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/lang/Comparable;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, DIRECTION);
        mv.visitVarInsn(Opcodes.ASTORE, 5);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PROJECTILE_BASE, "getProjectile", GET_PROJECTILE_DESC, false);
        mv.visitVarInsn(Opcodes.ASTORE, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DIRECTION, "getStepX", "()I", false);
        mv.visitInsn(Opcodes.I2D);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DIRECTION, "getStepY", "()I", false);
        mv.visitInsn(Opcodes.I2F);
        mv.visitLdcInsn(0.1f);
        mv.visitInsn(Opcodes.FADD);
        mv.visitInsn(Opcodes.F2D);
        mv.visitVarInsn(Opcodes.ALOAD, 5);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DIRECTION, "getStepZ", "()I", false);
        mv.visitInsn(Opcodes.I2D);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PROJECTILE_BASE, "getPower", "()F", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PROJECTILE_BASE, "getUncertainty", "()F", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PROJECTILE, "shoot", "(DDDFF)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ALOAD, 6);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SERVER_LEVEL, "addFreshEntity",
                "(Lnet/minecraft/world/entity/Entity;)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STACK, "shrink", "(I)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Level event 1002 is the dispenser launch sound, as the removed base played it. */
    private static void emitPlaySound(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "playSound", "(L" + SOURCE + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SOURCE, "level", "()L" + SERVER_LEVEL + ";", false);
        mv.visitIntInsn(Opcodes.SIPUSH, 1002);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SOURCE, "pos", "()L" + BLOCK_POS + ";", false);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SERVER_LEVEL, "levelEvent",
                "(IL" + BLOCK_POS + ";I)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitFloat(ClassWriter cw, String name, float value) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PROTECTED, name, "()F", null, null);
        mv.visitCode();
        mv.visitLdcInsn(value);
        mv.visitInsn(Opcodes.FRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
