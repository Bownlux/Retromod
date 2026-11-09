// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.renderer.special;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.function.Consumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3fc;

public interface SpecialModelRenderer<T> {
    void submit(T argument, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, int lightCoords, int overlayCoords,
            boolean hasFoil, int outlineColor);
    void getExtents(Consumer<Vector3fc> output);
    T extractArgument(ItemStack stack);
}
