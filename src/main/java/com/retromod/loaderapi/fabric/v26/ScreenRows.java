/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26;

import com.retromod.core.RedirectKey;
import com.retromod.core.RetromodTransformer;
import com.retromod.loaderapi.LoaderApiRegistration;
import com.retromod.loaderapi.fabric.v26.synth.BoundaryTypeSpec;
import com.retromod.loaderapi.fabric.v26.synth.HostSamSpec;
import com.retromod.loaderapi.fabric.v26.synth.SamBridgeSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Registers batch S1: old HUD callbacks, screen render events, item tooltips and the 1.21.4 to 1.21.8 HUD and debug APIs. */
public final class ScreenRows {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-LoaderApi");
    private static final String API = "net/fabricmc/fabric/api/";
    private static final String SCREEN = "com/retromod/loaderapi/fabric/v26/screen/";
    private static final String SHARED = SCREEN + "shared/";
    private static final String BRIDGES = "com/retromod/loaderapi/fabric/v26/synth/";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String GRAPHICS = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;";
    private static final String DELTA_TRACKER = "Lnet/minecraft/client/DeltaTracker;";
    private static final String IDENTIFIER = "Lnet/minecraft/resources/Identifier;";
    private static final String SCREEN_EVENT_DESC = "(Lnet/minecraft/client/gui/screens/Screen;" + GRAPHICS + "IIF)V";
    private static final String TOOLTIP_V1 = API + "client/item/v1/ItemTooltipCallback";

    static final String HUD_CALLBACK = API + "client/rendering/v1/HudRenderCallback";
    static final String SHARED_HUD_CALLBACK = SHARED + "LegacyHudRenderCallback";
    static final String IDENTIFIED_LAYER = API + "client/rendering/v1/IdentifiedLayer";
    static final String SHARED_IDENTIFIED_LAYER = SHARED + "LegacyIdentifiedLayer";
    static final String IDENTIFIED_LAYER_BRIDGE = BRIDGES + "IdentifiedLayerBridge";
    static final String HUD_LAYER_BRIDGE = BRIDGES + "HudLayerBridge";
    static final String HUD_LAYER_DESC = "(" + GRAPHICS + DELTA_TRACKER + ")V";

    /** Old single-method types a library can name in a signature, each mapped to one shared interface. */
    public static final List<BoundaryTypeSpec> SHARED_TYPES = List.of(
            new BoundaryTypeSpec("net/minecraft/client/gui/LayeredDraw$Layer", SHARED + "LegacyHudLayer",
                    "render", HUD_LAYER_DESC, "renderLegacyHudLayer", "(" + OBJECT + OBJECT + ")V",
                    HUD_LAYER_BRIDGE),
            new BoundaryTypeSpec(API + "client/rendering/v1/HudLayerRegistrationCallback",
                    SHARED + "LegacyHudLayerRegistrationCallback", "register",
                    "(L" + SHARED + "LegacyLayeredDrawerWrapper;)V", "registerLegacyHudLayers",
                    "(" + OBJECT + ")V", BRIDGES + "HudLayerRegistrationCallbackBridge"),
            new BoundaryTypeSpec(API + "client/rendering/v1/GatherDebugTextEvents$Left",
                    SHARED + "LegacyGatherDebugTextLeft", "onGatherLeftDebugText", "(Ljava/util/List;)V",
                    "gatherLegacyLeftDebugText", "(Ljava/util/List;)V", BRIDGES + "GatherDebugTextLeftBridge"),
            new BoundaryTypeSpec(API + "client/rendering/v1/GatherDebugTextEvents$Right",
                    SHARED + "LegacyGatherDebugTextRight", "onGatherRightDebugText", "(Ljava/util/List;)V",
                    "gatherLegacyRightDebugText", "(Ljava/util/List;)V", BRIDGES + "GatherDebugTextRightBridge"));

