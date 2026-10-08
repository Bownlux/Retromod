/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges block constructors that 1.20.3 reshaped for block codecs, for intermediary-namespace
 * Fabric mods on pre-26.1 hosts.
 *
 * <p>An older mod registering these blocks dies with {@code NoSuchMethodError} in its block
 * registry {@code <clinit>}, which stops every later registration in the same mod:
 * <ul>
 *   <li>{@code DropExperienceBlock(Properties)} and {@code DropExperienceBlock(Properties,
 *       IntProvider)} became {@code DropExperienceBlock(IntProvider, Properties)}. The
 *       one-argument form drops no experience, which is what its old constructor did.</li>
 *   <li>{@code AmethystClusterBlock(int, int, Properties)} became
 *       {@code AmethystClusterBlock(float, float, Properties)}. The sizes widen unchanged.</li>
 * </ul>
 *
 * <p>Each old {@code new} call becomes a static factory with the arguments in the new shape. A mod
 * class that extends one of these blocks is rebased onto a generated subclass that accepts the old
 * {@code super(...)} shapes and passes the modern shape straight through, so a 1.20.3+ subclass
 * transformed on the same host keeps working.
 */
public final class Pre1_20_3BlockConstructorBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String FACTORY = "com/retromod/generated/LegacyBlockConstructors";
    static final String DROP_EXPERIENCE_BASE = "com/retromod/generated/LegacyDropExperienceBlock";
    static final String AMETHYST_CLUSTER_BASE = "com/retromod/generated/LegacyAmethystClusterBlock";
    static final String DROP_EXPERIENCE_BLOCK = "net/minecraft/class_2431";
    static final String AMETHYST_CLUSTER_BLOCK = "net/minecraft/class_5542";
    static final String INT_PROVIDER = "net/minecraft/class_6017";
    static final String CONSTANT_INT = "net/minecraft/class_6016";
    static final String CONSTANT_INT_OF = "method_34998";

    private static final String L_PROPERTIES = "Lnet/minecraft/class_4970$class_2251;";
    private static final String L_INT_PROVIDER = "L" + INT_PROVIDER + ";";

    static final String DROP_OLD_PROPERTIES = "(" + L_PROPERTIES + ")V";
    static final String DROP_OLD_PROPERTIES_PROVIDER = "(" + L_PROPERTIES + L_INT_PROVIDER + ")V";
    static final String DROP_MODERN = "(" + L_INT_PROVIDER + L_PROPERTIES + ")V";
    static final String CLUSTER_OLD = "(II" + L_PROPERTIES + ")V";
    static final String CLUSTER_MODERN = "(FF" + L_PROPERTIES + ")V";

    private Pre1_20_3BlockConstructorBridge() {}

    /** Registers each factory only when the host has the new constructor and not the old one. */
    public static void register(RetromodTransformer transformer) {
        boolean dropExperience = constructorChanged(
                DROP_EXPERIENCE_BLOCK, DROP_OLD_PROPERTIES, DROP_MODERN);
        boolean amethystCluster = constructorChanged(
                AMETHYST_CLUSTER_BLOCK, CLUSTER_OLD, CLUSTER_MODERN);
        if (!dropExperience && !amethystCluster) {
            LOGGER.debug("The host still supports the old block constructors");
            return;
        }
        registerRedirects(transformer, dropExperience, amethystCluster);
        LOGGER.debug("Added support for old DropExperienceBlock or AmethystClusterBlock constructors");
    }

    /** Unconditional registration for offline transforms and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer transformer,
            boolean dropExperience, boolean amethystCluster) {
        transformer.registerSyntheticClass(FACTORY, generateFactory());
        if (dropExperience) {
            transformer.registerConstructorRedirect(DROP_EXPERIENCE_BLOCK, DROP_OLD_PROPERTIES,
                    FACTORY, "dropExperienceBlock", factoryDesc(DROP_OLD_PROPERTIES, DROP_EXPERIENCE_BLOCK));
            transformer.registerConstructorRedirect(DROP_EXPERIENCE_BLOCK, DROP_OLD_PROPERTIES_PROVIDER,
                    FACTORY, "dropExperienceBlock",
                    factoryDesc(DROP_OLD_PROPERTIES_PROVIDER, DROP_EXPERIENCE_BLOCK));
            transformer.registerSyntheticClass(DROP_EXPERIENCE_BASE, generateDropExperienceBase());
            transformer.registerSuperclassRebase(DROP_EXPERIENCE_BLOCK, DROP_EXPERIENCE_BASE);
        }
        if (amethystCluster) {
            transformer.registerConstructorRedirect(AMETHYST_CLUSTER_BLOCK, CLUSTER_OLD,
                    FACTORY, "amethystClusterBlock", factoryDesc(CLUSTER_OLD, AMETHYST_CLUSTER_BLOCK));
            transformer.registerSyntheticClass(AMETHYST_CLUSTER_BASE, generateAmethystClusterBase());
            transformer.registerSuperclassRebase(AMETHYST_CLUSTER_BLOCK, AMETHYST_CLUSTER_BASE);
        }
    }

    private static boolean constructorChanged(String owner, String oldDesc, String modernDesc) {
        ClassNode node = ClassResourceInspector.read(owner);
        return node != null && hasConstructor(node, modernDesc) && !hasConstructor(node, oldDesc);
    }

    private static boolean hasConstructor(ClassNode owner, String descriptor) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals("<init>") && method.desc.equals(descriptor)) return true;
        }
        return false;
    }

    private static String factoryDesc(String ctorDesc, String owner) {
        return ctorDesc.substring(0, ctorDesc.length() - 1) + "L" + owner + ";";
    }

    static byte[] generateFactory() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                FACTORY, null, "java/lang/Object", null);

        // dropExperienceBlock(Properties): the old constructor used ConstantInt.of(0).
        MethodVisitor noExperience = begin(cw, "dropExperienceBlock",
                factoryDesc(DROP_OLD_PROPERTIES, DROP_EXPERIENCE_BLOCK), DROP_EXPERIENCE_BLOCK);
        noExperience.visitInsn(Opcodes.ICONST_0);
        noExperience.visitMethodInsn(Opcodes.INVOKESTATIC, CONSTANT_INT, CONSTANT_INT_OF,
                "(I)L" + CONSTANT_INT + ";", false);
        noExperience.visitVarInsn(Opcodes.ALOAD, 0);
        finish(noExperience, DROP_EXPERIENCE_BLOCK, DROP_MODERN);

        MethodVisitor withExperience = begin(cw, "dropExperienceBlock",
                factoryDesc(DROP_OLD_PROPERTIES_PROVIDER, DROP_EXPERIENCE_BLOCK), DROP_EXPERIENCE_BLOCK);
        withExperience.visitVarInsn(Opcodes.ALOAD, 1);
        withExperience.visitVarInsn(Opcodes.ALOAD, 0);
        finish(withExperience, DROP_EXPERIENCE_BLOCK, DROP_MODERN);

        MethodVisitor cluster = begin(cw, "amethystClusterBlock",
                factoryDesc(CLUSTER_OLD, AMETHYST_CLUSTER_BLOCK), AMETHYST_CLUSTER_BLOCK);
        cluster.visitVarInsn(Opcodes.ILOAD, 0);
        cluster.visitInsn(Opcodes.I2F);
        cluster.visitVarInsn(Opcodes.ILOAD, 1);
        cluster.visitInsn(Opcodes.I2F);
        cluster.visitVarInsn(Opcodes.ALOAD, 2);
        finish(cluster, AMETHYST_CLUSTER_BLOCK, CLUSTER_MODERN);

        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] generateDropExperienceBase() {
        ClassWriter cw = beginBase(DROP_EXPERIENCE_BASE, DROP_EXPERIENCE_BLOCK);

        MethodVisitor noExperience = beginSuper(cw, DROP_OLD_PROPERTIES);
        noExperience.visitInsn(Opcodes.ICONST_0);
        noExperience.visitMethodInsn(Opcodes.INVOKESTATIC, CONSTANT_INT, CONSTANT_INT_OF,
                "(I)L" + CONSTANT_INT + ";", false);
        noExperience.visitVarInsn(Opcodes.ALOAD, 1);
        finishSuper(noExperience, DROP_EXPERIENCE_BLOCK, DROP_MODERN);

        MethodVisitor withExperience = beginSuper(cw, DROP_OLD_PROPERTIES_PROVIDER);
        withExperience.visitVarInsn(Opcodes.ALOAD, 2);
        withExperience.visitVarInsn(Opcodes.ALOAD, 1);
        finishSuper(withExperience, DROP_EXPERIENCE_BLOCK, DROP_MODERN);

        MethodVisitor modern = beginSuper(cw, DROP_MODERN);
        modern.visitVarInsn(Opcodes.ALOAD, 1);
        modern.visitVarInsn(Opcodes.ALOAD, 2);
        finishSuper(modern, DROP_EXPERIENCE_BLOCK, DROP_MODERN);

        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] generateAmethystClusterBase() {
        ClassWriter cw = beginBase(AMETHYST_CLUSTER_BASE, AMETHYST_CLUSTER_BLOCK);

        MethodVisitor old = beginSuper(cw, CLUSTER_OLD);
        old.visitVarInsn(Opcodes.ILOAD, 1);
        old.visitInsn(Opcodes.I2F);
        old.visitVarInsn(Opcodes.ILOAD, 2);
        old.visitInsn(Opcodes.I2F);
        old.visitVarInsn(Opcodes.ALOAD, 3);
        finishSuper(old, AMETHYST_CLUSTER_BLOCK, CLUSTER_MODERN);

        MethodVisitor modern = beginSuper(cw, CLUSTER_MODERN);
        modern.visitVarInsn(Opcodes.FLOAD, 1);
        modern.visitVarInsn(Opcodes.FLOAD, 2);
        modern.visitVarInsn(Opcodes.ALOAD, 3);
        finishSuper(modern, AMETHYST_CLUSTER_BLOCK, CLUSTER_MODERN);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassWriter beginBase(String name, String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, superName, null);
        return cw;
    }

    private static MethodVisitor beginSuper(ClassWriter cw, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        return mv;
    }

    private static void finishSuper(MethodVisitor mv, String superName, String superDesc) {
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", superDesc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static MethodVisitor begin(ClassWriter cw, String name, String desc, String owner) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, owner);
        mv.visitInsn(Opcodes.DUP);
        return mv;
    }

    private static void finish(MethodVisitor mv, String owner, String ctorDesc) {
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", ctorDesc, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
