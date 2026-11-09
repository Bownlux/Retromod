/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.ImplementedMethodBridge;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.HostLibraryNames;
import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps the pre-1.20.5 {@code KeyDispatchDataCodec} members working, and the class itself on 26.3, which removed it. */
public final class LegacyKeyDispatchCodecBridge {

    public static final String HELPER = "com/retromod/generated/LegacyKeyDispatchCodec";
    static final String STAND_IN = "com/retromod/generated/LegacyKeyDispatchDataCodec";
    static final String CODEC = "com/mojang/serialization/Codec";
    static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    static final String MAP_CODEC_CODEC = MAP_CODEC + "$MapCodecCodec";
    static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";
    static final String EITHER = "com/mojang/datafixers/util/Either";
    static final String SUPPLIER = "java/util/function/Supplier";
    static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";
    static final String LEVELGEN = "net/minecraft/world/level/levelgen/";

    /** The host's spelling of the class, its accessor, and its two {@code of} factories. */
    record Names(String owner, String codec, String of, String oldOf) {}

    static final Names MOJANG = new Names("net/minecraft/util/KeyDispatchDataCodec", "codec", "of", "of");
    /** Pre-26.1 Fabric hosts run intermediary names: {@code comp_640} and {@code method_42116}, {@code method_42115}. */
    static final Names INTERMEDIARY = new Names("net/minecraft/class_7243", "comp_640", "method_42116", "method_42115");

    /** What the host's class looks like: the 1.20.4 codec shape, the 1.20.5 map codec shape, or gone (26.3). */
    enum Shape { CODEC, MAP_CODEC, REMOVED }

    private static final byte[] HELPER_BYTES = HELPER.getBytes(StandardCharsets.UTF_8);

    private LegacyKeyDispatchCodecBridge() {}

    /** Mojang-named mods: Forge and NeoForge mods, and Fabric mods after the 26.x remap. */
    public static void register(RetromodTransformer transformer) {
        Shape shape = mojangShape();
        if (shape == Shape.CODEC) return;
        registerRedirects(transformer, MOJANG);
        if (shape == Shape.REMOVED) registerStandIn(transformer);
    }

    /** The 26.3 edge, for every mod that names the class, whatever version it was built for. */
    public static void registerRemovedClass(RetromodTransformer transformer) {
        if (mojangShape() == Shape.REMOVED) registerStandIn(transformer);
    }

    /** A running pre-26.1 Fabric host, whose classes say which shape it has. */
    public static void registerIntermediary(RetromodTransformer transformer) {
        ClassNode host = ClassResourceInspector.read(INTERMEDIARY.owner());
        if (host != null && shapeOf(host, INTERMEDIARY) == Shape.MAP_CODEC) {
            registerRedirects(transformer, INTERMEDIARY);
        }
    }

    /** Offline pre-26.1 Fabric targets, where the host version decides. */
    public static void registerIntermediaryForVersion(RetromodTransformer transformer, String host) {
        if (shapeByVersion(host) == Shape.MAP_CODEC) registerRedirects(transformer, INTERMEDIARY);
    }

    /** The host's class decides; offline, or when only the class is missing, the host version does. */
    static Shape mojangShape() {
        ClassNode host = ClassResourceInspector.read(MOJANG.owner());
        if (host != null) return shapeOf(host, MOJANG);
        // A game whose registries can be read has no such class at all.
        if (ClassResourceInspector.read(BUILT_IN_REGISTRIES) != null) return Shape.REMOVED;
        return shapeByVersion(RetromodVersion.TARGET_MC_VERSION);
    }

    static Shape shapeOf(ClassNode host, Names names) {
        String mapCodecAccessor = "()L" + MAP_CODEC + ";";
        for (MethodNode method : host.methods) {
            if (method.name.equals(names.codec()) && method.desc.equals(mapCodecAccessor)) return Shape.MAP_CODEC;
        }
        return Shape.CODEC;
    }

