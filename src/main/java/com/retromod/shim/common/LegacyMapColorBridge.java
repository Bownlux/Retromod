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

/** Bridges {@code MapColor.calculateRGBColor}, which 1.21.2 replaced with {@code calculateARGBColor}. */
public final class LegacyMapColorBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String HELPER = "com/retromod/generated/LegacyMapColors";
    static final String MAP_COLOR = "net/minecraft/world/level/material/MapColor";
    static final String OLD_NAME = "calculateRGBColor";
    static final String NEW_NAME = "calculateARGBColor";
    static final String COLOR_DESC = "(L" + MAP_COLOR + "$Brightness;)I";
    static final String HELPER_DESC = "(L" + MAP_COLOR + ";L" + MAP_COLOR + "$Brightness;)I";

    private LegacyMapColorBridge() {}

    public static void register(RetromodTransformer transformer) {
        if (!hostReplacedRgbColor()) return;
        registerRedirects(transformer);
        LOGGER.debug("Bridged MapColor.calculateRGBColor onto its 1.21.2 ARGB color");
    }

    static boolean hostReplacedRgbColor() {
        ClassNode mapColor = ClassResourceInspector.read(MAP_COLOR);
        // Offline the host class is not readable, so the host version decides.
        if (mapColor == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.2") >= 0;
        }
        boolean hasOld = mapColor.methods.stream().anyMatch(m -> m.name.equals(OLD_NAME) && m.desc.equals(COLOR_DESC));
        boolean hasNew = mapColor.methods.stream().anyMatch(m -> m.name.equals(NEW_NAME) && m.desc.equals(COLOR_DESC));
        return hasNew && !hasOld;
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(HELPER, generateHelper());
        transformer.registerMethodRedirect(MAP_COLOR, OLD_NAME, COLOR_DESC, HELPER, OLD_NAME, HELPER_DESC, true);
    }

    // A Fabric host before 26.1 runs intermediary names, where the color method kept its id.
    static final String INTERMEDIARY_HELPER = "com/retromod/generated/LegacyMapColorsIntermediary";
    static final String INTERMEDIARY_MAP_COLOR = "net/minecraft/class_3620";
    static final String INTERMEDIARY_NAME = "method_15820";
    static final String INTERMEDIARY_COLOR_DESC = "(L" + INTERMEDIARY_MAP_COLOR + "$class_6594;)I";

    /**
     * The same for Fabric mods built before 1.21.2. Their call keeps the intermediary id that now
     * names the ARGB method, so it reaches calculateARGBColor without the bridge and swaps red and
     * blue. A mod built for 1.21.2 or newer means ARGB by the same id, so it is left alone.
     */
    public static void registerFabric(RetromodTransformer transformer) {
        if (RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION)) {
            // Calls are redirected after the remap, so they already carry the new Mojang name.
            if (!transformer.getSyntheticClasses().containsKey(HELPER)) {
                transformer.registerSyntheticClass(HELPER, generateHelper());
            }
            transformer.registerMethodRedirect(MAP_COLOR, NEW_NAME, COLOR_DESC, HELPER, OLD_NAME, HELPER_DESC, true);
            transformer.limitRedirectToModsBefore(MAP_COLOR, NEW_NAME, COLOR_DESC, "1.21.2");
            return;
        }
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.2") < 0) return;
        String helperDesc = "(L" + INTERMEDIARY_MAP_COLOR + ";" + INTERMEDIARY_COLOR_DESC.substring(1);
        if (!transformer.getSyntheticClasses().containsKey(INTERMEDIARY_HELPER)) {
            transformer.registerSyntheticClass(INTERMEDIARY_HELPER, generateHelper(INTERMEDIARY_HELPER,
                    INTERMEDIARY_MAP_COLOR, INTERMEDIARY_NAME, INTERMEDIARY_COLOR_DESC, helperDesc));
        }
        transformer.registerMethodRedirect(INTERMEDIARY_MAP_COLOR, INTERMEDIARY_NAME, INTERMEDIARY_COLOR_DESC,
                INTERMEDIARY_HELPER, OLD_NAME, helperDesc, true);
        transformer.limitRedirectToModsBefore(INTERMEDIARY_MAP_COLOR, INTERMEDIARY_NAME, INTERMEDIARY_COLOR_DESC,
                "1.21.2");
    }

    static byte[] generateHelper() {
        return generateHelper(HELPER, MAP_COLOR, NEW_NAME, COLOR_DESC, HELPER_DESC);
    }

    static byte[] generateHelper(String helper, String mapColor, String newName, String colorDesc,
            String helperDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                helper, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, OLD_NAME, helperDesc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, mapColor, newName, colorDesc, false);
        // Both methods scale the same channels; the old one packed them as ABGR for NativeImage.
        // Reversing 0xAARRGGBB gives 0xBBGGRRAA, and rotating that right by 8 bits gives 0xAABBGGRR.
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "reverseBytes", "(I)I", false);
        mv.visitIntInsn(Opcodes.BIPUSH, 8);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "rotateRight", "(II)I", false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
