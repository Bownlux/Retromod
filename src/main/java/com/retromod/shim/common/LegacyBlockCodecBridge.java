/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyRegistries;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Keeps blocks that build the old block codec loading on 26.3, which removed block codecs.
 *
 * <p>From 1.20.3 to 26.2 every block declared a {@code MapCodec}, and most mods filled theirs in a
 * static initializer with {@code simpleCodec(MyBlock::new)}. 26.3 dropped {@code codec()},
 * {@code simpleCodec} and {@code propertiesCodec} from {@code BlockBehaviour} and reads no block
 * codec anywhere, so the call threw {@code NoSuchMethodError} while the block class initialized,
 * and every block and item the mod registered after it was lost.
 *
 * <p>The helpers return codecs that only stand in. The mod's own {@code codec()} override stays as
 * an extra method nothing calls. A codec that 26.3 code did decode would fail with a message that
 * names the cause, rather than build a block from data it never wrote.
 *
 * <p>26.3 also removed the {@code BLOCK_TYPE} registry those codecs were registered in, so a mod
 * that registered its block types failed with {@code NoSuchFieldError} before its blocks. The
 * registry and its key now name a registry of Retromod's own that accepts the registrations.
 * Nothing on 26.3 reads it, which matches a host that no longer uses block codecs.
 */
public final class LegacyBlockCodecBridge {

    static final String HELPER = "com/retromod/generated/LegacyBlockCodecs";

    static final String BLOCK_BEHAVIOUR = "net/minecraft/world/level/block/state/BlockBehaviour";
    static final String PROPERTIES = BLOCK_BEHAVIOUR + "$Properties";
    static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    static final String RECORD_BUILDER = "com/mojang/serialization/codecs/RecordCodecBuilder";
    static final String SIMPLE_CODEC = "(Ljava/util/function/Function;)L" + MAP_CODEC + ";";
    static final String PROPERTIES_CODEC = "()L" + RECORD_BUILDER + ";";
    static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";
    static final String REGISTRY = "net/minecraft/core/Registry";
    static final String BLOCK_TYPES = "blockTypes";
    static final String BLOCK_TYPE_KEY = "blockTypeKey";
    static final String REGISTRIES = "net/minecraft/core/registries/Registries";
    static final String REGISTRIES_HELPER = "com/retromod/shim/common/embedded/LegacyRegistries";
    private static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";

    private static final String SUPPLIER = "java/util/function/Supplier";
    private static final String FUNCTION = "java/util/function/Function";

    private LegacyBlockCodecBridge() {}

    /** Registers on a host whose blocks no longer have codecs. */
    public static void register(RetromodTransformer transformer) {
        if (hostLostBlockCodecs()) registerRedirects(transformer);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(HELPER, generateHelper());
        // A block's static initializer names its own class as the owner, so the redirect has to
        // follow the hierarchy up to BlockBehaviour.
        transformer.registerInheritedMethodRedirect(BLOCK_BEHAVIOUR, "simpleCodec", SIMPLE_CODEC,
                HELPER, "simpleCodec", SIMPLE_CODEC);
        transformer.registerInheritedMethodRedirect(BLOCK_BEHAVIOUR, "propertiesCodec", PROPERTIES_CODEC,
                HELPER, "propertiesCodec", PROPERTIES_CODEC);
        transformer.registerFieldRedirect(BUILT_IN_REGISTRIES, "BLOCK_TYPE", "L" + REGISTRY + ";",
                HELPER, BLOCK_TYPES, "()L" + REGISTRY + ";");
        // A deferred register names the registry by its key, which 26.3 removed with it.
        transformer.registerExactFieldRedirect(REGISTRIES, "BLOCK_TYPE", "L" + RESOURCE_KEY + ";",
                HELPER, BLOCK_TYPE_KEY, "()L" + RESOURCE_KEY + ";");
        SyntheticEmbedder.registerClassResource(transformer, REGISTRIES_HELPER, LegacyRegistries.class);
    }

    /** Whether the host's {@code BlockBehaviour} lost {@code simpleCodec}. */
    static boolean hostLostBlockCodecs() {
        ClassNode behaviour = ClassResourceInspector.read(BLOCK_BEHAVIOUR);
        // Offline the host classes are not readable, so the host version decides.
        if (behaviour == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        }
        return hostLostBlockCodecs(behaviour);
    }

