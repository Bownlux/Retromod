/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps the stone and deepslate ore targets that pre-1.19.3 ore features read from
 * {@code OreFeatures}.
 *
 * <p>1.19.3 turned them into locals of the data pack bootstrap, so a mod's ore feature class
 * failed in its static initializer, and with it the biome loading handler that adds the ores,
 * before that handler's other additions such as natural spawns. Each read now builds the same
 * tag test the constant held.
 */
public final class LegacyOreRuleTests {

    static final String SYNTH = "com/retromod/generated/LegacyOreRuleTests";

    private static final String ORE_FEATURES = "net/minecraft/data/worldgen/features/OreFeatures";
    private static final String TEMPLATES = "net/minecraft/world/level/levelgen/structure/templatesystem/";
    private static final String RULE_TEST = TEMPLATES + "RuleTest";
    private static final String TAG_MATCH = TEMPLATES + "TagMatchTest";
    private static final String[] CONSTANTS = {"STONE_ORE_REPLACEABLES", "DEEPSLATE_ORE_REPLACEABLES"};

    private LegacyOreRuleTests() {
    }

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(SYNTH, generate());
        for (String constant : CONSTANTS) {
            transformer.registerFieldRedirect(ORE_FEATURES, constant, "L" + RULE_TEST + ";",
                    SYNTH, constant, "()L" + RULE_TEST + ";");
        }
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SYNTH, null, "java/lang/Object", null);
        for (String constant : CONSTANTS) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, constant, "()L" + RULE_TEST + ";", null, null);
            mv.visitCode();
            mv.visitTypeInsn(NEW, TAG_MATCH);
            mv.visitInsn(DUP);
            mv.visitFieldInsn(GETSTATIC, "net/minecraft/tags/BlockTags", constant, "Lnet/minecraft/tags/TagKey;");
            mv.visitMethodInsn(INVOKESPECIAL, TAG_MATCH, "<init>", "(Lnet/minecraft/tags/TagKey;)V", false);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
