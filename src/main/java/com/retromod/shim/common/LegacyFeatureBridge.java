/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyFeatureSupport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps an older mod's custom worldgen features working on 26.3, which rebuilt features as data.
 *
 * <p>Through 26.2 a mod wrote {@code class RockFeature extends Feature<NoneFeatureConfiguration>},
 * placed blocks in {@code place(FeaturePlaceContext)}, and registered one instance as a feature
 * type, which configured features then named with their own configuration. 26.3 turned
 * {@code Feature} into the interface of the configured feature itself, made feature types bare
 * codecs in {@code FEATURE_TYPE}, and removed {@code FeaturePlaceContext} and the configuration
 * classes. The mod's feature class then failed to load with {@code IncompatibleClassChangeError},
 * and with it the class that registered it, which is often all of the mod's content.
 *
 * <p>The old model is rebuilt on the new one. The mod's feature is rebased onto a generated
 * {@code LegacyFeature}, which is a {@code MapCodec}, so the mod's own registration puts it into
 * {@code FEATURE_TYPE}. It decodes the flattened 26.3 feature JSON with the mod's configuration
 * codec into a generated configured feature, whose {@code place} hands the mod's
 * {@code place(context)} a stand-in context with the level, generator, random, origin, and that
 * configuration. In jars written before 26.3, {@code Registries.FEATURE} meant feature types and
 * is pointed at {@code FEATURE_TYPE}, and {@code CONFIGURED_FEATURE} is pointed at {@code FEATURE}.
 *
 * <p>A mod that used vanilla's own feature classes or configurations in code, such as
 * {@code Feature.TREE} or {@code OreConfiguration}, is not covered; the data form of those is.
 */
public final class LegacyFeatureBridge {

    static final String FEATURE = "net/minecraft/world/level/levelgen/feature/Feature";
    public static final String LEGACY_FEATURE = "com/retromod/generated/LegacyFeature";
    public static final String CONFIGURED = "com/retromod/generated/LegacyConfiguredFeature";
    static final String CONTEXT = "com/retromod/generated/LegacyFeaturePlaceContext";
    static final String CONFIGURATION = "com/retromod/generated/LegacyFeatureConfiguration";
    static final String NONE = "com/retromod/generated/LegacyNoneFeatureConfiguration";
    static final String SUPPORT = "com/retromod/shim/common/embedded/LegacyFeatureSupport";

    private static final String LEVEL = "Lnet/minecraft/world/level/WorldGenLevel;";
    private static final String GENERATOR = "Lnet/minecraft/world/level/chunk/ChunkGenerator;";
    private static final String RANDOM = "Lnet/minecraft/util/RandomSource;";
    private static final String POS = "Lnet/minecraft/core/BlockPos;";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String CODEC = "Lcom/mojang/serialization/Codec;";
    private static final String PLACE_DESC = "(" + LEVEL + GENERATOR + RANDOM + POS + ")Z";
    private static final String PLACE_WITH_CONFIG = "(L" + CONFIGURATION + ";" + LEVEL + GENERATOR + RANDOM + POS + ")Z";
    private static final String CONTEXT_CTOR = "(Ljava/util/Optional;" + LEVEL + GENERATOR + RANDOM + POS + "L" + CONFIGURATION + ";)V";

    /** Old names in either namespace that mark a jar written for the old feature model. */
    private static final Set<String> OLD_NAMES = Set.of("net/minecraft/class_3031", "net/minecraft/class_5821",
            "net/minecraft/class_3111", "net/minecraft/class_2975",
            "net/minecraft/world/level/levelgen/feature/FeaturePlaceContext",
            "net/minecraft/world/level/levelgen/feature/ConfiguredFeature",
            "net/minecraft/world/level/levelgen/feature/configurations/NoneFeatureConfiguration",
            "CONFIGURED_FEATURE");

    private static final Set<String> LEGACY_CLASSES = ConcurrentHashMap.newKeySet();

    private LegacyFeatureBridge() {}

