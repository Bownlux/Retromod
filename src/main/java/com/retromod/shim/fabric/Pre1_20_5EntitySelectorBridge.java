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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges the {@code EntitySelector} constructor that 1.20.5 reshaped, for intermediary-namespace
 * Fabric mods on pre-26.1 hosts.
 *
 * <p>Before 1.20.5 the constructor took a single {@code Predicate<Entity>}. It now takes a
 * {@code List<Predicate<Entity>>} that the selector combines with a logical and. A mod that builds
 * a selector by hand, usually a static "all entities" selector, dies with
 * {@code NoSuchMethodError} in its {@code <clinit>}, and every later use of that class fails with
 * {@code NoClassDefFoundError}.
 *
 * <p>Each old {@code new} call becomes a static factory that wraps the predicate in a one-element
 * list. The selector then tests exactly the predicate the old constructor stored. Subclasses are
 * not bridged: the constructor stays public, but no known mod extends the selector.
 */
public final class Pre1_20_5EntitySelectorBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String FACTORY = "com/retromod/generated/LegacyEntitySelectors";
    static final String FACTORY_METHOD = "entitySelector";
    static final String ENTITY_SELECTOR = "net/minecraft/class_2300";

    private static final String TAIL = "Lnet/minecraft/class_2096$class_2099;Ljava/util/function/Function;"
            + "Lnet/minecraft/class_238;Ljava/util/function/BiConsumer;ZLjava/lang/String;"
            + "Ljava/util/UUID;Lnet/minecraft/class_1299;Z";
    static final String OLD_DESC = "(IZZLjava/util/function/Predicate;" + TAIL + ")V";
    static final String MODERN_DESC = "(IZZLjava/util/List;" + TAIL + ")V";
    static final String FACTORY_DESC = "(IZZLjava/util/function/Predicate;" + TAIL + ")L"
            + ENTITY_SELECTOR + ";";

    /** The argument that changed from a predicate to a list of predicates. */
    private static final int PREDICATE_INDEX = 3;

    private Pre1_20_5EntitySelectorBridge() {}

    /** Registers the factory only when the host has the list constructor and not the old one. */
    public static void register(RetromodTransformer transformer) {
        ClassNode selector = ClassResourceInspector.read(ENTITY_SELECTOR);
        if (selector == null || !hasConstructor(selector, MODERN_DESC) || hasConstructor(selector, OLD_DESC)) {
            LOGGER.debug("The host still supports the old EntitySelector constructor");
            return;
        }
        registerRedirects(transformer);
        LOGGER.debug("Added support for the pre-1.20.5 EntitySelector constructor");
    }

    /** Unconditional registration for offline transforms and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(FACTORY, generateFactory());
        transformer.registerConstructorRedirect(ENTITY_SELECTOR, OLD_DESC, FACTORY, FACTORY_METHOD, FACTORY_DESC);
    }

    private static boolean hasConstructor(ClassNode owner, String descriptor) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals("<init>") && method.desc.equals(descriptor)) return true;
        }
        return false;
    }

    static byte[] generateFactory() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                FACTORY, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                FACTORY_METHOD, FACTORY_DESC, null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, ENTITY_SELECTOR);
        mv.visitInsn(Opcodes.DUP);
        Type[] arguments = Type.getArgumentTypes(OLD_DESC);
        int slot = 0;
        for (int i = 0; i < arguments.length; i++) {
            mv.visitVarInsn(arguments[i].getOpcode(Opcodes.ILOAD), slot);
            if (i == PREDICATE_INDEX) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/List", "of",
                        "(Ljava/lang/Object;)Ljava/util/List;", true);
            }
            slot += arguments[i].getSize();
        }
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, ENTITY_SELECTOR, "<init>", MODERN_DESC, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
