/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26;

import com.retromod.core.RetromodTransformer;
import com.retromod.loaderapi.LoaderApiRegistration;
import com.retromod.shim.common.RebuiltApiClasses;

import java.util.List;

/** Registers the rebuilt classes of batches D1, D2, V0 and R2 and the redirects their rename table rows cannot say. */
public final class ModelRenderRows {

    static final String RENDERER = RebuiltApiClasses.ROOT + "fabric/client/renderer/";
    static final String MODEL = RebuiltApiClasses.ROOT + "fabric/client/model/";
    static final String FLUID = RebuiltApiClasses.ROOT + "fabric/client/render/fluid/";
    static final String BLOCK_STATE_MODEL = "net/minecraft/client/renderer/block/dispatch/BlockStateModel";
    static final String PLUGIN = "net/fabricmc/fabric/api/client/model/loading/v1/ModelLoadingPlugin";
    static final String PLUGIN_CONTEXT = PLUGIN + "$Context";
    static final String EVENT = "net/fabricmc/fabric/api/event/Event";
    static final String FLUID_HANDLER = "net/fabricmc/fabric/api/client/render/fluid/v1/FluidRenderHandler";
    static final String OLD_RENDER_CONTEXT = "net/fabricmc/fabric/api/renderer/v1/render/RenderContext";

    /** The rebuilt classes the rows name; each brings in the rebuilt classes it uses. */
    static final List<String> ROOTS = List.of(
            RENDERER + "LegacyRendererAccess", RENDERER + "LegacyRenderer", RENDERER + "LegacyRenderers", RENDERER + "LegacyRenderContexts",
            RENDERER + "LegacyFabricModels", RENDERER + "LegacyFabricBakedModel", RENDERER + "LegacyForwardingBakedModel",
            RENDERER + "LegacyQuadViews", RENDERER + "LegacyBakedModelConsumer", RENDERER + "LegacyMaterialView",
            RENDERER + "LegacyShadeMode",
            MODEL + "LegacyModelLoading", MODEL + "LegacyModelLoadingRegistry", MODEL + "LegacyModelLoadingPlugin",
            MODEL + "LegacyUnbakedModels", MODEL + "LegacyModelResolver", MODEL + "LegacyBeforeBake", MODEL + "LegacyAfterBake",
            MODEL + "LegacyModelProviders",
            FLUID + "LegacyFluidHandlerRegistry", FLUID + "LegacyFluidRenders", FLUID + "LegacySimpleFluidRenderHandler");

    /** Mods built before 1.21.5 implemented the old BakedModel, which the remap turns into BlockStateModel. */
    static final String FIRST_WITHOUT_BAKED_MODEL = "1.21.5";
    /** 1.21.2 renamed the plugin's method to {@code initialize}. */
    static final String FIRST_WITH_INITIALIZE = "1.21.2";
    /** 1.21.4 split block state models out of {@code modifyModelOnLoad}, which kept its name. */
    static final String FIRST_WITH_BLOCK_ON_LOAD = "1.21.4";

    private ModelRenderRows() {}

    public static void register(LoaderApiRegistration registration) {
        for (String root : ROOTS) FabricApi26Rows.shipRebuilt(registration, root);
        RetromodTransformer transformer = registration.transformer();

        // An old mod's baked model gets the 26.x block model members from its old ones. Only the implements list is
        // claimed: the Minecraft side redirects old calls keyed on BlockStateModel, and a type claim would clear them.
        registration.replaceImplementedInterfaceForModsBefore(BLOCK_STATE_MODEL, RENDERER + "LegacyFabricBakedModel",
                FIRST_WITHOUT_BAKED_MODEL);

        // An old plugin class gets initialize from its onInitializeModelLoader; a lambda or call is renamed.
        registration.replaceImplementedInterfaceForModsBefore(PLUGIN, MODEL + "LegacyModelLoadingPlugin", FIRST_WITH_INITIALIZE);
        String initialize = "(L" + PLUGIN_CONTEXT + ";)V";
        registration.redirectMethod(PLUGIN, "onInitializeModelLoader", initialize, PLUGIN, "initialize", initialize, false);
        transformer.registerInterfaceMethodRename(PLUGIN, "onInitializeModelLoader", "initialize");

        // modifyModelOnLoad kept its name on 26.x but lost the block state models, so only older mods get the old event.
        String onLoad = "()L" + EVENT + ";";
        registration.redirectMethod(PLUGIN_CONTEXT, "modifyModelOnLoad", onLoad, MODEL + "LegacyModelLoading", "modifyModelOnLoad",
                "(L" + PLUGIN_CONTEXT + ";)L" + EVENT + ";", true);
        transformer.limitRedirectToModsBefore(PLUGIN_CONTEXT, "modifyModelOnLoad", onLoad, FIRST_WITH_BLOCK_ON_LOAD);

        // An old fluid handler's super.renderFluid reaches the old default, which must not run its override again.
        String render = "(L" + FLUID_HANDLER + ";Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
                + "Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/block/state/BlockState;"
                + "Lnet/minecraft/world/level/material/FluidState;)V";
        transformer.registerSuperCallHelper(FLUID + "LegacyFluidRenders", "renderFluid", render, "superRenderFluid");
    }

    /** Registers what must follow the rename table's rows: limits on calls those rows claim, which a claim would clear. */
    public static void registerAfterRows(LoaderApiRegistration registration) {
        RetromodTransformer transformer = registration.transformer();
        // Only old mods' transform calls are counted. A call is matched under the old context's name and, after the
        // rename, under the host emitter's, so each limit is keyed on both.
        String push = "(Lnet/fabricmc/fabric/api/client/renderer/v1/mesh/QuadTransform;)V";
        for (String owner : List.of(OLD_RENDER_CONTEXT, "net/fabricmc/fabric/api/client/renderer/v1/mesh/QuadEmitter")) {
            transformer.limitRedirectToModsBefore(owner, "pushTransform", push, FIRST_WITHOUT_BAKED_MODEL);
            transformer.limitRedirectToModsBefore(owner, "popTransform", "()V", FIRST_WITHOUT_BAKED_MODEL);
        }
    }
}
