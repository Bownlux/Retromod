/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyPoseGui;
import org.objectweb.asm.tree.ClassNode;

/**
 * Pre-1.20 GUI drawing with a {@code PoseStack} on 26.1 and newer, where the 1.20 bridge's
 * tesselator quads cannot link. See {@link LegacyPoseGui} for how each draw reaches the frame's
 * {@code GuiGraphicsExtractor}.
 *
 * <p>Covered: the HUD and screen events' {@code getPoseStack()}, the static and instance
 * {@code blit}, {@code fill}, {@code drawString}, and {@code drawCenteredString} of
 * {@code GuiComponent} and the classes mods called them through, {@code Font.draw} and
 * {@code drawShadow}, and the texture and color set through {@code RenderSystem}. A screen's own
 * {@code render(PoseStack, ...)} runs through {@link LegacyPoseScreenAdapter}, with the background,
 * tooltip and widget render calls it makes.
 */
public final class LegacyPoseGuiBridge {

    static final String HELPER = "com/retromod/shim/common/embedded/LegacyPoseGui";
    private static final String POSE = "Lcom/mojang/blaze3d/vertex/PoseStack;";
    private static final String FONT = "Lnet/minecraft/client/gui/Font;";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String TEXTURE_MANAGER = "net/minecraft/client/renderer/texture/TextureManager";
    private static final String[] TEXTS = {"Ljava/lang/String;", "Lnet/minecraft/network/chat/Component;",
            "Lnet/minecraft/util/FormattedCharSequence;"};
    private static final String[] DRAW_OWNERS = {
            "net/minecraft/client/gui/GuiComponent", "net/minecraft/client/gui/GuiGraphics",
            "net/minecraft/client/gui/GuiGraphicsExtractor", "net/minecraft/client/gui/Gui",
            "net/minecraft/client/gui/screens/Screen",
            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
            "net/minecraft/client/gui/components/AbstractWidget", "net/minecraftforge/client/gui/overlay/ForgeGui"};
    private static final String[] POSE_EVENTS = {
            "client/event/RenderGuiEvent", "client/event/RenderGuiEvent$Pre", "client/event/RenderGuiEvent$Post",
            "client/event/RenderGuiLayerEvent", "client/event/RenderGuiLayerEvent$Pre", "client/event/RenderGuiLayerEvent$Post",
            "client/event/RenderGuiOverlayEvent", "client/event/RenderGuiOverlayEvent$Pre", "client/event/RenderGuiOverlayEvent$Post",
            "client/event/ScreenEvent$Render", "client/event/ScreenEvent$Render$Pre", "client/event/ScreenEvent$Render$Post",
            "client/event/ScreenEvent$BackgroundRendered"};

    /** Host types with an old {@code render(pose, x, y, delta)} that old code called on a screen or widget it held. */
    static final String[] RENDERABLES = {
            "net/minecraft/client/gui/screens/Screen", "net/minecraft/client/gui/components/Renderable",
            "net/minecraft/client/gui/components/AbstractWidget", "net/minecraft/client/gui/components/AbstractButton",
            "net/minecraft/client/gui/components/Button", "net/minecraft/client/gui/components/ImageButton",
            "net/minecraft/client/gui/components/Checkbox", "net/minecraft/client/gui/components/CycleButton",
            "net/minecraft/client/gui/components/EditBox", "net/minecraft/client/gui/components/AbstractSliderButton",
            "net/minecraft/client/gui/components/AbstractSelectionList", "net/minecraft/client/gui/components/ObjectSelectionList",
            "net/minecraft/client/gui/components/ContainerObjectSelectionList", "net/minecraft/client/gui/components/OptionsList",
            "net/minecraft/client/gui/screens/options/controls/KeyBindsList"};

    private LegacyPoseGuiBridge() {}

    public static void register(RetromodTransformer transformer) {
        ClassNode renderSystem = ClassResourceInspector.read("com/mojang/blaze3d/systems/RenderSystem");
        registerRedirects(transformer, renderSystem);
        registerOldBinds(transformer, renderSystem, ClassResourceInspector.read(TEXTURE_MANAGER));
        registerScreens(transformer);
    }

