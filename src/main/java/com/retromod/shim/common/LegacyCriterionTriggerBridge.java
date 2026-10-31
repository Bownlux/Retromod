/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyCriteria;
import com.retromod.shim.forge.embedded.LateVanillaRegistration;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.*;

/** Keeps advancement triggers a mod built before 1.20.2 reads from JSON, on hosts that read them with codecs. */
public final class LegacyCriterionTriggerBridge {

    static final String INSTANCE = "com/retromod/generated/LegacyCriterionInstance";
    static final String TRIGGER = "com/retromod/generated/LegacySimpleCriterionTrigger";
    static final String CALLS = "com/retromod/generated/LegacyCriteriaCalls";
    static final String READ_CONTEXT = "com/retromod/generated/LegacyDeserializationContext";
    static final String WRITE_CONTEXT = "com/retromod/generated/LegacySerializationContext";
    static final String SUPPORT = "com/retromod/shim/common/embedded/LegacyCriteria";
    static final String LATE = "com/retromod/shim/forge/embedded/LateVanillaRegistration";

    private static final String CRITERIA = "net/minecraft/advancements/critereon/";
    static final String OLD_INSTANCE = CRITERIA + "AbstractCriterionTriggerInstance";
    static final String SIMPLE_TRIGGER = CRITERIA + "SimpleCriterionTrigger";
    static final java.util.List<String> SIMPLE_TRIGGER_NAMES = java.util.List.of(SIMPLE_TRIGGER,
            "net/minecraft/advancements/criterion/SimpleCriterionTrigger",
            "net/minecraft/advancements/triggers/SimpleCriterionTrigger");
    private static final String SIMPLE_INSTANCE = SIMPLE_TRIGGER + "$SimpleInstance";
    private static final String CONTEXT_PREDICATE = CRITERIA + "ContextAwarePredicate";
    private static final String ENTITY_PREDICATE = CRITERIA + "EntityPredicate";
    private static final String ITEM_PREDICATE = CRITERIA + "ItemPredicate";
    private static final String TRIGGERS = "net/minecraft/advancements/CriteriaTriggers";
    private static final String CRITERION_TRIGGER = "net/minecraft/advancements/CriterionTrigger";
    private static final String ID = "net/minecraft/resources/ResourceLocation";
    private static final String OBJECT = "Ljava/lang/Object;";

    // Assembled at runtime so the release jar's Gson relocation leaves the game's Gson names alone.
    private static final String GSON = String.join("/", "com", "google", "gson") + "/";
    private static final String JSON_OBJECT = "L" + GSON + "JsonObject;";
    private static final String JSON_ELEMENT = "L" + GSON + "JsonElement;";

    private LegacyCriterionTriggerBridge() {}

