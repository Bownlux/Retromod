/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Forge 1.20.1 menu registration and opening on NeoForge.
 *
 * <p>A mod builds its menu type with {@code IForgeMenuType.create((id, inv, buf) -> ...)} and opens
 * it with {@code NetworkHooks.openScreen}. NeoForge renamed both, and its {@code IContainerFactory}
 * reads a {@code RegistryFriendlyByteBuf} where Forge's read a {@code FriendlyByteBuf}. Pointing
 * the old interface at the new one would leave every mod lambda implementing a method NeoForge
 * never calls, so the mod keeps a Forge-shaped interface and an adapter forwards to it.
 *
 * <p>The generated classes are embedded per mod, like the other Forge to NeoForge synthetics.
 */
final class ForgeMenuBridge {

    static final String FACTORY = "com/retromod/shim/forge/embedded/ForgeContainerFactory";
    static final String ADAPTER = "com/retromod/shim/forge/embedded/ForgeContainerFactoryAdapter";
    static final String MENUS = "com/retromod/shim/forge/embedded/ForgeMenus";

    private static final String NEO_FACTORY = "net/neoforged/neoforge/network/IContainerFactory";
    private static final String MENU_TYPE_EXTENSION =
            "net/neoforged/neoforge/common/extensions/IMenuTypeExtension";
    private static final String L_MENU_TYPE = "Lnet/minecraft/world/inventory/MenuType;";
    private static final String L_MENU = "Lnet/minecraft/world/inventory/AbstractContainerMenu;";
    private static final String L_INVENTORY = "Lnet/minecraft/world/entity/player/Inventory;";
    private static final String L_PLAYER = "Lnet/minecraft/server/level/ServerPlayer;";
    private static final String SERVER_PLAYER = "net/minecraft/server/level/ServerPlayer";
    private static final String L_PROVIDER = "Lnet/minecraft/world/MenuProvider;";
    private static final String L_POS = "Lnet/minecraft/core/BlockPos;";
    private static final String L_CONSUMER = "Ljava/util/function/Consumer;";
    private static final String FORGE_CREATE = "(I" + L_INVENTORY + "Lnet/minecraft/network/FriendlyByteBuf;)" + L_MENU;
    private static final String NEO_CREATE = "(I" + L_INVENTORY + "Lnet/minecraft/network/RegistryFriendlyByteBuf;)" + L_MENU;

    private ForgeMenuBridge() {}

    static void register(RetromodTransformer t) {
        t.registerSyntheticClass(FACTORY, generateFactory());
        t.registerSyntheticClass(ADAPTER, generateAdapter());
        t.registerSyntheticClass(MENUS, generateMenus());

        t.registerClassRedirect("net/minecraftforge/network/IContainerFactory", FACTORY);

        // Keyed on both spellings of the factory type, since the class move can apply first.
        for (String factory : new String[]{"net/minecraftforge/network/IContainerFactory", FACTORY}) {
            t.registerMethodRedirect(
                "net/minecraftforge/common/extensions/IForgeMenuType", "create",
                "(L" + factory + ";)" + L_MENU_TYPE,
                MENUS, "create", "(L" + FACTORY + ";)" + L_MENU_TYPE);
        }

        // NetworkHooks returned nothing; the NeoForge calls return an OptionalInt that is dropped.
        String hooks = "net/minecraftforge/network/NetworkHooks";
        t.registerMethodRedirect(hooks, "openScreen", "(" + L_PLAYER + L_PROVIDER + ")V",
                MENUS, "openScreen", "(" + L_PLAYER + L_PROVIDER + ")V");
        t.registerMethodRedirect(hooks, "openScreen", "(" + L_PLAYER + L_PROVIDER + L_POS + ")V",
                MENUS, "openScreen", "(" + L_PLAYER + L_PROVIDER + L_POS + ")V");
        t.registerMethodRedirect(hooks, "openScreen", "(" + L_PLAYER + L_PROVIDER + L_CONSUMER + ")V",
                MENUS, "openScreen", "(" + L_PLAYER + L_PROVIDER + L_CONSUMER + ")V");
    }

    /** The Forge-shaped factory the mod's lambdas implement. */
    static byte[] generateFactory() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, FACTORY, null, "java/lang/Object", null);
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "create", FORGE_CREATE, null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** NeoForge's factory, forwarding each menu to the mod's Forge-shaped one. */
    static byte[] generateAdapter() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, ADAPTER, null, "java/lang/Object", new String[]{NEO_FACTORY});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "factory", "L" + FACTORY + ";", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(L" + FACTORY + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, ADAPTER, "factory", "L" + FACTORY + ";");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor create = cw.visitMethod(ACC_PUBLIC, "create", NEO_CREATE, null, null);
        create.visitCode();
        create.visitVarInsn(ALOAD, 0);
        create.visitFieldInsn(GETFIELD, ADAPTER, "factory", "L" + FACTORY + ";");
        create.visitVarInsn(ILOAD, 1);
        create.visitVarInsn(ALOAD, 2);
        create.visitVarInsn(ALOAD, 3);
        create.visitMethodInsn(INVOKEINTERFACE, FACTORY, "create", FORGE_CREATE, true);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Static stand-ins for {@code IForgeMenuType.create} and {@code NetworkHooks.openScreen}. */
    static byte[] generateMenus() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, MENUS, null, "java/lang/Object", null);

        MethodVisitor create = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create",
                "(L" + FACTORY + ";)" + L_MENU_TYPE, null, null);
        create.visitCode();
        create.visitTypeInsn(NEW, ADAPTER);
        create.visitInsn(DUP);
        create.visitVarInsn(ALOAD, 0);
        create.visitMethodInsn(INVOKESPECIAL, ADAPTER, "<init>", "(L" + FACTORY + ";)V", false);
        create.visitMethodInsn(INVOKESTATIC, MENU_TYPE_EXTENSION, "create",
                "(L" + NEO_FACTORY + ";)" + L_MENU_TYPE, true);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();

        emitOpenScreen(cw, "", "(" + L_PROVIDER + ")Ljava/util/OptionalInt;");
        emitOpenScreen(cw, L_POS, "(" + L_PROVIDER + L_POS + ")Ljava/util/OptionalInt;");
        emitOpenScreen(cw, L_CONSUMER, "(" + L_PROVIDER + L_CONSUMER + ")Ljava/util/OptionalInt;");

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code openScreen(player, provider[, extra])}: player.openMenu(provider[, extra]), result dropped. */
    private static void emitOpenScreen(ClassWriter cw, String extra, String openMenuDesc) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "openScreen",
                "(" + L_PLAYER + L_PROVIDER + extra + ")V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        if (!extra.isEmpty()) m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKEVIRTUAL, SERVER_PLAYER, "openMenu", openMenuDesc, false);
        m.visitInsn(POP);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
