/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Carries a block or item's registry id from the registration call into its {@code Properties}.
 *
 * <p>26.x made {@code BlockBehaviour.<init>} call {@code requireNonNull(properties.id)}, so a block
 * cannot be constructed until its id is stamped. NeoForge mods are covered by
 * {@link com.retromod.shim.forge.RegistryIdBridgeSynthetic}, which hangs the id off the deferred
 * register. Fabric has no deferred register: a mod calls
 * {@code Registry.register(BuiltInRegistries.BLOCK, id, block)} and builds the block in the argument
 * list. Every Fabric mod that adds a block died at its own class init with
 * {@code NullPointerException: Block id not set}.
 *
 * <p>The id is still reachable, because the arguments evaluate left to right: the id is on the stack
 * before the block expression runs. {@link FabricRegistryIdAdapter} stamps it into the thread local
 * here, and the {@code Properties} factories below read it back while the block is being built. The
 * id is popped as soon as the registration returns, so nothing leaks into the next one.
 *
 * <p>This class references only Minecraft and itself, because it is embedded into a Fabric mod where
 * no loader class of any other family exists.
 */
public final class FabricRegistryIdSynthetic {

    private FabricRegistryIdSynthetic() {}

    public static final String INTERNAL = "com/retromod/shim/fabric/embedded/FabricRegistryIdBridge";

    private static final String THREADLOCAL = "java/lang/ThreadLocal";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String RK = "net/minecraft/resources/ResourceKey";
    private static final String REGISTRIES = "net/minecraft/core/registries/Registries";
    private static final String BB = "net/minecraft/world/level/block/state/BlockBehaviour";
    private static final String BBP = BB + "$Properties";
    private static final String IP = "net/minecraft/world/item/Item$Properties";

    private static final String L_ID = "L" + IDENTIFIER + ";";
    private static final String L_RK = "L" + RK + ";";
    private static final String L_BBP = "L" + BBP + ";";
    private static final String L_IP = "L" + IP + ";";

    /** Descriptor of the created class. */
    public static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, INTERNAL, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "CURRENT", "L" + THREADLOCAL + ";",
                null, null).visitEnd();

        emitClinit(cw);
        emitPush(cw);
        emitPop(cw);
        emitStamp(cw, "stampBlock", BBP, L_BBP, "BLOCK");
        emitStamp(cw, "stampItem", IP, L_IP, "ITEM");
        emitBlockOf(cw);
        emitBlockOfFullCopy(cw);
        emitItemProps(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static { CURRENT = new ThreadLocal(); }} */
    private static void emitClinit(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, THREADLOCAL);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, THREADLOCAL, "<init>", "()V", false);
        mv.visitFieldInsn(PUTSTATIC, INTERNAL, "CURRENT", "L" + THREADLOCAL + ";");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static void push(Identifier id) { CURRENT.set(id); }} */
    private static void emitPush(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "push", "(" + L_ID + ")V",
                null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, INTERNAL, "CURRENT", "L" + THREADLOCAL + ";");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "set", "(Ljava/lang/Object;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static void pop() { CURRENT.remove(); }} */
    private static void emitPop(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "pop", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, INTERNAL, "CURRENT", "L" + THREADLOCAL + ";");
        mv.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "remove", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * {@code static P stampX(P p) { Object id = CURRENT.get(); if (id == null) return p;
     * return p.setId(ResourceKey.create(Registries.FIELD, (Identifier) id)); }}
     *
     * <p>A missing id returns the Properties untouched, so a call outside a registration behaves
     * exactly as it did before rather than inventing a key.
     */
    private static void emitStamp(ClassWriter cw, String name, String owner, String desc,
                                  String registriesField) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, "(" + desc + ")" + desc,
                null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, INTERNAL, "CURRENT", "L" + THREADLOCAL + ";");
        mv.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "get", "()Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, 1);

        Label present = new Label();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitJumpInsn(IFNONNULL, present);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);

        mv.visitLabel(present);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, REGISTRIES, registriesField, L_RK);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, IDENTIFIER);
        mv.visitMethodInsn(INVOKESTATIC, RK, "create", "(" + L_RK + L_ID + ")" + L_RK, false);
        mv.visitMethodInsn(INVOKEVIRTUAL, owner, "setId", "(" + L_RK + ")" + desc, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static Properties blockOf() { return stampBlock(Properties.of()); }} */
    private static void emitBlockOf(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "blockOf", "()" + L_BBP,
                null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, BBP, "of", "()" + L_BBP, false);
        mv.visitMethodInsn(INVOKESTATIC, INTERNAL, "stampBlock", "(" + L_BBP + ")" + L_BBP, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * {@code static Item.Properties itemProps() { return stampItem(new Item.Properties()); }}
     *
     * <p>Items need the same treatment as blocks and fail the same way. A mod that adds a block
     * almost always registers a BlockItem beside it, so fixing only the block moves the failure one
     * line down to {@code Item id not set}.
     */
    private static void emitItemProps(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "itemProps", "()" + L_IP,
                null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, IP);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, IP, "<init>", "()V", false);
        mv.visitMethodInsn(INVOKESTATIC, INTERNAL, "stampItem", "(" + L_IP + ")" + L_IP, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static Properties blockOfFullCopy(BlockBehaviour b) { ... }} */
    private static void emitBlockOfFullCopy(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "blockOfFullCopy",
                "(L" + BB + ";)" + L_BBP, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, BBP, "ofFullCopy", "(L" + BB + ";)" + L_BBP, false);
        mv.visitMethodInsn(INVOKESTATIC, INTERNAL, "stampBlock", "(" + L_BBP + ")" + L_BBP, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
