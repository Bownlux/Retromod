/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridges worldgen type interfaces whose {@code codec()} returned a {@code Codec} before 1.20.5
 * and a {@code MapCodec} since, for Mojang-named mods built for 1.20.4 or older.
 *
 * <p>A mod registers its structure type as a lambda, {@code () -> MyStructure.CODEC}. The lambda is
 * built against the old method type, so it never implements the host's {@code codec()} and the
 * datapack load that reads the structure fails with {@code AbstractMethodError}. Placement
 * modifier types were registered through {@code PlacementModifierType.register(String, Codec)},
 * which 1.20.5 retyped to {@code MapCodec}.
 *
 * <p>Such a lambda is built as a {@code Supplier} instead and wrapped in a generated class that
 * implements every bridged interface. The wrapper reads the supplier each time the codec is
 * asked for, so a codec declared after the type still resolves. A record codec is unwrapped to
 * its map codec; any other codec is treated as a map codec, which is what the old type promised.
 *
 * <p>Registered for each interface whose host {@code codec()} returns a {@code MapCodec}; offline,
 * the host version decides. 26.2 replaced the structure processor registry with bare codecs, which
 * {@code WorldgenTypeBridgeSynthetic} handles.
 */
public final class LegacyCodecTypeBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String HELPER = "com/retromod/generated/LegacyCodecType";

    static final String CODEC = "com/mojang/serialization/Codec";
    static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    static final String MAP_CODEC_CODEC = MAP_CODEC + "$MapCodecCodec";
    static final String SUPPLIER = "java/util/function/Supplier";
    static final String PLACEMENT_MODIFIER_TYPE = "net/minecraft/world/level/levelgen/placement/PlacementModifierType";
    static final String REGISTRY = "net/minecraft/core/Registry";
    static final String BUILT_IN = "net/minecraft/core/registries/BuiltInRegistries";

    static final List<String> INTERFACES = List.of(
            PLACEMENT_MODIFIER_TYPE,
            "net/minecraft/world/level/levelgen/structure/StructureType",
            "net/minecraft/world/level/levelgen/structure/placement/StructurePlacementType",
            "net/minecraft/world/level/levelgen/structure/templatesystem/StructureProcessorType",
            "net/minecraft/world/level/levelgen/structure/pools/StructurePoolElementType");

    static final String OLD_SAM = "()L" + CODEC + ";";
    static final String NEW_SAM = "()L" + MAP_CODEC + ";";

    private LegacyCodecTypeBridge() {}

    public static void register(RetromodTransformer transformer) {
        List<String> bridged = new ArrayList<>();
        for (String iface : INTERFACES) {
            if (mapCodecOnHost(iface)) bridged.add(iface);
        }
        if (bridged.isEmpty()) {
            LOGGER.debug("Worldgen codec type bridge not needed on this host");
            return;
        }
        registerRedirects(transformer, bridged);
        LOGGER.info("Bridged {} worldgen types whose codec became a MapCodec", bridged.size());
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, List<String> interfaces) {
        transformer.registerSyntheticClass(HELPER, generateHelper(interfaces));
        for (String iface : interfaces) {
            transformer.registerLambdaAdapter(iface, "codec", OLD_SAM,
                    new RetromodTransformer.LambdaAdapter(SUPPLIER, "get", "()Ljava/lang/Object;",
                            HELPER, wrapName(iface), "(L" + SUPPLIER + ";)L" + iface + ";"));
        }
        if (interfaces.contains(PLACEMENT_MODIFIER_TYPE)) {
            transformer.registerMethodRedirect(PLACEMENT_MODIFIER_TYPE, "register",
                    "(Ljava/lang/String;L" + CODEC + ";)L" + PLACEMENT_MODIFIER_TYPE + ";",
                    HELPER, "registerPlacementModifier",
                    "(Ljava/lang/String;L" + CODEC + ";)L" + PLACEMENT_MODIFIER_TYPE + ";");
        }
    }

    static String wrapName(String iface) {
        return "wrap" + iface.substring(iface.lastIndexOf('/') + 1);
    }

    private static boolean mapCodecOnHost(String iface) {
        ClassNode node = ClassResourceInspector.read(iface);
        if (node == null) {
            String host = RetromodVersion.TARGET_MC_VERSION;
            return RetromodVersion.compareMcVersions(host, "1.20.5") >= 0
                    && RetromodVersion.compareMcVersions(host, "26.2") < 0;
        }
        for (MethodNode method : node.methods) {
            if (method.name.equals("codec") && method.desc.equals(NEW_SAM)) return true;
        }
        return false;
    }

    static byte[] generateHelper(List<String> interfaces) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", interfaces.toArray(new String[0]));
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "source", "Ljava/lang/Object;", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 1);
        init.visitFieldInsn(Opcodes.PUTFIELD, HELPER, "source", "Ljava/lang/Object;");
        init.visitInsn(Opcodes.RETURN);
        end(init);

        emitCodec(cw);

        for (String iface : interfaces) {
            MethodVisitor wrap = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, wrapName(iface),
                    "(L" + SUPPLIER + ";)L" + iface + ";", null, null);
            wrap.visitCode();
            wrap.visitTypeInsn(Opcodes.NEW, HELPER);
            wrap.visitInsn(Opcodes.DUP);
            wrap.visitVarInsn(Opcodes.ALOAD, 0);
            wrap.visitMethodInsn(Opcodes.INVOKESPECIAL, HELPER, "<init>", "(Ljava/lang/Object;)V", false);
            wrap.visitInsn(Opcodes.ARETURN);
            end(wrap);
        }

        if (interfaces.contains(PLACEMENT_MODIFIER_TYPE)) {
            MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "registerPlacementModifier",
                    "(Ljava/lang/String;L" + CODEC + ";)L" + PLACEMENT_MODIFIER_TYPE + ";", null, null);
            register.visitCode();
            register.visitFieldInsn(Opcodes.GETSTATIC, BUILT_IN, "PLACEMENT_MODIFIER_TYPE", "L" + REGISTRY + ";");
            register.visitVarInsn(Opcodes.ALOAD, 0);
            register.visitTypeInsn(Opcodes.NEW, HELPER);
            register.visitInsn(Opcodes.DUP);
            register.visitVarInsn(Opcodes.ALOAD, 1);
            register.visitMethodInsn(Opcodes.INVOKESPECIAL, HELPER, "<init>", "(Ljava/lang/Object;)V", false);
            register.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "register",
                    "(L" + REGISTRY + ";Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", true);
            register.visitTypeInsn(Opcodes.CHECKCAST, PLACEMENT_MODIFIER_TYPE);
            register.visitInsn(Opcodes.ARETURN);
            end(register);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The codec the source names, as a map codec. A supplier is read on each call. */
    private static void emitCodec(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "codec", NEW_SAM, null, null);
        mv.visitCode();
        Label notSupplier = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, HELPER, "source", "Ljava/lang/Object;");
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, SUPPLIER);
        mv.visitJumpInsn(Opcodes.IFEQ, notSupplier);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, SUPPLIER);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, SUPPLIER, "get", "()Ljava/lang/Object;", true);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitLabel(notSupplier);

        Label notMap = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, MAP_CODEC);
        mv.visitJumpInsn(Opcodes.IFEQ, notMap);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, MAP_CODEC);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(notMap);

        Label notRecord = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, MAP_CODEC_CODEC);
        mv.visitJumpInsn(Opcodes.IFEQ, notRecord);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, MAP_CODEC_CODEC);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MAP_CODEC_CODEC, "codec", NEW_SAM, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(notRecord);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, CODEC);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, MAP_CODEC, "assumeMapUnsafe",
                "(L" + CODEC + ";)L" + MAP_CODEC + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        end(mv);
    }

    private static void end(MethodVisitor mv) {
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
