/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.LoggerFactory;

/** A capability registered, attached to an entity and a stack, and read back with Forge 1.20.1's API, which Forge 26.2 reshaped. */
final class LegacyCapabilityCases {

    static final Capability<ICounter> COUNTER = CapabilityManager.get(new CapabilityToken<>() {});
    private static final ResourceLocation KEY = new ResourceLocation("retromod_test_mod_forge", "counter");

    private static int registerEvents;
    private static int entityAttaches;
    private static int stackAttaches;
    // Objects other than entities that reached the Entity listener, which Forge 1.20.1 never sent it.
    private static int wrongObjects;

    private LegacyCapabilityCases() {}

    /** One number a capability holds. */
    interface ICounter {
        int get();

        void set(int value);
    }

    static void register(IEventBus modBus) {
        try {
            modBus.addListener(LegacyCapabilityCases::registerCapabilities);
            modBus.addListener(LegacyCapabilityCases::checkRegistered);
            // As comforts attaches: an object with one @SubscribeEvent AttachCapabilitiesEvent<Entity> listener.
            MinecraftForge.EVENT_BUS.register(new EntityAttacher());
            MinecraftForge.EVENT_BUS.addGenericListener(ItemStack.class, LegacyCapabilityCases::attachToStack);
            MinecraftForge.EVENT_BUS.addListener(LegacyCapabilityCases::check);
        } catch (Throwable t) {
            log("Old capability listeners register (construction)", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        registerEvents++;
        event.register(ICounter.class);
    }

    private static void checkRegistered(FMLCommonSetupEvent event) {
        log("(a) RegisterCapabilitiesEvent reaches a mod-bus listener once and registers its capability (common setup)",
                registerEvents != 1 ? "fail: the listener ran " + registerEvents + " times"
                        : !COUNTER.isRegistered() ? "fail: the capability is not registered" : "success");
    }

    /** The listener object Forge 1.20.1 mods register on the game bus. */
    public static final class EntityAttacher {
        @SubscribeEvent
        public void onAttach(AttachCapabilitiesEvent<Entity> event) {
            if (!(event.getObject() instanceof Entity)) {
                wrongObjects++;
                return;
            }
            entityAttaches++;
            if (event.getObject() instanceof ArmorStand) event.addCapability(KEY, new CounterProvider(7));
        }
    }

    private static void attachToStack(AttachCapabilitiesEvent<ItemStack> event) {
        stackAttaches++;
        if (event.getObject().is(Items.STICK)) event.addCapability(KEY, new CounterProvider(3));
    }

    // Entities and stacks exist once a server runs, so the attach results are in the log.
    private static void check(ServerStartedEvent event) {
        String entity;
        try {
            ArmorStand stand = EntityType.ARMOR_STAND.create(event.getServer().overworld());
            int value = stand == null ? -1 : stand.getCapability(COUNTER).map(ICounter::get).orElse(-1);
            entity = value == 7 ? "success" : "fail: read " + value;
        } catch (Throwable t) {
            entity = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        log("(b) An AttachCapabilitiesEvent<Entity> listener attaches to an armor stand, and getCapability reads 7 back"
                + " (server started)", entity);
        String stack;
        try {
            int value = new ItemStack(Items.STICK).getCapability(COUNTER).map(ICounter::get).orElse(-1);
            stack = value == 3 ? "success" : "fail: read " + value;
        } catch (Throwable t) {
            stack = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        log("(c) addGenericListener(ItemStack.class, ...) attaches to a stick, and getCapability reads 3 back"
                + " (server started)", stack);
        log("(d) The Entity attach listener hears only entities, and the stack listener only stacks (server started)",
                wrongObjects > 0 ? "fail: the Entity listener received " + wrongObjects + " other objects"
                        : entityAttaches == 0 || stackAttaches == 0
                        ? "fail: heard " + entityAttaches + " entities and " + stackAttaches + " stacks" : "success");
    }

    /** Holds a counter set to its start value. */
    private static final class CounterProvider implements ICapabilityProvider {
        private final LazyOptional<ICounter> holder;

        CounterProvider(int start) {
            Counter counter = new Counter();
            counter.set(start);
            holder = LazyOptional.of(() -> counter);
        }

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
            return COUNTER.orEmpty(capability, holder);
        }
    }

    private static final class Counter implements ICounter {
        private int value;

        @Override
        public int get() {
            return value;
        }

        @Override
        public void set(int value) {
            this.value = value;
        }
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }
}
