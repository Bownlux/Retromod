/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemModels;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Minecraft 26.2's item model for an item an old mod drew with BlockEntityWithoutLevelRenderer: it runs that renderer. */
public final class LegacyItemRendererModel implements ItemModel {

    // The old renderer drew in the unit block, which the display transform then centred.
    private static final Vector3fc[] EXTENTS = {
        new Vector3f(0f, 0f, 0f), new Vector3f(1f, 0f, 0f), new Vector3f(0f, 1f, 0f), new Vector3f(1f, 1f, 0f),
        new Vector3f(0f, 0f, 1f), new Vector3f(1f, 0f, 1f), new Vector3f(0f, 1f, 1f), new Vector3f(1f, 1f, 1f)};
    private static final Supplier<Vector3fc[]> EXTENTS_SUPPLIER = () -> EXTENTS;
    private static final SpecialModelRenderer<Draw> RENDERER = new Renderer();
    private static final Object NONE = new Object();
    // Renderer classes that threw, whose items draw their fallback until the next resource reload, as the error would
    // repeat every frame. One renderer often draws dozens of items, which all stop together.
    private static final Set<Class<?>> BROKEN = ConcurrentHashMap.newKeySet();

    private final ModelRenderProperties properties;
    private final ItemModel fallback;
    private final Matrix4fc transformation;
    // Forge 1.20.1 asked for the renderer every frame, and some old mods build a new one each time, so it is asked once.
    private final Map<Item, Object> renderers = new ConcurrentHashMap<>();

    LegacyItemRendererModel(ModelRenderProperties properties, ItemModel fallback, Matrix4fc transformation) {
        this.properties = properties;
        this.fallback = fallback;
        this.transformation = transformation;
    }

    @Override
    public void update(ItemStackRenderState renderState, ItemStack stack, ItemModelResolver resolver, ItemDisplayContext displayContext,
            ClientLevel level, ItemOwner owner, int seed) {
        Item item = stack.getItem();
        Object renderer = renderers.get(item);
        if (renderer == null) renderer = renderers.computeIfAbsent(item, any -> rendererOrNone(stack));
        // An item whose renderer is missing or failed shows its flat icon, as before Retromod drew old renderers.
        if (renderer == NONE || BROKEN.contains(renderer.getClass())) {
            if (fallback != null) fallback.update(renderState, stack, resolver, displayContext, level, owner, seed);
            return;
        }
        renderState.appendModelIdentityElement(this);
        // The old renderer drew every frame and may animate, so the GUI keeps no picture of it.
        renderState.setAnimated();
        ItemStackRenderState.LayerRenderState layer = renderState.newLayer();
        properties.applyToLayer(layer, displayContext);
        layer.setLocalTransform(transformation);
        layer.setExtents(EXTENTS_SUPPLIER);
        // The renderer runs when the frame submits, which hands it no display context, so the draw keeps it.
        layer.setupSpecialModel(RENDERER, new Draw(renderer, stack.copy(), displayContext));
    }

    private static Object rendererOrNone(ItemStack stack) {
        // The loader's IClientItemExtensions, Forge's or NeoForge's, which its install named.
        Object extensions = LegacyItemRendererCalls.extensionsOf(stack);
        if (extensions == null) return NONE;
        try {
            Object renderer = LegacyItemRendererCalls.rendererOf(extensions);
            return renderer == null || renderer == LegacyItemRendererCalls.NO_GETTER ? NONE : renderer;
        } catch (RuntimeException | LinkageError failure) {
            // Noted once per properties class, which one mod shares across its items.
            LegacyLog.warnOnce("item renderer properties " + extensions.getClass().getName(), "The old renderer properties "
                    + extensions.getClass().getName() + " threw " + failure + ", so Retromod draws their items' flat icons");
            return NONE;
        }
    }

    /** The item model type path each mod's namespace gets; Retromod's item definitions name it. */
    public static final String TYPE_PATH = "retromod_legacy_item";

    /** {@code <namespace>:retromod_legacy_item}'s codec; each namespace needs its own, since the host maps a codec to one id. */
    public static MapCodec<? extends ItemModel.Unbaked> newType() {
        AtomicReference<MapCodec<Unbaked>> type = new AtomicReference<>();
        type.set(Codec.mapPair(Identifier.CODEC.fieldOf("model"), ItemModels.CODEC.optionalFieldOf("fallback")).xmap(
                pair -> new Unbaked(pair.getFirst(), pair.getSecond(), type.get()),
                unbaked -> Pair.of(unbaked.model(), unbaked.fallback())));
        return type.get();
    }

    @Override
    public String toString() {
        return "LegacyItemRendererModel[" + properties + "]";
    }

    /** {@code {"model": <transforms model>, "fallback": <item model>}}: the old model's display transforms and the flat icon. */
    record Unbaked(Identifier model, Optional<ItemModel.Unbaked> fallback, MapCodec<Unbaked> type) implements ItemModel.Unbaked {

        @Override
        public void resolveDependencies(Resolver resolver) {
            resolver.markDependency(model);
            fallback.ifPresent(unbaked -> unbaked.resolveDependencies(resolver));
        }

        @Override
        public ItemModel bake(BakingContext context, Matrix4fc transformation) {
            // Each resource reload bakes the models again, and a renderer that failed before gets another try.
            BROKEN.clear();
            ModelBaker baker = context.blockModelBaker();
            ResolvedModel resolved = baker.getModel(model);
            ModelRenderProperties properties = ModelRenderProperties.fromResolvedModel(baker, resolved, resolved.getTopTextureSlots());
            ItemModel bakedFallback = fallback.map(unbaked -> unbaked.bake(context, transformation)).orElse(null);
            return new LegacyItemRendererModel(properties, bakedFallback, transformation);
        }
    }

    /** One drawn stack: its renderer, a copy of the stack, and the display context the old renderer was handed. */
    record Draw(Object renderer, ItemStack stack, ItemDisplayContext context) {}

    /** Runs the old renderer when the frame submits the item, then submits what it wrote. */
    private static final class Renderer implements SpecialModelRenderer<Draw> {

        @Override
        public void submit(Draw draw, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, int overlayCoords,
                boolean hasFoil, int outlineColor) {
            if (draw == null) return;
            LegacyItemGeometry geometry = LegacyItemGeometry.begin(poseStack, lightCoords, overlayCoords);
            SubmitNodeCollector drawn = null;
            try {
                LegacyItemRendererCalls.render(draw.renderer(), draw.stack(), draw.context(), geometry.pose(), geometry.buffers(), lightCoords,
                        overlayCoords);
                drawn = collector;
            } catch (Exception | LinkageError failure) {
                // Forge 1.20.1 stopped the game here; a renderer that reaches an API Minecraft removed now draws flat icons.
                Class<?> type = draw.renderer().getClass();
                BROKEN.add(type);
                LegacyLog.warnOnce("item renderer " + type.getName(), "The old item renderer " + type.getName() + " threw " + failure
                        + " while drawing " + draw.stack().getItem() + ", so Retromod draws the flat icons of its items until the"
                        + " next resource reload");
            } finally {
                // What a renderer that threw wrote is dropped, so a half-drawn item never reaches the frame.
                geometry.finish(drawn);
            }
        }

        @Override
        public void getExtents(Consumer<Vector3fc> output) {
            for (Vector3fc corner : EXTENTS) output.accept(corner);
        }

        @Override
        public Draw extractArgument(ItemStack stack) {
            // Never set up by the host: the item model hands each draw over itself.
            return null;
        }
    }
}
