/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps pre-1.20.3 sand and gravel blocks working.
 *
 * <p>1.20.3 deleted {@code SandBlock} and {@code GravelBlock} and folded both into
 * {@code ColoredFallingBlock}, whose constructor takes the dust color as a {@code ColorRGBA}
 * record instead of an {@code int}. Renaming the class alone leaves {@code new SandBlock(int,
 * Properties)} and a subclass's {@code super(int, Properties)} pointing at a constructor that does
 * not exist.
 *
 * <p>Both classes move to a generated {@code ColoredFallingBlock} subclass that keeps the old
 * constructors: the sand one wraps its color, and the gravel one supplies gravel's old dust color.
 * It is registered by the Forge 1.20 to NeoForge 1.21 shim, so it applies to Forge-family mods on
 * NeoForge and Forge 1.21 and newer, where class names are Mojang's. For a mod built before 1.21.5,
 * a type test or cast against the old classes now names the generated class, so vanilla sand and
 * gravel no longer match it.
 */
public final class LegacyFallingBlockBridge {

    public static final String GENERATED = "com/retromod/generated/LegacyColoredFallingBlock";
    /** Turns on {@link #addDustColor} in the post-remap class passes. */
    public static final String DUST_FLAG = "legacy-falling-dust";
    /** The version that brought {@code SandBlock} back, as a {@code ColoredFallingBlock} subclass. */
    public static final String SAND_RETURNED = "1.21.5";
    static final String SAND = "net/minecraft/world/level/block/SandBlock";
    static final String GRAVEL = "net/minecraft/world/level/block/GravelBlock";
    static final String FALLING = "net/minecraft/world/level/block/FallingBlock";
    /** 1.21.1 still has the plain dust color; 1.21.11 and 26.3 left it abstract, and the versions between are unchecked. */
    static final String FIRST_ABSTRACT_DUST_HOST = "1.21.2";

    /** {@code GravelBlock.getDustColor} in 1.20.2, the color vanilla gravel still uses. */
    static final int GRAVEL_DUST_COLOR = -8356741;
    /** {@code FallingBlock.getDustColor} in 1.20.1, which a plain falling block used. */
    static final int PLAIN_DUST_COLOR = -16777216;

    /** The falling block types and the dust color method, as the host names them. */
    public record Names(String falling, String colored, String color, String properties, String dustName, String dustDesc) {
        public static final Names MOJANG = new Names(FALLING, "net/minecraft/world/level/block/ColoredFallingBlock",
                "net/minecraft/util/ColorRGBA", "net/minecraft/world/level/block/state/BlockBehaviour$Properties", "getDustColor",
                "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)I");
        /** Fabric hosts before 26.1, which run intermediary names. */
        public static final Names INTERMEDIARY = new Names("net/minecraft/class_2346", "net/minecraft/class_8812",
                "net/minecraft/class_8805", "net/minecraft/class_4970$class_2251", "method_10130",
                "(Lnet/minecraft/class_2680;Lnet/minecraft/class_1922;Lnet/minecraft/class_2338;)I");

        String propertiesDesc() {
            return "L" + properties + ";";
        }

        String hostCtor() {
            return "(L" + color + ";" + propertiesDesc() + ")V";
        }
    }

    private LegacyFallingBlockBridge() {}

    public static void register(RetromodTransformer transformer) {
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20.3") < 0) return;
        registerAbstractBase(transformer, Names.MOJANG);
        transformer.registerClassRedirect(SAND, GENERATED);
        // 1.21.5 brought SandBlock back as a ColoredFallingBlock, so a mod built for it keeps the host class.
        transformer.limitClassRedirectToModsBefore(SAND, SAND_RETURNED);
        transformer.registerClassRedirect(GRAVEL, GENERATED);
    }

    /** The plain falling block 1.20.3 made abstract: built as the generated block, and given a dust color where the host has none. */
    public static void registerAbstractBase(RetromodTransformer transformer, Names names) {
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20.3") < 0) return;
        if (!transformer.getSyntheticClasses().containsKey(GENERATED)) transformer.registerSyntheticClass(GENERATED, generate(names));
        // A mod's plain falling block is built as the generated class (#415).
        transformer.registerConstructorRedirect(names.falling(), "(" + names.propertiesDesc() + ")V", GENERATED, "plain",
                "(" + names.propertiesDesc() + ")L" + names.falling() + ";");
        if (hostLeavesDustAbstract(ClassResourceInspector.read(names.falling()), names)) transformer.enablePostProcess(DUST_FLAG);
    }

