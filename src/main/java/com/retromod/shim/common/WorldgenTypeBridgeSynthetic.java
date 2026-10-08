/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 *
 * 26.2 worldgen-type registry bridge: StructureProcessorType SAM values -> MapCodec.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * MC 26.2 changed {@code Registries.STRUCTURE_PROCESSOR} from
 * {@code Registry<StructureProcessorType<?>>} to {@code Registry<MapCodec<? extends
 * StructureProcessor>>} (verified: 26.1 still has the old type) and gutted
 * {@code StructureProcessorType} to bare codec constants. A 1.21.x mod registers a SAM lambda
 * ({@code () -> MY_MAP_CODEC}) into the registry - generic erasure lets it through - and the
 * datapack loader then dies deserializing any {@code processor_list} that references it:
 * {@code ClassCastException: ...$$Lambda cannot be cast to MapCodec} in
 * {@code KeyDispatchCodec.decode} (captured live: YUNG's Better Dungeons on the headless 26.2
 * dedicated server; every other worldgen registry binds because StructureType /
 * StructurePoolElementType KEPT their SAM shapes).
 *
 * <p>The bridge intercepts the static {@code Registry.register(registry, key, value)} overloads.
 * When the target registry is {@code BuiltInRegistries.STRUCTURE_PROCESSOR} and the value is not
 * already a {@code MapCodec}, it invokes the value's no-arg {@code codec()} (the old SAM - the
 * 1.21.x interface returned {@code MapCodec} directly, with {@code Codec}-returning fallbacks
 * unwrapped via {@code MapCodec.MapCodecCodec}) and registers THAT, while returning the mod's
 * ORIGINAL value so its {@code CHECKCAST StructureProcessorType} + field store still hold.
 *
 * <p>{@code BuiltInRegistries.RECIPE_SERIALIZER} is the one exception to returning the original:
 * {@link LegacyRecipeSerializerShim} adapts a legacy serializer into a new 26.1
 * {@code RecipeSerializer} object, and the mod keeps that adapter because its field type is now
 * that class.
 *
 * <p>Generated as an INTERFACE: the mod's call site is {@code INVOKESTATIC} with the
 * InterfaceMethodref flag ({@code Registry} is an interface), and the redirect preserves that
 * flag - a static on a CLASS would die with IncompatibleClassChangeError (the LegacyEventBus
 * lesson, in reverse).
 */
public final class WorldgenTypeBridgeSynthetic {

    private WorldgenTypeBridgeSynthetic() {}

    public static final String INTERNAL = "com/retromod/shim/common/embedded/WorldgenTypeBridge";

    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String BUILT_IN = "net/minecraft/core/registries/BuiltInRegistries";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String MAP_CODEC_CODEC = "com/mojang/serialization/MapCodec$MapCodecCodec";
    private static final String CODEC = "com/mojang/serialization/Codec";

    private static final String L_REG = "L" + REGISTRY + ";";
    private static final String L_OBJ = "Ljava/lang/Object;";

    public static final String LOOT_FUNCTION_WRAPPER =
            "com/retromod/shim/common/embedded/LootItemFunctionType";
    public static final String LOOT_CONDITION_WRAPPER =
            "com/retromod/shim/common/embedded/LootItemConditionType";

    // Registries whose element type is now MapCodec directly. Loot + density changed at 26.1;
    // STRUCTURE_PROCESSOR only at 26.2 (26.1 still holds StructureProcessorType SAM objects, so
    // converting there would BREAK a 26.1 host - hence the two layer-specific variants below).
    private static final String[] MAPCODEC_REGISTRIES_26_1 = {
            "LOOT_CONDITION_TYPE", "LOOT_FUNCTION_TYPE", "DENSITY_FUNCTION_TYPE"};
    private static final String[] MAPCODEC_REGISTRIES_26_2 = {
            "LOOT_CONDITION_TYPE", "LOOT_FUNCTION_TYPE", "DENSITY_FUNCTION_TYPE",
            "STRUCTURE_PROCESSOR"};