    /** Registers on a host whose {@code Feature} is the 26.3 interface. */
    public static void register(RetromodTransformer t) {
        if (!hostHasFeatureInterface() || t.getSyntheticClasses().containsKey(LEGACY_FEATURE)) return;
        SyntheticEmbedder.registerClassResource(t, SUPPORT, LegacyFeatureSupport.class);
        t.registerSyntheticClass(CONFIGURATION, generateConfigurationInterface());
        t.registerSyntheticClass(NONE, generateNone());
        t.registerSyntheticClass(CONTEXT, generateContext());
        t.registerSyntheticClass(LEGACY_FEATURE, generateLegacyFeature());
        t.registerSyntheticClass(CONFIGURED, generateConfigured());
        t.registerSuperclassRebase(FEATURE, LEGACY_FEATURE);
        t.registerClassRedirect("net/minecraft/world/level/levelgen/feature/FeaturePlaceContext", CONTEXT);
        t.registerClassRedirect("net/minecraft/world/level/levelgen/feature/configurations/FeatureConfiguration",
                CONFIGURATION);
        t.registerClassRedirect("net/minecraft/world/level/levelgen/feature/configurations/NoneFeatureConfiguration",
                NONE);
    }

    public static boolean hostHasFeatureInterface() {
        ClassNode feature = ClassResourceInspector.read(FEATURE);
        if (feature == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        }
        return (feature.access & Opcodes.ACC_INTERFACE) != 0;
    }

    /** Remembers a jar's classes when the jar was written for the old feature model. */
    public static void markJar(RetromodTransformer transformer, Iterable<byte[]> classes) {
        if (transformer.getSyntheticClasses().containsKey(LEGACY_FEATURE)) markOldModelJar(classes);
    }

    static void markOldModelJar(Iterable<byte[]> classes) {
        boolean legacy = false;
        for (byte[] bytes : classes) {
            if (usesOldModel(bytes)) {
                legacy = true;
                break;
            }
        }
        if (!legacy) return;
        for (byte[] bytes : classes) LEGACY_CLASSES.add(new ClassReader(bytes).getClassName());
    }

