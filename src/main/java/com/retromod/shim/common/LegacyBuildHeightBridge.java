/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/** Answers the exclusive build height getters 1.21.2 replaced with inclusive ones, for older mods on every loader. */
public final class LegacyBuildHeightBridge {

    static final String HELPER = "com/retromod/generated/LegacyBuildHeight";
    static final String HEIGHT = "net/minecraft/world/level/LevelHeightAccessor";
    static final String HELPER_DESC = "(L" + HEIGHT + ";)I";

    /** One namespace's names: the accessor, its floor and height getters, and the two inclusive getters. */
    record Names(String helper, String height, String minY, String heightName, String maxY, String maxSectionY,
            List<String> calledOn) {
        String helperDesc() {
            return "(L" + height + ";)I";
        }
    }

    // getMaxBuildHeight and getMaxSection were one past the top. getMaxY and getMaxSectionY are the
    // top itself, so a plain rename would shift every loop bound by one.
    static final Names MOJANG = new Names(HELPER, HEIGHT, "getMinY", "getHeight", "getMaxY", "getMaxSectionY",
            List.of("net/minecraft/world/level/Level", "net/minecraft/server/level/ServerLevel",
                    "net/minecraft/client/multiplayer/ClientLevel", "net/minecraft/world/level/LevelReader",
                    "net/minecraft/world/level/LevelAccessor", "net/minecraft/world/level/WorldGenLevel",
                    "net/minecraft/world/level/BlockGetter", "net/minecraft/world/level/chunk/ChunkAccess",
                    "net/minecraft/world/level/chunk/LevelChunk", "net/minecraft/world/level/chunk/ProtoChunk",
                    "net/minecraft/server/level/WorldGenRegion"));

    // A Fabric host before 26.1 runs intermediary names, where every getter kept its id.
    static final Names INTERMEDIARY = new Names("com/retromod/generated/LegacyBuildHeightIntermediary",
            "net/minecraft/class_5539", "method_31607", "method_31605", "method_31600", "method_31597",
            List.of("net/minecraft/class_1937", "net/minecraft/class_3218", "net/minecraft/class_638",
                    "net/minecraft/class_4538", "net/minecraft/class_1936", "net/minecraft/class_5281",
                    "net/minecraft/class_1922", "net/minecraft/class_2791", "net/minecraft/class_2818",
                    "net/minecraft/class_2839", "net/minecraft/class_3233"));

    // The ids a Fabric mod built before 1.21.2 calls and overrides the old getters by.
    static final String MAX_BUILD_HEIGHT_ID = "method_31600";
    static final String MAX_SECTION_ID = "method_31597";

    private LegacyBuildHeightBridge() {}

    /** Sends each old getter to a helper that adds one to its 1.21.2 replacement. */
    public static void register(RetromodTransformer transformer) {
        registerHelper(transformer, MOJANG);
        redirect(transformer, MOJANG, "getMaxBuildHeight", "getMaxBuildHeight", false);
        redirect(transformer, MOJANG, "getMaxSection", "getMaxSection", false);
    }

    /**
     * The same for Fabric mods built before 1.21.2. Their getters keep the intermediary id that
     * now names the inclusive getter, so the 26.x name table gives them getMaxY, and a Fabric host
     * before 26.1 runs that id as getMaxY too. A mod built for 1.21.2 or newer means the inclusive
     * getter by the same id, so it is left alone.
     */
    public static void registerFabric(RetromodTransformer transformer) {
        boolean mojang = RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION);
        if (!mojang && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.2") < 0) return;
        Names names = mojang ? MOJANG : INTERMEDIARY;
        registerHelper(transformer, names);
        // Calls through host types are redirected after the remap, so they carry the host's names.
        redirect(transformer, names, names.maxY(), "getMaxBuildHeight", true);
        redirect(transformer, names, names.maxSectionY(), "getMaxSection", true);
        // The mod's own override keeps the old meaning under the old name, and the class answers the
        // host's getter one lower, so neither the host nor the helper reads the old value as inclusive.
        transformer.registerOwnOverrideRename(MAX_BUILD_HEIGHT_ID, names.maxY(), "()I", "getMaxBuildHeight", "1.21.2", -1);
        transformer.registerOwnOverrideRename(MAX_SECTION_ID, names.maxSectionY(), "()I", "getMaxSection", "1.21.2", -1);
        // That override's super call reaches the host's getter, which must not run the override again.
        transformer.registerSuperCallHelper(names.helper(), "getMaxBuildHeight", names.helperDesc(),
                "getMaxBuildHeightSuper");
        transformer.registerSuperCallHelper(names.helper(), "getMaxSection", names.helperDesc(), "getMaxSectionSuper");
    }

    /** Redirects {@code name} on the accessor, its subclasses, and the classes mods call it through. */
    private static void redirect(RetromodTransformer transformer, Names names, String name, String helperName,
            boolean onlyOlderMods) {
        transformer.registerInheritedMethodRedirect(names.height(), name, "()I", names.helper(), helperName,
                names.helperDesc());
        if (onlyOlderMods) transformer.limitRedirectToModsBefore(names.height(), name, "()I", "1.21.2");
        for (String owner : names.calledOn()) {
            transformer.registerMethodRedirect(owner, name, "()I", names.helper(), helperName, names.helperDesc());
            if (onlyOlderMods) transformer.limitRedirectToModsBefore(owner, name, "()I", "1.21.2");
        }
    }

    private static void registerHelper(RetromodTransformer transformer, Names names) {
        if (!transformer.getSyntheticClasses().containsKey(names.helper())) {
            transformer.registerSyntheticClass(names.helper(), generate(names));
        }
    }

    /** The Mojang-named helper. */
    static byte[] generate() {
        return generate(MOJANG);
    }

    /**
     * {@code getMaxBuildHeight(level)} is {@code level.getMaxY() + 1}, and the same for sections. The
     * super variants answer the host's own value from the floor and height, without calling an override.
     */
    static byte[] generate(Names names) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, names.helper(), null, "java/lang/Object", null);
        plusOne(cw, names, "getMaxBuildHeight", names.maxY());
        plusOne(cw, names, "getMaxSection", names.maxSectionY());

        // getMaxBuildHeightSuper(level): level.getMinY() + level.getHeight()
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getMaxBuildHeightSuper", names.helperDesc(), null, null);
        mv.visitCode();
        floorPlusHeight(mv, names);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // getMaxSectionSuper(level): ((level.getMinY() + level.getHeight() - 1) >> 4) + 1
        mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "getMaxSectionSuper", names.helperDesc(), null, null);
        mv.visitCode();
        floorPlusHeight(mv, names);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ISUB);
        mv.visitInsn(ICONST_4);
        mv.visitInsn(ISHR);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void plusOne(ClassWriter cw, Names names, String name, String inclusive) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, names.helperDesc(), null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, names.height(), inclusive, "()I", true);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void floorPlusHeight(MethodVisitor mv, Names names) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, names.height(), names.minY(), "()I", true);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEINTERFACE, names.height(), names.heightName(), "()I", true);
        mv.visitInsn(IADD);
    }
}