    /** 1.20.5 moved the class to map codecs, and 26.3 removed it. */
    static Shape shapeByVersion(String host) {
        if (RetromodVersion.compareMcVersions(host, "1.20.5") < 0) return Shape.CODEC;
        return RetromodVersion.compareMcVersions(host, "26.3") < 0 ? Shape.MAP_CODEC : Shape.REMOVED;
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, Names names) {
        transformer.registerSyntheticClass(HELPER, generateHelper(names, hostReadsBothShapes()));
        String holder = "L" + names.owner() + ";";
        String codec = "L" + CODEC + ";";
        // codec() returned the Codec, of and the record constructor took one. A codec() whose
        // value goes straight into a registration keeps the map codec, see adaptRegistrations.
        transformer.registerMethodRedirect(names.owner(), names.codec(), "()" + codec,
                HELPER, "codec", "(" + holder + ")" + codec, true);
        transformer.registerMethodRedirect(names.owner(), names.oldOf(), "(" + codec + ")" + holder,
                HELPER, "of", "(" + codec + ")" + holder);
        transformer.registerConstructorRedirect(names.owner(), "(" + codec + ")V",
                HELPER, "of", "(" + codec + ")" + holder);
    }

    /** A host interface whose {@code codec()} is a map codec, and how its old implementations are bridged. */
    record CodecAccessor(String iface, String oldName, String legacy, String since, String until, String evaluator,
            String rebuilt) {
        CodecAccessor(String iface, String oldName, String legacy, String since, String until) {
            this(iface, oldName, legacy, since, until, null, null);
        }

        CodecAccessor(String iface, String oldName, String legacy, String since, String until, String evaluator) {
            this(iface, oldName, legacy, since, until, evaluator, null);
        }
    }

    static final String DENSITY_FUNCTION_BRIDGE = "com/retromod/generated/LegacyDensityFunctionCodec";

    static final List<CodecAccessor> MAP_CODEC_ACCESSORS = List.of(
            new CodecAccessor(LEVELGEN + "SurfaceRules$RuleSource", null,
                    "com/retromod/generated/LegacyRuleSourceCodec", "26.2", "26.3"),
            new CodecAccessor(LEVELGEN + "SurfaceRules$ConditionSource", null,
                    "com/retromod/generated/LegacyConditionSourceCodec", "26.2", "26.3"),
            new CodecAccessor(LEVELGEN + "material/rule/MaterialRule", LEVELGEN + "SurfaceRules$RuleSource",
                    "com/retromod/generated/LegacyMaterialRuleCodec", "26.3", null,
                    LEVELGEN + "material/rule/RuleEvaluator"),
            new CodecAccessor(LEVELGEN + "material/condition/MaterialCondition", LEVELGEN + "SurfaceRules$ConditionSource",
                    "com/retromod/generated/LegacyMaterialConditionCodec", "26.3", null,
                    LEVELGEN + "material/condition/ConditionEvaluator"),
            // An old density function keeps compute and mapAll; the rebuilt interface compiles it from them.
            new CodecAccessor(LEVELGEN + "densityfunction/DensityFunction", LEVELGEN + "DensityFunction",
                    DENSITY_FUNCTION_BRIDGE, "26.3", null, null, Legacy263DensityFunctionBridge.TYPE));

