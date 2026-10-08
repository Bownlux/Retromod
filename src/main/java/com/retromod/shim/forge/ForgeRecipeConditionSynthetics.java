/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

/**
 * Stand-ins for the Forge 1.20.1 recipe condition API that NeoForge replaced with codecs.
 *
 * <p>Forge mods declare custom recipe conditions as classes implementing {@code ICondition} with a
 * companion {@code IConditionSerializer}, and register the serializer from their mod constructor
 * through {@code CraftingHelper.register}. NeoForge 21 reads conditions through
 * {@code MapCodec}s registered in its own registry, so none of those three types exist and the
 * mod dies in its constructor with {@code NoClassDefFoundError}.
 *
 * <p>These stand-ins only let the mod construct. The interfaces carry no members, so the mod's
 * own condition classes still load, and {@code register} returns its argument without registering
 * anything. A recipe that uses one of the mod's custom conditions is therefore not gated by it:
 * NeoForge ignores the old {@code conditions} key, so the recipe either loads unconditionally or
 * fails on a missing item and is skipped with a log line. Real support would need a codec adapter
 * that wraps each Forge serializer and registers it during {@code RegisterEvent}.
 */
final class ForgeRecipeConditionSynthetics {

    static final String CONDITION = "net/minecraftforge/common/crafting/conditions/ICondition";
    static final String CONTEXT = CONDITION + "$IContext";
    static final String SERIALIZER = "net/minecraftforge/common/crafting/conditions/IConditionSerializer";
    static final String CRAFTING_HELPER = "net/minecraftforge/common/crafting/CraftingHelper";

    private ForgeRecipeConditionSynthetics() {}

    /** Every stand-in, keyed by internal name. */
    static Map<String, byte[]> generateAll() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(CONDITION, emptyInterface(CONDITION));
        classes.put(CONTEXT, emptyInterface(CONTEXT));
        classes.put(SERIALIZER, emptyInterface(SERIALIZER));
        classes.put(CRAFTING_HELPER, craftingHelper());
        return classes;
    }

    private static byte[] emptyInterface(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code CraftingHelper.register(IConditionSerializer)} returns its argument unchanged. */
    private static byte[] craftingHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CRAFTING_HELPER, null, "java/lang/Object", null);
        String desc = "(L" + SERIALIZER + ";)L" + SERIALIZER + ";";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