    /** Full 26.2-layer registration (all MapCodec-ized registries incl. STRUCTURE_PROCESSOR). */
    public static void register(RetromodTransformer t) {
        registerWith(t, generate());
    }

    /**
     * 26.1-layer registration: the loot/density registries went MapCodec at 26.1, and the
     * {@code LootItemFunctionType}/{@code LootItemConditionType} wrapper records were DELETED
     * (#114: Apollo's Enchantment Rebalance constructs {@code new LootItemFunctionType(codec)}
     * and registers it - NoClassDefFoundError at its entrypoint, then every {@code aer:} loot
     * condition datapack reference failed). Provides Retromod-named wrapper stand-ins (a
     * class-redirect rewrites the mod's references; embedding at the original names would
     * split-package with the live loot packages on NeoForge/Forge) whose {@code codec()} the
     * value conversion unwraps at registration.
     */
    public static void registerPre26_2(RetromodTransformer t) {
        registerWith(t, generate(false));
        t.registerSyntheticClass(LOOT_FUNCTION_WRAPPER, generateWrapper(LOOT_FUNCTION_WRAPPER));
        t.registerSyntheticClass(LOOT_CONDITION_WRAPPER, generateWrapper(LOOT_CONDITION_WRAPPER));
        t.registerClassRedirect(
                "net/minecraft/world/level/storage/loot/functions/LootItemFunctionType",
                LOOT_FUNCTION_WRAPPER);
        t.registerClassRedirect(
                "net/minecraft/world/level/storage/loot/predicates/LootItemConditionType",
                LOOT_CONDITION_WRAPPER);
    }

