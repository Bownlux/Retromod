/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.fabric.client.rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemRenderer;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemRendererCalls;
import com.retromod.legacyapi.minecraft.client.renderer.LegacyItemRendererModel;
import com.retromod.shim.common.embedded.MultiBufferSource;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/** This mod's builtin item renderers, drawn through an item model that wraps each registered item's baked model. */
final class LegacyBuiltinItemRenderers implements LegacyBuiltinItemRendererRegistry {

    static final LegacyBuiltinItemRenderers INSTANCE = new LegacyBuiltinItemRenderers();

    // Each registered item's renderer, held as the item renderer the shared item model runs.
    private final Map<Item, Adapter> renderers = new ConcurrentHashMap<>();
    private final AtomicBoolean installed = new AtomicBoolean();
    // Where Retromod's item definitions put a builtin item's old display transforms, under the item's own id.
    private static final String TRANSFORMS = "item/retromod/item_renderer/";

    private LegacyBuiltinItemRenderers() {}

    @Override
    public void register(Item item, LegacyBuiltinItemRenderer renderer) {
        register((ItemLike) item, renderer);
    }

    @Override
    public void register(ItemLike item, LegacyBuiltinItemRenderer renderer) {
        Objects.requireNonNull(renderer, "renderer is null");
        register(item, (stack, mode, matrices, buffers, light, overlay) -> renderer.render(stack, matrices, buffers, light, overlay));
    }

    @Override
    public void register(ItemLike item, DynamicItemRenderer renderer) {
        Objects.requireNonNull(item, "item is null");
        Objects.requireNonNull(renderer, "renderer is null");
        Item key = Objects.requireNonNull(item.asItem(), "item is null");
        if (renderers.putIfAbsent(key, new Adapter(renderer)) != null) {
            throw new IllegalArgumentException("Item " + BuiltInRegistries.ITEM.getKey(key) + " already has a builtin renderer!");
        }
        install();
    }

    @Override
    public DynamicItemRenderer get(ItemLike item) {
        Adapter adapter = renderers.get(Objects.requireNonNull(item, "item is null").asItem());
        return adapter == null ? null : adapter.renderer;
    }

    // Fabric calls client registrations before the first resource load, so the model wrapping is in place for it.
    private void install() {
        if (!installed.compareAndSet(false, true)) return;
        // The item model asks this copy for a stack's renderer through an extensions object, as on Forge.
        LegacyItemRendererCalls.useExtensions(stack -> {
            Adapter adapter = renderers.get(stack.getItem());
            return adapter == null ? null : new Extensions(adapter);
        });
        ModelLoadingPlugin.register(context -> context.modifyItemModelAfterBake().register(this::wrap));
    }

    // A registered item's baked model runs its renderer, with the old display transforms Retromod wrote for the item.
    private ItemModel wrap(ItemModel model, ModelModifier.AfterBakeItem.Context context) {
        Identifier id = context.itemId();
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == null || !renderers.containsKey(item)) return model;
        ModelBaker baker = context.bakingContext().blockModelBaker();
        ResolvedModel transforms = baker.getModel(Identifier.fromNamespaceAndPath(id.getNamespace(), TRANSFORMS + id.getPath()));
        ModelRenderProperties properties = ModelRenderProperties.fromResolvedModel(baker, transforms, transforms.getTopTextureSlots());
        // The baked definition, the flat icon where the item had one, draws where the renderer fails.
        return LegacyItemRendererModel.wrap(properties, model, context.transformation());
    }

    /** What the item model reads as a stack's client extensions: one getter for the renderer. */
    static final class Extensions {
        private final Adapter renderer;

        Extensions(Adapter renderer) {
            this.renderer = renderer;
        }

        public LegacyItemRenderer getCustomRenderer() {
            return renderer;
        }
    }

    /** A registered renderer as the item renderer the shared item model runs. */
    static final class Adapter extends LegacyItemRenderer implements LegacyItemRendererModel.DelegatingRenderer {
        final DynamicItemRenderer renderer;

        Adapter(DynamicItemRenderer renderer) {
            this.renderer = renderer;
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext displayContext, PoseStack poseStack, MultiBufferSource bufferSource,
                int packedLight, int packedOverlay) {
            renderer.render(stack, displayContext, poseStack, bufferSource, packedLight, packedOverlay);
        }

        @Override
        public Object delegate() {
            return renderer;
        }
    }
}
