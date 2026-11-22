/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Gives Fabric API's removed FabricEntityTypeBuilder calls their vanilla EntityType.Builder form, which the class
 * itself already becomes where Fabric API dropped it.
 */
public final class LegacyFabricEntityTypeBuilderBridge {

    static final String OLD = "net/fabricmc/fabric/api/object/builder/v1/entity/FabricEntityTypeBuilder";
    static final String BUILDER = "net/minecraft/world/entity/EntityType$Builder";
    static final String HELPER = "com/retromod/generated/LegacyFabricEntityTypeBuilder";
    private static final String FABRIC_BUILDER = "net/fabricmc/fabric/api/object/builder/v1/entity/FabricEntityType$Builder";
    private static final String CATEGORY = "Lnet/minecraft/world/entity/MobCategory;";
    private static final String FACTORY = "Lnet/minecraft/world/entity/EntityType$EntityFactory;";
    private static final String DIMENSIONS = "net/minecraft/world/entity/EntityDimensions";

    private LegacyFabricEntityTypeBuilderBridge() {}

    /** Registers where the host's Fabric API no longer has the old builder, as the polyfill's redirect does. */
    public static void register(RetromodTransformer t) {
        if (ClassResourceInspector.exists(OLD)) return;
        t.registerSyntheticClass(HELPER, generateHelper());
        String builder = "L" + BUILDER + ";";
        // The class redirect may reach a call before or after this lookup, so both owners are keyed.
        for (String owner : new String[] {OLD, BUILDER}) {
            String self = "L" + owner + ";";
            t.registerMethodRedirect(owner, "create", "(" + CATEGORY + FACTORY + ")" + self, HELPER, "create", "(" + CATEGORY + FACTORY + ")" + builder);
            t.registerMethodRedirect(owner, "create", "(" + CATEGORY + ")" + self, HELPER, "create", "(" + CATEGORY + ")" + builder);
            t.registerMethodRedirect(owner, "create", "()" + self, HELPER, "create", "()" + builder);
            instance(t, owner, "dimensions", "(L" + DIMENSIONS + ";)");
            instance(t, owner, "trackRangeBlocks", "(I)");
            instance(t, owner, "trackable", "(II)");
            instance(t, owner, "trackable", "(IIZ)");
            instance(t, owner, "forceTrackedVelocityUpdates", "(Z)");
            instance(t, owner, "specificSpawnBlocks", "([Lnet/minecraft/world/level/block/Block;)");
            rename(t, owner, "trackRangeChunks", "clientTrackingRange", "(I)");
            rename(t, owner, "trackedUpdateRate", "updateInterval", "(I)");
            rename(t, owner, "disableSummon", "noSummon", "()");
            rename(t, owner, "disableSaving", "noSave", "()");
            rename(t, owner, "spawnableFarFromPlayer", "canSpawnFarFromPlayer", "()");
            rename(t, owner, "requires", "requiredFeatures", "([Lnet/minecraft/world/flag/FeatureFlag;)");
        }
    }

    private static void instance(RetromodTransformer t, String owner, String name, String args) {
        String builder = "L" + BUILDER + ";";
        t.registerMethodRedirect(owner, name, args + "L" + owner + ";", HELPER, name, "(" + builder + args.substring(1) + builder, true);
    }

    private static void rename(RetromodTransformer t, String owner, String name, String newName, String args) {
        String builder = "L" + BUILDER + ";";
        t.registerMethodRedirect(owner, name, args + "L" + owner + ";", BUILDER, newName, args + builder);
    }

    /** Static adapters that build the same entity type through the vanilla builder. */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        String builder = "L" + BUILDER + ";";

