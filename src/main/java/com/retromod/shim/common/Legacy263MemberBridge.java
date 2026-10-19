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
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/** Bridges the inventory and player members 26.3 reshaped, for mods built for 26.2 or older. */
public final class Legacy263MemberBridge {

    static final String HELPER = "com/retromod/generated/Legacy263Members";
    /** The helper's cached lookup of a player's own {@code drop(stack, retainOwnership)}. */
    static final String OWN_DROP = "ownDrop";
    /** The helper's cached lookup of an entity's own {@code drop(stack, throwRandomly, retainOwnership)}. */
    static final String OWN_THROWING_DROP = "ownThrowingDrop";

    static final String INVENTORY = "net/minecraft/world/entity/player/Inventory";
    static final String PLAYER = "net/minecraft/world/entity/player/Player";
    static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String LEVEL = "net/minecraft/world/level/Level";
    static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    static final String ITEM_ENTITY = "net/minecraft/world/entity/item/ItemEntity";
    static final String PREDICTION = "net/minecraft/util/Prediction";
    static final String CONTAINER = "net/minecraft/world/Container";

    static final String CLEAR_OLD = "(Ljava/util/function/Predicate;IL" + CONTAINER + ";)I";
    static final String CLEAR_NEW = "(Ljava/util/function/Predicate;ZIL" + CONTAINER + ";)I";
    static final String DROP_OLD = "(L" + ITEM_STACK + ";Z)L" + ITEM_ENTITY + ";";
    static final String DROP_NEW = "(L" + ITEM_STACK + ";ZL" + PREDICTION + ";)L" + ITEM_ENTITY + ";";
    static final String THROW_OLD = "(L" + ITEM_STACK + ";ZZ)L" + ITEM_ENTITY + ";";
    static final String CREATE_TO_DROP = THROW_OLD;
    static final String CAPTURE_DROPS = "()Ljava/util/Collection;";
    static final String PLACE_BACK_OLD = "(L" + ITEM_STACK + ";)V";
    static final String PLACE_BACK_NEW = "(L" + ITEM_STACK + ";L" + PREDICTION + ";)V";
    static final String PLACE_BACK_FLAG_OLD = "(L" + ITEM_STACK + ";Z)V";
    static final String PLACE_BACK_FLAG_NEW = "(L" + ITEM_STACK + ";ZL" + PREDICTION + ";)V";

    /** What the host lost, and whether its entities capture their drops, as NeoForge's do. */
    record Members(boolean clear, boolean drop, boolean throwingDrop, boolean capturesDrops,
            boolean placeBack) {
        Members(boolean clear, boolean drop, boolean throwingDrop, boolean capturesDrops) {
            this(clear, drop, throwingDrop, capturesDrops, false);
        }

        boolean any() {
            return clear || drop || throwingDrop || placeBack;
        }
    }

    private Legacy263MemberBridge() {}

