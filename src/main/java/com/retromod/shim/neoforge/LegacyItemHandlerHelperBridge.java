/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/** Keeps {@code ItemHandlerHelper.giveItemToPlayer} working where the loader removed the class. */
public final class LegacyItemHandlerHelperBridge {

    static final String HELPER = "com/retromod/generated/LegacyItemHandlerHelper";
    static final String FORGE_HELPER = "net/minecraftforge/items/ItemHandlerHelper";
    static final String NEOFORGE_HELPER = "net/neoforged/neoforge/items/ItemHandlerHelper";

    static final String PLAYER = "net/minecraft/world/entity/player/Player";
    static final String INVENTORY = "net/minecraft/world/entity/player/Inventory";
    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String ITEM_ENTITY = "net/minecraft/world/entity/item/ItemEntity";
    static final String LEVEL = "net/minecraft/world/level/Level";
    static final String VEC3 = "net/minecraft/world/phys/Vec3";

    private static final String L_STACK = "L" + ITEM_STACK + ";";
    private static final String L_LEVEL = "L" + LEVEL + ";";
    private static final String L_VEC3 = "L" + VEC3 + ";";

    static final String GIVE = "(L" + PLAYER + ";" + L_STACK + ")V";
    static final String GIVE_TO_SLOT = "(L" + PLAYER + ";" + L_STACK + "I)V";

    /** Two seconds, the pickup delay the old helper gave the dropped rest. */
    private static final int PICKUP_DELAY = 40;

    private LegacyItemHandlerHelperBridge() {}

    /** Registers the bridge for each {@code ItemHandlerHelper} the host lacks. */
    public static void register(RetromodTransformer transformer) {
        List<String> owners = removedOwners();
        if (!owners.isEmpty()) registerRedirects(transformer, owners);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, List<String> owners) {
        transformer.registerSyntheticClass(HELPER, generateHelper());
        // Mods call giveItemToPlayer to hand the player an item, such as an empty jar or bowl after
        // eating, and without the class it fails with NoClassDefFoundError.
        for (String owner : owners) {
            transformer.registerMethodRedirect(owner, "giveItemToPlayer", GIVE, HELPER, "giveItemToPlayer", GIVE);
            transformer.registerMethodRedirect(owner, "giveItemToPlayer", GIVE_TO_SLOT,
                    HELPER, "giveItemToPlayer", GIVE_TO_SLOT);
        }
    }

    /** The {@code ItemHandlerHelper} classes mods may name that the host lacks. */
    static List<String> removedOwners() {
        List<String> owners = new ArrayList<>();
        // Offline, only a NeoForge 26.3 target is known to have lost it, with the rest of its item
        // handler API. Forge's class of the same name, renamed to NeoForge's, fails the same way.
        if (ClassResourceInspector.read(PLAYER) == null) {
            if (McReflect.isNeoForge()
                    && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0) {
                owners.add(NEOFORGE_HELPER);
                owners.add(FORGE_HELPER);
            }
            return owners;
        }
        // A Forge name is left to the Forge to NeoForge rename while NeoForge still has its class.
        boolean neoForgeHelper = ClassResourceInspector.exists(NEOFORGE_HELPER);
        if (McReflect.isNeoForge() && !neoForgeHelper) owners.add(NEOFORGE_HELPER);
        if (!neoForgeHelper && !ClassResourceInspector.exists(FORGE_HELPER)) owners.add(FORGE_HELPER);
        return owners;
    }