        // create(category, factory): the vanilla factory method takes them the other way round.
        MethodVisitor m = start(cw, "create", "(" + CATEGORY + FACTORY + ")" + builder);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESTATIC, BUILDER, "of", "(" + FACTORY + CATEGORY + ")" + builder, false);
        end(m);
        m = start(cw, "create", "(" + CATEGORY + ")" + builder);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESTATIC, BUILDER, "createNothing", "(" + CATEGORY + ")" + builder, false);
        end(m);
        // The old builder's own default category.
        m = start(cw, "create", "()" + builder);
        m.visitFieldInsn(GETSTATIC, "net/minecraft/world/entity/MobCategory", "MISC", CATEGORY);
        m.visitMethodInsn(INVOKESTATIC, BUILDER, "createNothing", "(" + CATEGORY + ")" + builder, false);
        end(m);

        // dimensions(builder, dimensions): the vanilla builder takes the width, height and eye height.
        m = start(cw, "dimensions", "(" + builder + "L" + DIMENSIONS + ";)" + builder);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, DIMENSIONS, "width", "()F", false);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, DIMENSIONS, "height", "()F", false);
        m.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "sized", "(FF)" + builder, false);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, DIMENSIONS, "eyeHeight", "()F", false);
        m.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "eyeHeight", "(F)" + builder, false);
        m.visitVarInsn(ASTORE, 2);
        // The builder has no setter for a fixed size or attachments, so the whole dimensions go in its field where it can be reached.
        org.objectweb.asm.Label start = new org.objectweb.asm.Label();
        org.objectweb.asm.Label end = new org.objectweb.asm.Label();
        org.objectweb.asm.Label unreachable = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        m.visitTryCatchBlock(start, end, unreachable, "java/lang/Exception");
        m.visitLabel(start);
        m.visitLdcInsn(org.objectweb.asm.Type.getObjectType(BUILDER));
        m.visitLdcInsn("dimensions");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
        m.visitInsn(DUP);
        m.visitInsn(ICONST_1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "setAccessible", "(Z)V", false);
        m.visitVarInsn(ALOAD, 2);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "set", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
        m.visitLabel(end);
        m.visitJumpInsn(GOTO, done);
        m.visitLabel(unreachable);
        m.visitInsn(POP);
        m.visitLabel(done);
        m.visitVarInsn(ALOAD, 2);
        end(m);

        // trackRangeBlocks(builder, blocks): the vanilla range counts chunks, rounded up.
        m = start(cw, "trackRangeBlocks", "(" + builder + "I)" + builder);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ILOAD, 1);
        m.visitIntInsn(BIPUSH, 15);
        m.visitInsn(IADD);
        m.visitIntInsn(BIPUSH, 16);
        m.visitInsn(IDIV);
        m.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "clientTrackingRange", "(I)" + builder, false);
        end(m);

        // trackable(builder, range in blocks, update rate[, velocity updates]).
        for (boolean velocity : new boolean[] {false, true}) {
            m = start(cw, "trackable", "(" + builder + "II" + (velocity ? "Z" : "") + ")" + builder);
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(ILOAD, 1);
            m.visitMethodInsn(INVOKESTATIC, HELPER, "trackRangeBlocks", "(" + builder + "I)" + builder, false);
            m.visitVarInsn(ILOAD, 2);
            m.visitMethodInsn(INVOKEVIRTUAL, BUILDER, "updateInterval", "(I)" + builder, false);
            if (velocity) {
                m.visitVarInsn(ILOAD, 3);
                m.visitMethodInsn(INVOKESTATIC, HELPER, "forceTrackedVelocityUpdates", "(" + builder + "Z)" + builder, false);
            }
            end(m);
        }

        // forceTrackedVelocityUpdates(builder, value): Fabric API's own method on the vanilla builder.
        m = start(cw, "forceTrackedVelocityUpdates", "(" + builder + "Z)" + builder);
        m.visitVarInsn(ALOAD, 0);
        m.visitTypeInsn(CHECKCAST, FABRIC_BUILDER);
        m.visitVarInsn(ILOAD, 1);
        m.visitMethodInsn(INVOKEINTERFACE, FABRIC_BUILDER, "alwaysUpdateVelocity", "(Z)" + builder, true);
        end(m);

        // specificSpawnBlocks(builder, blocks): the vanilla builder has no such list, so spawning keeps its usual rules.
        m = start(cw, "specificSpawnBlocks", "(" + builder + "[Lnet/minecraft/world/level/block/Block;)" + builder);
        m.visitVarInsn(ALOAD, 0);
        end(m);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodVisitor start(ClassWriter cw, String name, String desc) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        m.visitCode();
        return m;
    }

    private static void end(MethodVisitor m) {
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
