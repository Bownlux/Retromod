package net.minecraft.client.renderer.item;

import com.mojang.math.Transformation;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

public final class CuboidItemModelWrapper {
    public record Unbaked(Identifier model, Optional<Transformation> transformation, List<ItemTintSource> tints)
            implements ItemModel.Unbaked {
    }
}