    /** Old callbacks the host still serves through its own interface, renamed or with another shape. */
    public static final List<HostSamSpec> HOST_TYPES = List.of(
            new HostSamSpec(List.of(API + "client/screen/v1/ScreenEvents$AfterRender"),
                    API + "client/screen/v1/ScreenEvents$AfterExtract", "afterRender", SCREEN_EVENT_DESC,
                    "afterExtract", SCREEN_EVENT_DESC, SamBridgeSpec.passThrough(SCREEN_EVENT_DESC),
                    BRIDGES + "ScreenAfterRenderBridge", null),
            new HostSamSpec(List.of(API + "client/screen/v1/ScreenEvents$BeforeRender"),
                    API + "client/screen/v1/ScreenEvents$BeforeExtract", "beforeRender", SCREEN_EVENT_DESC,
                    "beforeExtract", SCREEN_EVENT_DESC, SamBridgeSpec.passThrough(SCREEN_EVENT_DESC),
                    BRIDGES + "ScreenBeforeRenderBridge", null),
            // The 1.16.5 to 1.20.4 shape had no tooltip context; v0 named the same callback.
            new HostSamSpec(List.of(TOOLTIP_V1, API + "event/client/ItemTooltipCallback"), TOOLTIP_V1,
                    "getTooltip", "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/TooltipFlag;"
                            + "Ljava/util/List;)V",
                    "getTooltip", "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/Item$TooltipContext;"
                            + "Lnet/minecraft/world/item/TooltipFlag;Ljava/util/List;)V",
                    List.of(new SamBridgeSpec.HostArg(0), new SamBridgeSpec.HostArg(2), new SamBridgeSpec.HostArg(3)),
                    BRIDGES + "ItemTooltipCallbackBridge",
                    new HostSamSpec.Target(SCREEN + "LegacyTooltips", "getTooltip",
                            "(" + OBJECT + OBJECT + OBJECT + "Ljava/util/List;)V")));

    /** The two shapes of {@code onHudRender} that still draw: the 1.21 one and the 1.20 one. */
    private static final List<String[]> HUD_SHAPES = List.of(
            new String[]{"(" + GRAPHICS + DELTA_TRACKER + ")V", "renderLegacyHud", "(" + OBJECT + OBJECT + ")V"},
            new String[]{"(" + GRAPHICS + "F)V", "renderLegacyHudWithPartialTick", "(" + OBJECT + "F)V"});

    /** Compiled helpers that redirected calls land on. */
    public static final List<String> HELPERS = List.of(SCREEN + "LegacyTooltips", SCREEN + "LegacyHudLayers",
            SCREEN + "LegacyDebugText");

    static final String SHARED_LAYERED_DRAWER = SHARED + "LegacyLayeredDrawerWrapper";

    /** Shared interfaces that redirected calls land on, so a call written as a class call is fixed up. */
    static final List<String> SHARED_INTERFACES = List.of(SHARED_HUD_CALLBACK, SHARED_IDENTIFIED_LAYER,
            SHARED_LAYERED_DRAWER);

    /**
     * Stand-ins older shims gave {@code HudRenderCallback}: a per-mod synthetic whose every copy
     * held its own event and HUD element, and a stand-in whose HUD element never drew on 26.x.
     */
    static final List<String> RETIRED_STAND_INS = List.of(
            "com/retromod/generated/legacyhud/HudRenderCallback",
            "com/retromod/shim/fabric/embedded/HudRenderCallbackShim");

    private ScreenRows() {}

    /** Registers the shared types, host callback bridges and helper owners; the call rows come from the rename table. */
    public static void register(LoaderApiRegistration registration) {
        // The layer type is Minecraft's, so its spec stands back if the Minecraft side redirects it.
        for (BoundaryTypeSpec spec : SHARED_TYPES) {
            if (!spec.register(registration)) {
                LOGGER.debug("{} stays with Retromod's Minecraft side, which redirects it", spec.oldName());
            }
        }
        for (HostSamSpec spec : HOST_TYPES) spec.register(registration);
        registerHudCallback(registration);
        registerIdentifiedLayer(registration);
        // A helper is a class, so an interface-static call sent to it must lose its interface flag.
        for (String helper : HELPERS) {
            registration.claim(RedirectKey.type(helper));
            registration.transformer().registerClassOwner(helper);
        }
        registration.claim(RedirectKey.type(SHARED_LAYERED_DRAWER));
        for (String type : SHARED_INTERFACES) markInterface(registration.transformer(), type);
        for (String standIn : RETIRED_STAND_INS) registration.claim(RedirectKey.type(standIn));
    }

