/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.Tags;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge 1.20.1 calls that Forge 26.2 removed or renamed: {@code NetworkHooks.openScreen}, the
 * reach attribute on {@code ForgeMod}, {@code Tags.Items.SHEARS}, the griefing hook, the shield
 * {@code ToolAction} and the enchantment registry key. The menu, tag, attribute and griefing checks
 * need a running server or a joined player, so their results are in the log rather than the summary.
 */
final class ForgeSmallRemovals {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final String MOD_ID = "retromod_test_mod_forge";
    static final BlockPos MENU_POS = new BlockPos(12, 64, -7);

    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, MOD_ID);
    static final RegistryObject<MenuType<PosMenu>> POS_MENU = MENUS.register("pos_menu",
            () -> IForgeMenuType.create(ForgeSmallRemovals::readMenu));
    private static final Set<String> GRIEF_SIDES = ConcurrentHashMap.newKeySet();

    private ForgeSmallRemovals() {}

    static void register(IEventBus modBus) {
        MENUS.register(modBus);
        MinecraftForge.EVENT_BUS.addListener(ForgeSmallRemovals::checkData);
        MinecraftForge.EVENT_BUS.addListener(ForgeSmallRemovals::openOnLogin);
        MinecraftForge.EVENT_BUS.addListener(ForgeSmallRemovals::checkGriefing);
        // The screen that lets the client build the menu exists only on a client.
        if (FMLEnvironment.dist == Dist.CLIENT) modBus.addListener(ForgeSmallRemovalsClient::registerScreen);
    }

    /** Forge 26.2 dropped the shield ability; the default shield set still holds it. */
    static boolean shieldAction() {
        return ToolActions.DEFAULT_SHIELD_ACTIONS.contains(ToolActions.SHIELD_BLOCK);
    }

    /** The enchantment key Forge's key list no longer repeats. */
    static boolean enchantmentKey() {
        return ForgeRegistries.Keys.ENCHANTMENTS.location().toString().equals("minecraft:enchantment");
    }

    /** A renamed tag constant, and one with no c: counterpart that keeps its forge: id. */
    static boolean tagConstants() {
        return Tags.Blocks.STONE != null && Tags.Items.STAINED_GLASS.location().toString().equals("forge:stained_glass");
    }

    /** Every read of the removed reach field is the same registry object, as the old field was. */
    static boolean reachIsOneObject() {
        return ForgeMod.ENTITY_REACH != null && ForgeMod.ENTITY_REACH == ForgeMod.ENTITY_REACH;
    }

    // The attribute and tag data load with the server.
    private static void checkData(ServerStartedEvent event) {
        log("ForgeMod.ENTITY_REACH reads the vanilla reach of 3.0 (server started)",
                () -> ForgeMod.ENTITY_REACH.get().getDefaultValue() == 3.0);
        log("shears are in Tags.Items.SHEARS (server started)", () -> new ItemStack(Items.SHEARS).is(Tags.Items.SHEARS));
    }

    // The server opens the menu with a position the client's factory must read back, then closes it.
    private static void openOnLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        log("NetworkHooks.openScreen with a BlockPos opens the menu (server, on join)", () -> {
            NetworkHooks.openScreen(player, new SimpleMenuProvider((id, inventory, opener) -> new PosMenu(id, inventory),
                    Component.literal("Retromod test")), MENU_POS);
            boolean opened = player.containerMenu instanceof PosMenu;
            player.closeContainer();
            return opened;
        });
    }

    private static PosMenu readMenu(int id, Inventory inventory, FriendlyByteBuf extra) {
        BlockPos pos = extra.readBlockPos();
        log("an IContainerFactory reads back the BlockPos openScreen wrote (client, on join)", () -> pos.equals(MENU_POS));
        return new PosMenu(id, inventory);
    }

    // Mobs ask on both sides; a client level no longer carries the game rule.
    private static void checkGriefing(TickEvent.PlayerTickEvent event) {
        String side = event.side.isClient() ? "client" : "server";
        if (event.phase != TickEvent.Phase.END || !GRIEF_SIDES.add(side)) return;
        log("ForgeEventFactory.getMobGriefingEvent with the player's level allows griefing (" + side + ")",
                () -> ForgeEventFactory.getMobGriefingEvent(event.player.level(), event.player));
    }

    private static void log(String description, Check check) {
        String result;
        try {
            result = check.run() ? "success" : "fail: assertion was false";
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("[Retromod-Test-Forge] {}: {}", description, result);
    }

    @FunctionalInterface
    private interface Check {
        boolean run() throws Throwable;
    }

    /** A one-row chest menu under this mod's own type, so the client builds it through the mod's factory. */
    static final class PosMenu extends ChestMenu {
        PosMenu(int id, Inventory inventory) {
            super(POS_MENU.get(), id, inventory, new SimpleContainer(9), 1);
        }
    }
}