    /** Registers a bridge for each member this host lost. */
    public static void register(RetromodTransformer transformer) {
        Members members = new Members(clearTakesCountFlag(), dropTakesPrediction(), lostThrowingDrop(),
                capturesDrops(), placeBackTakesPrediction());
        if (members.any()) registerRedirects(transformer, members);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, Members members) {
        transformer.registerSyntheticClass(HELPER, generateHelper(members));
        // Inventory.clearOrCountMatchingItems gained a count-only flag.
        if (members.clear()) {
            transformer.registerInheritedMethodRedirect(INVENTORY, "clearOrCountMatchingItems", CLEAR_OLD,
                    HELPER, "clearOrCountMatchingItems", receiverFirst(INVENTORY, CLEAR_OLD));
        }
        // Player.drop(stack, retainOwnership) gained a Prediction, because every drop now swings the
        // arm on both sides.
        if (members.drop()) {
            // Mods call it on a ServerPlayer, a fake player or their own Player subclass.
            transformer.registerInheritedMethodRedirect(PLAYER, "drop", DROP_OLD,
                    HELPER, "drop", receiverFirst(PLAYER, DROP_OLD));
            // An entity's own override keeps running when a mod calls it through a Player reference.
            OwnOverrideCall.register(transformer, HELPER, "drop", receiverFirst(PLAYER, DROP_OLD));
        }
        // LivingEntity.drop(stack, throwRandomly, retainOwnership) was removed, and the new drop
        // cannot throw at random.
        if (members.throwingDrop()) {
            // Player declared it through 1.21.2 and LivingEntity from 1.21.5 to 26.2, so mods name either.
            String desc = receiverFirst(LIVING, THROW_OLD);
            for (String owner : new String[] {LIVING, PLAYER}) {
                transformer.registerInheritedMethodRedirect(owner, "drop", THROW_OLD, HELPER, "drop", desc);
            }
            OwnOverrideCall.register(transformer, HELPER, "drop", desc);
        }
        // Inventory.placeItemBackInInventory gained a Prediction for the drop it makes when the
        // inventory is full. Menus call it as they close, MCreator's among them.
        if (members.placeBack()) {
            transformer.registerInheritedMethodRedirect(INVENTORY, "placeItemBackInInventory", PLACE_BACK_OLD,
                    HELPER, "placeItemBackInInventory", receiverFirst(INVENTORY, PLACE_BACK_OLD));
            transformer.registerInheritedMethodRedirect(INVENTORY, "placeItemBackInInventory", PLACE_BACK_FLAG_OLD,
                    HELPER, "placeItemBackInInventory", receiverFirst(INVENTORY, PLACE_BACK_FLAG_OLD));
        }
    }

    /** Whether the host lost the old {@code clearOrCountMatchingItems}. */
    static boolean clearTakesCountFlag() {
        ClassNode inventory = ClassResourceInspector.read(INVENTORY);
        // Offline the host classes are not readable, so the host version decides.
        return inventory == null ? !hostBefore("26.3") : clearTakesCountFlag(inventory);
    }

    static boolean clearTakesCountFlag(ClassNode inventory) {
        return !hasMethod(inventory, "clearOrCountMatchingItems", CLEAR_OLD)
                && hasMethod(inventory, "clearOrCountMatchingItems", CLEAR_NEW);
    }

    /** Whether the host's player {@code drop} now takes a {@code Prediction}. */
    static boolean dropTakesPrediction() {
        ClassNode player = ClassResourceInspector.read(PLAYER);
        ClassNode living = ClassResourceInspector.read(LIVING);
        ClassNode prediction = ClassResourceInspector.read(PREDICTION);
        if (player == null || living == null || prediction == null) return !hostBefore("26.3");
        return dropTakesPrediction(player, living, prediction);
    }

    static boolean dropTakesPrediction(ClassNode player, ClassNode living, ClassNode prediction) {
        // Vanilla 26.3 declares the new drop on LivingEntity only, while NeoForge's Player
        // overrides it to fire its toss event, so both classes are read.
        boolean oldForm = hasMethod(player, "drop", DROP_OLD) || hasMethod(living, "drop", DROP_OLD);
        boolean newForm = hasMethod(player, "drop", DROP_NEW) || hasMethod(living, "drop", DROP_NEW);
        return !oldForm && newForm && hasField(prediction, "PREDICTED", "L" + PREDICTION + ";");
    }

    /** Whether the host lost the three-argument {@code drop} and can rebuild it. */
    static boolean lostThrowingDrop() {
        ClassNode player = ClassResourceInspector.read(PLAYER);
        ClassNode living = ClassResourceInspector.read(LIVING);
        ClassNode prediction = ClassResourceInspector.read(PREDICTION);
        if (player == null || living == null || prediction == null) return !hostBefore("26.3");
        return lostThrowingDrop(player, living, prediction);
    }

    static boolean lostThrowingDrop(ClassNode player, ClassNode living, ClassNode prediction) {
        boolean oldForm = hasMethod(player, "drop", THROW_OLD) || hasMethod(living, "drop", THROW_OLD);
        // The helper rebuilds it on LivingEntity's predicted drop and createItemStackToDrop.
        return !oldForm && hasMethod(living, "drop", DROP_NEW)
                && hasMethod(living, "createItemStackToDrop", CREATE_TO_DROP)
                && hasField(prediction, "PREDICTED", "L" + PREDICTION + ";");
    }

