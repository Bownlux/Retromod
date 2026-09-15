/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Keeps a pre-1.19.3 creative tab working on a Forge host.
 *
 * <p>Up to 1.19.2 a mod made a tab by subclassing: {@code new CreativeModeTab("name") { ItemStack
 * makeIcon() { ... } }}. 1.19.3 replaced that with a builder and deleted the {@code String}
 * constructor, so the subclass still loads and then dies the moment it runs its {@code super(...)}
 * call. That is issue #264, and it is the shape every MCreator mod of that era generates, so it is
 * not one mod's problem.
 *
 * <p>The inheritance edge moves onto a generated subclass that bridges the two styles. Its
 * {@code (String)} constructor builds a tab through {@code CreativeModeTab.builder()} and titles it
 * with the name the mod passed, then calls the {@code Builder} constructor. That constructor is a
 * Forge patch: vanilla only has a package-private one, which nothing outside the package could call.
 * This shim is Forge only for exactly that reason.
 *
 * <p>The generated base also declares {@code makeIcon}, so the mod's own override still overrides
 * something, and {@code getIconItem} is pointed at it. Without that the override would be dead code
 * and every bridged tab would show an empty icon.
 *
 * <p>What does not come back: the tab's contents. 1.19.2 filled a tab by tagging each item with it,
 * and 1.19.3 fills it from a {@code displayItems} callback the old mod never wrote. The tab appears
 * with its name and icon and starts empty, which is visibly wrong but loads, where before the mod
 * did not load at all.
 */
public final class CreativeModeTabBridge {

    private static final String GENERATED = "com/retromod/generated/LegacyCreativeModeTab";
    private static final String TAB = "net/minecraft/world/item/CreativeModeTab";
    private static final String BUILDER = "net/minecraft/world/item/CreativeModeTab$Builder";
    private static final String COMPONENT = "net/minecraft/network/chat/Component";
    private static final String MUTABLE = "net/minecraft/network/chat/MutableComponent";
    private static final String STACK = "net/minecraft/world/item/ItemStack";

    private CreativeModeTabBridge() {}

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(GENERATED, generate());
        transformer.registerSuperclassRebase(TAB, GENERATED);
    }

    /** {@code class LegacyCreativeModeTab extends CreativeModeTab} with the old entry points. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, GENERATED, null, TAB, null);

        emitStringConstructor(cw);
        emitMakeIcon(cw);
        emitGetIconItem(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code LegacyCreativeModeTab(String name)}, forwarding to the builder constructor.
     *
     * <p>The builder is assembled before the {@code super(...)} call. That is legal: the verifier
     * only forbids using {@code this} before the superclass constructor runs, and nothing here
     * touches it until the arguments are on the stack.
     */
    private static void emitStringConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TAB, "builder", "()L" + BUILDER + ";", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, COMPONENT, "literal",
                "(Ljava/lang/String;)L" + MUTABLE + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "title",
                "(L" + COMPONENT + ";)L" + BUILDER + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TAB, "<init>", "(L" + BUILDER + ";)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The old hook, declared so a mod's {@code @Override} still overrides something. */
    private static void emitMakeIcon(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "makeIcon", "()L" + STACK + ";",
                null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, STACK, "EMPTY", "L" + STACK + ";");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The current hook, answered by whatever {@code makeIcon} the subclass supplies. */
    private static void emitGetIconItem(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "getIconItem", "()L" + STACK + ";",
                null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, GENERATED, "makeIcon", "()L" + STACK + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
