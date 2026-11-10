/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockElementRotation;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.geometry.IGeometryBakingContext;
import net.minecraftforge.client.model.geometry.IGeometryLoader;
import net.minecraftforge.client.model.geometry.IUnbakedGeometry;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.joml.Vector3f;
import org.slf4j.LoggerFactory;

/** Forge 1.20.1's geometry loaders, which Forge 26.2 changed: one loader whose model nests a vanilla model, as lootr's barrel does. Client only. */
final class GeometryLoaderCases {

    private static final ResourceLocation MODEL = new ResourceLocation("retromod_test_mod_forge", "block/geometry_test");

    private static volatile boolean registered;
    private static volatile boolean read;
    private static volatile String baked = "the geometry did not bake";
    private static volatile String elements = "the geometry did not bake";
    private static boolean reported;

    private GeometryLoaderCases() {}

    static void register(IEventBus modBus) {
        try {
            modBus.addListener(GeometryLoaderCases::onRegisterGeometryLoaders);
            // Only models something names are baked, so the test model is registered on its own.
            modBus.addListener(GeometryLoaderCases::onRegisterAdditional);
            MinecraftForge.EVENT_BUS.addListener(GeometryLoaderCases::onClientTick);
        } catch (Throwable t) {
            log("Old geometry loader listeners register (construction)", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void onRegisterGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        registered = true;
        event.register("test_loader", new TestLoader());
    }

    private static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        event.register(MODEL);
    }

    // Forge 26.2 bakes models while the first client ticks run, so the result is read once a world is loaded.
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || reported || net.minecraft.client.Minecraft.getInstance().level == null) return;
        reported = true;
        log("RegisterGeometryLoaders registers the test loader", registered ? "success" : "fail: the listener did not run");
        log("The test loader reads block/geometry_test (first client tick in a world)",
                read ? "success" : "fail: the loader did not read the model");
        log("The test loader's geometry bakes with its nested model and that model's three parents (first client tick in a world)",
                baked == null ? "success" : "fail: " + baked);
        log("Old geometry code reads a vanilla model's elements and builds its own (first client tick in a world)",
                elements == null ? "success" : "fail: " + elements);
    }

    private static final class TestLoader implements IGeometryLoader<TestGeometry> {
        @Override
        public TestGeometry read(JsonObject json, JsonDeserializationContext context) {
            read = true;
            return new TestGeometry(context.deserialize(json.getAsJsonObject("inner"), BlockModel.class));
        }
    }

    private static final class TestGeometry implements IUnbakedGeometry<TestGeometry> {
        private final UnbakedModel inner;

        TestGeometry(UnbakedModel inner) {
            this.inner = inner;
        }

        @Override
        public void resolveParents(Function<ResourceLocation, UnbakedModel> getter, IGeometryBakingContext context) {
            inner.resolveParents(getter);
        }

        @Override
        public BakedModel bake(IGeometryBakingContext context, ModelBaker baker, Function<Material, TextureAtlasSprite> sprites,
                ModelState state, ItemOverrides overrides, ResourceLocation location) {
            try {
                TextureAtlasSprite particle = sprites.apply(context.getMaterial("particle"));
                elements = readElements(inner);
                BakedModel model = inner.bake(baker, sprites, state, location);
                List<BakedQuad> quads = new ArrayList<>(model.getQuads(null, null, RandomSource.create(42L)));
                quads.addAll(model.getQuads(null, Direction.UP, RandomSource.create(42L)));
                // Where the nested model or a parent fails, the missing model's purple and black quads stand in.
                boolean missing = quads.stream().anyMatch(quad -> quad.getSprite().contents().name().getPath().equals("missingno"));
                baked = particle == null ? "no particle sprite" : quads.isEmpty() ? "the nested model has no quads"
                        : missing ? "the missing model stands in for the nested model or its parents" : null;
                return model;
            } catch (Throwable t) {
                baked = t.getClass().getSimpleName() + ": " + t.getMessage();
                throw t;
            }
        }
    }

    // Sophisticated Backpacks reads the elements of the models its backpack builds on, as DynamicTrees builds elements of its own.
    private static String readElements(UnbakedModel inner) {
        try {
            List<BlockElement> found = ((BlockModel) inner).getElements();
            if (found.isEmpty()) return "cube_all's parents gave no elements";
            BlockElement cube = found.get(0);
            if (cube.from.x() != 0 || cube.to.y() != 16 || cube.faces.size() != 6 || cube.rotation != null || !cube.shade) {
                return "the cube element read from " + cube.from + " to " + cube.to + " with " + cube.faces.size()
                        + " faces, rotation " + cube.rotation + ", shade " + cube.shade;
            }
            BlockElementFace north = cube.faces.get(Direction.NORTH);
            if (north == null || north.cullForDirection != Direction.NORTH || north.tintIndex != -1) {
                return "the cube's north face read " + (north == null ? "null" : north.cullForDirection + " tint " + north.tintIndex);
            }
            // block/cube names no "uv", so 1.20.1 filled the corners in from the element's bounds.
            float[] corners = north.uv.uvs;
            if (corners == null || corners[0] != 0 || corners[1] != 0 || corners[2] != 16 || corners[3] != 16) {
                return "the cube's north face, loaded without uv, read corners " + java.util.Arrays.toString(corners);
            }
            BlockElementRotation turn = new BlockElementRotation(new Vector3f(8, 8, 8), Direction.Axis.Y, 22.5f, true);
            BlockElementFace face = new BlockElementFace(null, 2, "#all", new BlockFaceUV(new float[]{0, 0, 16, 8}, 90));
            BlockElement built = new BlockElement(new Vector3f(1, 2, 3), new Vector3f(4, 5, 6), Map.of(Direction.UP, face), turn, false);
            BlockElementFace up = built.faces.get(Direction.UP);
            if (built.rotation.angle() != 22.5f || built.rotation.axis() != Direction.Axis.Y || built.rotation.origin().y() != 8
                    || built.shade || built.to.z() != 6) {
                return "a built element read back rotation " + built.rotation + ", shade " + built.shade + ", to " + built.to;
            }
            if (up.tintIndex != 2 || !up.texture.equals("#all") || up.uv.rotation != 90 || up.uv.getU(0) != 0 || up.uv.getU(2) != 16) {
                return "a built face read back tint " + up.tintIndex + ", texture " + up.texture + ", uv rotation " + up.uv.rotation;
            }
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }
}
