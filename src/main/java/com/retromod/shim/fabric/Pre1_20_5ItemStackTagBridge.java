/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Bridges the item stack NBT accessors that 1.20.5 replaced with data components, for
 * intermediary-namespace Fabric mods on pre-26.1 hosts.
 *
 * <p>Before 1.20.5 an {@code ItemStack} carried one {@code CompoundTag}, read and written through
 * {@code getTag()}, {@code getOrCreateTag()}, {@code setTag(CompoundTag)} and {@code hasTag()}.
 * The free-form part of that tag now lives in the {@code minecraft:custom_data} component, and the
 * accessors are gone, so a mod dies with {@code NoSuchMethodError} on its first item tag access.
 * A Mixin that shadows one of them fails to apply as a whole.
 *
 * <p>Each old call becomes a static adapter over the custom data component. The getters hand out
 * a tag the caller may change in place, as before. Copied stacks share one component instance, so
 * the getters first give the stack its own copy: a change through one stack never shows up in
 * another, and the container sync still sees the change. The price is that every read installs
 * a new tag: a tag the mod kept from an earlier read no longer reaches the stack once it reads the
 * tag again. Data that 1.20.5 moved into dedicated components, such as the display name,
 * enchantments or damage, is not in the custom data tag, so old code that reads those keys from
 * the tag finds nothing.
 */
