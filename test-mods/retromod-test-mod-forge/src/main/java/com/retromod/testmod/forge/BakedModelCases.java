/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.LoggerFactory;

/** Forge 1.20.1's baked model API, which Forge 26.2 rebuilt around block and item models: one wrapped block model and one extra model. Client only. */
final class BakedModelCases {

    private static final ResourceLocation EXTRA = new ResourceLocation("retromod_test_mod_forge", "block/cross_class_block");

    private static boolean registered;
    private static String modified = "the ModifyBakingResult listener did not run";
    private static boolean extraFound;
    private static boolean reported;
    private static volatile boolean asked;
    // Patchouli's listener waits for books its own common setup loads, so the event has to come after setup.
    private static volatile boolean commonSetupDone;
    private static volatile Boolean setupDoneAtRegister;

    private BakedModelCases() {}

    static void register(IEventBus modBus) {
        try {
            modBus.addListener(BakedModelCases::onRegisterAdditional);
            modBus.addListener(BakedModelCases::onCommonSetup);
            modBus.addListener(BakedModelCases::onModifyBakingResult);
            modBus.addListener(BakedModelCases::onBakingCompleted);
            MinecraftForge.EVENT_BUS.addListener(BakedModelCases::onClientTick);
        } catch (Throwable t) {
            log("Old baked model listeners register (construction)", "fail: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        commonSetupDone = true;
    }

    // Forge 1.20.1 asked for extra models before it first baked them.
    private static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        if (setupDoneAtRegister == null) setupDoneAtRegister = commonSetupDone;
        registered = true;
        event.register(EXTRA);
    }

    // Wraps the test block's one state by its 1.20.1 key, as mods wrap their blocks to change their quads.
    private static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ResourceLocation, BakedModel> models = event.getModels();
        ModelResourceLocation key = BlockModelShaper.stateToModelLocation(CrossClassBlocks.CROSS_CLASS_BLOCK.defaultBlockState());
        BakedModel original = models.get(key);
        if (original == null) {
            modified = "no model under " + key;
            return;
        }
        models.put(key, new AskedWrapper(original));
        modified = models.get(key) instanceof AskedWrapper ? null : "the put did not read back";
    }

    // The missing model has the purple and black texture; the extra model is the test block's own.
    private static void onBakingCompleted(ModelEvent.BakingCompleted event) {
        BakedModel extra = event.getModelManager().getModel(EXTRA);
        BakedModel missing = event.getModelManager().getMissingModel();
        extraFound = extra != null && extra.getParticleIcon() != null
                && !extra.getParticleIcon().contents().name().equals(missing.getParticleIcon().contents().name());
    }

    // Forge 26.2 bakes models while the first client ticks run, so the results are read once a world is loaded.
    // The wrapper is asked once the block is drawn in that world.
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!reported && Minecraft.getInstance().level != null) {
            reported = true;
            log("RegisterAdditional runs before the first bake", registered ? "success" : "fail: the listener did not run");
            log("RegisterAdditional comes after common setup, which Patchouli's book models wait for", setupDoneAtRegister == null
                    ? "fail: the listener did not run" : setupDoneAtRegister ? "success" : "fail: it came before common setup");
            log("ModifyBakingResult replaces the test block's model by its 1.20.1 key", modified == null ? "success" : "fail: " + modified);
            log("ModelManager.getModel finds the extra model after the bake", extraFound ? "success" : "fail: the missing model");
        }
        if (asked && Minecraft.getInstance().level != null) {
            asked = false;
            log("Minecraft asks the BakedModelWrapper for the test block's quads (place the block to see this)", "success");
        }
    }

    private static final class AskedWrapper extends BakedModelWrapper<BakedModel> {
        private boolean noted;

        AskedWrapper(BakedModel original) {
            super(original);
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType renderType) {
            if (!noted) {
                noted = true;
                asked = true;
            }
            return super.getQuads(state, side, random, data, renderType);
        }
    }

    private static void log(String what, String result) {
        LoggerFactory.getLogger("Retromod-Test-Forge").info("[Retromod-Test-Forge] {}: {}", what, result);
    }
}