    /**
     * Records that a shared type is an interface, so a call that a redirect sends to it from a
     * class call site is written as an interface call. Its type must already be claimed.
     */
    private static void markInterface(RetromodTransformer transformer, String type) {
        transformer.registerEmbeddedShim(type.replace('/', '.'));
    }

    /**
     * {@code HudRenderCallback} had three shapes. Every mod names one shared type; lambdas of the
     * two shapes 26.x can draw are built on its erased methods, and a class keeps its own method,
     * which the shared type finds when it first draws.
     */
    private static void registerHudCallback(LoaderApiRegistration registration) {
        RetromodTransformer transformer = registration.transformer();
        List<String> names = List.of(HUD_CALLBACK, SHARED_HUD_CALLBACK);
        // Every claim comes first: a method claim clears the lambda retype on the same key.
        for (String name : names) registration.claim(RedirectKey.type(name));
        for (String name : names) {
            for (String[] shape : HUD_SHAPES) registration.claim(RedirectKey.method(name, "onHudRender", shape[0]));
        }
        transformer.registerClassRedirect(HUD_CALLBACK, SHARED_HUD_CALLBACK);
        for (String name : names) {
            for (String[] shape : HUD_SHAPES) {
                // Calls made through a mod's own subtype are matched by hierarchy.
                transformer.registerInheritedMethodRedirect(name, "onHudRender", shape[0],
                        SHARED_HUD_CALLBACK, shape[1], shape[2]);
                transformer.registerLambdaSamRetype(name, "onHudRender", shape[0], shape[1], shape[2]);
            }
        }
        // Another jar's call through a mod sub-interface cannot see the hierarchy, so the
        // sub-interface declares the old methods itself, each forwarding to its erased one.
        for (String[] shape : HUD_SHAPES) {
            transformer.registerInterfaceDefaultBridge(SHARED_HUD_CALLBACK, "onHudRender", shape[0],
                    shape[1], shape[2]);
        }
    }

    /**
     * {@code IdentifiedLayer} has two methods, its id and the layer's render, so a class that
     * implements it gets a bridge that extends the layer's bridge and adds the id.
     */
    private static void registerIdentifiedLayer(LoaderApiRegistration registration) {
        RetromodTransformer transformer = registration.transformer();
        List<String> names = List.of(IDENTIFIED_LAYER, SHARED_IDENTIFIED_LAYER);
        String idDesc = "()" + IDENTIFIER;
        for (String name : names) registration.claim(RedirectKey.type(name));
        for (String name : names) {
            registration.claim(RedirectKey.method(name, "id", idDesc));
            registration.claim(RedirectKey.method(name, "render", HUD_LAYER_DESC));
        }
        registration.registerPerModSynthetic(IDENTIFIED_LAYER_BRIDGE, new SamBridgeSpec(IDENTIFIED_LAYER_BRIDGE,
                List.of(SHARED_IDENTIFIED_LAYER, HUD_LAYER_BRIDGE), "id", idDesc, "legacyHudLayerId",
                "()" + OBJECT, List.of(), SamBridgeSpec.Result.SAME).generate());
        transformer.registerClassRedirect(IDENTIFIED_LAYER, SHARED_IDENTIFIED_LAYER);
        for (String name : names) {
            transformer.registerInterfaceReplacement(name, IDENTIFIED_LAYER_BRIDGE);
            transformer.registerMethodRedirect(name, "id", idDesc, SHARED_IDENTIFIED_LAYER,
                    "legacyHudLayerId", "()" + OBJECT);
            transformer.registerMethodRedirect(name, "render", HUD_LAYER_DESC, SHARED_IDENTIFIED_LAYER,
                    "renderLegacyHudLayer", "(" + OBJECT + OBJECT + ")V");
        }
        // Each class gets its own erased methods, which win over two jars' bridge defaults.
        transformer.registerImplementedMethodBridge(IDENTIFIED_LAYER_BRIDGE, "legacyHudLayerId", "()" + OBJECT,
                "id", idDesc);
        transformer.registerImplementedMethodBridge(IDENTIFIED_LAYER_BRIDGE, "renderLegacyHudLayer",
                "(" + OBJECT + OBJECT + ")V", "render", HUD_LAYER_DESC);
    }
}
