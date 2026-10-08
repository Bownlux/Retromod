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
 * <p>1.19.2 filled a tab by tagging each item with it, and listed every constructed tab. 1.19.3
 * fills a tab from a {@code displayItems} callback and shows only registered tabs. The bridged
 * {@code Item.Properties.tab(tab)} call and {@link LegacyCreativeTabClaimAdapter} record which item
 * asked for which tab in {@code LegacyCreativeTabContents}, the generated
 * {@code LegacyCreativeModeTabContents} callback lists those items, and the constructor queues the
 * tab for the creative tab registry under the mod's namespace and its old label. A mod that adds
 * items to a vanilla tab such as {@code TAB_MISC} still lists them nowhere, since those constants
 * are gone.
 */
public final class CreativeModeTabBridge {

    static final String GENERATED = "com/retromod/generated/LegacyCreativeModeTab";
    private static final String TAB = "net/minecraft/world/item/CreativeModeTab";
    private static final String BUILDER = "net/minecraft/world/item/CreativeModeTab$Builder";
    private static final String COMPONENT = "net/minecraft/network/chat/Component";
    private static final String MUTABLE = "net/minecraft/network/chat/MutableComponent";
    private static final String STACK = "net/minecraft/world/item/ItemStack";
    private static final String ITEM_PROPERTIES = "net/minecraft/world/item/Item$Properties";
    private static final String GENERATOR = "net/minecraft/world/item/CreativeModeTab$DisplayItemsGenerator";
    private static final String PARAMETERS = "net/minecraft/world/item/CreativeModeTab$ItemDisplayParameters";
    private static final String OUTPUT = "net/minecraft/world/item/CreativeModeTab$Output";
    private static final String ITEM_LIKE = "net/minecraft/world/level/ItemLike";
    public static final String CONTENTS = "com/retromod/generated/LegacyCreativeModeTabContents";
    static final String TAB_CONTENTS = "com/retromod/shim/forge/embedded/LegacyCreativeTabContents";
    private static final String OBJ = "Ljava/lang/Object;";

    private CreativeModeTabBridge() {}

    public static void register(RetromodTransformer transformer) {
        com.retromod.core.SyntheticEmbedder.registerClassResource(
                transformer, TAB_CONTENTS, CreativeModeTabBridge.class);
        transformer.registerSyntheticClass(CONTENTS, generateContents());
        transformer.registerSyntheticClass(GENERATED, generate());
        transformer.registerSuperclassRebase(TAB, GENERATED);
        // Item.Properties.tab(tab) went with the String constructor. The call keeps returning the
        // properties and remembers the tab, so the item constructed from them is listed there.
        transformer.registerMethodRedirect(ITEM_PROPERTIES, "tab", "(L" + TAB + ";)L" + ITEM_PROPERTIES + ";",
                GENERATED, "tab", "(L" + ITEM_PROPERTIES + ";L" + TAB + ";)L" + ITEM_PROPERTIES + ";", true);
    }

    /** {@code static Item.Properties tab(Item.Properties properties, CreativeModeTab tab)}: records the tab. */
    private static void emitPropertiesTab(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tab",
                "(L" + ITEM_PROPERTIES + ";L" + TAB + ";)L" + ITEM_PROPERTIES + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TAB_CONTENTS, "tag", "(" + OBJ + OBJ + ")" + OBJ, false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ITEM_PROPERTIES);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    /**
     * {@code class LegacyCreativeModeTabContents implements CreativeModeTab.DisplayItemsGenerator}.
     *
     * <p>The callback has to exist before the tab's {@code super(...)} call, when the tab itself
     * cannot be passed yet, so it starts empty and the constructor fills in {@code tab} afterward.
     */
    static byte[] generateContents() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, CONTENTS, null,
                "java/lang/Object", new String[]{GENERATOR});
        cw.visitField(Opcodes.ACC_PUBLIC, "tab", OBJ, null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // for (Object item : LegacyCreativeTabContents.contents(this.tab)) output.accept((ItemLike) item);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "accept",
                "(L" + PARAMETERS + ";L" + OUTPUT + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, CONTENTS, "tab", OBJ);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TAB_CONTENTS, "contents", "(" + OBJ + ")[" + OBJ, false);
        mv.visitVarInsn(Opcodes.ASTORE, 3);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, 4);
        org.objectweb.asm.Label loop = new org.objectweb.asm.Label();
        org.objectweb.asm.Label done = new org.objectweb.asm.Label();
        mv.visitLabel(loop);
        mv.visitFrame(Opcodes.F_FULL, 5, new Object[]{CONTENTS, PARAMETERS, OUTPUT, "[" + OBJ, Opcodes.INTEGER},
                0, new Object[0]);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitInsn(Opcodes.ARRAYLENGTH);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, done);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitVarInsn(Opcodes.ILOAD, 4);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitTypeInsn(Opcodes.CHECKCAST, ITEM_LIKE);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, OUTPUT, "accept", "(L" + ITEM_LIKE + ";)V", true);
        mv.visitIincInsn(4, 1);
        mv.visitJumpInsn(Opcodes.GOTO, loop);
        mv.visitLabel(done);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class LegacyCreativeModeTab extends CreativeModeTab} with the old entry points. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, GENERATED, null, TAB, null);

        emitStringConstructor(cw);
        emitMakeIcon(cw);
        emitGetIconItem(cw);
        emitPropertiesTab(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code LegacyCreativeModeTab(String label)}, forwarding to the builder constructor.
     *
     * <p>The builder is assembled before the {@code super(...)} call. That is legal: the verifier
     * only forbids using {@code this} before the superclass constructor runs, and nothing here
     * touches it until the arguments are on the stack. The title is {@code itemGroup.<label>},
     * the translation key the old constructor used, so the mod's language file still names it.
     */
    private static void emitStringConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V",
                null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, CONTENTS);
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, CONTENTS, "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ASTORE, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TAB, "builder", "()L" + BUILDER + ";", false);
        mv.visitLdcInsn("itemGroup.");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        // Component is an interface, so the static call needs an InterfaceMethodref (#290).
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, COMPONENT, "translatable",
                "(Ljava/lang/String;)L" + MUTABLE + ";", true);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "title",
                "(L" + COMPONENT + ";)L" + BUILDER + ";", false);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "displayItems",
                "(L" + GENERATOR + ";)L" + BUILDER + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TAB, "<init>", "(L" + BUILDER + ";)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, CONTENTS, "tab", OBJ);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, TAB_CONTENTS, "created",
                "(" + OBJ + "Ljava/lang/String;)V", false);
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
