package net.minecraft.world.entity.npc.villager;

import net.minecraft.resources.ResourceKey;

import java.util.Map;

public final class VillagerProfession {
    private final Map<Integer, ResourceKey<?>> trades;
    public VillagerProfession(Map<Integer, ResourceKey<?>> trades) { this.trades = trades; }
    public ResourceKey<?> getTrades(int level) { return trades.get(level); }
}
