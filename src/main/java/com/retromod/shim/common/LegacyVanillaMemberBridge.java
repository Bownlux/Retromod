/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Function;

import static com.retromod.shim.common.LegacyRemovedMemberBridge.CHECKED_1_21_11;
import static com.retromod.shim.common.LegacyRemovedMemberBridge.lacksMethod;

/** Entity, player, inventory, item and server members that 1.21.x mods call and Minecraft reshaped by 26.3. */
public final class LegacyVanillaMemberBridge {

    static final String ENTITY = "net/minecraft/world/entity/Entity";
    static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    static final String PLAYER = "net/minecraft/world/entity/player/Player";
    static final String INVENTORY = "net/minecraft/world/entity/player/Inventory";
    static final String SERVER = "net/minecraft/server/MinecraftServer";
    static final String COOLDOWNS = "net/minecraft/world/item/ItemCooldowns";
    static final String DYE_ITEM = "net/minecraft/world/item/DyeItem";
    static final String STACK = "net/minecraft/world/item/ItemStack";
    static final String SPAWN_EGG = "net/minecraft/world/item/SpawnEggItem";
    static final String STATE = "net/minecraft/world/level/block/state/BlockState";
    static final String STATE_BASE = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";
    static final String HAND = "net/minecraft/world/InteractionHand";
    static final String SLOT = "net/minecraft/world/entity/EquipmentSlot";
    static final String LEVEL = "net/minecraft/world/level/Level";
    static final String ITEM = "net/minecraft/world/item/Item";
    static final String PROFILE = "com/mojang/authlib/GameProfile";
    static final String LIST = "net/minecraft/core/NonNullList";

    static final String ENTITY_MEMBERS = LegacyRemovedMemberBridge.ENTITY_MEMBERS;
    static final String PERMISSION_MEMBERS = RebuiltApiClasses.ROOT + "minecraft/world/entity/LegacyPermissionMembers";
    static final String INVENTORIES = RebuiltApiClasses.ROOT + "minecraft/world/entity/player/LegacyInventories";
    static final String ITEM_MEMBERS = RebuiltApiClasses.ROOT + "minecraft/world/item/LegacyItemMembers";
    static final String STATE_MEMBERS = RebuiltApiClasses.ROOT + "minecraft/world/level/block/LegacyBlockStateMembers";
    static final String SERVER_MEMBERS = RebuiltApiClasses.ROOT + "minecraft/server/LegacyServerMembers";

    // The first host each change was read on. 1.21.11 is the oldest Mojang-named jar at hand; the rest moved later.
    static final String FIRST_HOST_26_1 = "26.1";
    static final String FIRST_HOST_26_3 = "26.3";

    // The entity classes javac names as the owner of a call to a member they inherit.
    static final List<String> ENTITY_OWNERS = List.of(ENTITY, LIVING, "net/minecraft/world/entity/Mob",
            "net/minecraft/world/entity/PathfinderMob", PLAYER, "net/minecraft/server/level/ServerPlayer",
            "net/minecraft/client/player/AbstractClientPlayer", "net/minecraft/client/player/LocalPlayer",
            "net/minecraft/world/entity/TamableAnimal", "net/minecraft/world/entity/animal/Animal",
            "net/minecraft/world/entity/monster/Monster");

    private LegacyVanillaMemberBridge() {}

    static void register(RetromodTransformer t, Function<String, ClassNode> host) {
        registerEntityMembers(t, host);
        // Old fall damage and damage overrides need the adapter, which reads these flags.
        if (lacksMethod(host, ENTITY, "causeFallDamage", LegacyEntityOverrideAdapter.OLD_FALL, CHECKED_1_21_11)) {
            t.enablePostProcess(LegacyEntityOverrideAdapter.FALL_FLAG);
        }
        if (!lacksMethod(host, ENTITY, "hurtServer", LegacyEntityOverrideAdapter.HURT_SERVER, CHECKED_1_21_11)
                || host.apply(ENTITY) == null) {
            t.enablePostProcess(LegacyEntityOverrideAdapter.HURT_FLAG);
        }
        registerInventory(t, host);
        registerItemMembers(t, host);
        registerStateMembers(t, host);
        // isSingleplayerOwner took a name and id in place of the game profile.
        String ownerDesc = "(L" + PROFILE + ";)Z";
        if (lacksMethod(host, SERVER, "isSingleplayerOwner", ownerDesc, CHECKED_1_21_11)) {
            RebuiltApiClasses.registerTree(t, SERVER_MEMBERS);
            redirect(t, List.of(SERVER, "net/minecraft/server/dedicated/DedicatedServer",
                    "net/minecraft/client/server/IntegratedServer"), "isSingleplayerOwner", ownerDesc,
                    SERVER_MEMBERS, CHECKED_1_21_11);
        }
    }