    static boolean usesOldModel(byte[] classBytes) {
        String text = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        for (String name : OLD_NAMES) {
            if (text.contains(name)) return true;
        }
        boolean[] extendsFeature = new boolean[1];
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                              String[] interfaces) {
                extendsFeature[0] = FEATURE.equals(superName);
            }
        }, ClassReader.SKIP_CODE);
        return extendsFeature[0];
    }

    /**
     * In a jar written for the old model, {@code Registries.FEATURE} and
     * {@code BuiltInRegistries.FEATURE} named feature types, which 26.3 keeps in {@code FEATURE_TYPE},
     * and {@code CONFIGURED_FEATURE} is now {@code FEATURE}. Both renames happen here in one pass,
     * scoped to old jars, because a global field redirect for the second would run first and the
     * first would then rename its result again.
     */
    public static byte[] apply(byte[] classBytes, String className) {
        if (classBytes == null || !LEGACY_CLASSES.contains(className)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC) continue;
                boolean registries = field.owner.equals("net/minecraft/core/registries/Registries")
                        || field.owner.equals("net/minecraft/core/registries/BuiltInRegistries");
                if (!registries) continue;
                if (field.name.equals("FEATURE")) {
                    field.name = "FEATURE_TYPE";
                    changed = true;
                } else if (field.name.equals("CONFIGURED_FEATURE")) {
                    field.name = "FEATURE";
                    changed = true;
                }
            }
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** {@code FeatureConfiguration}, with its {@code NONE} constant. */
    static byte[] generateConfigurationInterface() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, CONFIGURATION, null,
                "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "NONE", "L" + NONE + ";", null, null)
                .visitEnd();
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitFieldInsn(Opcodes.GETSTATIC, NONE, "INSTANCE", "L" + NONE + ";");
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, CONFIGURATION, "NONE", "L" + NONE + ";");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code NoneFeatureConfiguration}: {@code INSTANCE} and a {@code CODEC} that always decodes to it. */
    static byte[] generateNone() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, NONE, null, "java/lang/Object",
                new String[]{CONFIGURATION});
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "INSTANCE", "L" + NONE + ";", null, null)
                .visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "CODEC", CODEC, null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, NONE);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, NONE, "<init>", "()V", false);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, NONE, "INSTANCE", "L" + NONE + ";");
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "unitCodec", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        clinit.visitTypeInsn(Opcodes.CHECKCAST, "com/mojang/serialization/Codec");
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, NONE, "CODEC", CODEC);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code FeaturePlaceContext}: what the old {@code place(context)} reads. */
    static byte[] generateContext() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, CONTEXT, null, "java/lang/Object", null);
        String[][] fields = {{"topFeature", "Ljava/util/Optional;"}, {"level", LEVEL}, {"chunkGenerator", GENERATOR},
                {"random", RANDOM}, {"origin", POS}, {"config", "L" + CONFIGURATION + ";"}};
        for (String[] field : fields) {
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, field[0], field[1], null, null).visitEnd();
            MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC, field[0], "()" + field[1], null, null);
            get.visitCode();
            get.visitVarInsn(Opcodes.ALOAD, 0);
            get.visitFieldInsn(Opcodes.GETFIELD, CONTEXT, field[0], field[1]);
            get.visitInsn(Opcodes.ARETURN);
            get.visitMaxs(0, 0);
            get.visitEnd();
        }
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", CONTEXT_CTOR, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        for (int i = 0; i < fields.length; i++) {
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitVarInsn(Opcodes.ALOAD, i + 1);
            init.visitFieldInsn(Opcodes.PUTFIELD, CONTEXT, fields[i][0], fields[i][1]);
        }
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The feature type: a {@code MapCodec} over the mod's configuration, and the old base class. */
    static byte[] generateLegacyFeature() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SUPER, LEGACY_FEATURE, null,
                MAP_CODEC, new String[]{FEATURE});
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "configCodec", CODEC, null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE, "delegate", "L" + MAP_CODEC + ";", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + CODEC + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, MAP_CODEC, "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, LEGACY_FEATURE, "configCodec", CODEC);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // delegate(): built on first use, since the mod's codec may not exist until its class loads.
        MethodVisitor delegate = cw.visitMethod(Opcodes.ACC_PRIVATE, "delegate", "()L" + MAP_CODEC + ";", null, null);
        delegate.visitCode();
        org.objectweb.asm.Label ready = new org.objectweb.asm.Label();
        delegate.visitVarInsn(Opcodes.ALOAD, 0);
        delegate.visitFieldInsn(Opcodes.GETFIELD, LEGACY_FEATURE, "delegate", "L" + MAP_CODEC + ";");
        delegate.visitJumpInsn(Opcodes.IFNONNULL, ready);
        delegate.visitVarInsn(Opcodes.ALOAD, 0);
        delegate.visitVarInsn(Opcodes.ALOAD, 0);
        delegate.visitVarInsn(Opcodes.ALOAD, 0);
        delegate.visitFieldInsn(Opcodes.GETFIELD, LEGACY_FEATURE, "configCodec", CODEC);
        delegate.visitLdcInsn(Type.getObjectType(CONFIGURED));
        delegate.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, "delegateCodec",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", false);
        delegate.visitTypeInsn(Opcodes.CHECKCAST, MAP_CODEC);
        delegate.visitFieldInsn(Opcodes.PUTFIELD, LEGACY_FEATURE, "delegate", "L" + MAP_CODEC + ";");
        delegate.visitLabel(ready);
        delegate.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        delegate.visitVarInsn(Opcodes.ALOAD, 0);
        delegate.visitFieldInsn(Opcodes.GETFIELD, LEGACY_FEATURE, "delegate", "L" + MAP_CODEC + ";");
        delegate.visitInsn(Opcodes.ARETURN);
        delegate.visitMaxs(0, 0);
        delegate.visitEnd();

        forward(cw, "keys", "(Lcom/mojang/serialization/DynamicOps;)Ljava/util/stream/Stream;");
        forward(cw, "decode", "(Lcom/mojang/serialization/DynamicOps;Lcom/mojang/serialization/MapLike;)"
                + "Lcom/mojang/serialization/DataResult;");
        forward(cw, "encode", "(Ljava/lang/Object;Lcom/mojang/serialization/DynamicOps;"
                + "Lcom/mojang/serialization/RecordBuilder;)Lcom/mojang/serialization/RecordBuilder;");

        MethodVisitor codec = cw.visitMethod(Opcodes.ACC_PUBLIC, "codec", "()L" + MAP_CODEC + ";", null, null);
        codec.visitCode();
        codec.visitVarInsn(Opcodes.ALOAD, 0);
        codec.visitInsn(Opcodes.ARETURN);
        codec.visitMaxs(0, 0);
        codec.visitEnd();

        // The 26.3 interface's place: the type used directly runs with no configuration.
        MethodVisitor place = cw.visitMethod(Opcodes.ACC_PUBLIC, "place", PLACE_DESC, null, null);
        place.visitCode();
        place.visitVarInsn(Opcodes.ALOAD, 0);
        place.visitFieldInsn(Opcodes.GETSTATIC, NONE, "INSTANCE", "L" + NONE + ";");
        for (int i = 1; i <= 4; i++) place.visitVarInsn(Opcodes.ALOAD, i);
        place.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LEGACY_FEATURE, "place", PLACE_WITH_CONFIG, false);
        place.visitInsn(Opcodes.IRETURN);
        place.visitMaxs(0, 0);
        place.visitEnd();

        // The old public place(config, level, generator, random, origin).
        MethodVisitor withConfig = cw.visitMethod(Opcodes.ACC_PUBLIC, "place", PLACE_WITH_CONFIG, null, null);
        withConfig.visitCode();
        withConfig.visitVarInsn(Opcodes.ALOAD, 0);
        withConfig.visitTypeInsn(Opcodes.NEW, CONTEXT);
        withConfig.visitInsn(Opcodes.DUP);
        withConfig.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Optional", "empty", "()Ljava/util/Optional;", false);
        for (int i = 2; i <= 5; i++) withConfig.visitVarInsn(Opcodes.ALOAD, i);
        withConfig.visitVarInsn(Opcodes.ALOAD, 1);
        withConfig.visitMethodInsn(Opcodes.INVOKESPECIAL, CONTEXT, "<init>", CONTEXT_CTOR, false);
        withConfig.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LEGACY_FEATURE, "place", "(L" + CONTEXT + ";)Z", false);
        withConfig.visitInsn(Opcodes.IRETURN);
        withConfig.visitMaxs(0, 0);
        withConfig.visitEnd();

        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "place", "(L" + CONTEXT + ";)Z", null, null).visitEnd();

        staticHelper(cw, "isReplaceable", "(Lnet/minecraft/tags/TagKey;)Ljava/util/function/Predicate;",
                "(Ljava/lang/Object;)Ljava/util/function/Predicate;", 1);
        staticHelper(cw, "checkNeighbors", "(Ljava/util/function/Function;" + POS + "Ljava/util/function/Predicate;)Z",
                "(Ljava/util/function/Function;Ljava/lang/Object;Ljava/util/function/Predicate;)Z", 3);
        staticHelper(cw, "isAdjacentToAir", "(Ljava/util/function/Function;" + POS + ")Z",
                "(Ljava/util/function/Function;Ljava/lang/Object;)Z", 2);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void forward(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LEGACY_FEATURE, "delegate", "()L" + MAP_CODEC + ";", false);
        Type[] args = Type.getArgumentTypes(desc);
        for (int i = 0; i < args.length; i++) mv.visitVarInsn(Opcodes.ALOAD, i + 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MAP_CODEC, name, desc, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void staticHelper(ClassWriter cw, String name, String desc, String supportDesc, int argCount) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        for (int i = 0; i < argCount; i++) mv.visitVarInsn(Opcodes.ALOAD, i);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SUPPORT, name, supportDesc, false);
        mv.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** One configured feature from data: the mod's feature type and the configuration it read. */
    static byte[] generateConfigured() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, CONFIGURED, null, "java/lang/Object",
                new String[]{FEATURE});
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "type", "L" + LEGACY_FEATURE + ";", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "config", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + LEGACY_FEATURE + ";Ljava/lang/Object;)V",
                null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, CONFIGURED, "type", "L" + LEGACY_FEATURE + ";");
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 2);
        init.visitFieldInsn(Opcodes.PUTFIELD, CONFIGURED, "config", "Ljava/lang/Object;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor codec = cw.visitMethod(Opcodes.ACC_PUBLIC, "codec", "()L" + MAP_CODEC + ";", null, null);
        codec.visitCode();
        codec.visitVarInsn(Opcodes.ALOAD, 0);
        codec.visitFieldInsn(Opcodes.GETFIELD, CONFIGURED, "type", "L" + LEGACY_FEATURE + ";");
        codec.visitInsn(Opcodes.ARETURN);
        codec.visitMaxs(0, 0);
        codec.visitEnd();

        MethodVisitor place = cw.visitMethod(Opcodes.ACC_PUBLIC, "place", PLACE_DESC, null, null);
        place.visitCode();
        place.visitVarInsn(Opcodes.ALOAD, 0);
        place.visitFieldInsn(Opcodes.GETFIELD, CONFIGURED, "type", "L" + LEGACY_FEATURE + ";");
        place.visitVarInsn(Opcodes.ALOAD, 0);
        place.visitFieldInsn(Opcodes.GETFIELD, CONFIGURED, "config", "Ljava/lang/Object;");
        place.visitTypeInsn(Opcodes.CHECKCAST, CONFIGURATION);
        for (int i = 1; i <= 4; i++) place.visitVarInsn(Opcodes.ALOAD, i);
        place.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LEGACY_FEATURE, "place", PLACE_WITH_CONFIG, false);
        place.visitInsn(Opcodes.IRETURN);
        place.visitMaxs(0, 0);
        place.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