    /** Writes the helper on vanilla's inventory, which every host still has. */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);

        // The two-argument form is the three-argument one with no preferred slot.
        MethodVisitor give = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "giveItemToPlayer", GIVE, null, null);
        give.visitCode();
        give.visitVarInsn(ALOAD, 0);
        give.visitVarInsn(ALOAD, 1);
        give.visitInsn(ICONST_M1);
        give.visitMethodInsn(INVOKESTATIC, HELPER, "giveItemToPlayer", GIVE_TO_SLOT, false);
        give.visitInsn(RETURN);
        give.visitMaxs(0, 0);
        give.visitEnd();

        emitGiveToSlot(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Writes {@code giveItemToPlayer(player, stack, slot)}, which does what the old helper did. */
    private static void emitGiveToSlot(ClassWriter cw) {
        // Locals: 0 player, 1 stack, 2 preferred slot, 3 what is left to give, 4 the inventory,
        // 5 the level, 6 the dropped item entity.
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "giveItemToPlayer", GIVE_TO_SLOT, null, null);
        mv.visitCode();
        Label start = new Label();
        // An empty stack gives nothing.
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM_STACK, "isEmpty", "()Z", false);
        mv.visitJumpInsn(IFEQ, start);
        mv.visitInsn(RETURN);
        // Work on a copy, so the caller's stack is not changed.
        mv.visitLabel(start);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM_STACK, "copy", "()" + L_STACK, false);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, PLAYER, "getInventory", "()L" + INVENTORY + ";", false);
        mv.visitVarInsn(ASTORE, 4);

        // Try the preferred slot, then vanilla's insert for the rest. It fills matching stacks before
        // empty slots, tops up a matching offhand, and for a creative player removes what does not fit.
        emitPreferredSlot(mv);
        Label inserted = new Label();
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM_STACK, "isEmpty", "()Z", false);
        mv.visitJumpInsn(IFNE, inserted);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, INVENTORY, "add", "(" + L_STACK + ")Z", false);
        mv.visitInsn(POP);
        mv.visitLabel(inserted);

        // Play the pickup sound and drop what did not fit.
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "level", "()" + L_LEVEL, false);
        mv.visitVarInsn(ASTORE, 5);
        emitPickupSound(mv);
        emitDropRest(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Puts the stack in the preferred slot when it is an empty main inventory slot. */
    private static void emitPreferredSlot(MethodVisitor mv) {
        Label skip = new Label();
        // if (0 <= slot < INVENTORY_SIZE && inventory.getItem(slot).isEmpty()) inventory.add(slot, rest)
        mv.visitVarInsn(ILOAD, 2);
        mv.visitJumpInsn(IFLT, skip);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitFieldInsn(GETSTATIC, INVENTORY, "INVENTORY_SIZE", "I");
        mv.visitJumpInsn(IF_ICMPGE, skip);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, INVENTORY, "getItem", "(I)" + L_STACK, false);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM_STACK, "isEmpty", "()Z", false);
        mv.visitJumpInsn(IFEQ, skip);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, INVENTORY, "add", "(I" + L_STACK + ")Z", false);
        mv.visitInsn(POP);
        mv.visitLabel(skip);
    }

    /** Plays the item pickup sound at the player, when any of the stack went in. */
    private static void emitPickupSound(MethodVisitor mv) {
        Label play = new Label();
        Label done = new Label();
        // Something went in when the rest is empty or smaller than the stack.
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM_STACK, "isEmpty", "()Z", false);
        mv.visitJumpInsn(IFNE, play);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM_STACK, "getCount", "()I", false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM_STACK, "getCount", "()I", false);
        mv.visitJumpInsn(IF_ICMPEQ, done);
        // level.playSound(null, feet, ITEM_PICKUP, PLAYERS, 0.2, pitch)
        mv.visitLabel(play);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitInsn(ACONST_NULL);
        pushFeet(mv);
        mv.visitFieldInsn(GETSTATIC, "net/minecraft/sounds/SoundEvents", "ITEM_PICKUP",
                "Lnet/minecraft/sounds/SoundEvent;");
        mv.visitFieldInsn(GETSTATIC, "net/minecraft/sounds/SoundSource", "PLAYERS",
                "Lnet/minecraft/sounds/SoundSource;");
        mv.visitLdcInsn(0.2F);
        // Vanilla's pickup pitch: ((a - b) * 0.7 + 1) * 2 for two random floats.
        pushRandomFloat(mv);
        pushRandomFloat(mv);
        mv.visitInsn(FSUB);
        mv.visitLdcInsn(0.7F);
        mv.visitInsn(FMUL);
        mv.visitInsn(FCONST_1);
        mv.visitInsn(FADD);
        mv.visitInsn(FCONST_2);
        mv.visitInsn(FMUL);
        mv.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "playSound", "(L" + ENTITY
                + ";DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V", false);
        mv.visitLabel(done);
    }

    /** Drops what did not fit at the player's feet, on the server only. */
    private static void emitDropRest(MethodVisitor mv) {
        Label done = new Label();
        // Nothing to drop when everything fit, and the client spawns nothing.
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM_STACK, "isEmpty", "()Z", false);
        mv.visitJumpInsn(IFNE, done);
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "isClientSide", "()Z", false);
        mv.visitJumpInsn(IFNE, done);
        // The rest becomes an item entity with the old helper's two second pickup delay.
        mv.visitTypeInsn(NEW, ITEM_ENTITY);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 5);
        pushFeet(mv);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESPECIAL, ITEM_ENTITY, "<init>", "(" + L_LEVEL + "DDD" + L_STACK + ")V", false);
        mv.visitVarInsn(ASTORE, 6);
        mv.visitVarInsn(ALOAD, 6);
        mv.visitIntInsn(BIPUSH, PICKUP_DELAY);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM_ENTITY, "setPickUpDelay", "(I)V", false);
        // Keep only its vertical motion, so it falls instead of scattering.
        mv.visitVarInsn(ALOAD, 6);
        mv.visitVarInsn(ALOAD, 6);
        mv.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "getDeltaMovement", "()" + L_VEC3, false);
        mv.visitInsn(DCONST_0);
        mv.visitInsn(DCONST_1);
        mv.visitInsn(DCONST_0);
        mv.visitMethodInsn(INVOKEVIRTUAL, VEC3, "multiply", "(DDD)" + L_VEC3, false);
        mv.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "setDeltaMovement", "(" + L_VEC3 + ")V", false);
        // Add it to the level.
        mv.visitVarInsn(ALOAD, 5);
        mv.visitVarInsn(ALOAD, 6);
        mv.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "addFreshEntity", "(L" + ENTITY + ";)Z", false);
        mv.visitInsn(POP);
        mv.visitLabel(done);
    }

    /** Pushes the player's x, y + 0.5 and z. */
    private static void pushFeet(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "getX", "()D", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "getY", "()D", false);
        mv.visitLdcInsn(0.5D);
        mv.visitInsn(DADD);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "getZ", "()D", false);
    }

    /** Pushes {@code level.getRandom().nextFloat()}. */
    private static void pushRandomFloat(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "getRandom", "()Lnet/minecraft/util/RandomSource;", false);
        mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraft/util/RandomSource", "nextFloat", "()F", true);
    }
}
