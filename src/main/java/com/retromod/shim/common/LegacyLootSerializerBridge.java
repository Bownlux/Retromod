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

/** Keeps loot functions and conditions a mod built before 1.20.5 reads with its own Gson serializer. */
public final class LegacyLootSerializerBridge {

    static final String SERIALIZER = "com/retromod/generated/LegacyLootSerializer";
    static final String FUNCTION_SERIALIZER = "com/retromod/generated/LegacyConditionalFunctionSerializer";
    static final String TYPES = "com/retromod/generated/LegacyLootTypes";
    static final String SUPPORT = "com/retromod/shim/common/embedded/LegacyLootSerializers";

    private static final String LOOT = "net/minecraft/world/level/storage/loot/";
    static final String OLD_SERIALIZER = LOOT + "Serializer";
    static final String OLD_FUNCTION_SERIALIZER = LOOT + "functions/LootItemConditionalFunction$Serializer";
    static final String CONDITIONAL_FUNCTION = LOOT + "functions/LootItemConditionalFunction";
    static final String FUNCTION_TYPE = LOOT + "functions/LootItemFunctionType";
    static final String CONDITION_TYPE = LOOT + "predicates/LootItemConditionType";
    private static final String CONDITIONS = "[L" + LOOT + "predicates/LootItemCondition;";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String OBJECT = "Ljava/lang/Object;";

    // Assembled at runtime so the release jar's Gson relocation leaves the game's Gson names alone.
    private static final String GSON = String.join("/", "com", "google", "gson") + "/";
    private static final String JSON = "L" + GSON + "JsonObject;";
    private static final String READ_CONTEXT = "L" + GSON + "JsonDeserializationContext;";
    private static final String WRITE_CONTEXT = "L" + GSON + "JsonSerializationContext;";

    private LegacyLootSerializerBridge() {}

    /** Registers on hosts from 1.20.5, which replaced the Gson loot serializers with codecs. */
    public static void register(RetromodTransformer t) {
        if (!hostReadsLootWithCodecs()) return;
        SyntheticEmbedder.registerClassResource(t, SUPPORT, LegacyLootSerializers.class);
        t.registerSyntheticClass(SERIALIZER, generateSerializer());
        t.registerSyntheticClass(FUNCTION_SERIALIZER, generateFunctionSerializer());
        t.registerSyntheticClass(TYPES, generateTypes());
        t.registerClassRedirect(OLD_SERIALIZER, SERIALIZER);
        t.registerClassRedirect(OLD_FUNCTION_SERIALIZER, FUNCTION_SERIALIZER);

        // new LootItemFunctionType(serializer) builds the type around a codec that runs it.
        t.registerConstructorRedirect(FUNCTION_TYPE, "(L" + OLD_SERIALIZER + ";)V",
                TYPES, "functionType", "(L" + SERIALIZER + ";)L" + FUNCTION_TYPE + ";");
        t.registerConstructorRedirect(CONDITION_TYPE, "(L" + OLD_SERIALIZER + ";)V",
                TYPES, "conditionType", "(L" + SERIALIZER + ";)L" + CONDITION_TYPE + ";");

        // A conditional function's super(conditions[]) takes a list from 1.20.5 and one optional
        // holder from 26.3.
        if (conditionalFunctionTakesOptional()) {
            t.registerSuperConstructorAdapter(CONDITIONAL_FUNCTION, "(" + CONDITIONS + ")V", "(Ljava/util/Optional;)V",
                    mv -> mv.visitMethodInsn(INVOKESTATIC, SUPPORT, "conditionHolder",
                            "([Ljava/lang/Object;)Ljava/util/Optional;", false));
        } else {
            t.registerSuperConstructorAdapter(CONDITIONAL_FUNCTION, "(" + CONDITIONS + ")V", "(Ljava/util/List;)V",
                    mv -> mv.visitMethodInsn(INVOKESTATIC, "java/util/Arrays", "asList",
                            "([Ljava/lang/Object;)Ljava/util/List;", false));
        }
    }

    // Offline, where the host cannot be read, the version decides.
    private static boolean hostReadsLootWithCodecs() {
        if (ClassResourceInspector.read(CONDITIONAL_FUNCTION) == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20.5") >= 0;
        }
        return !ClassResourceInspector.exists(OLD_SERIALIZER);
    }

    private static boolean conditionalFunctionTakesOptional() {
        ClassNode function = ClassResourceInspector.read(CONDITIONAL_FUNCTION);
        if (function == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        return function.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals("(Ljava/util/Optional;)V"));
    }

    // ---- generated classes -------------------------------------------------------------------

