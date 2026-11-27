/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.retromod.testmod.forge.legacy118.Era118Cases;
import com.retromod.testmod.forge.legacy118.Era118Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Forge 1.18.2 shapes from the legacy118 source set (#408, #412): checked at common setup, once the registries are filled. */
final class Era118Checks {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-Forge");
    private static final String MOD_ID = "retromod_test_mod_forge";
    static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);

    private Era118Checks() {}

    /** Registers the 1.18 disc and registry handler on a Forge 1.x host, in a translated copy only. */
    static void register(IEventBus modBus) {
        // Only a translated copy links them; on Forge 1.20.1 the mod translates from retromod-input/.
        if (!onForgeBeforeTwentySix() || !translated()) return;
        // 1.21 removed RecordItem.
        if (hostHas("net.minecraft.world.item.RecordItem")) ITEMS.register("legacy_disc", () -> (Item) Era118Cases.disc());
        ITEMS.register(modBus);
        modBus.register(Era118Registry.class);
        // Common setup comes after every registry event, and before a server start that the test library can stop on Forge 1.20.1.
        modBus.addListener(Era118Checks::check);
    }

    /** #412: new MenuType(supplier) builds a menu type on any host; an untranslated copy has nothing to check. */
    static boolean menuTypeBuilds() {
        return !translated() || Era118Cases.menuType() != null;
    }

    /** #408: an IItemRenderProperties class implements the host's client item extensions once translated. */
    static boolean renderPropertiesAreClientExtensions() throws ClassNotFoundException {
        if (!translated()) return true;
        for (String name : Era118Cases.renderPropertiesInterfaces()) {
            if (name.endsWith(".IClientItemExtensions")) return true;
        }
        return false;
    }

    private static void check(FMLCommonSetupEvent event) {
        // The old registry event runs on Forge 1.20 to 1.21.5, whose event bus Retromod can post its stand-in on.
        if (hostHas("net.minecraftforge.eventbus.api.Event")) {
            boolean menu = BuiltInRegistries.MENU.containsKey(new ResourceLocation(MOD_ID, "legacy_menu"));
            LOG.info("[Retromod-Test-Forge] #412 a RegistryEvent.Register handler registers its menu (common setup): {}",
                    menu ? "success" : "fail: the menu is not in the registry");
        }
        if (hostHas("net.minecraft.world.item.RecordItem")) {
            boolean disc = ForgeRegistries.ITEMS.containsKey(new ResourceLocation(MOD_ID, "legacy_disc"));
            LOG.info("[Retromod-Test-Forge] #408 a 1.18.2 RecordItem(int, Supplier, Properties) disc registers (common setup): {}",
                    disc ? "success" : "fail: the disc is not in the registry");
        }
    }

    private static boolean onForgeBeforeTwentySix() {
        return !LegacyEventBaseCase.onNeoForge()
                && net.minecraftforge.fml.loading.FMLLoader.versionInfo().mcVersion().startsWith("1.");
    }

    /** Whether this copy is Retromod's translation: served from its cache, or rewritten under a -retromod name. */
    private static boolean translated() {
        String location = String.valueOf(Era118Checks.class.getProtectionDomain().getCodeSource().getLocation());
        return location.contains("retromod-cache") || location.contains("-retromod");
    }

    private static boolean hostHas(String className) {
        try {
            Class.forName(className, false, Era118Checks.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
