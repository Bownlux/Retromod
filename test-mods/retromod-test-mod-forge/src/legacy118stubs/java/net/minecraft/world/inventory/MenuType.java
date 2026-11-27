package net.minecraft.world.inventory;

/** Compile-only: 1.18.2's menu type, built from a supplier alone and named through its registry entry base. */
public class MenuType<T extends AbstractContainerMenu> extends net.minecraftforge.registries.ForgeRegistryEntry<MenuType<?>> {
    public MenuType(MenuSupplier<T> supplier) {
    }

    public interface MenuSupplier<T extends AbstractContainerMenu> {
        T create(int id, net.minecraft.world.entity.player.Inventory inventory);
    }
}
