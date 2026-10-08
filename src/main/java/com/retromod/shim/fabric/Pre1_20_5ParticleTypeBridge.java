/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.fabric.embedded.LegacyParticleSupport;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges the pre-1.20.5 custom particle API for intermediary-namespace Fabric mods on pre-26.1
 * hosts.
 *
 * <p>1.20.5 deleted {@code ParticleOptions.Deserializer} ({@code class_2394$class_2395}), the
 * {@code ParticleType(boolean, Deserializer)} constructor, and {@code getDeserializer()}. A
 * {@code ParticleType} now serializes through an abstract {@code MapCodec codec()} and
 * {@code StreamCodec streamCodec()}. An older mod therefore dies with
 * {@code NoClassDefFoundError: class_2394$class_2395} while registering its particles.
 *
 * <p>The old deserializer interface is redirected to a generated stand-in, and the mod's
 * {@code ParticleType} subclasses are rebased onto a generated base. That base keeps the old
 * constructor, turns the mod's old {@code Codec codec()} into the modern {@code MapCodec}, and
 * serves as its own stream codec by calling the mod's {@code writeToNetwork} and
 * {@code fromNetwork}. It also keeps a pass-through {@code (boolean)} constructor and leaves modern
 * overrides alone, so a 1.20.5+ mod transformed on the same host keeps working.
 *
 * <p>The same release made {@code SimpleParticleType(boolean)} protected. A mod calling it directly
 * gets a public subclass instead, which is what Fabric API's own factory builds.
 *
 * <p>Command-style option strings only parse for the mod's own legacy particles and for simple
 * particles. Vanilla particles with options, such as dust, now use a different command syntax.
 */