    /** Whether the host's {@code placeItemBackInInventory} now takes a {@code Prediction}. */
    static boolean placeBackTakesPrediction() {
        ClassNode inventory = ClassResourceInspector.read(INVENTORY);
        ClassNode prediction = ClassResourceInspector.read(PREDICTION);
        if (inventory == null || prediction == null) return !hostBefore("26.3");
        return placeBackTakesPrediction(inventory, prediction);
    }

    static boolean placeBackTakesPrediction(ClassNode inventory, ClassNode prediction) {
        return !hasMethod(inventory, "placeItemBackInInventory", PLACE_BACK_OLD)
                && hasMethod(inventory, "placeItemBackInInventory", PLACE_BACK_NEW)
                && hasMethod(inventory, "placeItemBackInInventory", PLACE_BACK_FLAG_NEW)
                && hasField(prediction, "SERVER_ONLY", "L" + PREDICTION + ";");
    }

    /** Whether the host has NeoForge's {@code Entity.captureDrops()}. */
    static boolean capturesDrops() {
        ClassNode entity = ClassResourceInspector.read(ENTITY);
        // Offline the loader is unknown, so capturing is left out.
        return entity != null && capturesDrops(entity);
    }

    static boolean capturesDrops(ClassNode entity) {
        return hasMethod(entity, "captureDrops", CAPTURE_DROPS);
    }

