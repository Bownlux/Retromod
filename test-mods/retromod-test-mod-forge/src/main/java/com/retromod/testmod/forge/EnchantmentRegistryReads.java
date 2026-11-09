/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads {@code ForgeRegistries.ENCHANTMENTS}, which Forge 26.2 removed when enchantments moved to data packs. */
final class EnchantmentRegistryReads {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");

    private EnchantmentRegistryReads() {}

    /** Looks enchantments up once a server has loaded them; NeoForge maps the Forge field in its own shim. */
    static void register() {
        if (!LegacyEventBaseCase.onNeoForge()) {
            MinecraftForge.EVENT_BUS.addListener(EnchantmentRegistryReads::checkLookups);
        }
        // BuiltInRegistries.ENCHANTMENT is vanilla, so every host from 1.21 lacks it.
        MinecraftForge.EVENT_BUS.addListener(EnchantmentRegistryReads::checkBuiltInRegistry);
    }

    /** A register built in a static initializer, as Farmer's Delight builds its own, names the enchantment registry and holds an entry. */
    static boolean staticRegisterNamesEnchantments(IEventBus modBus) {
        Registers.ENCHANTMENTS.register(modBus);
        if (!Registers.ENCHANTMENTS.getRegistryName().toString().equals("minecraft:enchantment")) return false;
        // NeoForge maps the field in its own shim, so the entry is a Forge case.
        if (LegacyEventBaseCase.onNeoForge()) return true;
        modBus.addListener(EnchantmentRegistryReads::checkEntryAfterRegistration);
        return !Entries.TEST_ENCHANTMENT.isPresent();
    }

    // Registry events end before common setup, so reaching it shows the entry did not stop mod loading.
    private static void checkEntryAfterRegistration(FMLCommonSetupEvent event) {
        String result;
        try {
            if (Entries.TEST_ENCHANTMENT.isPresent()) {
                result = "fail: an enchantment defined in code is present";
            } else {
                result = getFailsAsNotPresent();
            }
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("[Retromod-Test-Forge] an enchantment registered from code gets past registry events and is not present (common setup): {}",
                result);
    }

    // The entry never binds, so get() throws Forge's own error for an empty registry object.
    private static String getFailsAsNotPresent() {
        try {
            return "fail: get() returned " + Entries.TEST_ENCHANTMENT.get();
        } catch (NullPointerException expected) {
            String message = String.valueOf(expected.getMessage());
            return message.contains("Registry Object not present") ? "success" : "fail: get() threw " + message;
        }
    }

    // The world's enchantments come from data packs, so the lookups wait for the server.
    private static void checkLookups(ServerStartedEvent event) {
        String result;
        try {
            Enchantment sharpness = ForgeRegistries.ENCHANTMENTS.getValue(new ResourceLocation("minecraft", "sharpness"));
            ResourceLocation id = sharpness == null ? null : ForgeRegistries.ENCHANTMENTS.getKey(sharpness);
            result = id != null && id.toString().equals("minecraft:sharpness") ? "success"
                    : "fail: sharpness read back as " + id;
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("[Retromod-Test-Forge] ForgeRegistries.ENCHANTMENTS finds sharpness and names it back (server started): {}",
                result);
    }

    // The vanilla field old mods read, as Saint's Dragons reads it for its trades, then the old enchant call.
    private static void checkBuiltInRegistry(ServerStartedEvent event) {
        String result;
        try {
            Enchantment sharpness = BuiltInRegistries.ENCHANTMENT.get(new ResourceLocation("minecraft", "sharpness"));
            ResourceLocation id = sharpness == null ? null : BuiltInRegistries.ENCHANTMENT.getKey(sharpness);
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            if (sharpness != null) sword.enchant(sharpness, 2);
            int level = sharpness == null ? 0 : EnchantmentHelper.getItemEnchantmentLevel(sharpness, sword);
            result = id == null || !id.toString().equals("minecraft:sharpness") ? "fail: sharpness read back as " + id
                    : level != 2 ? "fail: the sword has sharpness " + level : "success";
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("[Retromod-Test-Forge] BuiltInRegistries.ENCHANTMENT finds sharpness, names it back and enchants a sword (server started): {}",
                result);
    }

    /** Holds the register, so a host without the field fails only the check that loads it. */
    private static final class Registers {
        static final DeferredRegister<Enchantment> ENCHANTMENTS =
                DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, "retromod_test_mod_forge");
    }

    /** One entry registered from a static initializer; only Forge loads this class. */
    private static final class Entries {
        static final RegistryObject<TestEnchantment> TEST_ENCHANTMENT =
                Registers.ENCHANTMENTS.register("test_enchantment", TestEnchantment::new);
    }

    /** An enchantment defined in code, built only if a host ever registers it. */
    private static final class TestEnchantment extends Enchantment {
        TestEnchantment() {
            super(Enchantment.Rarity.RARE, EnchantmentCategory.WEAPON, new EquipmentSlot[] {EquipmentSlot.MAINHAND});
        }
    }
}