    static boolean hostLostBlockCodecs(ClassNode behaviour) {
        // The stand-in for propertiesCodec reads a block's properties through properties().
        return !hasMethod(behaviour, "simpleCodec", SIMPLE_CODEC)
                && hasMethod(behaviour, "properties", "()L" + PROPERTIES + ";");
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    /**
     * Writes the helper. It is generated rather than compiled because its signatures name
     * DataFixerUpper types, which are not on Retromod's compile classpath. An instance is both
     * the decoder's value supplier, which fails, and the encoder's getter for a block's properties.
     */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", new String[] {SUPPLIER, FUNCTION});

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // simpleCodec(factory): MapCodec.unit(new LegacyBlockCodecs())
        MethodVisitor simple = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "simpleCodec", SIMPLE_CODEC, null, null);
        simple.visitCode();
        emitNewHelper(simple);
        simple.visitMethodInsn(Opcodes.INVOKESTATIC, MAP_CODEC, "unit",
                "(L" + SUPPLIER + ";)L" + MAP_CODEC + ";", false);
        simple.visitInsn(Opcodes.ARETURN);
        simple.visitMaxs(0, 0);
        simple.visitEnd();

        // propertiesCodec(): RecordCodecBuilder.of(helper, MapCodec.unit(helper))
        MethodVisitor properties = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "propertiesCodec", PROPERTIES_CODEC, null, null);
        properties.visitCode();
        emitNewHelper(properties);
        properties.visitInsn(Opcodes.DUP);
        properties.visitMethodInsn(Opcodes.INVOKESTATIC, MAP_CODEC, "unit",
                "(L" + SUPPLIER + ";)L" + MAP_CODEC + ";", false);
        properties.visitMethodInsn(Opcodes.INVOKESTATIC, RECORD_BUILDER, "of",
                "(L" + FUNCTION + ";L" + MAP_CODEC + ";)L" + RECORD_BUILDER + ";", false);
        properties.visitInsn(Opcodes.ARETURN);
        properties.visitMaxs(0, 0);
        properties.visitEnd();

        // apply(block): the block's own properties, which is what the old getter returned.
        MethodVisitor apply = cw.visitMethod(Opcodes.ACC_PUBLIC, "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        apply.visitCode();
        apply.visitVarInsn(Opcodes.ALOAD, 1);
        apply.visitTypeInsn(Opcodes.CHECKCAST, BLOCK_BEHAVIOUR);
        apply.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BLOCK_BEHAVIOUR, "properties",
                "()L" + PROPERTIES + ";", false);
        apply.visitInsn(Opcodes.ARETURN);
        apply.visitMaxs(0, 0);
        apply.visitEnd();

        emitBlockTypes(cw);

        // get(): decoding a block from data, which 26.3 never does.
        MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC, "get", "()Ljava/lang/Object;", null, null);
        get.visitCode();
        get.visitTypeInsn(Opcodes.NEW, "java/lang/UnsupportedOperationException");
        get.visitInsn(Opcodes.DUP);
        get.visitLdcInsn("A block codec from a mod built before Minecraft 26.3 was asked to decode a"
                + " block. Minecraft 26.3 removed block codecs, so Retromod keeps this one only so the"
                + " block can load. The mod needs a version built for 26.3 to read blocks from data.");
        get.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>",
                "(Ljava/lang/String;)V", false);
        get.visitInsn(Opcodes.ATHROW);
        get.visitMaxs(0, 0);
        get.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code blockTypes()} and {@code blockTypeKey()}: a registry under Retromod's id that accepts
     * the block types, shared by every mod. On NeoForge it is registered with the loader, so a
     * deferred register for its key is filled as before.
     */
    private static void emitBlockTypes(ClassWriter cw) {
        emitRegistryCall(cw, BLOCK_TYPES, "registry", REGISTRY);
        emitRegistryCall(cw, BLOCK_TYPE_KEY, "key", RESOURCE_KEY);
    }

    private static void emitRegistryCall(ClassWriter cw, String name, String helper, String type) {
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()L" + type + ";", null, null);
        m.visitCode();
        m.visitLdcInsn("retromod");
        m.visitLdcInsn("legacy_block_type");
        m.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRIES_HELPER, helper,
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false);
        m.visitTypeInsn(Opcodes.CHECKCAST, type);
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    private static void emitNewHelper(MethodVisitor mv) {
        mv.visitTypeInsn(Opcodes.NEW, HELPER);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, HELPER, "<init>", "()V", false);
    }
}