    private static void registerWith(RetromodTransformer t, byte[] bridgeBytes) {
        t.registerSyntheticClass(INTERNAL, bridgeBytes);
        for (String key : new String[]{"L" + IDENTIFIER + ";", "L" + RESOURCE_KEY + ";",
                "Ljava/lang/String;"}) {
            t.registerMethodRedirect(REGISTRY, "register",
                    "(" + L_REG + key + L_OBJ + ")" + L_OBJ,
                    INTERNAL, "register", "(" + L_REG + key + L_OBJ + ")" + L_OBJ);
        }
    }

    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
    }

    public static byte[] generate() {
        return generate(true);
    }

    public static byte[] generate(boolean withStructureProcessor) {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, INTERNAL, null,
                "java/lang/Object", null);

        emitRegister(cw, "L" + IDENTIFIER + ";");
        emitRegister(cw, "L" + RESOURCE_KEY + ";");
        emitRegister(cw, "Ljava/lang/String;");
        emitConvert(cw, withStructureProcessor
                ? MAPCODEC_REGISTRIES_26_2 : MAPCODEC_REGISTRIES_26_1);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Stand-in for a deleted MapCodec-wrapper record ({@code LootItemFunctionType} /
     * {@code LootItemConditionType} on 1.21.x): {@code <init>(MapCodec)} + {@code codec()}.
     * The registration-time conversion invokes {@code codec()} and registers the MapCodec.
     */
    public static byte[] generateWrapper(String internalName) {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, internalName, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "codec", "L" + MAP_CODEC + ";", null, null).visitEnd();

        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + MAP_CODEC + ";)V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitVarInsn(ALOAD, 0);
        c.visitVarInsn(ALOAD, 1);
        c.visitFieldInsn(PUTFIELD, internalName, "codec", "L" + MAP_CODEC + ";");
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        MethodVisitor g = cw.visitMethod(ACC_PUBLIC, "codec", "()L" + MAP_CODEC + ";", null, null);
        g.visitCode();
        g.visitVarInsn(ALOAD, 0);
        g.visitFieldInsn(GETFIELD, internalName, "codec", "L" + MAP_CODEC + ";");
        g.visitInsn(ARETURN);
        g.visitMaxs(0, 0);
        g.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * static Object register(Registry r, K key, Object v) {
     *     Object conv = convert(r, v);
     *     Object res = Registry.register(r, key, conv);
     *     return conv == v ? res : v;   // the mod's field wants ITS value (the SAM lambda)
     * }
     */
    private static void emitRegister(ClassWriter cw, String lKey) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register",
                "(" + L_REG + lKey + L_OBJ + ")" + L_OBJ, null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKESTATIC, INTERNAL, "convert", "(" + L_REG + L_OBJ + ")" + L_OBJ, true);
        m.visitVarInsn(ASTORE, 3);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 3);
        m.visitMethodInsn(INVOKESTATIC, REGISTRY, "register",
                "(" + L_REG + lKey + L_OBJ + ")" + L_OBJ, true);
        m.visitVarInsn(ASTORE, 4);
        // A legacy recipe serializer was adapted into a new object. The mod's field is typed
        // RecipeSerializer, which is now that class, so it must keep the registered adapter.
        Label notRecipeSerializer = new Label();
        m.visitFieldInsn(GETSTATIC, BUILT_IN, "RECIPE_SERIALIZER", L_REG);
        m.visitVarInsn(ALOAD, 0);
        m.visitJumpInsn(IF_ACMPNE, notRecipeSerializer);
        m.visitVarInsn(ALOAD, 4);
        m.visitInsn(ARETURN);
        m.visitLabel(notRecipeSerializer);
        m.visitVarInsn(ALOAD, 3);
        m.visitVarInsn(ALOAD, 2);
        Label same = new Label();
        m.visitJumpInsn(IF_ACMPEQ, same);
        m.visitVarInsn(ALOAD, 2);      // converted: hand the mod back its own value
        m.visitInsn(ARETURN);
        m.visitLabel(same);
        m.visitVarInsn(ALOAD, 4);      // unconverted: vanilla behavior (return the registered value)
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /**
     * static Object convert(Registry r, Object v) {
     *     if (r != BuiltInRegistries.STRUCTURE_PROCESSOR || v instanceof MapCodec) return v;
     *     try {
     *         Object c = v.getClass().getMethod("codec").invoke(v);   // the old SAM
     *         if (c instanceof MapCodec) return c;                     // 1.21.x shape
     *         if (c instanceof MapCodec.MapCodecCodec mcc) return mcc.codec();
     *         if (c instanceof Codec cc) return cc.fieldOf("value");   // last resort
     *     } catch (Throwable t) { warn }
     *     return v;
     * }
     */
    private static void emitConvert(ClassWriter cw, String[] mapCodecRegistries) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "convert",
                "(" + L_REG + L_OBJ + ")" + L_OBJ, null, null);
        m.visitCode();
        Label ret = new Label();
        Label proceed = new Label();
        Label tryStart = new Label(), tryEnd = new Label(), handler = new Label(), warn = new Label();
        m.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");

        // if (r == BuiltInRegistries.RECIPE_SERIALIZER) return LegacyRecipeSerializerBridge.adapt(v)
        Label notRecipeSerializer = new Label();
        m.visitFieldInsn(GETSTATIC, BUILT_IN, "RECIPE_SERIALIZER", L_REG);
        m.visitVarInsn(ALOAD, 0);
        m.visitJumpInsn(IF_ACMPNE, notRecipeSerializer);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKESTATIC, LegacyRecipeSerializerShim.BRIDGE, "adapt",
                "(" + L_OBJ + ")" + L_OBJ, false);
        m.visitInsn(ARETURN);
        m.visitLabel(notRecipeSerializer);

        // if (r == BuiltInRegistries.A || r == B || ...) proceed; else return v
        for (String field : mapCodecRegistries) {
            m.visitFieldInsn(GETSTATIC, BUILT_IN, field, L_REG);
            m.visitVarInsn(ALOAD, 0);
            m.visitJumpInsn(IF_ACMPEQ, proceed);
        }
        m.visitJumpInsn(GOTO, ret);
        m.visitLabel(proceed);
        m.visitVarInsn(ALOAD, 1);
        m.visitTypeInsn(INSTANCEOF, MAP_CODEC);
        m.visitJumpInsn(IFNE, ret);

        m.visitLabel(tryStart);
        // Method mth = v.getClass().getMethod("codec"); mth.setAccessible(true);
        // Object c = mth.invoke(v);
        // setAccessible is required: SAM lambda classes are PACKAGE-PRIVATE, so invoking their
        // public method from the bridge's package fails IllegalAccessException without it
        // (29/29 conversions failed on the first server run). Knot mods live in the unnamed
        // module, so setAccessible succeeds.
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        m.visitLdcInsn("codec");
        m.visitInsn(ICONST_0);
        m.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        m.visitInsn(DUP);
        m.visitInsn(ICONST_1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "setAccessible", "(Z)V", false);
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(ICONST_0);
        m.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
                "(" + L_OBJ + "[" + L_OBJ + ")" + L_OBJ, false);
        m.visitVarInsn(ASTORE, 2);

        m.visitVarInsn(ALOAD, 2);
        m.visitTypeInsn(INSTANCEOF, MAP_CODEC);
        Label notMap = new Label();
        m.visitJumpInsn(IFEQ, notMap);
        m.visitVarInsn(ALOAD, 2);
        m.visitInsn(ARETURN);
        m.visitLabel(notMap);
        m.visitVarInsn(ALOAD, 2);
        m.visitTypeInsn(INSTANCEOF, MAP_CODEC_CODEC);
        Label notMcc = new Label();
        m.visitJumpInsn(IFEQ, notMcc);
        m.visitVarInsn(ALOAD, 2);
        m.visitTypeInsn(CHECKCAST, MAP_CODEC_CODEC);
        m.visitMethodInsn(INVOKEVIRTUAL, MAP_CODEC_CODEC, "codec", "()L" + MAP_CODEC + ";", false);
        m.visitInsn(ARETURN);
        m.visitLabel(notMcc);
        m.visitVarInsn(ALOAD, 2);
        m.visitTypeInsn(INSTANCEOF, CODEC);
        m.visitJumpInsn(IFEQ, warn);
        m.visitVarInsn(ALOAD, 2);
        m.visitTypeInsn(CHECKCAST, CODEC);
        m.visitLdcInsn("value");
        m.visitMethodInsn(INVOKEINTERFACE, CODEC, "fieldOf",
                "(Ljava/lang/String;)L" + MAP_CODEC + ";", true);
        m.visitLabel(tryEnd);
        m.visitInsn(ARETURN);
        m.visitLabel(handler);
        // print the failure AND its cause (an InvocationTargetException wraps the real error from
        // the processor class's <clinit>, which codec() triggers via GETSTATIC CODEC)
        m.visitVarInsn(ASTORE, 3);
        m.visitFieldInsn(GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
        m.visitLdcInsn("[Retromod] processor-type codec() reflection failed: ");
        m.visitVarInsn(ALOAD, 3);
        m.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf",
                "(" + L_OBJ + ")Ljava/lang/String;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitLdcInsn(" | cause: ");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitVarInsn(ALOAD, 3);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "getCause",
                "()Ljava/lang/Throwable;", false);
        m.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf",
                "(" + L_OBJ + ")Ljava/lang/String;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        m.visitLabel(warn);
        m.visitFieldInsn(GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
        m.visitLdcInsn("[Retromod] Could not convert structure-processor type to MapCodec "
                + "(26.2 registry shape); datapack references to it will fail: ");
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        m.visitLabel(ret);
        m.visitVarInsn(ALOAD, 1);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
