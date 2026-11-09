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

/** Rebuilds the {@code InteractionResultHolder} calls that 1.21.2 folded into {@code InteractionResult}. */
public final class LegacyInteractionResultHolderBridge {

    /** The names one helper is written in: Mojang names, or a Fabric host's intermediary. */
    record Names(String helper, String holder, String result, String success, String pass, String fail,
            String stack, String successField, String consumeField, String serverField, String passField,
            String failField, String withItem, String heldItem, String successName, String consumeName,
            String passName, String failName, String sidedName, String getResultName, String getObjectName,
            String enumSidedName) {
        String res() {
            return "L" + result + ";";
        }
    }

    static final Names MOJANG = new Names("com/retromod/generated/LegacyInteractionResultHolder",
            "net/minecraft/world/InteractionResultHolder", "net/minecraft/world/InteractionResult",
            "net/minecraft/world/InteractionResult$Success", "net/minecraft/world/InteractionResult$Pass",
            "net/minecraft/world/InteractionResult$Fail", "net/minecraft/world/item/ItemStack",
            "SUCCESS", "CONSUME", "SUCCESS_SERVER", "PASS", "FAIL", "heldItemTransformedTo", "heldItemTransformedTo",
            "success", "consume", "pass", "fail", "sidedSuccess", "getResult", "getObject", "sidedSuccess");

    // A Fabric 1.21.2 to 1.21.11 host keeps these ids. Checked against the 1.21.1, 1.21.2, 1.21.5
    // and 1.21.11 tables.
    static final Names INTERMEDIARY = new Names("com/retromod/generated/LegacyIntermediaryInteractionResultHolder",
            "net/minecraft/class_1271", "net/minecraft/class_1269", "net/minecraft/class_1269$class_9860",
            "net/minecraft/class_1269$class_9859", "net/minecraft/class_1269$class_9857", "net/minecraft/class_1799",
            "field_5812", "field_21466", "field_52422", "field_5811", "field_5814", "method_61393", "method_61396",
            "method_22427", "method_22428", "method_22430", "method_22431", "method_29237", "method_5467",
            "method_5466", "method_29236");

    /** The first version without {@code InteractionResultHolder}. */
    static final String REMOVED_IN = "1.21.2";

    private LegacyInteractionResultHolderBridge() {}

    /** Registers the bridge in Mojang names on a host that removed the holder. */
    public static void register(RetromodTransformer transformer) {
        if (hostRemovedHolder(MOJANG)) registerRedirects(transformer, MOJANG);
    }