    /** The 1.16 texture bind and tint, which 1.17 renamed and 26.1 removed; they feed the next old GUI blit as the newer forms do. */
    static void registerOldBinds(RetromodTransformer transformer, ClassNode renderSystem, ClassNode textureManager) {
        for (String bind : new String[] {"bind", "bindForSetup"}) {
            if (!lacksOrUnknown(textureManager, bind)) continue;
            for (String id : new String[] {"Lnet/minecraft/resources/Identifier;", "Lnet/minecraft/resources/ResourceLocation;"}) {
                transformer.registerMethodRedirect(TEXTURE_MANAGER, bind, "(" + id + ")V", HELPER, "bindTexture",
                        "(" + OBJECT + OBJECT + ")V", true);
                // From 1.20 GUI code names its texture in each blit, so a later bindForSetup is renderer setup, not a GUI bind.
                if (bind.equals("bindForSetup")) {
                    transformer.limitRedirectToModsBefore(TEXTURE_MANAGER, bind, "(" + id + ")V", "1.20");
                }
            }
        }
        if (!lacksOrUnknown(renderSystem, "color4f")) return;
        transformer.registerMethodRedirect("com/mojang/blaze3d/systems/RenderSystem", "color4f", "(FFFF)V",
                HELPER, "setShaderColor", "(FFFF)V");
        // The state manager's own tint, under its 1.16 home and the 26.3 one.
        for (String manager : new String[] {"com/mojang/blaze3d/platform/GlStateManager",
                "com/mojang/renderpearl/backend/opengl/GlStateManager"}) {
            for (String name : new String[] {"color4f", "_color4f"}) {
                transformer.registerMethodRedirect(manager, name, "(FFFF)V", HELPER, "setShaderColor", "(FFFF)V");
            }
        }
    }

    /** Whether the host class lacks every method of that name; offline, where nothing is readable, it counts as lacking it. */
    private static boolean lacksOrUnknown(ClassNode owner, String method) {
        return owner == null || owner.methods.stream().noneMatch(m -> m.name.equals(method));
    }

    /** Old screens' own render, which {@link LegacyPoseScreenAdapter} reaches, and the screen and widget calls it makes. */
    static void registerScreens(RetromodTransformer transformer) {
        String screens = LegacyPoseScreenAdapter.HELPER;
        RebuiltApiClasses.registerTree(transformer, screens);
        // A super call stays for the adapter: through the helper it would run the screen's own render again.
        String render = "(" + POSE + "IIF)V";
        for (String owner : RENDERABLES) {
            transformer.registerWrappingMethodRedirect(owner, "render", render, screens, "render",
                    "(Lnet/minecraft/client/gui/components/Renderable;" + render.substring(1));
        }
        // 26.1 gave Button's constructor a narration argument; this is the only registration on every loader.
        String button = "net/minecraft/client/gui/components/Button";
        String oldButton = "(IIIILnet/minecraft/network/chat/Component;L" + button + "$OnPress;)";
        transformer.registerConstructorRedirect(button, oldButton + "V", screens, "button", oldButton + "L" + button + ";");
        transformer.registerSuperConstructorRedirect(button, oldButton + "V",
                oldButton.replace(")", "L" + button + "$CreateNarration;)") + "V",
                button, "DEFAULT_NARRATION", "L" + button + "$CreateNarration;");
        String screen = "net/minecraft/client/gui/screens/Screen";
        String receiver = "(" + OBJECT + POSE;
        // The extract names are what the render-to-extract naming rule gives these calls when nothing else matched them.
        for (String background : new String[] {"renderBackground", "extractBackground"}) {
            transformer.registerInheritedMethodRedirect(screen, background, "(" + POSE + ")V",
                    screens, "renderBackground", receiver + ")V");
            transformer.registerInheritedMethodRedirect(screen, background, "(" + POSE + "I)V",
                    screens, "renderBackground", receiver + "I)V");
        }
        transformer.registerInheritedMethodRedirect(screen, "renderDirtBackground", "(I)V",
                screens, "renderDirtBackground", "(" + OBJECT + "I)V");
        String component = "Lnet/minecraft/network/chat/Component;";
        String[][] tooltips = {
                {"renderTooltip", component, "renderTooltip"}, {"extractTooltip", component, "renderTooltip"},
                {"renderTooltip", "Ljava/util/List;", "renderTooltip"}, {"extractTooltip", "Ljava/util/List;", "renderTooltip"},
                {"renderComponentTooltip", "Ljava/util/List;", "renderComponentTooltip"},
                {"extractComponentTooltip", "Ljava/util/List;", "renderComponentTooltip"}};
        for (String[] tooltip : tooltips) {
            transformer.registerInheritedMethodRedirect(screen, tooltip[0], "(" + POSE + tooltip[1] + "II)V",
                    screens, tooltip[2], receiver + tooltip[1] + "II)V");
        }
    }

