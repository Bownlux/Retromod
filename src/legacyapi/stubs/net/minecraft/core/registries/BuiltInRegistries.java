// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.core.registries;

import net.minecraft.core.DefaultedRegistry;
import net.minecraft.world.level.block.Block;

public class BuiltInRegistries {
    public static final DefaultedRegistry<net.minecraft.world.entity.npc.villager.VillagerType> VILLAGER_TYPE = null;
    public static final DefaultedRegistry<Block> BLOCK = null;
    public static final DefaultedRegistry<net.minecraft.world.entity.EntityType<?>> ENTITY_TYPE = null;
    public static final net.minecraft.core.component.DataComponentInitializers DATA_COMPONENT_INITIALIZERS = null;
    public static final net.minecraft.core.Registry<net.minecraft.world.level.gamerules.GameRule<?>> GAME_RULE = null;
    public static final net.minecraft.core.DefaultedRegistry<net.minecraft.world.level.gameevent.GameEvent> GAME_EVENT = null;
    public static <T> net.minecraft.core.HolderGetter<T> acquireBootstrapRegistrationLookup(net.minecraft.core.Registry<T> registry) { throw new UnsupportedOperationException(); }
    private BuiltInRegistries() {}
    public static final net.minecraft.core.DefaultedRegistry<net.minecraft.world.item.Item> ITEM = null;
    public static final net.minecraft.core.Registry<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITY_TYPE = null;
}
