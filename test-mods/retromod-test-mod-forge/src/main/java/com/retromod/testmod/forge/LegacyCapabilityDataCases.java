/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.LoggerFactory;

/** Capability and handler data saved with Forge 1.20.1's NBT methods, which Forge 26.2 passes registries to, and an item's old hook. */
final class LegacyCapabilityDataCases {

    private static final String MOD_ID = "retromod_test_mod_forge";
    static final Capability<IStoredCounter> STORED = CapabilityManager.get(new CapabilityToken<>() {});
    private static final ResourceLocation KEY = new ResourceLocation(MOD_ID, "stored_counter");
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    static final RegistryObject<Item> POUCH = ITEMS.register("legacy_pouch", () -> new PouchItem(new Item.Properties()));

    private LegacyCapabilityDataCases() {}

    /** One number a capability saves. */
    interface IStoredCounter {
        int get();

        void set(int value);
    }

    static void register(IEventBus modBus) {
        try {
            ITEMS.register(modBus);
            // The armor stand's provider is attached as Forge 1.20.1 mods attach theirs, then saved with the entity.
            MinecraftForge.EVENT_BUS.addGenericListener(Entity.class, LegacyCapabilityDataCases::attach);
            MinecraftForge.EVENT_BUS.addListener(LegacyCapabilityDataCases::check);
        } catch (Throwable t) {
            log("Old capability data cases register (construction)", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void attach(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof ArmorStand) event.addCapability(KEY, new StoredCounterProvider());
    }

    // Stacks, registries and entities exist once a server runs, so the results are in the log.
    private static void check(ServerStartedEvent event) {
        run("An ItemStackHandler saved with serializeNBT() and loaded with deserializeNBT(tag) keeps its stack (server started)", () -> {
            ItemStackHandler handler = new ItemStackHandler(2);
            handler.setStackInSlot(1, new ItemStack(Items.APPLE, 3));
            ItemStackHandler copy = new ItemStackHandler(2);
            copy.deserializeNBT(handler.serializeNBT());
            ItemStack back = copy.getStackInSlot(1);
            return back.is(Items.APPLE) && back.getCount() == 3 ? "success" : "fail: read " + back;
        });
        run("A handler subclass's old serializeNBT and deserializeNBT overrides run when it is saved as an ItemStackHandler (server started)", () -> {
            TaggedHandler tagged = new TaggedHandler();
            tagged.marker = 5;
            ItemStackHandler saved = tagged;
            TaggedHandler loaded = new TaggedHandler();
            ((ItemStackHandler) loaded).deserializeNBT(saved.serializeNBT());
            return loaded.marker == 5 ? "success" : "fail: read " + loaded.marker;
        });
        run("An item's initCapabilities gives its stack ITEM_HANDLER (server started)", () -> {
            int slots = new ItemStack(POUCH.get()).getCapability(ForgeCapabilities.ITEM_HANDLER).map(IItemHandler::getSlots).orElse(-1);
            return slots == 1 ? "success" : "fail: read " + slots + " slots";
        });
        // Saving and loading an entity crosses Forge's capability dispatcher in both directions.
        run("An armor stand's capability set to 7, saved with serializeNBT() and loaded into a new one with deserializeNBT(tag), reads 7 (server started)", () -> {
            ServerLevel level = event.getServer().overworld();
            ArmorStand first = EntityType.ARMOR_STAND.create(level);
            if (first == null || !first.getCapability(STORED).isPresent()) return "fail: no capability was attached";
            first.getCapability(STORED).ifPresent(counter -> counter.set(7));
            CompoundTag saved = first.serializeNBT();
            ArmorStand second = EntityType.ARMOR_STAND.create(level);
            second.deserializeNBT(saved);
            int value = second.getCapability(STORED).map(IStoredCounter::get).orElse(-1);
            return value == 7 ? "success" : "fail: read " + value;
        });
    }

    /** Adds a marker beside the stacks, as Quark's and Create's handlers add their own data. */
    static final class TaggedHandler extends ItemStackHandler {
        int marker;

        TaggedHandler() {
            super(1);
        }

        @Override
        public CompoundTag serializeNBT() {
            CompoundTag tag = super.serializeNBT();
            tag.putInt("Marker", marker);
            return tag;
        }

        @Override
        public void deserializeNBT(CompoundTag tag) {
            super.deserializeNBT(tag);
            marker = tag.getInt("Marker");
        }
    }

    /** An item whose stacks carry a one-slot item handler, from the hook Forge 26.2 replaced. */
    static final class PouchItem extends Item {
        PouchItem(Properties properties) {
            super(properties);
        }

        @Override
        public ICapabilityProvider initCapabilities(ItemStack stack, CompoundTag nbt) {
            LazyOptional<IItemHandler> handler = LazyOptional.of(() -> new ItemStackHandler(1));
            return new ICapabilityProvider() {
                @Override
                public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                    return ForgeCapabilities.ITEM_HANDLER.orEmpty(capability, handler);
                }
            };
        }
    }

    /** Saves its counter with the old serializeNBT() and reads it back with deserializeNBT(tag). */
    private static final class StoredCounterProvider implements ICapabilitySerializable<CompoundTag> {
        private final Counter counter = new Counter();
        private final LazyOptional<IStoredCounter> holder = LazyOptional.of(() -> counter);

        @Override
        public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
            return STORED.orEmpty(capability, holder);
        }

        @Override
        public CompoundTag serializeNBT() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Value", counter.get());
            return tag;
        }

        @Override
        public void deserializeNBT(CompoundTag tag) {
            counter.set(tag.getInt("Value"));
        }
    }

    private static final class Counter implements IStoredCounter {
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

    private interface Check {
        String run() throws Exception;
    }

    private static void run(String what, Check check) {
        String result;
        try {
            result = check.run();
        } catch (Throwable t) {
            result = "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        log(what, result);
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }
}