    static void registerRedirects(RetromodTransformer transformer, ClassNode renderSystem) {
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyPoseGui.class);

        for (String loader : new String[] {"net/neoforged/neoforge/", "net/minecraftforge/"}) {
            for (String event : POSE_EVENTS) {
                transformer.registerMethodRedirect(loader + event, "getPoseStack", "()" + POSE,
                        HELPER, "poseFor", "(" + OBJECT + ")" + OBJECT, true);
            }
        }

        String ints6 = "IIIIII";
        for (String owner : DRAW_OWNERS) {
            transformer.registerInheritedMethodRedirect(owner, "blit", "(" + POSE + ints6 + ")V",
                    HELPER, "blitInstance", "(" + OBJECT + OBJECT + ints6 + ")V");
            transformer.registerInheritedMethodRedirect(owner, "blit", "(" + POSE + "IIFFIIII)V",
                    HELPER, "blitUv", "(" + OBJECT + "IIFFIIII)V");
            transformer.registerInheritedMethodRedirect(owner, "blit", "(" + POSE + "IIIIFFIIII)V",
                    HELPER, "blitSized", "(" + OBJECT + "IIIIFFIIII)V");
            transformer.registerInheritedMethodRedirect(owner, "blit", "(" + POSE + "IIIFFIIII)V",
                    HELPER, "blitZ", "(" + OBJECT + "IIIFFIIII)V");
            transformer.registerInheritedMethodRedirect(owner, "fill", "(" + POSE + "IIIII)V",
                    HELPER, "fill", "(" + OBJECT + "IIIII)V");
            for (String text : TEXTS) {
                for (String name : new String[] {"drawString", "drawCenteredString"}) {
                    transformer.registerInheritedMethodRedirect(owner, name, "(" + POSE + FONT + text + "III)V",
                            HELPER, name, "(" + OBJECT + OBJECT + OBJECT + "III)V");
                }
            }
        }
        for (String text : TEXTS) {
            transformer.registerMethodRedirect("net/minecraft/client/gui/Font", "draw", "(" + POSE + text + "FFI)I",
                    HELPER, "fontDraw", "(" + OBJECT + OBJECT + OBJECT + "FFI)I", true);
            transformer.registerMethodRedirect("net/minecraft/client/gui/Font", "drawShadow", "(" + POSE + text + "FFI)I",
                    HELPER, "fontDrawShadow", "(" + OBJECT + OBJECT + OBJECT + "FFI)I", true);
        }
        // A Fabric mod's draw keeps its intermediary id, which the 26.x name table names drawInBatch. The shadowed
        // sequence draw shares that name, so it draws without its shadow.
        for (String text : new String[] {TEXTS[0], TEXTS[2]}) {
            transformer.registerMethodRedirect("net/minecraft/client/gui/Font", "drawInBatch", "(" + POSE + text + "FFI)I",
                    HELPER, "fontDraw", "(" + OBJECT + OBJECT + OBJECT + "FFI)I", true);
        }

        // Only where the host dropped them: a newer mod's own calls must reach the real methods.
        String system = "com/mojang/blaze3d/systems/RenderSystem";
        if (lacks(renderSystem, "setShaderTexture")) {
            for (String id : new String[] {"Lnet/minecraft/resources/Identifier;", "Lnet/minecraft/resources/ResourceLocation;"}) {
                transformer.registerMethodRedirect(system, "setShaderTexture", "(I" + id + ")V",
                        HELPER, "setShaderTexture", "(I" + OBJECT + ")V");
            }
        }
        if (lacks(renderSystem, "setShaderColor")) {
            transformer.registerMethodRedirect(system, "setShaderColor", "(FFFF)V", HELPER, "setShaderColor", "(FFFF)V");
        }
    }

    private static boolean lacks(ClassNode owner, String method) {
        return owner != null && owner.methods.stream().noneMatch(m -> m.name.equals(method));
    }
}