    /** The Fabric registration, which also covers the old enum's {@code sidedSuccess(boolean)}. */
    public static void registerFabric(RetromodTransformer transformer) {
        Names names = RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION) ? MOJANG : INTERMEDIARY;
        if (!hostRemovedHolder(names)) return;
        registerRedirects(transformer, names);
        registerEnumSidedSuccess(transformer, names);
    }

    /** Whether the host's result type is the 1.21.2 interface, which means the holder is gone. */
    static boolean hostRemovedHolder(Names names) {
        ClassNode result = ClassResourceInspector.read(names.result());
        // Offline, where the host class cannot be read, the host version decides.
        if (result == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, REMOVED_IN) >= 0;
        }
        return (result.access & Opcodes.ACC_INTERFACE) != 0;
    }

    static void registerRedirects(RetromodTransformer transformer, Names names) {
        transformer.registerSyntheticClass(names.helper(), generateHelper(names));
        // The holder folded into the result, so a mod's Item.use override returns the new type.
        transformer.registerClassRedirect(names.holder(), names.result());
        String holder = "L" + names.holder() + ";";
        String object = "Ljava/lang/Object;";
        // The static factories keep the result and, for a success, the item it handed back.
        String[][] factories = {
                {names.successName(), "(" + object + ")" + holder, "success", "(" + object + ")" + names.res()},
                {names.consumeName(), "(" + object + ")" + holder, "consume", "(" + object + ")" + names.res()},
                {names.passName(), "(" + object + ")" + holder, "pass", "(" + object + ")" + names.res()},
                {names.failName(), "(" + object + ")" + holder, "fail", "(" + object + ")" + names.res()},
                {names.sidedName(), "(" + object + "Z)" + holder, "sidedSuccess", "(" + object + "Z)" + names.res()},
        };
        for (String[] factory : factories) {
            transformer.registerMethodRedirect(names.holder(), factory[0], factory[1],
                    names.helper(), factory[2], factory[3]);
        }
        // The getters take the holder, which is now the result itself, as their first argument.
        transformer.registerMethodRedirect(names.holder(), names.getResultName(), "()" + names.res(),
                names.helper(), "getResult", "(" + names.res() + ")" + names.res());
        transformer.registerMethodRedirect(names.holder(), names.getObjectName(), "()" + object,
                names.helper(), "getObject", "(" + names.res() + ")" + object);
        // new InteractionResultHolder(result, object), under either owner the class pass leaves.
        String ctor = "(" + names.res() + object + ")V";
        String factoryDesc = "(" + names.res() + object + ")" + names.res();
        for (String owner : new String[]{names.holder(), names.result()}) {
            transformer.registerConstructorRedirect(owner, ctor, names.helper(), "create", factoryDesc);
        }
    }

    /** The 1.21.1 enum's {@code sidedSuccess(isClientSide)}, which 1.21.2 dropped with the sided split. */
    static void registerEnumSidedSuccess(RetromodTransformer transformer, Names names) {
        transformer.registerMethodRedirect(names.result(), names.enumSidedName(), "(Z)" + names.res(),
                names.helper(), "sidedSuccess", "(Z)" + names.res());
    }

    static byte[] generateHelper(Names names) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String left, String right) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, names.helper(), null,
                "java/lang/Object", null);
        String object = "Ljava/lang/Object;";
        // success and consume keep the item a mod handed back, which the game then puts in the hand.
        emitWithItem(cw, names, "success", "(" + object + ")", names.successField());
        emitWithItem(cw, names, "consume", "(" + object + ")", names.consumeField());
        // sidedSuccess answered success on the client and consume on the server. Since 1.21.2 a
        // plain success swings only on the client, which is the same pair.
        emitWithItem(cw, names, "sidedSuccess", "(" + object + "Z)", names.successField());
        // pass and fail carry no item since 1.21.2, as vanilla's own pass and fail do.
        emitConstant(cw, names, "pass", "(" + object + ")", names.passField(), names.pass());
        emitConstant(cw, names, "fail", "(" + object + ")", names.failField(), names.fail());
        emitConstant(cw, names, "sidedSuccess", "(Z)", names.successField(), names.success());
        emitCreate(cw, names);
        emitGetResult(cw, names);
        emitGetObject(cw, names);
        emitHeld(cw, names);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code name(object...)}: the given success constant carrying {@code object} as its held item. */
    private static void emitWithItem(ClassWriter cw, Names names, String name, String params, String field) {
        MethodVisitor mv = begin(cw, name, params + names.res());
        mv.visitFieldInsn(Opcodes.GETSTATIC, names.result(), field, "L" + names.success() + ";");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, names.helper(), "held", heldDesc(names), false);
        end(mv, Opcodes.ARETURN);
    }

    /** {@code name(...)}: one constant, whatever the arguments. */
    private static void emitConstant(ClassWriter cw, Names names, String name, String params, String field,
            String type) {
        MethodVisitor mv = begin(cw, name, params + names.res());
        mv.visitFieldInsn(Opcodes.GETSTATIC, names.result(), field, "L" + type + ";");
        end(mv, Opcodes.ARETURN);
    }

    /** {@code create(result, object)}: the old constructor, which paired any result with an object. */
    private static void emitCreate(ClassWriter cw, Names names) {
        MethodVisitor mv = begin(cw, "create", "(" + names.res() + "Ljava/lang/Object;)" + names.res());
        Label notSuccess = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, names.success());
        mv.visitJumpInsn(Opcodes.IFEQ, notSuccess);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, names.success());
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, names.helper(), "held", heldDesc(names), false);
        mv.visitInsn(Opcodes.ARETURN);
        // Only a success can carry an item now, so any other result stands alone.
        mv.visitLabel(notSuccess);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        end(mv, Opcodes.ARETURN);
    }

    /** {@code getResult(result)}: the plain result, as the old getter returned it without the object. */
    private static void emitGetResult(ClassWriter cw, Names names) {
        MethodVisitor mv = begin(cw, "getResult", "(" + names.res() + ")" + names.res());
        Label plain = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, names.success());
        mv.visitJumpInsn(Opcodes.IFEQ, plain);
        // A success that carries an item is a new record, so a mod's == SUCCESS check needs the
        // constant back. Dropping the item leaves a record equal to one of the three constants.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, names.success());
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.success(), names.withItem(),
                "(L" + names.stack() + ";)L" + names.success() + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, 1);
        for (String field : new String[]{names.successField(), names.consumeField(), names.serverField()}) {
            Label next = new Label();
            mv.visitFieldInsn(Opcodes.GETSTATIC, names.result(), field, "L" + names.success() + ";");
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false);
            mv.visitJumpInsn(Opcodes.IFEQ, next);
            mv.visitFieldInsn(Opcodes.GETSTATIC, names.result(), field, "L" + names.success() + ";");
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(next);
        }
        mv.visitLabel(plain);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        end(mv, Opcodes.ARETURN);
    }

    /** {@code getObject(result)}: the item a success hands back, or null when the result carries none. */
    private static void emitGetObject(ClassWriter cw, Names names) {
        MethodVisitor mv = begin(cw, "getObject", "(" + names.res() + ")Ljava/lang/Object;");
        Label none = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, names.success());
        mv.visitJumpInsn(Opcodes.IFEQ, none);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, names.success());
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.success(), names.heldItem(), "()L" + names.stack() + ";",
                false);
        mv.visitInsn(Opcodes.ARETURN);
        // Null rather than an empty stack: handed back to success(), it leaves the hand alone.
        mv.visitLabel(none);
        mv.visitInsn(Opcodes.ACONST_NULL);
        end(mv, Opcodes.ARETURN);
    }

    /** {@code held(success, object)}: the success carrying {@code object} when it is an item stack. */
    private static void emitHeld(ClassWriter cw, Names names) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "held", heldDesc(names), null,
                null);
        mv.visitCode();
        Label plain = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, names.stack());
        mv.visitJumpInsn(Opcodes.IFEQ, plain);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitTypeInsn(Opcodes.CHECKCAST, names.stack());
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, names.success(), names.withItem(),
                "(L" + names.stack() + ";)L" + names.success() + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        // Any other object, such as an entity, has nowhere to go and the result stands alone.
        mv.visitLabel(plain);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        end(mv, Opcodes.ARETURN);
    }

    private static String heldDesc(Names names) {
        return "(L" + names.success() + ";Ljava/lang/Object;)" + names.res();
    }

    private static MethodVisitor begin(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        return mv;
    }

    private static void end(MethodVisitor mv, int returnOpcode) {
        mv.visitInsn(returnOpcode);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
