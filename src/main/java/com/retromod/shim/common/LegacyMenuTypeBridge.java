/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/** Rebuilds the one-argument MenuType constructor that 1.19.3 replaced with one that also takes feature flags. */
public final class LegacyMenuTypeBridge {

    /** Not LegacyMenuTypes: the Fabric 26.x extended menu bridge already uses that name. */
    static final String HELPER = "com/retromod/generated/LegacyMenuTypeConstructors";

    /** The class and member names one host runs with. */
    public record Names(String menuType, String supplier, String flags, String flagSet, String defaultFlags) {
        String oldCtor() {
            return "(L" + supplier + ";)V";
        }

        String newCtor() {
            return "(L" + supplier + ";L" + flagSet + ";)V";
        }
    }

    public static final Names MOJANG = new Names("net/minecraft/world/inventory/MenuType",
            "net/minecraft/world/inventory/MenuType$MenuSupplier", "net/minecraft/world/flag/FeatureFlags",
            "net/minecraft/world/flag/FeatureFlagSet", "DEFAULT_FLAGS");
    /** Fabric hosts before 26.1 run intermediary names. */
    public static final Names INTERMEDIARY = new Names("net/minecraft/class_3917", "net/minecraft/class_3917$class_3918",
            "net/minecraft/class_7701", "net/minecraft/class_7699", "field_40183");

    private LegacyMenuTypeBridge() {}

    /** Registers the bridge; {@code forgeFactory} and {@code forgeMenus} name NeoForge's Forge factory bridge, or are null elsewhere. */
    public static void register(RetromodTransformer t, Names names, String forgeFactory, String forgeMenus) {
        // The shims register in version order, so a plain helper never replaces the NeoForge one.
        if (forgeFactory == null && t.getSyntheticClasses().containsKey(HELPER)) return;
        t.registerSyntheticClass(HELPER, generateHelper(names, forgeFactory, forgeMenus));
        // new MenuType(supplier) becomes a call that adds the flags every mod menu gets now.
        t.registerConstructorRedirect(names.menuType(), names.oldCtor(), HELPER, "create",
                "(Ljava/lang/Object;)L" + names.menuType() + ";");
        // A mod's own MenuType subclass passes the same flags to its super call.
        t.registerSuperConstructorRedirect(names.menuType(), names.oldCtor(), names.newCtor(),
                names.flags(), names.defaultFlags(), "L" + names.flagSet() + ";");
    }

    /** {@code create(Object)}: the menu type a 1.19.2 mod's {@code new MenuType(supplier)} built. */
    static byte[] generateHelper(Names names, String forgeFactory, String forgeMenus) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        String menuType = "L" + names.menuType() + ";";
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create", "(Ljava/lang/Object;)" + menuType, null, null);
        m.visitCode();
        if (forgeFactory != null) {
            // On NeoForge a Forge factory is built through NeoForge's own menu type, which reads its extra data.
            Label supplier = new Label();
            m.visitVarInsn(ALOAD, 0);
            m.visitTypeInsn(INSTANCEOF, forgeFactory);
            m.visitJumpInsn(IFEQ, supplier);
            m.visitVarInsn(ALOAD, 0);
            m.visitTypeInsn(CHECKCAST, forgeFactory);
            m.visitMethodInsn(INVOKESTATIC, forgeMenus, "create", "(L" + forgeFactory + ";)" + menuType, false);
            m.visitInsn(ARETURN);
            m.visitLabel(supplier);
        }
        // DEFAULT_FLAGS is what Forge's own menu factory has passed since 1.19.3.
        Label start = new Label();
        Label end = new Label();
        Label privateOnHost = new Label();
        m.visitTryCatchBlock(start, end, privateOnHost, "java/lang/IllegalAccessError");
        m.visitLabel(start);
        m.visitTypeInsn(NEW, names.menuType());
        m.visitInsn(DUP);
        m.visitVarInsn(ALOAD, 0);
        m.visitTypeInsn(CHECKCAST, names.supplier());
        m.visitFieldInsn(GETSTATIC, names.flags(), names.defaultFlags(), "L" + names.flagSet() + ";");
        m.visitMethodInsn(INVOKESPECIAL, names.menuType(), "<init>", names.newCtor(), false);
        m.visitLabel(end);
        m.visitInsn(ARETURN);
        // Forge, NeoForge and Fabric API open the constructor; plain vanilla keeps it private, so reflection reaches it.
        m.visitLabel(privateOnHost);
        m.visitVarInsn(ASTORE, 1);
        m.visitLdcInsn(org.objectweb.asm.Type.getObjectType(names.menuType()));
        m.visitInsn(ICONST_2);
        m.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        m.visitInsn(DUP);
        m.visitInsn(ICONST_0);
        m.visitLdcInsn(org.objectweb.asm.Type.getObjectType(names.supplier()));
        m.visitInsn(AASTORE);
        m.visitInsn(DUP);
        m.visitInsn(ICONST_1);
        m.visitLdcInsn(org.objectweb.asm.Type.getObjectType(names.flagSet()));
        m.visitInsn(AASTORE);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredConstructor",
                "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;", false);
        m.visitInsn(DUP);
        m.visitInsn(ICONST_1);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Constructor", "setAccessible", "(Z)V", false);
        m.visitInsn(ICONST_2);
        m.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        m.visitInsn(DUP);
        m.visitInsn(ICONST_0);
        m.visitVarInsn(ALOAD, 0);
        m.visitInsn(AASTORE);
        m.visitInsn(DUP);
        m.visitInsn(ICONST_1);
        m.visitFieldInsn(GETSTATIC, names.flags(), names.defaultFlags(), "L" + names.flagSet() + ";");
        m.visitInsn(AASTORE);
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Constructor", "newInstance",
                "([Ljava/lang/Object;)Ljava/lang/Object;", false);
        m.visitTypeInsn(CHECKCAST, names.menuType());
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
