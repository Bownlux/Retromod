/*
 * Retromod Test Mod (NeoForge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.neoforge;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An entity type built inside a deferred register's supplier from its bare name. From 1.21.2 the
 * type takes its translation key and loot table from the key it is built with, and the bare name
 * alone put it in Minecraft's namespace. The result is logged once registration is over.
 */
public final class EntityTypeKeys {

    private static final Logger LOG = LoggerFactory.getLogger("Retromod-Test-NeoForge");
    private static final String MOD_ID = "retromod_test_mod_neoforge";

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);
    private static final DeferredHolder<EntityType<?>, EntityType<Marker>> KEY_ENTITY = register("key_entity",
            EntityType.Builder.<Marker>of(Marker::new, MobCategory.MISC));

    private EntityTypeKeys() {}

    private static DeferredHolder<EntityType<?>, EntityType<Marker>> register(String name,
                                                                              EntityType.Builder<Marker> builder) {
        return ENTITY_TYPES.register(name, () -> builder.build(name));
    }

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
        modBus.addListener(EntityTypeKeys::check);
    }

    private static void check(FMLCommonSetupEvent event) {
        String result;
        try {
            String key = KEY_ENTITY.get().getDescriptionId();
            result = key.equals("entity." + MOD_ID + ".key_entity")
                    ? "success" : "fail: built under another id: " + key;
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOG.info("[Retromod-Test-NeoForge] an entity type built from its bare name in a deferred register gets the registered id (common setup): {}",
                result);
    }
}
