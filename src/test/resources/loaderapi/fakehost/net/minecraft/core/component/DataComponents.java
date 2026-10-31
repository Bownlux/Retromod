package net.minecraft.core.component;

import net.minecraft.world.item.component.Compostable;
import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.food.VillagerFood;

public final class DataComponents {
    public static final DataComponentType<Compostable> COMPOSTABLE = new DataComponentType<>("compostable");
    public static final DataComponentType<CookingFuel> COOKING_FUEL = new DataComponentType<>("cooking_fuel");
    public static final DataComponentType<VillagerFood> VILLAGER_FOOD = new DataComponentType<>("villager_food");
    private DataComponents() {}
}