public final class Pre1_20_5ParticleTypeBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String PARTICLE_TYPE = "net/minecraft/class_2396";
    static final String PARTICLE_OPTIONS = "net/minecraft/class_2394";
    static final String OLD_DESERIALIZER = "net/minecraft/class_2394$class_2395";
    static final String STREAM_CODEC = "net/minecraft/class_9139";
    static final String FRIENDLY_BYTE_BUF = "net/minecraft/class_2540";

    static final String DESERIALIZER = "com/retromod/generated/LegacyParticleDeserializer";
    static final String DESERIALIZER_VIEW = "com/retromod/generated/LegacyParticleDeserializerView";
    static final String LEGACY_TYPE = "com/retromod/generated/LegacyParticleType";
    static final String SUPPORT = "com/retromod/shim/fabric/embedded/LegacyParticleSupport";
    static final String SIMPLE_TYPE = "net/minecraft/class_2400";
    static final String LEGACY_SIMPLE_TYPE = "com/retromod/generated/LegacySimpleParticleType";

    private static final String CODEC = "com/mojang/serialization/Codec";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String STRING_READER = "com/mojang/brigadier/StringReader";

    static final String GET_DESERIALIZER = "method_10298";
    static final String CODEC_METHOD = "method_29138";
    static final String STREAM_CODEC_METHOD = "method_56179";
    static final String FROM_COMMAND = "method_10296";
    static final String FROM_NETWORK = "method_10297";

    private static final String L_TYPE = "L" + PARTICLE_TYPE + ";";
    private static final String L_OPTIONS = "L" + PARTICLE_OPTIONS + ";";
    private static final String L_DESERIALIZER = "L" + DESERIALIZER + ";";
    private static final String FROM_COMMAND_DESC =
            "(" + L_TYPE + "L" + STRING_READER + ";)" + L_OPTIONS;
    private static final String FROM_NETWORK_DESC =
            "(" + L_TYPE + "L" + FRIENDLY_BYTE_BUF + ";)" + L_OPTIONS;

    private Pre1_20_5ParticleTypeBridge() {}

    /** Registers the bridge only on a host whose ParticleType has the 1.20.5 codec shape. */
    public static void register(RetromodTransformer transformer) {
        registerSimpleTypeConstructor(transformer);
        ClassNode particleType = ClassResourceInspector.read(PARTICLE_TYPE);
        if (particleType == null) {
            LOGGER.debug("Legacy particle support is not needed because class_2396 is unavailable");
            return;
        }
        if (hasMethod(particleType, GET_DESERIALIZER)
                || !hasMethod(particleType, STREAM_CODEC_METHOD)
                || ClassResourceInspector.exists(OLD_DESERIALIZER)) {
            LOGGER.debug("The host still supports ParticleOptions.Deserializer");
            return;
        }
        registerRedirects(transformer);
        LOGGER.debug("Added support for pre-1.20.5 particle deserializers");
    }

    /** Unconditional registration for offline transforms and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, SUPPORT, LegacyParticleSupport.class);
        transformer.registerSyntheticClass(DESERIALIZER, generateDeserializerInterface());
        transformer.registerSyntheticClass(DESERIALIZER_VIEW, generateDeserializerView());
        transformer.registerSyntheticClass(LEGACY_TYPE, generateLegacyParticleType());

        transformer.registerClassRedirect(OLD_DESERIALIZER, DESERIALIZER);
        transformer.registerSuperclassRebase(PARTICLE_TYPE, LEGACY_TYPE);
        transformer.registerMethodRedirect(
                PARTICLE_TYPE, GET_DESERIALIZER, "()L" + OLD_DESERIALIZER + ";",
                DESERIALIZER_VIEW, "of", "(" + L_TYPE + ")" + L_DESERIALIZER,
                true);
    }

    /** Redirects {@code new SimpleParticleType(boolean)} where the host made it protected. */
    static void registerSimpleTypeConstructor(RetromodTransformer transformer) {
        ClassNode simpleType = ClassResourceInspector.read(SIMPLE_TYPE);
        if (simpleType == null) return;
        for (MethodNode method : simpleType.methods) {
            if (method.name.equals("<init>") && method.desc.equals("(Z)V")
                    && (method.access & Opcodes.ACC_PUBLIC) == 0) {
                registerSimpleTypeRedirect(transformer);
                return;
            }
        }
    }

    static void registerSimpleTypeRedirect(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(LEGACY_SIMPLE_TYPE, generateLegacySimpleType());
        transformer.registerConstructorRedirect(SIMPLE_TYPE, "(Z)V",
                LEGACY_SIMPLE_TYPE, "create", "(Z)L" + SIMPLE_TYPE + ";");
    }

    /** A public subclass that can reach the protected constructor on the mod's behalf. */
    static byte[] generateLegacySimpleType() {
        ClassWriter cw = newWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                LEGACY_SIMPLE_TYPE, null, SIMPLE_TYPE, null);

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Z)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ILOAD, 1);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, SIMPLE_TYPE, "<init>", "(Z)V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        MethodVisitor create = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create",
                "(Z)L" + SIMPLE_TYPE + ";", null, null);
        create.visitCode();
        create.visitTypeInsn(Opcodes.NEW, LEGACY_SIMPLE_TYPE);
        create.visitInsn(Opcodes.DUP);
        create.visitVarInsn(Opcodes.ILOAD, 0);
        create.visitMethodInsn(Opcodes.INVOKESPECIAL, LEGACY_SIMPLE_TYPE, "<init>", "(Z)V", false);
        create.visitInsn(Opcodes.ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static boolean hasMethod(ClassNode owner, String name) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name)) return true;
        }
        return false;
    }

    /** The removed {@code ParticleOptions.Deserializer}, under its intermediary method names. */
    static byte[] generateDeserializerInterface() {
        ClassWriter cw = newWriter();
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                DESERIALIZER, null, "java/lang/Object", null);
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                FROM_COMMAND, FROM_COMMAND_DESC, null,
                new String[] {"com/mojang/brigadier/exceptions/CommandSyntaxException"}).visitEnd();
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                FROM_NETWORK, FROM_NETWORK_DESC, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Stateless deserializer returned for {@code type.getDeserializer()}. The particle type is
     * always passed back in, so one instance can serve every type.
     */
    static byte[] generateDeserializerView() {
        ClassWriter cw = newWriter();
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                DESERIALIZER_VIEW, null, "java/lang/Object", new String[] {DESERIALIZER});

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        emitSupportCall(cw, FROM_COMMAND, FROM_COMMAND_DESC, "readFromCommand");
        emitSupportCall(cw, FROM_NETWORK, FROM_NETWORK_DESC, "readFromNetwork");

        MethodVisitor of = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "of",
                "(" + L_TYPE + ")" + L_DESERIALIZER, null, null);
        of.visitCode();
        of.visitTypeInsn(Opcodes.NEW, DESERIALIZER_VIEW);
        of.visitInsn(Opcodes.DUP);
        of.visitMethodInsn(Opcodes.INVOKESPECIAL, DESERIALIZER_VIEW, "<init>", "()V", false);
        of.visitInsn(Opcodes.ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitSupportCall(ClassWriter cw, String name, String desc, String helper) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, helper,
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, PARTICLE_OPTIONS);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Abstract base for the mod's particle types. It declares the old {@code Codec codec()} for the
     * mod to keep overriding, implements the modern {@code MapCodec codec()} from it, and is its own
     * {@code StreamCodec}. The JVM keys methods by full descriptor, so both {@code codec} methods
     * coexist under the same intermediary name.
     */
    static byte[] generateLegacyParticleType() {
        ClassWriter cw = newWriter();
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER,
                LEGACY_TYPE, null, PARTICLE_TYPE, new String[] {STREAM_CODEC});

        cw.visitField(Opcodes.ACC_PUBLIC, LegacyParticleSupport.DESERIALIZER_FIELD,
                "Ljava/lang/Object;", null, null).visitEnd();

        // A 1.20.5+ subclass calls super(boolean) and supplies its own codecs.
        MethodVisitor modernCtor = cw.visitMethod(Opcodes.ACC_PROTECTED, "<init>", "(Z)V", null, null);
        modernCtor.visitCode();
        modernCtor.visitVarInsn(Opcodes.ALOAD, 0);
        modernCtor.visitVarInsn(Opcodes.ILOAD, 1);
        modernCtor.visitMethodInsn(Opcodes.INVOKESPECIAL, PARTICLE_TYPE, "<init>", "(Z)V", false);
        modernCtor.visitInsn(Opcodes.RETURN);
        modernCtor.visitMaxs(0, 0);
        modernCtor.visitEnd();

        MethodVisitor legacyCtor = cw.visitMethod(Opcodes.ACC_PROTECTED, "<init>",
                "(Z" + L_DESERIALIZER + ")V", null, null);
        legacyCtor.visitCode();
        legacyCtor.visitVarInsn(Opcodes.ALOAD, 0);
        legacyCtor.visitVarInsn(Opcodes.ILOAD, 1);
        legacyCtor.visitMethodInsn(Opcodes.INVOKESPECIAL, PARTICLE_TYPE, "<init>", "(Z)V", false);
        legacyCtor.visitVarInsn(Opcodes.ALOAD, 0);
        legacyCtor.visitVarInsn(Opcodes.ALOAD, 2);
        legacyCtor.visitFieldInsn(Opcodes.PUTFIELD, LEGACY_TYPE,
                LegacyParticleSupport.DESERIALIZER_FIELD, "Ljava/lang/Object;");
        legacyCtor.visitInsn(Opcodes.RETURN);
        legacyCtor.visitMaxs(0, 0);
        legacyCtor.visitEnd();

        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, CODEC_METHOD,
                "()L" + CODEC + ";", null, null).visitEnd();

        // A record codec is a MapCodec wrapped as a Codec, which assumeMapUnsafe unwraps.
        MethodVisitor mapCodec = cw.visitMethod(Opcodes.ACC_PUBLIC, CODEC_METHOD,
                "()L" + MAP_CODEC + ";", null, null);
        mapCodec.visitCode();
        mapCodec.visitVarInsn(Opcodes.ALOAD, 0);
        mapCodec.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LEGACY_TYPE, CODEC_METHOD,
                "()L" + CODEC + ";", false);
        mapCodec.visitMethodInsn(Opcodes.INVOKESTATIC, MAP_CODEC, "assumeMapUnsafe",
                "(L" + CODEC + ";)L" + MAP_CODEC + ";", false);
        mapCodec.visitInsn(Opcodes.ARETURN);
        mapCodec.visitMaxs(0, 0);
        mapCodec.visitEnd();

        MethodVisitor streamCodec = cw.visitMethod(Opcodes.ACC_PUBLIC, STREAM_CODEC_METHOD,
                "()L" + STREAM_CODEC + ";", null, null);
        streamCodec.visitCode();
        streamCodec.visitVarInsn(Opcodes.ALOAD, 0);
        streamCodec.visitInsn(Opcodes.ARETURN);
        streamCodec.visitMaxs(0, 0);
        streamCodec.visitEnd();

        MethodVisitor decode = cw.visitMethod(Opcodes.ACC_PUBLIC, "decode",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        decode.visitCode();
        decode.visitVarInsn(Opcodes.ALOAD, 0);
        decode.visitVarInsn(Opcodes.ALOAD, 1);
        decode.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "readFromNetwork",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        decode.visitInsn(Opcodes.ARETURN);
        decode.visitMaxs(0, 0);
        decode.visitEnd();

        // StreamEncoder.encode(buffer, value)
        MethodVisitor encode = cw.visitMethod(Opcodes.ACC_PUBLIC, "encode",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        encode.visitCode();
        encode.visitVarInsn(Opcodes.ALOAD, 2);
        encode.visitVarInsn(Opcodes.ALOAD, 1);
        encode.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "writeToNetwork",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
        encode.visitInsn(Opcodes.RETURN);
        encode.visitMaxs(0, 0);
        encode.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when these classes are generated.
                return "java/lang/Object";
            }
        };
    }
}