    /** Whether the host's FallingBlock declares the dust color abstract; unreadable, the target decides. */
    static boolean hostLeavesDustAbstract(ClassNode falling, Names names) {
        if (falling == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_ABSTRACT_DUST_HOST) >= 0;
        }
        return falling.methods.stream().anyMatch(m -> m.name.equals(names.dustName()) && m.desc.equals(names.dustDesc())
                && (m.access & ACC_ABSTRACT) != 0);
    }

    /** A mod class that extends FallingBlock directly gets the old plain dust color when it has none of its own. */
    public static byte[] addDustColor(byte[] classBytes) {
        if (classBytes == null) return null;
        String constants = new String(classBytes, StandardCharsets.ISO_8859_1);
        Names names = constants.contains(Names.MOJANG.falling()) ? Names.MOJANG
                : constants.contains(Names.INTERMEDIARY.falling()) ? Names.INTERMEDIARY : null;
        if (names == null) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if (!names.falling().equals(node.superName) || (node.access & ACC_INTERFACE) != 0 || isMixin(node)) return classBytes;
        for (MethodNode method : node.methods) {
            if (method.name.equals(names.dustName()) && method.desc.equals(names.dustDesc())) return classBytes;
        }
        // An abstract mod base gets it too, so every block built on it has one.
        MethodVisitor dust = node.visitMethod(ACC_PUBLIC, names.dustName(), names.dustDesc(), null, null);
        dust.visitCode();
        dust.visitLdcInsn(PLAIN_DUST_COLOR);
        dust.visitInsn(IRETURN);
        dust.visitMaxs(1, 4);
        dust.visitEnd();
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean isMixin(ClassNode node) {
        List<AnnotationNode> annotations = node.invisibleAnnotations;
        return annotations != null && annotations.stream().anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"));
    }

    /** {@code class LegacyColoredFallingBlock extends ColoredFallingBlock} with the old constructors, under Mojang's names. */
    static byte[] generate() {
        return generate(Names.MOJANG);
    }

    static byte[] generate(Names names) {
        String props = names.propertiesDesc();
        String hostCtor = names.hostCtor();
        String colored = names.colored();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, GENERATED, null, colored, null);

        MethodVisitor sand = cw.visitMethod(ACC_PUBLIC, "<init>", "(I" + props + ")V", null, null);
        sand.visitCode();
        sand.visitVarInsn(ALOAD, 0);
        newColor(sand, names.color(), () -> sand.visitVarInsn(ILOAD, 1));
        sand.visitVarInsn(ALOAD, 2);
        sand.visitMethodInsn(INVOKESPECIAL, colored, "<init>", hostCtor, false);
        sand.visitInsn(RETURN);
        sand.visitMaxs(0, 0);
        sand.visitEnd();

        MethodVisitor gravel = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + props + ")V", null, null);
        gravel.visitCode();
        gravel.visitVarInsn(ALOAD, 0);
        newColor(gravel, names.color(), () -> gravel.visitLdcInsn(GRAVEL_DUST_COLOR));
        gravel.visitVarInsn(ALOAD, 1);
        gravel.visitMethodInsn(INVOKESPECIAL, colored, "<init>", hostCtor, false);
        gravel.visitInsn(RETURN);
        gravel.visitMaxs(0, 0);
        gravel.visitEnd();

        // A mod that already moved to the record passes straight through.
        MethodVisitor host = cw.visitMethod(ACC_PUBLIC, "<init>", hostCtor, null, null);
        host.visitCode();
        for (int slot = 0; slot <= 2; slot++) host.visitVarInsn(ALOAD, slot);
        host.visitMethodInsn(INVOKESPECIAL, colored, "<init>", hostCtor, false);
        host.visitInsn(RETURN);
        host.visitMaxs(0, 0);
        host.visitEnd();

        // static plain(properties): a falling block with the old plain block's dust color.
        MethodVisitor plain = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "plain", "(" + props + ")L" + names.falling() + ";", null, null);
        plain.visitCode();
        plain.visitTypeInsn(NEW, GENERATED);
        plain.visitInsn(DUP);
        plain.visitLdcInsn(PLAIN_DUST_COLOR);
        plain.visitVarInsn(ALOAD, 0);
        plain.visitMethodInsn(INVOKESPECIAL, GENERATED, "<init>", "(I" + props + ")V", false);
        plain.visitInsn(ARETURN);
        plain.visitMaxs(0, 0);
        plain.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void newColor(MethodVisitor mv, String color, Runnable pushColor) {
        mv.visitTypeInsn(NEW, color);
        mv.visitInsn(DUP);
        pushColor.run();
        mv.visitMethodInsn(INVOKESPECIAL, color, "<init>", "(I)V", false);
    }
}
