// Compile-only stub of a 1.21.11 to 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.model;

import java.util.function.Function;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

public abstract class EntityModel<T extends EntityRenderState> extends Model<T> {
    protected EntityModel(ModelPart root) { super(root, null); }
    protected EntityModel(ModelPart root, Function<Identifier, RenderType> renderType) { super(root, renderType); }
}