    /** Registers on hosts from 1.20.2, which removed the JSON trigger API. */
    public static void register(RetromodTransformer t) {
        if (!removedOnHost(OLD_INSTANCE, "1.20.2")) return;
        SyntheticEmbedder.registerClassResource(t, SUPPORT, LegacyCriteria.class);
        SyntheticEmbedder.registerClassResource(t, LATE, LateVanillaRegistration.class);
        t.registerSyntheticClass(READ_CONTEXT, emptyClass(READ_CONTEXT));
        t.registerSyntheticClass(WRITE_CONTEXT, emptyClass(WRITE_CONTEXT));
        t.registerSyntheticClass(INSTANCE, generateInstance());
        t.registerSyntheticClass(TRIGGER, generateTrigger());
        t.registerSyntheticClass(CALLS, generateCalls());

        // The removed types become stand-ins; a trigger's base gains the codec 1.20.2 asks for.
        t.registerClassRedirect(OLD_INSTANCE, INSTANCE);
        t.registerClassRedirect(CRITERIA + "DeserializationContext", READ_CONTEXT);
        t.registerClassRedirect(CRITERIA + "SerializationContext", WRITE_CONTEXT);
        // 26.1 moved the base to criterion/ and 26.2 to triggers/. The move runs before the
        // rebase, so the rebase is keyed on every name the base passes through.
        for (String base : SIMPLE_TRIGGER_NAMES) t.registerSuperclassRebase(base, TRIGGER);

        // The removed predicate members and the one-argument register.
        String read = "L" + CRITERIA + "DeserializationContext;";
        String write = "L" + CRITERIA + "SerializationContext;";
        String context = "L" + CONTEXT_PREDICATE + ";";
        t.registerMethodRedirect(TRIGGERS, "register", "(L" + CRITERION_TRIGGER + ";)L" + CRITERION_TRIGGER + ";",
                CALLS, "register", "(L" + CRITERION_TRIGGER + ";)L" + CRITERION_TRIGGER + ";");
        t.registerExactFieldRedirect(CONTEXT_PREDICATE, "ANY", context, CALLS, "any", "()" + context);
        t.registerMethodRedirect(ENTITY_PREDICATE, "fromJson", "(" + JSON_OBJECT + "Ljava/lang/String;" + read + ")" + context,
                CALLS, "fromJson", "(" + JSON_OBJECT + "Ljava/lang/String;L" + READ_CONTEXT + ";)" + context);
        t.registerMethodRedirect(CONTEXT_PREDICATE, "toJson", "(" + write + ")" + JSON_ELEMENT,
                CALLS, "toJson", "(" + context + "L" + WRITE_CONTEXT + ";)" + JSON_ELEMENT, true);

        // ItemPredicate became a codec record in 1.20.5.
        if (removedOnHost(ITEM_PREDICATE, "fromJson", "1.20.5")) {
            String item = "L" + ITEM_PREDICATE + ";";
            t.registerMethodRedirect(ITEM_PREDICATE, "fromJson", "(" + JSON_ELEMENT + ")" + item,
                    CALLS, "itemFromJson", "(" + JSON_ELEMENT + ")" + item);
            t.registerMethodRedirect(ITEM_PREDICATE, "serializeToJson", "()" + JSON_ELEMENT,
                    CALLS, "itemToJson", "(" + item + ")" + JSON_ELEMENT, true);
            t.registerExactFieldRedirect(ITEM_PREDICATE, "ANY", item, CALLS, "itemAny", "()" + item);
            t.registerMethodRedirect(ITEM_PREDICATE, "matches", "(Lnet/minecraft/world/item/ItemStack;)Z",
                    ITEM_PREDICATE, "test", "(Lnet/minecraft/world/item/ItemStack;)Z");
        }
    }

