/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyLootSerializers;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.*;

/** Gives a loot function built before 26.3 the condition list it takes, where 26.3 holds one optional condition. */
public final class LegacyLootConditionListBridge {

    static final String FUNCTION = "net/minecraft/world/level/storage/loot/functions/LootItemConditionalFunction";
    static final String FIELDS = "com/retromod/generated/LegacyConditionalFunctionFields";
    static final String SUPPORT = LegacyLootSerializerBridge.SUPPORT;
    static final String FIRST_OPTIONAL_HOST = "26.3";

    static final String INSTANCE = "com/mojang/serialization/codecs/RecordCodecBuilder$Instance";
    static final String P1 = "com/mojang/datafixers/Products$P1";
    static final String APP = "com/mojang/datafixers/kinds/App";
    static final String FUNCTION_TYPE = "java/util/function/Function";
    static final String COMMON_FIELDS_DESC = "(L" + INSTANCE + ";)L" + P1 + ";";
    static final String SIMPLE_BUILDER_DESC = "(L" + FUNCTION_TYPE + ";)L" + FUNCTION + "$Builder;";
    static final String LIST_CONSTRUCTOR = "(Ljava/util/List;)V";
    static final String OPTIONAL_CONSTRUCTOR = "(Ljava/util/Optional;)V";

    private LegacyLootConditionListBridge() {}

    /** Registers on a host whose conditional function takes one optional condition, through the 26.2 to 26.3 core moves. */
    public static void register(RetromodTransformer t) {
        if (!hostTakesOneCondition()) return;
        SyntheticEmbedder.registerClassResource(t, SUPPORT, LegacyLootSerializers.class);
        t.registerSyntheticClass(FIELDS, generateFields());
        // super(conditions) with a list becomes super(the one condition holder).
        t.registerSuperConstructorAdapter(FUNCTION, LIST_CONSTRUCTOR, OPTIONAL_CONSTRUCTOR,
                mv -> mv.visitMethodInsn(INVOKESTATIC, SUPPORT, "conditionHolder", "(Ljava/util/List;)Ljava/util/Optional;", false));
        // commonFields kept its descriptor, so an old codec links and then casts the holder to a list.
        // javac names the mod's own function class as the owner, so the redirect follows the hierarchy.
        for (String[] method : new String[][] {{"commonFields", COMMON_FIELDS_DESC}, {"simpleBuilder", SIMPLE_BUILDER_DESC}}) {
            t.registerInheritedMethodRedirect(FUNCTION, method[0], method[1], FIELDS, method[0], method[1]);
            t.limitRedirectToModsBefore(FUNCTION, method[0], method[1], FIRST_OPTIONAL_HOST);
        }
    }

    /** Read from the host: 26.3's constructor takes an Optional. Offline, the target version decides. */
    static boolean hostTakesOneCondition() {
        ClassNode function = ClassResourceInspector.read(FUNCTION);
        if (function == null) {
            String host = RetromodVersion.TARGET_MC_VERSION;
            return host != null && RetromodVersion.compareMcVersions(host, FIRST_OPTIONAL_HOST) >= 0;
        }
        return function.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals(OPTIONAL_CONSTRUCTOR));
    }

    /**
     * {@code abstract class LegacyConditionalFunctionFields extends LootItemConditionalFunction}: a subclass, so it
     * may call the host's protected static helpers, with the old helpers on top of them. Never constructed.
     */
    static byte[] generateFields() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER, FIELDS, null, FUNCTION, null);

        // commonFields(instance): the host's condition field, read as a list through instance.map.
        // The mapped field still writes through the host's own getter.
        MethodVisitor fields = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "commonFields", COMMON_FIELDS_DESC, null, null);
        fields.visitCode();
        fields.visitTypeInsn(NEW, P1);
        fields.visitInsn(DUP);
        fields.visitVarInsn(ALOAD, 0);
        fields.visitMethodInsn(INVOKESTATIC, SUPPORT, "conditionListOf", "()L" + FUNCTION_TYPE + ";", false);
        fields.visitVarInsn(ALOAD, 0);
        fields.visitMethodInsn(INVOKESTATIC, FUNCTION, "commonFields", COMMON_FIELDS_DESC, false);
        fields.visitMethodInsn(INVOKEVIRTUAL, P1, "t1", "()L" + APP + ";", false);
        fields.visitMethodInsn(INVOKEVIRTUAL, INSTANCE, "map", "(L" + FUNCTION_TYPE + ";L" + APP + ";)L" + APP + ";", false);
        fields.visitMethodInsn(INVOKESPECIAL, P1, "<init>", "(L" + APP + ";)V", false);
        fields.visitInsn(ARETURN);
        fields.visitMaxs(0, 0);
        fields.visitEnd();

        // simpleBuilder(constructor): the host's builder, handing the old constructor a list.
        MethodVisitor builder = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "simpleBuilder", SIMPLE_BUILDER_DESC, null, null);
        builder.visitCode();
        builder.visitVarInsn(ALOAD, 0);
        builder.visitMethodInsn(INVOKESTATIC, SUPPORT, "takingConditionList",
                "(L" + FUNCTION_TYPE + ";)L" + FUNCTION_TYPE + ";", false);
        builder.visitMethodInsn(INVOKESTATIC, FUNCTION, "simpleBuilder", SIMPLE_BUILDER_DESC, false);
        builder.visitInsn(ARETURN);
        builder.visitMaxs(0, 0);
        builder.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