    private static boolean hostBefore(String version) {
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, version) < 0;
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private static boolean hasField(ClassNode owner, String name, String desc) {
        for (FieldNode field : owner.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) return true;
        }
        return false;
    }

    /** The static helper's descriptor for an instance method: the receiver, then the parameters. */
    private static String receiverFirst(String owner, String desc) {
        return "(L" + owner + ";" + desc.substring(1);
    }

    /** Writes the helper class with a static method for each bridged member. */
    static byte[] generateHelper(Members members) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        if (members.clear()) emitClearOrCount(cw);
        // The two-argument drop runs the player's own override first, else the host's drop.
        // Its super$ twin answers the override's own super call with the host's drop only.
        if (members.drop()) {
            OwnOverrideCall.declareLookup(cw, OWN_DROP);
            String desc = receiverFirst(PLAYER, DROP_OLD);
            MethodVisitor call = begin(cw, "drop", desc);
            OwnOverrideCall.emitOwnCall(call, HELPER, OWN_DROP, "drop", desc, OwnOverrideCall.ownArguments(desc));
            emitHostDrop(call);
            emitHostDrop(begin(cw, OwnOverrideCall.superName("drop"), desc));
        }
        // The three-argument drop has the same shape, with the rebuilt random throw behind it.
        if (members.throwingDrop()) {
            OwnOverrideCall.declareLookup(cw, OWN_THROWING_DROP);
            String desc = receiverFirst(LIVING, THROW_OLD);
            MethodVisitor call = begin(cw, "drop", desc);
            OwnOverrideCall.emitOwnCall(call, HELPER, OWN_THROWING_DROP, "drop", desc,
                    OwnOverrideCall.ownArguments(desc));
            emitThrowingDrop(call, members.capturesDrops());
            emitThrowingDrop(begin(cw, OwnOverrideCall.superName("drop"), desc), members.capturesDrops());
        }
        if (members.placeBack()) {
            emitPlaceBack(begin(cw, "placeItemBackInInventory", receiverFirst(INVENTORY, PLACE_BACK_OLD)),
                    false);
            emitPlaceBack(begin(cw, "placeItemBackInInventory", receiverFirst(INVENTORY, PLACE_BACK_FLAG_OLD)),
                    true);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodVisitor begin(ClassWriter cw, String name, String desc) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        return mv;
    }

    /** Writes {@code inventory.clearOrCountMatchingItems(predicate, max == 0, max, craftSlots)}. */
    private static void emitClearOrCount(ClassWriter cw) {
        MethodVisitor mv = begin(cw, "clearOrCountMatchingItems", receiverFirst(INVENTORY, CLEAR_OLD));
        Label clears = new Label();
        Label call = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        // The old method only counted when max was 0, so that sets the count-only flag.
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitJumpInsn(Opcodes.IFNE, clears);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitJumpInsn(Opcodes.GOTO, call);
        mv.visitLabel(clears);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitLabel(call);
        // The clearing itself is unchanged, so max and craftSlots pass through.
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, INVENTORY, "clearOrCountMatchingItems", CLEAR_NEW, false);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Writes {@code inventory.placeItemBackInInventory(stack[, sendPacket], Prediction.SERVER_ONLY)}.
     * SERVER_ONLY is what 26.3's own menus pass when they hand items back as they close.
     */
    private static void emitPlaceBack(MethodVisitor mv, boolean withFlag) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        if (withFlag) mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitFieldInsn(Opcodes.GETSTATIC, PREDICTION, "SERVER_ONLY", "L" + PREDICTION + ";");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, INVENTORY, "placeItemBackInInventory",
                withFlag ? PLACE_BACK_FLAG_NEW : PLACE_BACK_NEW, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Writes {@code entity.drop(stack, throwRandomly, retainOwnership)} on 26.3's members. */
    private static void emitThrowingDrop(MethodVisitor mv, boolean capturesDrops) {
        Label hostDrop = new Label();
        Label done = new Label();
        // Without throwRandomly, or on the client, which spawns nothing, it is the host's drop.
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitJumpInsn(Opcodes.IFEQ, hostDrop);
        emitLevel(mv);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LEVEL, "isClientSide", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, hostDrop);
        // The server builds the item with createItemStackToDrop(stack, true, retainOwnership),
        // which is how 26.3's own inventory drop throws items at random.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LIVING, "createItemStackToDrop", CREATE_TO_DROP, false);
        mv.visitVarInsn(Opcodes.ASTORE, 4);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitJumpInsn(Opcodes.IFNULL, done);
        // On a host whose entities capture their drops, such as NeoForge, a capture in progress
        // takes the item instead, as the old method did there.
        if (capturesDrops) {
            Label spawn = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "captureDrops", CAPTURE_DROPS, false);
            mv.visitVarInsn(Opcodes.ASTORE, 5);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitJumpInsn(Opcodes.IFNULL, spawn);
            mv.visitVarInsn(Opcodes.ALOAD, 5);
            mv.visitVarInsn(Opcodes.ALOAD, 4);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Collection", "add", "(Ljava/lang/Object;)Z", true);
            mv.visitInsn(Opcodes.POP);
            mv.visitJumpInsn(Opcodes.GOTO, done);
            mv.visitLabel(spawn);
        }
        // Otherwise the item is added to the level, and the method returns it.
        emitLevel(mv);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LEVEL, "addFreshEntity", "(L" + ENTITY + ";)Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitLabel(done);
        mv.visitVarInsn(Opcodes.ALOAD, 4);
        mv.visitInsn(Opcodes.ARETURN);

        // The host's drop(stack, retainOwnership, PREDICTED) keeps a server player's drop
        // statistics and, on NeoForge, also fires a player's toss event.
        mv.visitLabel(hostDrop);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitFieldInsn(Opcodes.GETSTATIC, PREDICTION, "PREDICTED", "L" + PREDICTION + ";");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LIVING, "drop", DROP_NEW, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Pushes {@code entity.level()}. */
    private static void emitLevel(MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "level", "()L" + LEVEL + ";", false);
    }

    /** Writes {@code player.drop(stack, retainOwnership, Prediction.PREDICTED)}. */
    private static void emitHostDrop(MethodVisitor mv) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        // PREDICTED sends the server's arm swing to other players only, so the dropping player's own
        // view stays as it was. The stack, the thrower and NeoForge's toss event match the old call.
        mv.visitFieldInsn(Opcodes.GETSTATIC, PREDICTION, "PREDICTED", "L" + PREDICTION + ";");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PLAYER, "drop", DROP_NEW, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