    /** Gives an old rule source, condition or density function the map codec {@code codec()} its host interface declares. */
    public static void registerAccessorBridges(RetromodTransformer transformer) {
        // The mod's class still returns the holder, so the host would call a method it lacks.
        Shape shape = mojangShape();
        if (shape == Shape.CODEC) return;
        if (shape == Shape.REMOVED) registerStandIn(transformer);
        String holder = holderType(shape);
        for (CodecAccessor accessor : MAP_CODEC_ACCESSORS) {
            if (transformer.getSyntheticClasses().containsKey(accessor.legacy()) || !hostReturnsMapCodec(accessor)) {
                continue;
            }
            transformer.registerSyntheticClass(accessor.legacy(), generateAccessorBridge(accessor, holder));
            if (accessor.rebuilt() != null) RebuiltApiClasses.registerTree(transformer, accessor.rebuilt());
            // Only the implements lists change, so the mod's interfaces that extend this one get it too.
            transformer.registerInterfaceReplacement(accessor.iface(), accessor.legacy());
            if (accessor.oldName() != null) transformer.registerInterfaceReplacement(accessor.oldName(), accessor.legacy());
            // A class that lists the interface gets its own codec(). It wins over the defaults of two
            // mods' copies of the bridge, which a subclass in another jar reaches through both.
            String mapCodec = "()L" + MAP_CODEC + ";";
            transformer.registerImplementedMethodBridge(accessor.legacy(), "codec", mapCodec, "codec", "()L" + holder + ";",
                    new ImplementedMethodBridge.Unwrap(holder, "codec", mapCodec));
            // An old rule or condition is a function of the context; 26.3 compiles one against it instead.
            if (accessor.evaluator() != null) {
                transformer.registerImplementedMethodBridge(accessor.legacy(), "compile", compileDesc(accessor),
                        "apply", "(Ljava/lang/Object;)Ljava/lang/Object;");
            }
        }
    }

    /** The class an old {@code codec()} returns on this host: Mojang's, or the stand-in where 26.3 removed it. */
    static String holderType(Shape shape) {
        return shape == Shape.REMOVED ? STAND_IN : MOJANG.owner();
    }