public final class Pre1_20_5ItemStackTagBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String ITEM_STACK = "net/minecraft/class_1799";
    static final String HELPER = "com/retromod/generated/LegacyItemStackTags";
    private static final String COMPOUND_TAG = "net/minecraft/class_2487";
    private static final String CUSTOM_DATA = "net/minecraft/class_9279";
    private static final String COMPONENT_TYPE = "net/minecraft/class_9331";
    private static final String DATA_COMPONENTS = "net/minecraft/class_9334";
    private static final String CUSTOM_DATA_FIELD = "field_49628";
    private static final String GET_COMPONENT = "method_57824";
    private static final String SET_COMPONENT = "method_57379";
    private static final String REMOVE_COMPONENT = "method_57381";
    private static final String CUSTOM_DATA_OF = "method_57456";
    private static final String CUSTOM_DATA_IS_EMPTY = "method_57458";
    private static final String CUSTOM_DATA_LIVE_TAG = "method_57463";

    /** One removed accessor, named as the old intermediary method. */
    record Accessor(String name, String oldDesc) {
        String adapterDesc() {
            return "(L" + ITEM_STACK + ";" + oldDesc.substring(1);
        }
    }

    static final Accessor GET_TAG = new Accessor("method_7969", "()L" + COMPOUND_TAG + ";");
    static final Accessor GET_OR_CREATE_TAG = new Accessor("method_7948", "()L" + COMPOUND_TAG + ";");
    static final Accessor SET_TAG = new Accessor("method_7980", "(L" + COMPOUND_TAG + ";)V");
    static final Accessor HAS_TAG = new Accessor("method_7985", "()Z");
    /**
     * {@code enchant(Enchantment, int)}. Since 1.21 enchantments are data pack entries reached
     * through a holder, and a bare {@code Enchantment} cannot be turned into one without the
     * server's registries, so the adapter explains that instead of enchanting.
     */
    static final Accessor ENCHANT = new Accessor("method_7978", "(Lnet/minecraft/class_1887;I)V");
    static final List<Accessor> ACCESSORS = List.of(GET_TAG, GET_OR_CREATE_TAG, SET_TAG, HAS_TAG, ENCHANT);

    private Pre1_20_5ItemStackTagBridge() {}

    /** Registers the adapters only when the host lost the accessors and has the custom data component. */
    public static void register(RetromodTransformer transformer) {
        ClassNode stack = ClassResourceInspector.read(ITEM_STACK);
        if (stack == null || hasMethod(stack, GET_TAG.name(), GET_TAG.oldDesc())
                || !ClassResourceInspector.exists(CUSTOM_DATA)) {
            LOGGER.debug("The host still has the item stack NBT accessors, or lacks custom data");
            return;
        }
        registerRedirects(transformer);
        LOGGER.debug("Added support for the pre-1.20.5 item stack NBT accessors");
    }

    /** Unconditional registration for offline transforms and transform-shape tests. */
    public static void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(HELPER, generateHelper());
        for (Accessor accessor : ACCESSORS) {
            transformer.registerMethodRedirect(ITEM_STACK, accessor.name(), accessor.oldDesc(),
                    HELPER, accessor.name(), accessor.adapterDesc(), true);
        }
    }

    private static boolean hasMethod(ClassNode owner, String name, String descriptor) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return true;
        }
        return false;
    }

    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        emitOwnTag(cw);
        emitGetTag(cw);
        emitGetOrCreateTag(cw);
        emitSetTag(cw);
        emitHasTag(cw);
        emitEnchant(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code ownTag(stack, tag)}: stores a fresh copy of {@code tag} as the stack's custom data and
     * returns the stored, changeable tag. The copy is what keeps copied stacks independent.
     */
    private static void emitOwnTag(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "ownTag",
                "(L" + ITEM_STACK + ";L" + COMPOUND_TAG + ";)L" + COMPOUND_TAG + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, CUSTOM_DATA, CUSTOM_DATA_OF,
                "(L" + COMPOUND_TAG + ";)L" + CUSTOM_DATA + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        customDataType(mv);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, SET_COMPONENT,
                "(L" + COMPONENT_TYPE + ";Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CUSTOM_DATA, CUSTOM_DATA_LIVE_TAG,
                "()L" + COMPOUND_TAG + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code getTag(stack)}: the stack's own custom data tag, or null when it has none. */
    private static void emitGetTag(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                GET_TAG.name(), GET_TAG.adapterDesc(), null, null);
        mv.visitCode();
        loadCustomData(mv);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Label present = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, present);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(present);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CUSTOM_DATA, CUSTOM_DATA_LIVE_TAG,
                "()L" + COMPOUND_TAG + ";", false);
        callOwnTag(mv);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code getOrCreateTag(stack)}: as {@code getTag}, starting from an empty tag when absent. */
    private static void emitGetOrCreateTag(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                GET_OR_CREATE_TAG.name(), GET_OR_CREATE_TAG.adapterDesc(), null, null);
        mv.visitCode();
        loadCustomData(mv);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Label present = new Label();
        Label call = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, present);
        mv.visitTypeInsn(Opcodes.NEW, COMPOUND_TAG);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, COMPOUND_TAG, "<init>", "()V", false);
        mv.visitJumpInsn(Opcodes.GOTO, call);
        mv.visitLabel(present);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CUSTOM_DATA, CUSTOM_DATA_LIVE_TAG,
                "()L" + COMPOUND_TAG + ";", false);
        mv.visitLabel(call);
        callOwnTag(mv);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code setTag(stack, tag)}: replaces the custom data, or removes it for null. */
    private static void emitSetTag(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                SET_TAG.name(), SET_TAG.adapterDesc(), null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Label present = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, present);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        customDataType(mv);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, REMOVE_COMPONENT,
                "(L" + COMPONENT_TYPE + ";)Ljava/lang/Object;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(present);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        callOwnTag(mv);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code hasTag(stack)}: whether the stack has non-empty custom data. */
    private static void emitHasTag(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                HAS_TAG.name(), HAS_TAG.adapterDesc(), null, null);
        mv.visitCode();
        loadCustomData(mv);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        Label absent = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, absent);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CUSTOM_DATA, CUSTOM_DATA_IS_EMPTY, "()Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, absent);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(absent);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code enchant(stack, enchantment, level)}: fails with the reason, see {@link #ENCHANT}. */
    private static void emitEnchant(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                ENCHANT.name(), ENCHANT.adapterDesc(), null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/UnsupportedOperationException");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("ItemStack.enchant(Enchantment, int) was removed in Minecraft 1.21, where "
                + "enchantments are data pack entries reached through a registry holder. This "
                + "older mod's call cannot be translated; use a version of the mod made for this "
                + "Minecraft version to enchant items from code.");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>",
                "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Pushes {@code (CustomData) stack.get(DataComponents.CUSTOM_DATA)}, which may be null. */
    private static void loadCustomData(MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        customDataType(mv);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM_STACK, GET_COMPONENT,
                "(L" + COMPONENT_TYPE + ";)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, CUSTOM_DATA);
    }

    private static void customDataType(MethodVisitor mv) {
        mv.visitFieldInsn(Opcodes.GETSTATIC, DATA_COMPONENTS, CUSTOM_DATA_FIELD, "L" + COMPONENT_TYPE + ";");
    }

    private static void callOwnTag(MethodVisitor mv) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "ownTag",
                "(L" + ITEM_STACK + ";L" + COMPOUND_TAG + ";)L" + COMPOUND_TAG + ";", false);
    }
}