    // Offline, where the host cannot be read, the version decides.
    private static boolean removedOnHost(String type, String since) {
        if (ClassResourceInspector.read(CONTEXT_PREDICATE) == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, since) >= 0;
        }
        return !ClassResourceInspector.exists(type);
    }

    private static boolean removedOnHost(String type, String method, String since) {
        ClassNode node = ClassResourceInspector.read(type);
        if (node == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, since) >= 0;
        return node.methods.stream().noneMatch(m -> m.name.equals(method));
    }

    // ---- generated classes -------------------------------------------------------------------

    /** A stand-in for a removed type that old code only passes around, as null. */
    static byte[] emptyClass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The old {@code AbstractCriterionTriggerInstance}: an id and a player predicate, as a current simple instance. */
    static byte[] generateInstance() {
        String context = "L" + CONTEXT_PREDICATE + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER, INSTANCE, null, "java/lang/Object", new String[]{SIMPLE_INSTANCE});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "criterion", "L" + ID + ";", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "player", context, null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + ID + ";" + context + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, INSTANCE, "criterion", "L" + ID + ";");
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 2);
        init.visitFieldInsn(PUTFIELD, INSTANCE, "player", context);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        getter(cw, "getCriterion", "criterion", "L" + ID + ";");
        getter(cw, "getPlayerPredicate", "player", context);

        // player() is what a current trigger reads; the old ANY predicate passes every player either way.
        MethodVisitor player = cw.visitMethod(ACC_PUBLIC, "player", "()Ljava/util/Optional;", null, null);
        player.visitCode();
        player.visitVarInsn(ALOAD, 0);
        player.visitFieldInsn(GETFIELD, INSTANCE, "player", context);
        player.visitMethodInsn(INVOKESTATIC, "java/util/Optional", "ofNullable", "(" + OBJECT + ")Ljava/util/Optional;", false);
        player.visitInsn(ARETURN);
        player.visitMaxs(0, 0);
        player.visitEnd();

        // serializeToJson wrote an advancement back to JSON, a data generation job.
        MethodVisitor write = cw.visitMethod(ACC_PUBLIC, "serializeToJson", "(L" + WRITE_CONTEXT + ";)" + JSON_OBJECT, null, null);
        write.visitCode();
        write.visitTypeInsn(NEW, JSON_OBJECT.substring(1, JSON_OBJECT.length() - 1));
        write.visitInsn(DUP);
        write.visitMethodInsn(INVOKESPECIAL, JSON_OBJECT.substring(1, JSON_OBJECT.length() - 1), "<init>", "()V", false);
        write.visitInsn(ARETURN);
        write.visitMaxs(0, 0);
        write.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The old {@code SimpleCriterionTrigger} on the current one: its codec runs the mod's JSON reader. */
    static byte[] generateTrigger() {
        String context = "L" + CONTEXT_PREDICATE + ";";
        String instance = "L" + INSTANCE + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_SUPER, TRIGGER, null, SIMPLE_TRIGGER, null);

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, SIMPLE_TRIGGER, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor codec = cw.visitMethod(ACC_PUBLIC, "codec", "()Lcom/mojang/serialization/Codec;", null, null);
        codec.visitCode();
        codec.visitVarInsn(ALOAD, 0);
        codec.visitMethodInsn(INVOKESTATIC, SUPPORT, "codec", "(" + OBJECT + ")" + OBJECT, false);
        codec.visitTypeInsn(CHECKCAST, "com/mojang/serialization/Codec");
        codec.visitInsn(ARETURN);
        codec.visitMaxs(0, 0);
        codec.visitEnd();

        // createInstance(json, context) = createInstance(json, the JSON's player predicate, context).
        MethodVisitor read = cw.visitMethod(ACC_PUBLIC, "createInstance", "(" + JSON_OBJECT + "L" + READ_CONTEXT + ";)" + instance, null, null);
        read.visitCode();
        read.visitVarInsn(ALOAD, 0);
        read.visitVarInsn(ALOAD, 1);
        read.visitVarInsn(ALOAD, 1);
        read.visitMethodInsn(INVOKESTATIC, SUPPORT, "player", "(" + OBJECT + ")" + OBJECT, false);
        read.visitTypeInsn(CHECKCAST, CONTEXT_PREDICATE);
        read.visitVarInsn(ALOAD, 2);
        read.visitMethodInsn(INVOKEVIRTUAL, TRIGGER, "createInstance",
                "(" + JSON_OBJECT + context + "L" + READ_CONTEXT + ";)" + instance, false);
        read.visitInsn(ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();

        cw.visitMethod(ACC_PROTECTED | ACC_ABSTRACT, "createInstance",
                "(" + JSON_OBJECT + context + "L" + READ_CONTEXT + ";)" + instance, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Exact-typed static adapters for the removed predicate members, each forwarding to the helper. */
    static byte[] generateCalls() {
        String context = "L" + CONTEXT_PREDICATE + ";";
        String item = "L" + ITEM_PREDICATE + ";";
        String trigger = "L" + CRITERION_TRIGGER + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CALLS, null, "java/lang/Object", null);
        forward(cw, "register", "(" + trigger + ")" + trigger, "register", 1);
        forward(cw, "any", "()" + context, "any", 0);
        forward(cw, "fromJson", "(" + JSON_OBJECT + "Ljava/lang/String;L" + READ_CONTEXT + ";)" + context, "fromJson", 3);
        forward(cw, "toJson", "(" + context + "L" + WRITE_CONTEXT + ";)" + JSON_ELEMENT, "toJson", 1);
        forward(cw, "itemFromJson", "(" + JSON_ELEMENT + ")" + item, "itemFromJson", 1);
        forward(cw, "itemToJson", "(" + item + ")" + JSON_ELEMENT, "itemToJson", 1);
        forward(cw, "itemAny", "()" + item, "itemAny", 0);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static R name(args)}: the first {@code passed} arguments to the helper, its result cast to R. */
    private static void forward(ClassWriter cw, String name, String desc, String helper, int passed) {
        Type[] args = Type.getArgumentTypes(desc);
        Type result = Type.getReturnType(desc);
        StringBuilder helperDesc = new StringBuilder("(");
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        for (int i = 0; i < passed; i++) {
            mv.visitVarInsn(ALOAD, i);
            helperDesc.append(args[i].getSort() == Type.OBJECT && args[i].getInternalName().equals("java/lang/String")
                    ? "Ljava/lang/String;" : OBJECT);
        }
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, helper, helperDesc + ")" + OBJECT, false);
        mv.visitTypeInsn(CHECKCAST, result.getInternalName());
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void getter(ClassWriter cw, String name, String field, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, "()" + desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, INSTANCE, field, desc);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