    /** A bridge over {@code base} that also extends {@code rebuilt}, listed in place of each of {@code names}. */
    public static void registerSubBridge(RetromodTransformer transformer, String base, String bridge, String rebuilt,
            String... names) {
        // Only where the host gave the base its bridge, such as an old simple density function's.
        if (!transformer.getSyntheticClasses().containsKey(base) || transformer.getSyntheticClasses().containsKey(bridge)) {
            return;
        }
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, bridge, null,
                "java/lang/Object", new String[]{base, rebuilt});
        cw.visitEnd();
        transformer.registerSyntheticClass(bridge, cw.toByteArray());
        RebuiltApiClasses.registerTree(transformer, rebuilt);
        for (String name : names) transformer.registerInterfaceReplacement(name, bridge);
        // As for the base bridge, a class that lists this one gets its own codec().
        String holder = holderType(mojangShape());
        String mapCodec = "()L" + MAP_CODEC + ";";
        transformer.registerImplementedMethodBridge(bridge, "codec", mapCodec, "codec", "()L" + holder + ";",
                new ImplementedMethodBridge.Unwrap(holder, "codec", mapCodec));
    }

    /** {@code compile(MaterialRuleContext)} returning the accessor's evaluator. */
    static String compileDesc(CodecAccessor accessor) {
        return "(L" + LEVELGEN + "material/MaterialRuleContext;)L" + accessor.evaluator() + ";";
    }

    /** Whether the host's interface declares {@code codec()} as a map codec; offline, the version answers. */
    static boolean hostReturnsMapCodec(CodecAccessor accessor) {
        ClassNode host = ClassResourceInspector.read(accessor.iface());
        if (host != null) return declares(host, "codec", "()L" + MAP_CODEC + ";");
        // A game whose registries can be read lacks the interface.
        if (ClassResourceInspector.read(BUILT_IN_REGISTRIES) != null) return false;
        String version = RetromodVersion.TARGET_MC_VERSION;
        return RetromodVersion.compareMcVersions(version, accessor.since()) >= 0
                && (accessor.until() == null || RetromodVersion.compareMcVersions(version, accessor.until()) < 0);
    }

    /** {@code interface Legacy... extends <iface>}: the old {@code codec()}, and the host's calling it. */
    static byte[] generateAccessorBridge(CodecAccessor accessor, String holder) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        // A rule or condition keeps the Function its old interface extended, whose apply the mod implements.
        List<String> supers = new ArrayList<>(List.of(accessor.iface()));
        if (accessor.evaluator() != null) supers.add("java/util/function/Function");
        if (accessor.rebuilt() != null) supers.add(accessor.rebuilt());
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, accessor.legacy(), null,
                "java/lang/Object", supers.toArray(new String[0]));
        if (accessor.evaluator() != null) {
            MethodVisitor compile = cw.visitMethod(Opcodes.ACC_PUBLIC, "compile", compileDesc(accessor), null, null);
            compile.visitCode();
            compile.visitVarInsn(Opcodes.ALOAD, 0);
            compile.visitVarInsn(Opcodes.ALOAD, 1);
            compile.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", true);
            compile.visitTypeInsn(Opcodes.CHECKCAST, accessor.evaluator());
            compile.visitInsn(Opcodes.ARETURN);
            end(compile);
        }
        String oldAccessor = "()L" + holder + ";";
        String mapCodec = "()L" + MAP_CODEC + ";";
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "codec", oldAccessor, null, null).visitEnd();
        MethodVisitor bridge = cw.visitMethod(Opcodes.ACC_PUBLIC, "codec", mapCodec, null, null);
        bridge.visitCode();
        bridge.visitVarInsn(Opcodes.ALOAD, 0);
        bridge.visitMethodInsn(Opcodes.INVOKEINTERFACE, accessor.legacy(), "codec", oldAccessor, true);
        bridge.visitMethodInsn(Opcodes.INVOKEVIRTUAL, holder, "codec", mapCodec, false);
        bridge.visitInsn(Opcodes.ARETURN);
        end(bridge);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Puts a stand-in under the removed class's name. */
    static void registerStandIn(RetromodTransformer transformer) {
        if (transformer.getSyntheticClasses().containsKey(STAND_IN)) return;
        transformer.registerSyntheticClass(STAND_IN, generateStandIn());
        transformer.registerClassRedirect(MOJANG.owner(), STAND_IN);
    }

    /** Whether the host's DataFixerUpper has the members the helper reads both data shapes with. */
    static boolean hostReadsBothShapes() {
        ClassNode mapCodec = ClassResourceInspector.read(MAP_CODEC);
        // Checked in DFU 8.0.16 (1.21.1), 9.0.19 and 10.0.21; DFU 6.0.8 (1.20.1) lacks them. Offline, the version answers.
        if (mapCodec == null) return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21") >= 0;
        ClassNode either = ClassResourceInspector.read(EITHER);
        return declares(mapCodec, "assumeMapUnsafe", "(L" + CODEC + ";)L" + MAP_CODEC + ";")
                && either != null && declares(either, "unwrap", "(L" + EITHER + ";)Ljava/lang/Object;");
    }

    private static boolean declares(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    /** The helper: the old {@code of(Codec)} and {@code codec()}, in the host's names. */
    static byte[] generateHelper(Names names, boolean readsBothShapes) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HELPER, null,
                "java/lang/Object", null);
        String holder = "L" + names.owner() + ";";
        String codec = "L" + CODEC + ";";
        String mapCodec = "L" + MAP_CODEC + ";";

        // of(codec): the host's of(MapCodec) with the codec as a map codec.
        MethodVisitor of = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "of",
                "(" + codec + ")" + holder, null, null);
        of.visitCode();
        of.visitVarInsn(Opcodes.ALOAD, 0);
        of.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "mapCodec", "(" + codec + ")" + mapCodec, false);
        of.visitMethodInsn(Opcodes.INVOKESTATIC, names.owner(), names.of(), "(" + mapCodec + ")" + holder, false);
        of.visitInsn(Opcodes.ARETURN);
        end(of);

        // codec(holder): the host's map codec as a codec, which is what the old accessor returned.
        MethodVisitor accessor = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "codec",
                "(" + holder + ")" + codec, null, null);
        accessor.visitCode();
        accessor.visitVarInsn(Opcodes.ALOAD, 0);
        accessor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.owner(), names.codec(), "()" + mapCodec, false);
        accessor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MAP_CODEC, "codec", "()" + codec, false);
        accessor.visitInsn(Opcodes.ARETURN);
        end(accessor);

        emitMapCodec(cw, readsBothShapes);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Writes {@code public static MapCodec mapCodec(Codec)}: a record codec dispatched as its map codec
     * in 1.20.4, and any other codec under a "value" key, so the same data reads and writes the same way.
     */
    static void emitMapCodec(ClassWriter cw, boolean readsBothShapes) {
        String codec = "L" + CODEC + ";";
        String mapCodec = "L" + MAP_CODEC + ";";
        MethodVisitor convert = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "mapCodec",
                "(" + codec + ")" + mapCodec, null, null);
        convert.visitCode();
        Label notRecord = new Label();
        convert.visitVarInsn(Opcodes.ALOAD, 0);
        convert.visitTypeInsn(Opcodes.INSTANCEOF, MAP_CODEC_CODEC);
        convert.visitJumpInsn(Opcodes.IFEQ, notRecord);
        convert.visitVarInsn(Opcodes.ALOAD, 0);
        convert.visitTypeInsn(Opcodes.CHECKCAST, MAP_CODEC_CODEC);
        convert.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MAP_CODEC_CODEC, "codec", "()" + mapCodec, false);
        convert.visitInsn(Opcodes.ARETURN);
        convert.visitLabel(notRecord);
        convert.visitVarInsn(Opcodes.ALOAD, 0);
        convert.visitLdcInsn("value");
        convert.visitMethodInsn(Opcodes.INVOKEINTERFACE, CODEC, "fieldOf", "(Ljava/lang/String;)" + mapCodec, true);
        if (readsBothShapes) {
            // A unit codec from DFU 6 was dispatched inline, so data without a "value" key reads too:
            // Codec.mapEither(codec.fieldOf("value"), MapCodec.assumeMapUnsafe(codec)).xmap(Either::unwrap, Either::left)
            convert.visitVarInsn(Opcodes.ALOAD, 0);
            convert.visitMethodInsn(Opcodes.INVOKESTATIC, MAP_CODEC, "assumeMapUnsafe", "(" + codec + ")" + mapCodec, false);
            convert.visitMethodInsn(Opcodes.INVOKESTATIC, CODEC, "mapEither", "(" + mapCodec + mapCodec + ")" + mapCodec,
                    true);
            String either = "L" + EITHER + ";";
            visitFunction(convert, new Handle(Opcodes.H_INVOKESTATIC, EITHER, "unwrap", "(" + either + ")Ljava/lang/Object;",
                    false), Type.getMethodType("(" + either + ")Ljava/lang/Object;"));
            visitFunction(convert, new Handle(Opcodes.H_INVOKESTATIC, EITHER, "left", "(Ljava/lang/Object;)" + either,
                    false), Type.getMethodType("(Ljava/lang/Object;)" + either));
            convert.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MAP_CODEC, "xmap",
                    "(Ljava/util/function/Function;Ljava/util/function/Function;)" + mapCodec, false);
        }
        convert.visitInsn(Opcodes.ARETURN);
        end(convert);
    }

    /** Pushes a {@code Function} that calls the static {@code target}. */
    static void visitFunction(MethodVisitor mv, Handle target, Type instantiated) {
        mv.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", metafactory(),
                Type.getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;"), target, instantiated);
    }

    private static Handle metafactory() {
        return new Handle(Opcodes.H_INVOKESTATIC, LAMBDA_METAFACTORY, "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
    }

    /** The 26.3 stand-in: a holder of one map codec with the old record's members. */
    static byte[] generateStandIn() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        String self = "L" + STAND_IN + ";";
        String mapCodec = "L" + MAP_CODEC + ";";
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, STAND_IN, null,
                "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "codec", mapCodec, null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + mapCodec + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, STAND_IN, "codec", mapCodec);
        init.visitInsn(Opcodes.RETURN);
        end(init);

        MethodVisitor of = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "of",
                "(" + mapCodec + ")" + self, null, null);
        of.visitCode();
        of.visitTypeInsn(Opcodes.NEW, STAND_IN);
        of.visitInsn(Opcodes.DUP);
        of.visitVarInsn(Opcodes.ALOAD, 0);
        of.visitMethodInsn(Opcodes.INVOKESPECIAL, STAND_IN, "<init>", "(" + mapCodec + ")V", false);
        of.visitInsn(Opcodes.ARETURN);
        end(of);

        MethodVisitor codec = cw.visitMethod(Opcodes.ACC_PUBLIC, "codec", "()" + mapCodec, null, null);
        codec.visitCode();
        codec.visitVarInsn(Opcodes.ALOAD, 0);
        codec.visitFieldInsn(Opcodes.GETFIELD, STAND_IN, "codec", mapCodec);
        codec.visitInsn(Opcodes.ARETURN);
        end(codec);

        // A record compared and hashed by its codec, and printed the way the record was.
        MethodVisitor equals = cw.visitMethod(Opcodes.ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
        equals.visitCode();
        Label other = new Label();
        equals.visitVarInsn(Opcodes.ALOAD, 1);
        equals.visitTypeInsn(Opcodes.INSTANCEOF, STAND_IN);
        equals.visitJumpInsn(Opcodes.IFEQ, other);
        equals.visitVarInsn(Opcodes.ALOAD, 0);
        equals.visitFieldInsn(Opcodes.GETFIELD, STAND_IN, "codec", mapCodec);
        equals.visitVarInsn(Opcodes.ALOAD, 1);
        equals.visitTypeInsn(Opcodes.CHECKCAST, STAND_IN);
        equals.visitFieldInsn(Opcodes.GETFIELD, STAND_IN, "codec", mapCodec);
        equals.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "equals",
                "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
        equals.visitInsn(Opcodes.IRETURN);
        equals.visitLabel(other);
        equals.visitInsn(Opcodes.ICONST_0);
        equals.visitInsn(Opcodes.IRETURN);
        end(equals);

        MethodVisitor hash = cw.visitMethod(Opcodes.ACC_PUBLIC, "hashCode", "()I", null, null);
        hash.visitCode();
        hash.visitVarInsn(Opcodes.ALOAD, 0);
        hash.visitFieldInsn(Opcodes.GETFIELD, STAND_IN, "codec", mapCodec);
        hash.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "hashCode", "(Ljava/lang/Object;)I", false);
        hash.visitInsn(Opcodes.IRETURN);
        end(hash);

        MethodVisitor text = cw.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        text.visitCode();
        text.visitLdcInsn("KeyDispatchDataCodec[codec=");
        text.visitVarInsn(Opcodes.ALOAD, 0);
        text.visitFieldInsn(Opcodes.GETFIELD, STAND_IN, "codec", mapCodec);
        text.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                "(Ljava/lang/Object;)Ljava/lang/String;", false);
        text.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        text.visitLdcInsn("]");
        text.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        text.visitInsn(Opcodes.ARETURN);
        end(text);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Hands the host's own map codec to registrations and to {@code of} where an old codec fed them. */
    public static byte[] adaptRegistrations(byte[] classBytes) {
        if (classBytes == null || !contains(classBytes, HELPER_BYTES)) return classBytes;
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (RuntimeException unreadable) {
            return classBytes;
        }
        int changes = 0;
        for (MethodNode method : node.methods) {
            if (method.instructions == null || method.instructions.size() == 0) continue;
            Set<LabelNode> joins = joins(method);
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                // The registries that dispatch these types hold map codecs from 1.20.5 on and find a
                // value's id by identity when they write it, so a converted codec fails both ways.
                if (insn instanceof MethodInsnNode call && isHelperCodec(call) && feedsObjectSink(call)) {
                    inlineAccessor(call);
                    changes++;
                } else if (insn instanceof MethodInsnNode call && isHelperOf(call)
                        && buildFromMapCodec(method, call, joins)) {
                    changes++;
                } else if (insn instanceof InvokeDynamicInsnNode indy && isHelperCodecSupplier(indy)
                        && isSupplierRegistration(nextStep(indy))) {
                    supplyMapCodec(indy);
                    changes++;
                }
            }
        }
        if (changes == 0) return classBytes;
        // Each rewrite keeps one reference on the stack between steps no other path joins, so no frame changes.
        try {
            ClassWriter writer = new SafeClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException failed) {
            return classBytes;
        }
    }

    private static boolean isHelperCodec(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESTATIC && HELPER.equals(call.owner) && "codec".equals(call.name)
                && Type.getArgumentTypes(call.desc).length == 1;
    }

    private static boolean isHelperOf(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESTATIC && HELPER.equals(call.owner) && "of".equals(call.name)
                && call.desc.startsWith("(L" + CODEC + ";)");
    }

    /** The accessor itself, inline, so mixin code merged into Minecraft calls only the host. */
    private static void inlineAccessor(MethodInsnNode call) {
        String holder = Type.getArgumentTypes(call.desc)[0].getInternalName();
        call.setOpcode(Opcodes.INVOKEVIRTUAL);
        call.owner = holder;
        call.name = accessorName(holder);
        call.desc = "()L" + MAP_CODEC + ";";
        call.itf = false;
    }

    private static String accessorName(String holder) {
        return holder.equals(INTERMEDIARY.owner()) ? INTERMEDIARY.codec() : MOJANG.codec();
    }

    /** Builds {@code of(Codec.unit(x))} and {@code of(mapCodec.codec())} from the map codec itself. */
    private static boolean buildFromMapCodec(MethodNode method, MethodInsnNode of, Set<LabelNode> joins) {
        // DFU 6 dispatched a unit codec inline, but from DFU 9 a unit map codec's codec() is a plain
        // codec, which the helper would read under a "value" key the data never had.
        if (!(previousStep(of, joins) instanceof MethodInsnNode made)) return false;
        String mapCodec = "L" + MAP_CODEC + ";";
        if (isUnitCodec(made)) {
            // MapCodec.unit takes the same value or supplier.
            made.owner = MAP_CODEC;
            made.name = "unit";
            made.desc = "(" + Type.getArgumentTypes(made.desc)[0].getDescriptor() + ")" + mapCodec;
            made.itf = false;
        } else if (made.getOpcode() == Opcodes.INVOKEVIRTUAL && MAP_CODEC.equals(made.owner)
                && "codec".equals(made.name) && made.desc.equals("()L" + CODEC + ";")) {
            method.instructions.remove(made);
        } else {
            return false;
        }
        String holder = Type.getReturnType(of.desc).getInternalName();
        of.owner = holder;
        of.name = holder.equals(INTERMEDIARY.owner()) ? INTERMEDIARY.of() : MOJANG.of();
        of.desc = "(" + mapCodec + ")L" + holder + ";";
        of.itf = false;
        return true;
    }

    /** {@code Codec.unit}, or {@code MapCodec.unitCodec} where DFU 9 moved it, with a value or a supplier. */
    private static boolean isUnitCodec(MethodInsnNode call) {
        boolean unit = (CODEC.equals(call.owner) && "unit".equals(call.name))
                || (MAP_CODEC.equals(call.owner) && "unitCodec".equals(call.name));
        return unit && call.getOpcode() == Opcodes.INVOKESTATIC && Type.getArgumentTypes(call.desc).length == 1
                && call.desc.endsWith(")L" + CODEC + ";");
    }

    /** A {@code Supplier} made from a bound {@code codec()} method reference, which the redirect sent to the helper. */
    private static boolean isHelperCodecSupplier(InvokeDynamicInsnNode indy) {
        return LAMBDA_METAFACTORY.equals(indy.bsm.getOwner()) && "get".equals(indy.name)
                && indy.desc.endsWith(")L" + SUPPLIER + ";") && Type.getArgumentTypes(indy.desc).length == 1
                && indy.bsmArgs.length >= 3 && indy.bsmArgs[1] instanceof Handle target
                && target.getTag() == Opcodes.H_INVOKESTATIC && HELPER.equals(target.getOwner())
                && "codec".equals(target.getName()) && Type.getArgumentTypes(target.getDesc()).length == 1;
    }

    /** The supplier reads the host's accessor, as {@code inlineAccessor} does for a direct call. */
    private static void supplyMapCodec(InvokeDynamicInsnNode indy) {
        Handle target = (Handle) indy.bsmArgs[1];
        String holder = Type.getArgumentTypes(target.getDesc())[0].getInternalName();
        String accessor = "()L" + MAP_CODEC + ";";
        indy.bsmArgs[1] = new Handle(Opcodes.H_INVOKEVIRTUAL, holder, accessorName(holder), accessor, false);
        indy.bsmArgs[2] = Type.getMethodType(accessor);
    }

    // Calls into these take an Object or a Supplier for their own purposes, never as a registration.
    private static final List<String> NOT_REGISTRATIONS = List.of("java/", "com/google/", "com/mojang/datafixers/",
            "com/mojang/serialization/", HostLibraryNames.SLF4J_PACKAGE + "/", "org/apache/logging/");

    /** Whether the next instruction takes the value as its last {@code Object} argument, as a registration does. */
    static boolean feedsObjectSink(MethodInsnNode call) {
        return isRegistration(nextStep(call), "Ljava/lang/Object;");
    }

    /** A call that takes the value as its last argument, of type {@code valueDesc}, outside the JDK and libraries. */
    static boolean isRegistration(AbstractInsnNode next, String valueDesc) {
        if (!(next instanceof MethodInsnNode consumer)) return false;
        // Lithostitched registers through a BiConsumer its loader hands it.
        boolean registrar = consumer.owner.equals("java/util/function/BiConsumer") && consumer.name.equals("accept");
        if (!registrar) {
            for (String prefix : NOT_REGISTRATIONS) {
                if (consumer.owner.startsWith(prefix)) return false;
            }
        }
        Type[] arguments = Type.getArgumentTypes(consumer.desc);
        return arguments.length > 0 && arguments[arguments.length - 1].getDescriptor().equals(valueDesc);
    }

    // The game and its loaders take a Supplier for their own use almost everywhere, such as Forge's
    // Lazy.of or Util.make, and read it back as the type they expect. Only their registries register one.
    private static final List<String> HOST_PACKAGES = List.of("net/minecraft/", "com/mojang/", "net/minecraftforge/",
            "net/neoforged/", "net/fabricmc/");
    private static final List<String> REGISTRY_PACKAGES = List.of("net/minecraftforge/registries/",
            "net/neoforged/neoforge/registries/");

    /** A registration that takes a {@code Supplier} last: the mod's own, or one of a loader's registries. */
    static boolean isSupplierRegistration(AbstractInsnNode next) {
        if (!isRegistration(next, "L" + SUPPLIER + ";")) return false;
        String owner = ((MethodInsnNode) next).owner;
        return startsWithAny(owner, REGISTRY_PACKAGES) || !startsWithAny(owner, HOST_PACKAGES);
    }

    private static boolean startsWithAny(String name, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }

    /** The next instruction; a frame means another path joins there, so only line numbers and plain labels are skipped. */
    private static AbstractInsnNode nextStep(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next instanceof LineNumberNode || next instanceof LabelNode) next = next.getNext();
        return next;
    }

    /** The previous instruction, unless a jump or a frame joins between them. */
    private static AbstractInsnNode previousStep(AbstractInsnNode insn, Set<LabelNode> joins) {
        AbstractInsnNode previous = insn.getPrevious();
        while (previous instanceof LineNumberNode || (previous instanceof LabelNode label && !joins.contains(label))) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    /** Every label control flow can reach other than by falling through. */
    private static Set<LabelNode> joins(MethodNode method) {
        Set<LabelNode> joins = new HashSet<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof JumpInsnNode jump) joins.add(jump.label);
            if (insn instanceof TableSwitchInsnNode table) {
                joins.add(table.dflt);
                joins.addAll(table.labels);
            }
            if (insn instanceof LookupSwitchInsnNode lookup) {
                joins.add(lookup.dflt);
                joins.addAll(lookup.labels);
            }
        }
        if (method.tryCatchBlocks != null) {
            for (TryCatchBlockNode block : method.tryCatchBlocks) joins.add(block.handler);
        }
        return joins;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static void end(MethodVisitor mv) {
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
