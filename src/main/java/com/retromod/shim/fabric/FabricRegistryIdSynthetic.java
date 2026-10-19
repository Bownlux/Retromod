/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/** Carries a block or item's registry id from the registration call into its {@code Properties}. */
public final class FabricRegistryIdSynthetic {

    private FabricRegistryIdSynthetic() {}

    // Embedded into Fabric mods, where no other loader family exists, so the bridge references
    // only Minecraft and itself.
    public static final String INTERNAL = "com/retromod/shim/fabric/embedded/FabricRegistryIdBridge";

    private static final String THREADLOCAL = "java/lang/ThreadLocal";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String RK = "net/minecraft/resources/ResourceKey";
    private static final String REGISTRIES = "net/minecraft/core/registries/Registries";
    private static final String BB = "net/minecraft/world/level/block/state/BlockBehaviour";
    private static final String BBP = BB + "$Properties";
    private static final String IP = "net/minecraft/world/item/Item$Properties";

    /** Namespace of the system properties this bridge uses. */
    public static final String SHARED_ID_PROPERTY_PREFIX = "retromod.registry-id.";

    private static final String L_ID = "L" + IDENTIFIER + ";";
    private static final String L_RK = "L" + RK + ";";
    private static final String L_BBP = "L" + BBP + ";";
    private static final String L_IP = "L" + IP + ";";

    /** Builds the bridge class each Fabric mod embeds. */
    public static byte[] generate() {
        // 26.x blocks need properties.id in BlockBehaviour.<init>. Fabric has no deferred
        // register: a mod builds the block inside Registry.register(BLOCK, id, block), so every
        // Fabric mod that adds a block died at class init with "Block id not set". NeoForge mods
        // use RegistryIdBridgeSynthetic, which hangs the id off the deferred register.
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, INTERNAL, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "CURRENT", "L" + THREADLOCAL + ";",
                null, null).visitEnd();

        emitClinit(cw);
        // Arguments evaluate left to right, so the id is on the stack before the block is built.
        // FabricRegistryIdAdapter pushes it, the Properties factories read it back, and pop clears
        // it as soon as the registration returns, so nothing leaks into the next one.
        emitPush(cw);
        emitPop(cw);
        // enter and leave wrap the statement that fills a registered static field; isolate and
        // restore set the current id aside and put it back.
        emitEnter(cw);
        emitLeave(cw);
        com.retromod.shim.common.StaticFieldIdStamper.emitIsolation(cw, INTERNAL);
        emitStamp(cw, "stampBlock", BBP, L_BBP, "BLOCK");
        emitStamp(cw, "stampItem", IP, L_IP, "ITEM");
        emitBlockOf(cw);
        emitBlockOfFullCopy(cw);
        emitItemProps(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * System property key that holds the one id slot every embedded copy shares.
     *
     * <p>Each mod embeds its own relocated copy of this bridge, so a per-class field would give each
     * mod a private slot. A library that registers on behalf of a content mod (NexusLib's
     * {@code registerBlock(name, Supplier)} building Bountiful Fares' blocks) pushes the id into the
     * library's copy while the block constructor runs in the content mod and reads its own copy,
     * which is empty (#249). The JDK's system properties are the one map both copies can reach
     * without a shared class.
     */
    static final String SHARED_SLOT_KEY = SHARED_ID_PROPERTY_PREFIX + "slot";

    /**
     * {@code static { Properties p = System.getProperties(); p.putIfAbsent(KEY, new ThreadLocal());
     * Object slot = p.get(KEY); CURRENT = slot instanceof ThreadLocal ? slot : new ThreadLocal(); }}
     *
     * <p>A non-ThreadLocal value under the key falls back to a private slot, which is the old
     * per-mod behavior, rather than failing the class init of every block the mod registers.
     */
    private static void emitClinit(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "getProperties",
                "()Ljava/util/Properties;", false);
        mv.visitVarInsn(ASTORE, 0);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(SHARED_SLOT_KEY);
        mv.visitTypeInsn(NEW, THREADLOCAL);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, THREADLOCAL, "<init>", "()V", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/Properties", "putIfAbsent",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(SHARED_SLOT_KEY);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/Properties", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, 1);

        Label shared = new Label();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(INSTANCEOF, THREADLOCAL);
        mv.visitJumpInsn(IFNE, shared);
        mv.visitTypeInsn(NEW, THREADLOCAL);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, THREADLOCAL, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 1);

        mv.visitLabel(shared);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, THREADLOCAL);
        mv.visitFieldInsn(PUTSTATIC, INTERNAL, "CURRENT", "L" + THREADLOCAL + ";");
        // SAVED holds the ids isolate sets aside.
        com.retromod.shim.common.StaticFieldIdStamper.emitSavedInit(mv, INTERNAL);
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

    /** {@code static Object enter(Identifier id)}: sets the current id and returns the one it replaced. */
    private static void emitEnter(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "enter",
                com.retromod.shim.common.StaticFieldIdStamper.ENTER_DESC, null, null);
        mv.visitCode();
        // The wrapped statement can run another class's static initializer, which stamps its own
        // fields, so the replaced id goes back to leave to restore.
        mv.visitFieldInsn(GETSTATIC, INTERNAL, "CURRENT", "L" + THREADLOCAL + ";");
        mv.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "get", "()Ljava/lang/Object;", false);
        mv.visitFieldInsn(GETSTATIC, INTERNAL, "CURRENT", "L" + THREADLOCAL + ";");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "set", "(Ljava/lang/Object;)V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static void leave(Object previous)}: restores the id enter replaced, or clears it. */
    private static void emitLeave(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "leave",
                com.retromod.shim.common.StaticFieldIdStamper.LEAVE_DESC, null, null);
        mv.visitCode();
        Label restore = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitJumpInsn(IFNONNULL, restore);
        mv.visitFieldInsn(GETSTATIC, INTERNAL, "CURRENT", "L" + THREADLOCAL + ";");
        mv.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "remove", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitLabel(restore);
        mv.visitFieldInsn(GETSTATIC, INTERNAL, "CURRENT", "L" + THREADLOCAL + ";");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, THREADLOCAL, "set", "(Ljava/lang/Object;)V", false);
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
