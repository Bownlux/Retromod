/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.RegistryIdBridgeSynthetic;
import com.retromod.util.McReflect;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import static org.objectweb.asm.Opcodes.*;

/** Registers an old mod's codec as a map codec that is still a codec, where 1.20.5 and later read map codecs. */
public final class LegacyRegistryMapCodecBridge {

    /** The generated value class, and the static entry points the register wrappers and redirects call. */
    public static final String CLASS = "com/retromod/generated/LegacyRegistryMapCodec";
    /** The first host whose modifier, chunk generator, biome source and surface rule registries hold map codecs. */
    static final String FIRST_MAP_CODEC_HOST = "1.20.5";
    /** The first Forge host whose deferred register wrapper converts these values itself. */
    static final String FORGE_REGISTER_WRAPPER_HOST = "1.21.11";
    /** The first host whose structure processor registry holds map codecs instead of processor types. */
    static final String FIRST_PROCESSOR_MAP_CODEC_HOST = "26.2";
    static final String PROCESSOR_TYPE = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureProcessorType";
    /** The value an old processor type becomes: its map codec, and still a processor type for the mod's holder. */
    static final String PROCESSOR_VIEW = "com/retromod/generated/LegacyProcessorTypeCodec";

    static final String SERIALIZER_DESC = "(Ljava/lang/Object;Ljava/util/function/Supplier;)Ljava/util/function/Supplier;";
    static final String REGISTER_EACH_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/Consumer;)V";
    static final String REGISTER_DEFERRED_DESC =
            "(Ljava/lang/Object;Ljava/lang/String;Ljava/util/function/Supplier;)Ljava/lang/Object;";

    private static final String CODEC = "com/mojang/serialization/Codec";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String MAP_CODEC_CODEC = MAP_CODEC + "$MapCodecCodec";
    private static final String OPS = "Lcom/mojang/serialization/DynamicOps;";
    private static final String RESULT = "Lcom/mojang/serialization/DataResult;";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String FUNCTION = "Ljava/util/function/Function;";
    private static final String HELPERS = RegistryIdBridgeSynthetic.LOOT_MODIFIER_CODECS;
    private static final String EVENT_DESC = "(Lnet/minecraft/resources/ResourceKey;Ljava/util/function/Consumer;)V";
    private static final String FORGE_REGISTER = "net/minecraftforge/registries/DeferredRegister";
    private static final String FORGE_REGISTER_DESC =
            "(Ljava/lang/String;Ljava/util/function/Supplier;)Lnet/minecraftforge/registries/RegistryObject;";

    private LegacyRegistryMapCodecBridge() {}

    /** Registers the generated class and the embedded helper it calls, for the register wrappers. */
    public static void registerClasses(RetromodTransformer transformer) {
        boolean processorViews = hostProcessorTypesAreMapCodecs();
        transformer.registerSyntheticClass(CLASS, generate(LegacyKeyDispatchCodecBridge.hostReadsBothShapes(), processorViews));
        // Its own class, so the value class every register wrapper calls never needs the processor type to load.
        if (processorViews) {
            transformer.registerSyntheticClass(PROCESSOR_VIEW, WorldgenTypeBridgeSynthetic.generateWrapper(PROCESSOR_VIEW, PROCESSOR_TYPE));
        }
        SyntheticEmbedder.registerClassResource(transformer, HELPERS,
                com.retromod.shim.forge.embedded.LegacyLootModifierCodecs.class);
    }

    /** Converts what a mod built before 1.20.5 registers through a register event, and on Forge 1.21 to 1.21.10 a deferred register. */
    public static void register(RetromodTransformer transformer) {
        String host = RetromodVersion.TARGET_MC_VERSION;
        if (host == null || RetromodVersion.compareMcVersions(host, FIRST_MAP_CODEC_HOST) < 0) return;
        registerClasses(transformer);
        // A Forge mod's event keeps its owner on Forge; on NeoForge the class move may run first.
        String[] events = McReflect.isNeoForge()
                ? new String[] {"net/minecraftforge/registries/RegisterEvent", "net/neoforged/neoforge/registries/RegisterEvent"}
                : new String[] {"net/minecraftforge/registries/RegisterEvent"};
        // A 1.21 mod registers processor types, which became map codecs in 26.2, through the event too.
        String eventLimit = hostProcessorTypesAreMapCodecs() ? FIRST_PROCESSOR_MAP_CODEC_HOST : FIRST_MAP_CODEC_HOST;
        for (String event : events) {
            transformer.registerMethodRedirect(event, "register", EVENT_DESC, CLASS, "registerEach", REGISTER_EACH_DESC, true);
            transformer.limitRedirectToModsBefore(event, "register", EVENT_DESC, eventLimit);
        }
        // From 1.21.11 the Forge deferred register wrapper converts these itself (RegistryIdBridgeSynthetic).
        if (!McReflect.isNeoForge() && RetromodVersion.compareMcVersions(host, FORGE_REGISTER_WRAPPER_HOST) < 0) {
            transformer.registerMethodRedirect(FORGE_REGISTER, "register", FORGE_REGISTER_DESC,
                    CLASS, "registerDeferred", REGISTER_DEFERRED_DESC, true);
            transformer.limitRedirectToModsBefore(FORGE_REGISTER, "register", FORGE_REGISTER_DESC, FIRST_MAP_CODEC_HOST);
        }
    }

    /** Whether the host's {@code StructureProcessorType} is the 26.2 holder of codecs, with no {@code codec()}; offline, the version answers. */
    static boolean hostProcessorTypesAreMapCodecs() {
        ClassNode type = ClassResourceInspector.read(PROCESSOR_TYPE);
        if (type != null) return type.methods.stream().noneMatch(method -> method.name.equals("codec"));
        // A game whose registries can be read lacks the type, so there is nothing to implement.
        if (ClassResourceInspector.read("net/minecraft/core/registries/BuiltInRegistries") != null) return false;
        String host = RetromodVersion.TARGET_MC_VERSION;
        return host != null && RetromodVersion.compareMcVersions(host, FIRST_PROCESSOR_MAP_CODEC_HOST) >= 0;
    }

    static byte[] generate(boolean readsBothShapes) {
        return generate(readsBothShapes, false);
    }

    /**
     * {@code final class LegacyRegistryMapCodec extends MapCodec implements Codec}, with its static entry points.
     * Where processor types are map codecs, {@code of} also turns an old processor type into {@link #PROCESSOR_VIEW}.
     */
    static byte[] generate(boolean readsBothShapes, boolean processorViews) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // DataFixerUpper is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CLASS, null, MAP_CODEC, new String[] {CODEC});
        // The codec the mod registered, and the map codec 1.20.4's dispatch read it as.
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "codec", "L" + CODEC + ";", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "map", "L" + MAP_CODEC + ";", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PRIVATE, "<init>", "(L" + CODEC + ";L" + MAP_CODEC + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, MAP_CODEC, "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, CLASS, "codec", "L" + CODEC + ";");
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 2);
        init.visitFieldInsn(PUTFIELD, CLASS, "map", "L" + MAP_CODEC + ";");
        init.visitInsn(RETURN);
        end(init);

        // The registry's dispatch reads and writes through the map codec.
        delegate(cw, "map", MAP_CODEC, false, "keys", "(" + OPS + ")Ljava/util/stream/Stream;");
        delegate(cw, "map", MAP_CODEC, false, "decode", "(" + OPS + "Lcom/mojang/serialization/MapLike;)" + RESULT);
        delegate(cw, "map", MAP_CODEC, false, "encode",
                "(" + OBJECT + OPS + "Lcom/mojang/serialization/RecordBuilder;)Lcom/mojang/serialization/RecordBuilder;");
        // The mod, which still reads its registry entry as a Codec, gets its own codec's behavior.
        delegate(cw, "codec", CODEC, true, "decode", "(" + OPS + OBJECT + ")" + RESULT);
        delegate(cw, "codec", CODEC, true, "encode", "(" + OBJECT + OPS + OBJECT + ")" + RESULT);
        delegate(cw, "codec", "java/lang/Object", false, "toString", "()Ljava/lang/String;");

        emitOf(cw, processorViews);
        LegacyKeyDispatchCodecBridge.emitMapCodec(cw, readsBothShapes);
        emitConverter(cw);
        emitHelperCall(cw, "serializer", SERIALIZER_DESC,
                "(" + OBJECT + "Ljava/util/function/Supplier;" + FUNCTION + ")Ljava/util/function/Supplier;", 2);
        emitHelperCall(cw, "registerEach", REGISTER_EACH_DESC,
                "(" + OBJECT + OBJECT + "Ljava/util/function/Consumer;" + FUNCTION + ")V", 3);
        emitHelperCall(cw, "registerDeferred", REGISTER_DEFERRED_DESC,
                "(" + OBJECT + "Ljava/lang/String;Ljava/util/function/Supplier;" + FUNCTION + ")" + OBJECT, 3);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** An instance method that calls the same method on {@code field}. */
    private static void delegate(ClassWriter cw, String field, String owner, boolean itf, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, CLASS, field, field.equals("map") ? "L" + MAP_CODEC + ";" : "L" + CODEC + ";");
        int parameters = Type.getArgumentTypes(desc).length;
        for (int slot = 1; slot <= parameters; slot++) mv.visitVarInsn(ALOAD, slot);
        mv.visitMethodInsn(itf ? INVOKEINTERFACE : INVOKEVIRTUAL, owner, name, desc, itf);
        mv.visitInsn(ARETURN);
        end(mv);
    }

    /** {@code static Object of(Object value)}: a codec that is not yet a map codec becomes one that is both. */
    private static void emitOf(ClassWriter cw, boolean processorViews) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "of", "(" + OBJECT + ")" + OBJECT, null, null);
        mv.visitCode();
        Label unchanged = new Label();
        if (processorViews) emitProcessorType(mv);
        // A 1.20.5 mod's map codec, a value already converted, and anything else go through as they are.
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(INSTANCEOF, CODEC);
        mv.visitJumpInsn(IFEQ, unchanged);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(INSTANCEOF, MAP_CODEC);
        mv.visitJumpInsn(IFNE, unchanged);
        mv.visitTypeInsn(NEW, CLASS);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(CHECKCAST, CODEC);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(CHECKCAST, CODEC);
        mv.visitMethodInsn(INVOKESTATIC, CLASS, "mapCodec", "(L" + CODEC + ";)L" + MAP_CODEC + ";", false);
        mv.visitMethodInsn(INVOKESPECIAL, CLASS, "<init>", "(L" + CODEC + ";L" + MAP_CODEC + ";)V", false);
        mv.visitInsn(ARETURN);
        mv.visitLabel(unchanged);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);
        end(mv);
    }

    /**
     * At the start of {@code of}: an old structure processor type, a lambda returning its codec, becomes one value
     * that the registry reads as the map codec and the mod's holder still returns as its type.
     */
    private static void emitProcessorType(MethodVisitor mv) {
        Label notType = new Label();
        Label notCodec = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(INSTANCEOF, MAP_CODEC);
        mv.visitJumpInsn(IFNE, notType);
        // The helper checks the type by name, so this class loads and runs where the host has none.
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, HELPERS, "processorTypeCodec", "(" + OBJECT + ")" + OBJECT, false);
        mv.visitVarInsn(ASTORE, 1);
        // A 1.20.4 type returns a codec, read as 1.20.4's dispatch read it; a 1.21 type returns the map codec.
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(INSTANCEOF, CODEC);
        mv.visitJumpInsn(IFEQ, notCodec);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, CODEC);
        mv.visitMethodInsn(INVOKESTATIC, CLASS, "mapCodec", "(L" + CODEC + ";)L" + MAP_CODEC + ";", false);
        mv.visitVarInsn(ASTORE, 1);
        mv.visitLabel(notCodec);
        // Anything unread stays the mod's value.
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(INSTANCEOF, MAP_CODEC);
        mv.visitJumpInsn(IFEQ, notType);
        mv.visitTypeInsn(NEW, PROCESSOR_VIEW);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, MAP_CODEC);
        mv.visitMethodInsn(INVOKESPECIAL, PROCESSOR_VIEW, "<init>", "(L" + MAP_CODEC + ";)V", false);
        mv.visitInsn(ARETURN);
        mv.visitLabel(notType);
    }

    /** {@code static Function converter()}: {@code LegacyRegistryMapCodec::of}, which the embedded helper applies. */
    private static void emitConverter(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "converter", "()" + FUNCTION, null, null);
        mv.visitCode();
        LegacyKeyDispatchCodecBridge.visitFunction(mv, new Handle(H_INVOKESTATIC, CLASS, "of", "(" + OBJECT + ")" + OBJECT,
                false), Type.getMethodType("(" + OBJECT + ")" + OBJECT));
        mv.visitInsn(ARETURN);
        end(mv);
    }

    /** A static entry point that passes its arguments and {@code converter()} to the embedded helper's method. */
    private static void emitHelperCall(ClassWriter cw, String name, String desc, String helperDesc, int parameters) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        for (int slot = 0; slot < parameters; slot++) mv.visitVarInsn(ALOAD, slot);
        mv.visitMethodInsn(INVOKESTATIC, CLASS, "converter", "()" + FUNCTION, false);
        mv.visitMethodInsn(INVOKESTATIC, HELPERS, name, helperDesc, false);
        mv.visitInsn(Type.getReturnType(desc).getOpcode(IRETURN));
        end(mv);
    }

    private static void end(MethodVisitor mv) {
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
