/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;

/**
 * Rebases a removed class onto the class it used to extend.
 *
 * <p>This is the safest rebase there is. A mod that extended the removed class was already a
 * subtype of the parent, so every member it inherits still resolves, the constructor it calls has
 * the same shape, and anything that accepted the old type as a parameter accepted the parent too.
 * What it loses is only what the removed class added on top, which is stated per entry below.
 *
 * <p>A loader's own API counts too: Forge deleted ForgeSpawnEggItem once vanilla could do
 * the same job, and its parent is a vanilla class that survived.
 *
 * <p>It is not a substitute for the curated bridges. Use this only where the parent survives with a
 * matching constructor and the removed class was a convenience layer rather than a different
 * contract. Where a class was replaced by something with a new shape, such as the entity model and
 * item renderer reworks, a rebase would link and then behave wrongly, which is worse than the named
 * error Retromod prints instead.
 */
public final class RemovedBaseToParentBridge {

    private RemovedBaseToParentBridge() {}

    public static void register(RetromodTransformer transformer) {
        // EffectRenderingInventoryScreen was AbstractContainerScreen plus the potion effect panel
        // drawn beside the inventory. The panel moved to EffectsInInventory, which a screen holds
        // rather than extends, so there is no class to rename to. Both take
        // (Menu, Inventory, Component). A rebased screen opens and works; it stops drawing the
        // effect panel until the mod adopts the new helper.
        transformer.registerGeneratedLegacyBase(
                "net/minecraft/client/gui/screens/inventory/EffectRenderingInventoryScreen",
                "com/retromod/generated/LegacyEffectRenderingInventoryScreen",
                "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
                "(Lnet/minecraft/world/inventory/AbstractContainerMenu;"
                        + "Lnet/minecraft/world/entity/player/Inventory;"
                        + "Lnet/minecraft/network/chat/Component;)V");

        // AbstractProjectileDispenseBehavior was DefaultDispenseItemBehavior plus a getProjectile
        // hook that dispensers called to build the thrown entity. Both take no arguments. A rebased
        // behaviour registers and dispenses the item the default way, dropping it in front of the
        // dispenser, rather than firing it, because the mod's getProjectile override no longer
        // overrides anything.
        transformer.registerGeneratedLegacyBase(
                "net/minecraft/core/dispenser/AbstractProjectileDispenseBehavior",
                "com/retromod/generated/LegacyAbstractProjectileDispenseBehavior",
                "net/minecraft/core/dispenser/DefaultDispenseItemBehavior",
                "()V");

        // ForgeSpawnEggItem let a mod build a spawn egg from a deferred EntityType supplier, back
        // when vanilla's SpawnEggItem demanded the type at construction. Vanilla now takes the type
        // and both colours as components, so the wrapper had nothing left to add and Forge dropped
        // it: there is no spawn egg class anywhere under net/minecraftforge on 26.2.
        //
        // A rename rather than a rebase, because what mods do with it is name the type: a class
        // literal, a field, a call to its static factory. A rebase only reaches the extends slot
        // and would leave every one of those still pointing at a class that is gone. The limit of
        // the rename is the other direction: a subclass calling the old four-argument constructor
        // finds no such constructor on vanilla's SpawnEggItem and still needs a port.
        transformer.registerClassRedirect(
                "net/minecraftforge/common/ForgeSpawnEggItem",
                "net/minecraft/world/item/SpawnEggItem");
    }
}
