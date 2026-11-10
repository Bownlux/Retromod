/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
import com.retromod.shim.forge.Forge_1_21_1_to_1_21_2;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import com.retromod.shim.neoforge.NeoForge_1_21_1_to_1_21_2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The PathType constants 26.1 renamed are repaired on every loader from 26.1, and not before. */
class PathTypeRenameCoverageTest {

    private static final String PATH_TYPE = "net/minecraft/world/level/pathfinder/PathType";
    private static final String BLOCK_PATH_TYPES = "net/minecraft/world/level/pathfinder/BlockPathTypes";
    private static final String[][] RENAMES = {
        {"DAMAGE_FIRE", "FIRE"}, {"DANGER_FIRE", "FIRE_IN_NEIGHBOR"},
        {"DAMAGE_OTHER", "DAMAGING"}, {"DANGER_OTHER", "DAMAGING_IN_NEIGHBOR"},
        {"DANGER_POWDER_SNOW", "ON_TOP_OF_POWDER_SNOW"}, {"DANGER_TRAPDOOR", "ON_TOP_OF_TRAPDOOR"}};

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
    }

    @Test
    @DisplayName("each 26.1 loader shim renames all six PathType constants")
    void loaderShimsOn26RenameEveryConstant() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        for (VersionShim shim : List.of(new NeoForge_1_21_11_to_26_1(), new Forge_1_21_11_to_26_1(),
                new Fabric_1_21_11_to_26_1())) {
            transformer.clearRedirectsForTesting();
            shim.registerRedirects(transformer);
            for (String[] rename : RENAMES) {
                assertEquals(rename[1], fieldReadAfterTransform(PATH_TYPE, rename[0]),
                        shim.getShimName() + ": PathType." + rename[0] + " is gone from 26.1");
            }
        }
    }

    @Test
    @DisplayName("the 1.21.2 shims leave DANGER_FIRE alone, because 1.21.2 to 1.21.11 still declare it")
    void loaderShimsOn1212KeepTheOldNames() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        for (VersionShim shim : List.of(new NeoForge_1_21_1_to_1_21_2(), new Forge_1_21_1_to_1_21_2())) {
            transformer.clearRedirectsForTesting();
            shim.registerRedirects(transformer);
            for (String[] rename : RENAMES) {
                assertEquals(rename[0], fieldReadAfterTransform(PATH_TYPE, rename[0]),
                        shim.getShimName() + ": a 1.21.11 host has no PathType." + rename[1]);
            }
        }
    }

    @Test
    @DisplayName("a pre-1.20.5 BlockPathTypes.DANGER_FIRE read reaches PathType.FIRE_IN_NEIGHBOR on 26.1")
    void blockPathTypesReachTheNewName() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        Legacy1205MemberBridge.register(transformer);
        Common_1_21_11_to_26_1_ClassMoves.registerPathTypeRenames26_1(transformer);

        assertEquals(PATH_TYPE + ".FIRE_IN_NEIGHBOR", fieldAfterTransform(BLOCK_PATH_TYPES, "DANGER_FIRE"));
    }

    private String fieldReadAfterTransform(String owner, String name) {
        String field = fieldAfterTransform(owner, name);
        return field.substring(field.lastIndexOf('.') + 1);
    }

    /** Owner and name of the GETSTATIC a {@code owner.name} read becomes. */
    private String fieldAfterTransform(String owner, String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Mob", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "malus", "()Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, owner, name, "L" + owner + ";");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/Mob")).accept(cn, 0);
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof FieldInsnNode f && f.getOpcode() == GETSTATIC) return f.owner + "." + f.name;
            }
        }
        throw new AssertionError("the field read disappeared");
    }
}