    private static void registerEntityMembers(RetromodTransformer t, Function<String, ClassNode> host) {
        RebuiltApiClasses.registerTree(t, ENTITY_MEMBERS);
        // getSlotForHand(hand) was static on LivingEntity until 1.21.9; the hand answers now.
        String slotDesc = "(L" + HAND + ";)L" + SLOT + ";";
        if (lacksMethod(host, LIVING, "getSlotForHand", slotDesc, CHECKED_1_21_11)) {
            t.registerMethodRedirect(LIVING, "getSlotForHand", slotDesc, ENTITY_MEMBERS, "getSlotForHand", slotDesc);
            t.limitRedirectToModsBefore(LIVING, "getSlotForHand", slotDesc, CHECKED_1_21_11);
        }
        // Operator levels became permission sets.
        if (lacksMethod(host, PLAYER, "hasPermissions", "(I)Z", CHECKED_1_21_11)) {
            RebuiltApiClasses.registerTree(t, PERMISSION_MEMBERS);
            redirect(t, ENTITY_OWNERS, "hasPermissions", "(I)Z", PERMISSION_MEMBERS, CHECKED_1_21_11);
        }
        String level = "()L" + LEVEL + ";";
        if (lacksMethod(host, ENTITY, "getCommandSenderWorld", level, CHECKED_1_21_11)) {
            redirect(t, ENTITY_OWNERS, "getCommandSenderWorld", level, ENTITY_MEMBERS, CHECKED_1_21_11);
        }
        // Scoreboard tags became entityTags() in 26.1.
        if (lacksMethod(host, ENTITY, "getTags", "()Ljava/util/Set;", FIRST_HOST_26_1)) {
            redirect(t, ENTITY_OWNERS, "getTags", "()Ljava/util/Set;", ENTITY_MEMBERS, FIRST_HOST_26_1);
        }
        // startRiding gained a flag for the mount events, which the old call always fired.
        String riding = "(L" + ENTITY + ";Z)Z";
        if (lacksMethod(host, ENTITY, "startRiding", riding, CHECKED_1_21_11)) {
            redirect(t, ENTITY_OWNERS, "startRiding", riding, ENTITY_MEMBERS, CHECKED_1_21_11);
        }
        // Owners of tamed animals and horses are references now, as for every OwnableEntity.
        String owner = "()Ljava/util/UUID;";
        for (String ownable : List.of("net/minecraft/world/entity/TamableAnimal",
                "net/minecraft/world/entity/animal/equine/AbstractHorse")) {
            if (lacksMethod(host, ownable, "getOwnerUUID", owner, CHECKED_1_21_11)) {
                t.registerInheritedMethodRedirect(ownable, "getOwnerUUID", owner,
                        ENTITY_MEMBERS, "getOwnerUUID", "(Ljava/lang/Object;)Ljava/util/UUID;");
                t.limitRedirectToModsBefore(ownable, "getOwnerUUID", owner, CHECKED_1_21_11);
            }
        }
        // A fake player's super(level, pos, yaw, profile): 26.x places a player after it is built.
        String oldPlayer = "(L" + LEVEL + ";Lnet/minecraft/core/BlockPos;FL" + PROFILE + ";)V";
        if (lacksMethod(host, PLAYER, "<init>", oldPlayer, CHECKED_1_21_11)) {
            t.registerSuperConstructorAdapter(PLAYER, oldPlayer, "(L" + LEVEL + ";L" + PROFILE + ";)V",
                    LegacyVanillaMemberBridge::dropPositionAndYaw);
        }
    }

