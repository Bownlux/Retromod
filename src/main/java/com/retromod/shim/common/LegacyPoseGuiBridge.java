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
 * {@code render(PoseStack, ...)} override is not called by 26.1, so old screens still draw only
 * their widgets.
 */
public final class LegacyPoseGuiBridge {

    static final String HELPER = "com/retromod/shim/common/embedded/LegacyPoseGui";
    private static final String POSE = "Lcom/mojang/blaze3d/vertex/PoseStack;";
    private static final String FONT = "Lnet/minecraft/client/gui/Font;";
    private static final String OBJECT = "Ljava/lang/Object;";
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

    private LegacyPoseGuiBridge() {}

    public static void register(RetromodTransformer transformer) {
        registerRedirects(transformer, ClassResourceInspector.read("com/mojang/blaze3d/systems/RenderSystem"));
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