    /** The removed {@code Serializer<T>} interface: write a value into JSON and read it back. */
    static byte[] generateSerializer() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, SERIALIZER, null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "serialize", "(" + JSON + OBJECT + WRITE_CONTEXT + ")V", null, null).visitEnd();
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "deserialize", "(" + JSON + READ_CONTEXT + ")" + OBJECT, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * The removed {@code LootItemConditionalFunction.Serializer}: it read the {@code conditions}
     * list and handed it to the subclass's three-argument {@code deserialize}.
     */
    static byte[] generateFunctionSerializer() {
        String function = "L" + CONDITIONAL_FUNCTION + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER, FUNCTION_SERIALIZER, null, "java/lang/Object",
                new String[]{SERIALIZER});

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // serialize(json, function, context): the old base wrote the conditions here. Subclasses call
        // it first and add their own fields; writing loot back to JSON is a data generation job.
        MethodVisitor typedWrite = cw.visitMethod(ACC_PUBLIC, "serialize", "(" + JSON + function + WRITE_CONTEXT + ")V", null, null);
        typedWrite.visitCode();
        typedWrite.visitInsn(RETURN);
        typedWrite.visitMaxs(0, 4);
        typedWrite.visitEnd();

        MethodVisitor write = cw.visitMethod(ACC_PUBLIC, "serialize", "(" + JSON + OBJECT + WRITE_CONTEXT + ")V", null, null);
        write.visitCode();
        write.visitVarInsn(ALOAD, 0);
        write.visitVarInsn(ALOAD, 1);
        write.visitVarInsn(ALOAD, 2);
        write.visitTypeInsn(CHECKCAST, CONDITIONAL_FUNCTION);
        write.visitVarInsn(ALOAD, 3);
        write.visitMethodInsn(INVOKEVIRTUAL, FUNCTION_SERIALIZER, "serialize", "(" + JSON + function + WRITE_CONTEXT + ")V", false);
        write.visitInsn(RETURN);
        write.visitMaxs(0, 0);
        write.visitEnd();

        // deserialize(json, context) = deserialize(json, context, the conditions the JSON lists).
        MethodVisitor typedRead = cw.visitMethod(ACC_PUBLIC, "deserialize", "(" + JSON + READ_CONTEXT + ")" + function, null, null);
        typedRead.visitCode();
        typedRead.visitVarInsn(ALOAD, 0);
        typedRead.visitVarInsn(ALOAD, 1);
        typedRead.visitVarInsn(ALOAD, 2);
        typedRead.visitVarInsn(ALOAD, 1);
        typedRead.visitVarInsn(ALOAD, 2);
        typedRead.visitMethodInsn(INVOKESTATIC, SUPPORT, "conditions", "(" + OBJECT + OBJECT + ")" + OBJECT, false);
        typedRead.visitTypeInsn(CHECKCAST, CONDITIONS);
        typedRead.visitMethodInsn(INVOKEVIRTUAL, FUNCTION_SERIALIZER, "deserialize",
                "(" + JSON + READ_CONTEXT + CONDITIONS + ")" + function, false);
        typedRead.visitInsn(ARETURN);
        typedRead.visitMaxs(0, 0);
        typedRead.visitEnd();

        MethodVisitor read = cw.visitMethod(ACC_PUBLIC, "deserialize", "(" + JSON + READ_CONTEXT + ")" + OBJECT, null, null);
        read.visitCode();
        read.visitVarInsn(ALOAD, 0);
        read.visitVarInsn(ALOAD, 1);
        read.visitVarInsn(ALOAD, 2);
        read.visitMethodInsn(INVOKEVIRTUAL, FUNCTION_SERIALIZER, "deserialize", "(" + JSON + READ_CONTEXT + ")" + function, false);
        read.visitInsn(ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();

        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "deserialize", "(" + JSON + READ_CONTEXT + CONDITIONS + ")" + function,
                null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code functionType(serializer)} and {@code conditionType(serializer)}: the type around the serializer's codec. */
    static byte[] generateTypes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, TYPES, null, "java/lang/Object", null);
        writeTypeFactory(cw, "functionType", FUNCTION_TYPE, "functionCodec");
        writeTypeFactory(cw, "conditionType", CONDITION_TYPE, "conditionCodec");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void writeTypeFactory(ClassWriter cw, String name, String type, String codecMethod) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(L" + SERIALIZER + ";)L" + type + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, type);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, codecMethod, "(" + OBJECT + ")" + OBJECT, false);
        mv.visitTypeInsn(CHECKCAST, MAP_CODEC);
        mv.visitMethodInsn(INVOKESPECIAL, type, "<init>", "(L" + MAP_CODEC + ";)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