    /** Turns {@code level, pos, yaw, profile} on the stack into {@code level, profile}. */
    static void dropPositionAndYaw(MethodVisitor mv) {
        // The receiver is uninitialized, so the profile waits in the transformer's spill slot.
        int slot = RetromodTransformer.CONSTRUCTOR_ARG_SPILL_SLOT;
        mv.visitVarInsn(Opcodes.ASTORE, slot);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, slot);
    }

    // 1.21.5 moved the armor and offhand lists into the player's equipment.
    private static void registerInventory(RetromodTransformer t, Function<String, ClassNode> host) {
        ClassNode inventory = host.apply(INVENTORY);
        boolean listsGone = inventory == null ? lacksMethod(host, INVENTORY, "<init>", "(L" + PLAYER + ";)V", CHECKED_1_21_11)
                : inventory.fields.stream().noneMatch(f -> f.name.equals("armor"));
        if (!listsGone) return;
        RebuiltApiClasses.registerTree(t, INVENTORIES);
        String list = "L" + LIST + ";";
        for (String field : List.of("offhand", "armor")) {
            t.registerTypedFieldStaticBridge(INVENTORY, field, list, INVENTORIES, field, "(Ljava/lang/Object;)" + list,
                    null, null);
        }
        t.registerMethodRedirect(INVENTORY, "getArmor", "(I)L" + STACK + ";",
                INVENTORIES, "getArmor", "(Ljava/lang/Object;I)L" + STACK + ";", true);
        t.limitRedirectToModsBefore(INVENTORY, "getArmor", "(I)L" + STACK + ";", CHECKED_1_21_11);
        t.registerConstructorRedirect(INVENTORY, "(L" + PLAYER + ";)V",
                INVENTORIES, "newInventory", "(L" + PLAYER + ";)L" + INVENTORY + ";");
    }

    private static void registerItemMembers(RetromodTransformer t, Function<String, ClassNode> host) {
        RebuiltApiClasses.registerTree(t, ITEM_MEMBERS);
        // Cooldowns are kept per stack from 1.21.2.
        for (String[] cooldown : new String[][] {{"isOnCooldown", "(L" + ITEM + ";)Z"},
                {"addCooldown", "(L" + ITEM + ";I)V"}, {"getCooldownPercent", "(L" + ITEM + ";F)F"}}) {
            if (lacksMethod(host, COOLDOWNS, cooldown[0], cooldown[1], CHECKED_1_21_11)) {
                redirect(t, List.of(COOLDOWNS), cooldown[0], cooldown[1], ITEM_MEMBERS, CHECKED_1_21_11);
            }
        }
        // 26.1 made a dye item's color, and an item's tags, things the item holds.
        String color = "()Lnet/minecraft/world/item/DyeColor;";
        if (lacksMethod(host, DYE_ITEM, "getDyeColor", color, FIRST_HOST_26_1)) {
            t.registerInheritedMethodRedirect(DYE_ITEM, "getDyeColor", color, ITEM_MEMBERS, "getDyeColor",
                    "(Ljava/lang/Object;)Lnet/minecraft/world/item/DyeColor;");
            t.limitRedirectToModsBefore(DYE_ITEM, "getDyeColor", color, FIRST_HOST_26_1);
        }
        if (lacksMethod(host, STACK, "getTags", "()Ljava/util/stream/Stream;", FIRST_HOST_26_1)) {
            redirect(t, List.of(STACK), "getTags", "()Ljava/util/stream/Stream;", ITEM_MEMBERS, FIRST_HOST_26_1);
        }
        // 26.1 made SpawnEggItem.getType static; an old call still names an egg.
        String type = "(L" + STACK + ";)Lnet/minecraft/world/entity/EntityType;";
        if (isStaticOnHost(host, SPAWN_EGG, "getType", type, FIRST_HOST_26_1)) {
            t.registerInheritedMethodRedirect(SPAWN_EGG, "getType", type, ITEM_MEMBERS, "spawnEggType",
                    "(Ljava/lang/Object;L" + STACK + ";)Lnet/minecraft/world/entity/EntityType;");
            t.limitRedirectToModsBefore(SPAWN_EGG, "getType", type, FIRST_HOST_26_1);
        }
    }

    private static void registerStateMembers(RetromodTransformer t, Function<String, ClassNode> host) {
        boolean tags = lacksMethod(host, STATE_BASE, "getTags", "()Ljava/util/stream/Stream;", FIRST_HOST_26_1);
        boolean motion = lacksMethod(host, STATE_BASE, "blocksMotion", "()Z", FIRST_HOST_26_3);
        if (!tags && !motion) return;
        RebuiltApiClasses.registerTree(t, STATE_MEMBERS);
        if (tags) redirect(t, List.of(STATE, STATE_BASE), "getTags", "()Ljava/util/stream/Stream;", STATE_MEMBERS, FIRST_HOST_26_1);
        if (motion) redirect(t, List.of(STATE, STATE_BASE), "blocksMotion", "()Z", STATE_MEMBERS, FIRST_HOST_26_3);
    }

    /** Sends {@code owner.name(desc)} on each owner to a helper that takes the receiver first, for mods built before {@code limit}. */
    static void redirect(RetromodTransformer t, List<String> owners, String name, String desc, String helper, String limit) {
        String helperDesc = "(Ljava/lang/Object;" + desc.substring(1);
        for (String owner : owners) {
            t.registerMethodRedirect(owner, name, desc, helper, name, helperDesc, true);
            t.limitRedirectToModsBefore(owner, name, desc, limit);
        }
        // A mod's own subclass is named as the owner too; the redirect follows the hierarchy from the first owner.
        t.registerInheritedMethodRedirect(owners.get(0), name, desc, helper, name, helperDesc);
    }

    /** Whether the host declares {@code owner.name(desc)} static; with no host to read, whether the target is {@code firstHost} or newer. */
    static boolean isStaticOnHost(Function<String, ClassNode> host, String owner, String name, String desc, String firstHost) {
        ClassNode node = host.apply(owner);
        if (node == null) return lacksMethod(unread -> null, owner, name, desc, firstHost);
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc)
                && (m.access & Opcodes.ACC_STATIC) != 0);
    }
}
