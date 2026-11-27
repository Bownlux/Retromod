// Compile-only stub of a Fabric API 26.x host type for src/legacyapi; never packaged.
package net.fabricmc.fabric.api.client.model.loading.v1;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4fc;

public final class ModelModifier {
    public static final Identifier OVERRIDE_PHASE = null;
    public static final Identifier DEFAULT_PHASE = null;
    public static final Identifier WRAP_PHASE = null;
    public static final Identifier WRAP_LAST_PHASE = null;

    private ModelModifier() {}

    @FunctionalInterface
    public interface OnLoad {
        UnbakedModel modifyModelOnLoad(UnbakedModel model, Context context);

        interface Context {
            Identifier id();
        }
    }

    @FunctionalInterface
    public interface OnLoadBlock {
        BlockStateModel.UnbakedRoot modifyModelOnLoad(BlockStateModel.UnbakedRoot model, Context context);

        interface Context {
            BlockState state();
        }
    }

    @FunctionalInterface
    public interface BeforeBakeBlock {
        BlockStateModel.UnbakedRoot modifyModelBeforeBake(BlockStateModel.UnbakedRoot model, Context context);

        interface Context {
            BlockState state();
            ModelBaker baker();
        }
    }

    @FunctionalInterface
    public interface AfterBakeBlock {
        BlockStateModel modifyModelAfterBake(BlockStateModel model, Context context);

        interface Context {
            BlockState state();
            BlockStateModel.UnbakedRoot sourceModel();
            ModelBaker baker();
        }
    }

    @FunctionalInterface
    public interface BeforeBakeItem {
        ItemModel.Unbaked modifyModelBeforeBake(ItemModel.Unbaked model, Context context);

        interface Context {
            Identifier itemId();
            ItemModel.BakingContext bakingContext();
            Matrix4fc transformation();
        }
    }

    @FunctionalInterface
    public interface AfterBakeItem {
        ItemModel modifyModelAfterBake(ItemModel model, Context context);

        interface Context {
            Identifier itemId();
            ItemModel.Unbaked sourceModel();
            ItemModel.BakingContext bakingContext();
            Matrix4fc transformation();
        }
    }
}
