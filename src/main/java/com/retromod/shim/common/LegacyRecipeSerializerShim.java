/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps a 1.21.x mod's recipe serializers registering and loading on 26.1.
 *
 * <p>Through 1.21.11 {@code RecipeSerializer} was an interface a mod implemented with its own
 * {@code codec()} and {@code streamCodec()}. 26.1 made it a final class built from those two
 * codecs, moved the vanilla serializer constants onto each recipe class as {@code SERIALIZER}, and
 * removed {@code SimpleCraftingRecipeSerializer} (all verified against the 26.1.2 jar). A 1.21.x
 * serializer class then fails at definition with {@code IncompatibleClassChangeError} "can not
 * implement RecipeSerializer, because it is not an interface" before the mod's entry point
 * finishes (#249).
 *
 * <p>The repair has four parts. The mod class drops the interface, so it loads. Registering it
 * adapts the object into a real 26.1 {@code RecipeSerializer} built from its own codecs, which
 * {@link WorldgenTypeBridgeSynthetic} does at the {@code Registry.register} boundary. Calls to
 * {@code codec()} and {@code streamCodec()} accept either shape. And
 * {@code SimpleCraftingRecipeSerializer} becomes a generated stand-in with the same constructor
 * and codecs.
 *
 * <p>The adapted codec traps a {@code LinkageError} or a runtime exception from the mod's decoder
 * and reports it once as a recipe load error. A recipe whose codec still fails does not load, but
 * it no longer stops the data pack. {@link LegacyRecipeSubclassAdapter} repairs the common recipe
 * subclasses that read removed recipe internals.
 */
public class LegacyRecipeSerializerShim implements MinecraftVersionedApiShim {

    static final String RECIPE_SERIALIZER = "net/minecraft/world/item/crafting/RecipeSerializer";
    static final String OLD_SIMPLE_SERIALIZER =
            "net/minecraft/world/item/crafting/SimpleCraftingRecipeSerializer";
    static final String OLD_SIMPLE_FACTORY = OLD_SIMPLE_SERIALIZER + "$Factory";
    private static final String EMBEDDED = "com/retromod/shim/common/embedded/";
    public static final String BRIDGE = EMBEDDED + "LegacyRecipeSerializerBridge";
    public static final String SAFE_CODEC = EMBEDDED + "LegacyRecipeMapCodec";
    public static final String SIMPLE_SERIALIZER = EMBEDDED + "LegacySimpleCraftingRecipeSerializer";
    public static final String SIMPLE_FACTORY = SIMPLE_SERIALIZER + "$Factory";
    public static final String CATEGORY_GETTER = SIMPLE_SERIALIZER + "$CategoryGetter";
    private static final String CRAFTING = "net/minecraft/world/item/crafting/";
    private static final String CRAFTING_RECIPE = CRAFTING + "CraftingRecipe";
    private static final String CATEGORY = CRAFTING + "CraftingBookCategory";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String CODEC = "com/mojang/serialization/Codec";
    private static final String DATA_RESULT = "com/mojang/serialization/DataResult";
    private static final String STREAM_CODEC = "net/minecraft/network/codec/StreamCodec";
    private static final String L_MAP_CODEC = "L" + MAP_CODEC + ";";
    private static final String L_STREAM_CODEC = "L" + STREAM_CODEC + ";";
    private static final String FUNCTION = "java/util/function/Function";
    private static final String FACTORY_CREATE_DESC = "(L" + CRAFTING + "CraftingBookCategory;)L"
            + CRAFTING + "CraftingRecipe;";

    /** 1.21.x {@code RecipeSerializer} constant to the 26.1 recipe class that now owns it. */
    private static final Map<String, String> MOVED_CONSTANTS = Map.ofEntries(
            Map.entry("SHAPED_RECIPE", "ShapedRecipe"),
            Map.entry("SHAPELESS_RECIPE", "ShapelessRecipe"),
            Map.entry("BOOK_CLONING", "BookCloningRecipe"),
            Map.entry("MAP_EXTENDING", "MapExtendingRecipe"),
            Map.entry("FIREWORK_ROCKET", "FireworkRocketRecipe"),
            Map.entry("FIREWORK_STAR", "FireworkStarRecipe"),
            Map.entry("FIREWORK_STAR_FADE", "FireworkStarFadeRecipe"),
            Map.entry("BANNER_DUPLICATE", "BannerDuplicateRecipe"),
            Map.entry("SHIELD_DECORATION", "ShieldDecorationRecipe"),
            Map.entry("REPAIR_ITEM", "RepairItemRecipe"),
            Map.entry("SMELTING_RECIPE", "SmeltingRecipe"),
            Map.entry("BLASTING_RECIPE", "BlastingRecipe"),
            Map.entry("SMOKING_RECIPE", "SmokingRecipe"),
            Map.entry("CAMPFIRE_COOKING_RECIPE", "CampfireCookingRecipe"),
            Map.entry("STONECUTTER", "StonecutterRecipe"),
            Map.entry("SMITHING_TRANSFORM", "SmithingTransformRecipe"),
            Map.entry("SMITHING_TRIM", "SmithingTrimRecipe"),
            Map.entry("DECORATED_POT_RECIPE", "DecoratedPotRecipe"));

    @Override
    public String getShimName() {
        return "Legacy Recipe Serializers";
    }

    @Override
    public String getSourceVersion() {
        return "1.21.11";
    }

    @Override
    public String getTargetVersion() {
        return "26.1";
    }

    @Override
    public String getModLoaderType() {
        return "common";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(BRIDGE, generateBridge());
        transformer.registerSyntheticClass(SAFE_CODEC, generateSafeCodec());
        transformer.registerSyntheticClass(SIMPLE_SERIALIZER, generateSimpleSerializer());
        transformer.registerSyntheticClass(SIMPLE_FACTORY, generateSimpleFactory());
        transformer.registerSyntheticClass(CATEGORY_GETTER, generateCategoryGetter());
        // Recipe subclasses that copy vanilla recipes read the result through this helper, which
        // LegacyRecipeSubclassAdapter wires in after the transform loop.
        com.retromod.core.SyntheticEmbedder.registerClassResource(transformer,
                LegacyRecipeSubclassAdapter.INTERNALS, LegacyRecipeSerializerShim.class);
        // The mod class keeps its codec() and streamCodec() methods but no longer claims the
        // interface, which 26.1 turned into a final class.
        transformer.registerDroppedInterface(RECIPE_SERIALIZER);
        transformer.registerMethodRedirect(RECIPE_SERIALIZER, "codec",
                "()Lcom/mojang/serialization/MapCodec;", BRIDGE, "codec",
                "(Ljava/lang/Object;)Lcom/mojang/serialization/MapCodec;", true);
        transformer.registerMethodRedirect(RECIPE_SERIALIZER, "streamCodec",
                "()Lnet/minecraft/network/codec/StreamCodec;", BRIDGE, "streamCodec",
                "(Ljava/lang/Object;)Lnet/minecraft/network/codec/StreamCodec;", true);
        String serializerDesc = "L" + RECIPE_SERIALIZER + ";";
        MOVED_CONSTANTS.forEach((field, recipe) -> transformer.registerFieldRedirect(
                RECIPE_SERIALIZER, field, serializerDesc, CRAFTING + recipe, "SERIALIZER",
                serializerDesc));
        transformer.registerClassRedirect(OLD_SIMPLE_SERIALIZER, SIMPLE_SERIALIZER);
        transformer.registerClassRedirect(OLD_SIMPLE_FACTORY, SIMPLE_FACTORY);
    }

    public static Map<String, String> movedConstants() {
        return MOVED_CONSTANTS;
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }

    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
    }

    /**
     * {@code LegacyRecipeSerializerBridge}: {@code adapt} turns a legacy serializer object into a
     * real 26.1 {@code RecipeSerializer} (cached per object, so the registry and later lookups
     * agree), and {@code codec}/{@code streamCodec} answer for either shape.
     */
    public static byte[] generateBridge() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, BRIDGE, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "ADAPTED", "Ljava/util/Map;", null,
                null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, "java/util/IdentityHashMap");
        clinit.visitInsn(DUP);
        clinit.visitMethodInsn(INVOKESPECIAL, "java/util/IdentityHashMap", "<init>", "()V", false);
        clinit.visitFieldInsn(PUTSTATIC, BRIDGE, "ADAPTED", "Ljava/util/Map;");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        emitAdapt(cw);
        emitCodecAccessor(cw, "codec", L_MAP_CODEC, MAP_CODEC);
        emitCodecAccessor(cw, "streamCodec", L_STREAM_CODEC, STREAM_CODEC);
        emitInvokeNoArg(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitAdapt(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC | ACC_SYNCHRONIZED, "adapt",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        m.visitCode();
        Label convert = new Label();
        Label passThrough = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitJumpInsn(IFNULL, passThrough);
        m.visitVarInsn(ALOAD, 0);
        m.visitTypeInsn(INSTANCEOF, RECIPE_SERIALIZER);
        m.visitJumpInsn(IFEQ, convert);
        m.visitLabel(passThrough);
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(ARETURN);
        m.visitLabel(convert);
        Label unknown = new Label();
        m.visitFieldInsn(GETSTATIC, BRIDGE, "ADAPTED", "Ljava/util/Map;");
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        m.visitVarInsn(ASTORE, 1);
        m.visitVarInsn(ALOAD, 1);
        m.visitJumpInsn(IFNULL, unknown);
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(ARETURN);
        m.visitLabel(unknown);
        m.visitInsn(ACONST_NULL);
        m.visitVarInsn(ASTORE, 2);
        m.visitInsn(ACONST_NULL);
        m.visitVarInsn(ASTORE, 3);
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label handler = new Label();
        Label build = new Label();
        m.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
        m.visitLabel(tryStart);
        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn("codec");
        m.visitMethodInsn(INVOKESTATIC, BRIDGE, "invokeNoArg",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        m.visitTypeInsn(CHECKCAST, MAP_CODEC);
        m.visitVarInsn(ASTORE, 2);
        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn("streamCodec");
        m.visitMethodInsn(INVOKESTATIC, BRIDGE, "invokeNoArg",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        m.visitTypeInsn(CHECKCAST, STREAM_CODEC);
        m.visitVarInsn(ASTORE, 3);
        m.visitLabel(tryEnd);
        m.visitJumpInsn(GOTO, build);
        m.visitLabel(handler);
        m.visitVarInsn(ASTORE, 4);
        m.visitFieldInsn(GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
        m.visitLdcInsn("[Retromod] Could not read the codecs of the legacy recipe serializer ");
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;",
                false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;",
                false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitLdcInsn(", so its recipes will not load: ");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitVarInsn(ALOAD, 4);
        m.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                false);
        m.visitLabel(build);
        m.visitTypeInsn(NEW, RECIPE_SERIALIZER);
        m.visitInsn(DUP);
        m.visitTypeInsn(NEW, SAFE_CODEC);
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 2);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;",
                false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;",
                false);
        m.visitMethodInsn(INVOKESPECIAL, SAFE_CODEC, "<init>",
                "(Lcom/mojang/serialization/MapCodec;Ljava/lang/String;)V", false);
        m.visitVarInsn(ALOAD, 3);
        m.visitMethodInsn(INVOKESPECIAL, RECIPE_SERIALIZER, "<init>",
                "(Lcom/mojang/serialization/MapCodec;Lnet/minecraft/network/codec/StreamCodec;)V",
                false);
        m.visitVarInsn(ASTORE, 1);
        m.visitFieldInsn(GETSTATIC, BRIDGE, "ADAPTED", "Ljava/util/Map;");
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        m.visitInsn(POP);
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void emitCodecAccessor(ClassWriter cw, String name, String returnDesc,
            String returnType) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name,
                "(Ljava/lang/Object;)" + returnDesc, null, null);
        m.visitCode();
        Label legacy = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitTypeInsn(INSTANCEOF, RECIPE_SERIALIZER);
        m.visitJumpInsn(IFEQ, legacy);
        m.visitVarInsn(ALOAD, 0);
        m.visitTypeInsn(CHECKCAST, RECIPE_SERIALIZER);
        m.visitMethodInsn(INVOKEVIRTUAL, RECIPE_SERIALIZER, name, "()" + returnDesc, false);
        m.visitInsn(ARETURN);
        m.visitLabel(legacy);
        m.visitVarInsn(ALOAD, 0);
        m.visitLdcInsn(name);
        m.visitMethodInsn(INVOKESTATIC, BRIDGE, "invokeNoArg",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", false);
        m.visitTypeInsn(CHECKCAST, returnType);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void emitInvokeNoArg(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "invokeNoArg",
                "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", null,
                new String[]{"java/lang/ReflectiveOperationException"});
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;",
                false);
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(ICONST_0);
        m.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        m.visitInsn(DUP);
        m.visitInsn(ICONST_1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "setAccessible", "(Z)V",
                false);
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(ICONST_0);
        m.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /**
     * {@code LegacyRecipeMapCodec}: wraps the mod's codec and turns a {@code LinkageError} from it
     * into one reported {@code DataResult} error, so one recipe that reads removed internals fails
     * alone instead of aborting the recipe load. It is its own error message supplier.
     */
    public static byte[] generateSafeCodec() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SAFE_CODEC, null, MAP_CODEC,
                new String[]{"java/util/function/Supplier"});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "delegate", L_MAP_CODEC, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "serializer", "Ljava/lang/String;", null,
                null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_VOLATILE, "reported", "Z", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(Lcom/mojang/serialization/MapCodec;Ljava/lang/String;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, MAP_CODEC, "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, SAFE_CODEC, "delegate", L_MAP_CODEC);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 2);
        init.visitFieldInsn(PUTFIELD, SAFE_CODEC, "serializer", "Ljava/lang/String;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor get = cw.visitMethod(ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
        get.visitCode();
        get.visitLdcInsn("Retromod: recipe serializer ");
        get.visitVarInsn(ALOAD, 0);
        get.visitFieldInsn(GETFIELD, SAFE_CODEC, "serializer", "Ljava/lang/String;");
        get.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        get.visitLdcInsn(" builds recipes with the pre-26.1 recipe API, so this recipe cannot"
                + " load on this Minecraft version until the mod is updated");
        get.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        emitSafeKeys(cw);
        emitSafeDecode(cw);
        emitSafeEncode(cw);
        emitReportOnce(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitSafeKeys(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "keys",
                "(Lcom/mojang/serialization/DynamicOps;)Ljava/util/stream/Stream;", null, null);
        m.visitCode();
        Label present = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, SAFE_CODEC, "delegate", L_MAP_CODEC);
        m.visitJumpInsn(IFNONNULL, present);
        m.visitMethodInsn(INVOKESTATIC, "java/util/stream/Stream", "empty",
                "()Ljava/util/stream/Stream;", true);
        m.visitInsn(ARETURN);
        m.visitLabel(present);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, SAFE_CODEC, "delegate", L_MAP_CODEC);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, MAP_CODEC, "keys",
                "(Lcom/mojang/serialization/DynamicOps;)Ljava/util/stream/Stream;", false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void emitSafeDecode(ClassWriter cw) {
        String desc = "(Lcom/mojang/serialization/DynamicOps;Lcom/mojang/serialization/MapLike;)"
                + "Lcom/mojang/serialization/DataResult;";
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "decode", desc, null, null);
        m.visitCode();
        Label fail = new Label();
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label handler = new Label();
        Label runtimeHandler = new Label();
        m.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/LinkageError");
        m.visitTryCatchBlock(tryStart, tryEnd, runtimeHandler, "java/lang/RuntimeException");
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, SAFE_CODEC, "delegate", L_MAP_CODEC);
        m.visitJumpInsn(IFNULL, fail);
        m.visitLabel(tryStart);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, SAFE_CODEC, "delegate", L_MAP_CODEC);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKEVIRTUAL, MAP_CODEC, "decode", desc, false);
        m.visitLabel(tryEnd);
        m.visitInsn(ARETURN);
        // A decoder that throws, such as one reading item components before they are bound,
        // would abort the whole recipe reload; vanilla decoders report through DataResult.
        reportAndJump(m, runtimeHandler, 3, fail);
        m.visitLabel(handler);
        m.visitVarInsn(ASTORE, 3);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 3);
        m.visitMethodInsn(INVOKEVIRTUAL, SAFE_CODEC, "reportOnce", "(Ljava/lang/Throwable;)V",
                false);
        m.visitLabel(fail);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESTATIC, DATA_RESULT, "error",
                "(Ljava/util/function/Supplier;)Lcom/mojang/serialization/DataResult;", true);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /**
     * A second handler with its own code: sharing the LinkageError handler would merge the two
     * catch types, and this writer's frame computation merges classes to {@code Object}.
     */
    private static void reportAndJump(MethodVisitor m, Label handler, int local, Label next) {
        m.visitLabel(handler);
        m.visitVarInsn(ASTORE, local);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, local);
        m.visitMethodInsn(INVOKEVIRTUAL, SAFE_CODEC, "reportOnce", "(Ljava/lang/Throwable;)V",
                false);
        m.visitJumpInsn(GOTO, next);
    }

    private static void emitSafeEncode(ClassWriter cw) {
        String desc = "(Ljava/lang/Object;Lcom/mojang/serialization/DynamicOps;"
                + "Lcom/mojang/serialization/RecordBuilder;)Lcom/mojang/serialization/RecordBuilder;";
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "encode", desc, null, null);
        m.visitCode();
        Label unchanged = new Label();
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label handler = new Label();
        Label runtimeHandler = new Label();
        m.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/LinkageError");
        m.visitTryCatchBlock(tryStart, tryEnd, runtimeHandler, "java/lang/RuntimeException");
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, SAFE_CODEC, "delegate", L_MAP_CODEC);
        m.visitJumpInsn(IFNULL, unchanged);
        m.visitLabel(tryStart);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, SAFE_CODEC, "delegate", L_MAP_CODEC);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 2);
        m.visitVarInsn(ALOAD, 3);
        m.visitMethodInsn(INVOKEVIRTUAL, MAP_CODEC, "encode", desc, false);
        m.visitLabel(tryEnd);
        m.visitInsn(ARETURN);
        reportAndJump(m, runtimeHandler, 4, unchanged);
        m.visitLabel(handler);
        m.visitVarInsn(ASTORE, 4);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 4);
        m.visitMethodInsn(INVOKEVIRTUAL, SAFE_CODEC, "reportOnce", "(Ljava/lang/Throwable;)V",
                false);
        m.visitLabel(unchanged);
        m.visitVarInsn(ALOAD, 3);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void emitReportOnce(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PRIVATE, "reportOnce", "(Ljava/lang/Throwable;)V",
                null, null);
        m.visitCode();
        Label done = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, SAFE_CODEC, "reported", "Z");
        m.visitJumpInsn(IFNE, done);
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(ICONST_1);
        m.visitFieldInsn(PUTFIELD, SAFE_CODEC, "reported", "Z");
        m.visitFieldInsn(GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
        m.visitLdcInsn("[Retromod] ");
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, SAFE_CODEC, "get", "()Ljava/lang/Object;", false);
        m.visitTypeInsn(CHECKCAST, "java/lang/String");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitLdcInsn(". First failure: ");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V",
                false);
        m.visitLabel(done);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** The {@code SimpleCraftingRecipeSerializer.Factory} stand-in: {@code create(category)}. */
    public static byte[] generateSimpleFactory() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, SIMPLE_FACTORY, null,
                "java/lang/Object", null);
        cw.visitInnerClass(SIMPLE_FACTORY, SIMPLE_SERIALIZER, "Factory",
                ACC_PUBLIC | ACC_STATIC | ACC_INTERFACE | ACC_ABSTRACT);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "create",
                FACTORY_CREATE_DESC, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    public static byte[] generateCategoryGetter() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CATEGORY_GETTER, null,
                "java/lang/Object", new String[]{FUNCTION});
        emitDefaultConstructor(cw, "java/lang/Object");
        MethodVisitor apply = cw.visitMethod(ACC_PUBLIC, "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        apply.visitCode();
        apply.visitVarInsn(ALOAD, 1);
        apply.visitTypeInsn(CHECKCAST, CRAFTING_RECIPE);
        apply.visitMethodInsn(INVOKEINTERFACE, CRAFTING_RECIPE, "category",
                "()Lnet/minecraft/world/item/crafting/CraftingBookCategory;", true);
        apply.visitInsn(ARETURN);
        apply.visitMaxs(0, 0);
        apply.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * The {@code SimpleCraftingRecipeSerializer} stand-in. 1.21.x built a special recipe from its
     * crafting category alone, so the codecs are the category codec mapped through the factory.
     * It also serves as that mapping function.
     */
    public static byte[] generateSimpleSerializer() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, SIMPLE_SERIALIZER, null, "java/lang/Object",
                new String[]{FUNCTION});
        cw.visitInnerClass(SIMPLE_FACTORY, SIMPLE_SERIALIZER, "Factory",
                ACC_PUBLIC | ACC_STATIC | ACC_INTERFACE | ACC_ABSTRACT);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "constructor",
                "Lcom/retromod/shim/common/embedded/LegacySimpleCraftingRecipeSerializer$Factory;",
                null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(L" + SIMPLE_FACTORY + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, SIMPLE_SERIALIZER, "constructor",
                "Lcom/retromod/shim/common/embedded/LegacySimpleCraftingRecipeSerializer$Factory;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor apply = cw.visitMethod(ACC_PUBLIC, "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        apply.visitCode();
        apply.visitVarInsn(ALOAD, 0);
        apply.visitFieldInsn(GETFIELD, SIMPLE_SERIALIZER, "constructor",
                "Lcom/retromod/shim/common/embedded/LegacySimpleCraftingRecipeSerializer$Factory;");
        apply.visitVarInsn(ALOAD, 1);
        apply.visitTypeInsn(CHECKCAST, CATEGORY);
        apply.visitMethodInsn(INVOKEINTERFACE, SIMPLE_FACTORY, "create",
                FACTORY_CREATE_DESC, true);
        apply.visitInsn(ARETURN);
        apply.visitMaxs(0, 0);
        apply.visitEnd();
        MethodVisitor codec = cw.visitMethod(ACC_PUBLIC, "codec",
                "()Lcom/mojang/serialization/MapCodec;", null, null);
        codec.visitCode();
        codec.visitFieldInsn(GETSTATIC, CATEGORY, "CODEC", "Lcom/mojang/serialization/Codec;");
        codec.visitLdcInsn("category");
        codec.visitMethodInsn(INVOKEINTERFACE, CODEC, "fieldOf",
                "(Ljava/lang/String;)Lcom/mojang/serialization/MapCodec;", true);
        codec.visitFieldInsn(GETSTATIC, CATEGORY, "MISC",
                "Lnet/minecraft/world/item/crafting/CraftingBookCategory;");
        codec.visitMethodInsn(INVOKEVIRTUAL, MAP_CODEC, "orElse",
                "(Ljava/lang/Object;)Lcom/mojang/serialization/MapCodec;", false);
        codec.visitVarInsn(ALOAD, 0);
        emitNewCategoryGetter(codec);
        codec.visitMethodInsn(INVOKEVIRTUAL, MAP_CODEC, "xmap",
                "(Ljava/util/function/Function;Ljava/util/function/Function;)"
                + "Lcom/mojang/serialization/MapCodec;", false);
        codec.visitInsn(ARETURN);
        codec.visitMaxs(0, 0);
        codec.visitEnd();
        MethodVisitor stream = cw.visitMethod(ACC_PUBLIC, "streamCodec",
                "()Lnet/minecraft/network/codec/StreamCodec;", null, null);
        stream.visitCode();
        stream.visitFieldInsn(GETSTATIC, CATEGORY, "STREAM_CODEC", L_STREAM_CODEC);
        stream.visitVarInsn(ALOAD, 0);
        emitNewCategoryGetter(stream);
        stream.visitMethodInsn(INVOKEINTERFACE, STREAM_CODEC, "map",
                "(Ljava/util/function/Function;Ljava/util/function/Function;)"
                + "Lnet/minecraft/network/codec/StreamCodec;", true);
        stream.visitInsn(ARETURN);
        stream.visitMaxs(0, 0);
        stream.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitNewCategoryGetter(MethodVisitor m) {
        m.visitTypeInsn(NEW, CATEGORY_GETTER);
        m.visitInsn(DUP);
        m.visitMethodInsn(INVOKESPECIAL, CATEGORY_GETTER, "<init>", "()V", false);
    }

    private static void emitDefaultConstructor(ClassWriter cw, String superName) {
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }
}
