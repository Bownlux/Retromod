/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.ParseResults;
import com.retromod.shim.common.embedded.LegacyLog;
import java.util.stream.Stream;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.SignableCommand;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.world.entity.LivingEntity;

/** Small client calls the host removed, each rebuilt on the member that replaced it. */
public final class LegacySmallClientCalls {

    private LegacySmallClientCalls() {}

    /** {@code options.setKey(mapping, key)}: binds the key and saves the options, as the old call did. */
    public static void setKey(Options options, KeyMapping mapping, InputConstants.Key key) {
        mapping.setKey(key);
        options.save();
    }

    /** {@code SystemToast.multiline(...)}: the plain toast, which wraps its own text since 26.2. */
    public static SystemToast multiline(Minecraft minecraft, SystemToast.SystemToastId id, Component title, Component message) {
        return new SystemToast(id, title, message);
    }

    /** {@code widget.setColor(color)}: the color now lives on the text, so the message is recolored. */
    public static StringWidget setColor(StringWidget widget, int color) {
        widget.setMessage(widget.getMessage().copy().withColor(color));
        return widget;
    }

    /** {@code provider.createParticle(...)} without a random source; the level's own is passed, as the game does. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Particle createParticle(ParticleProvider provider, ParticleOptions options, ClientLevel level,
            double x, double y, double z, double xd, double yd, double zd) {
        return provider.createParticle(options, level, x, y, z, xd, yd, zd, level.getRandom());
    }

    /** {@code part.getAllParts()}, which returns a list now instead of a stream. */
    public static Stream<ModelPart> getAllParts(ModelPart part) {
        return part.getAllParts().stream();
    }

    /** {@code LivingEntityRenderer.getOverlayCoords(entity, whiteness)}: red while hurt or dying, white by the given amount. */
    public static int getOverlayCoords(LivingEntity entity, float whiteness) {
        return OverlayTexture.pack(OverlayTexture.u(whiteness), OverlayTexture.v(entity.hurtTime > 0 || entity.deathTime > 0));
    }

    /** {@code listener.sendUnsignedCommand(command)}: sends a command only when it has nothing to sign, as the old call did. */
    public static boolean sendUnsignedCommand(ClientPacketListener listener, String command) {
        // A command with signable arguments would need the player's signature, so it is refused and nothing is sent.
        ParseResults<?> parsed = listener.getCommands().parse(command, listener.getSuggestionsProvider());
        if (SignableCommand.hasSignableArguments(parsed)) return false;
        listener.send(new ServerboundChatCommandPacket(command));
        return true;
    }

    /** {@code CommonInputs.selected(key)}: Enter, Space or the keypad Enter, the keys that press a focused control. */
    public static boolean selected(int key) {
        return key == SelectKeys.ENTER || key == SelectKeys.SPACE || key == SelectKeys.KEYPAD_ENTER;
    }

    /** The host's numbers for the select keys, looked up once; 26.3 renumbered every key. */
    private static final class SelectKeys {
        static final int ENTER = code("enter");
        static final int SPACE = code("space");
        static final int KEYPAD_ENTER = code("keypad.enter");

        private static int code(String name) {
            return InputConstants.getKey("key.keyboard." + name).getValue();
        }
    }

    /** {@code widget.alignLeft()}: a string widget always draws from its left edge now, so nothing changes. */
    public static StringWidget alignLeft(StringWidget widget) {
        return widget;
    }

    /** {@code widget.alignCenter()}: the widget can no longer center its text, so it stays left-aligned. */
    public static StringWidget alignCenter(StringWidget widget) {
        warnAlignment();
        return widget;
    }

    /** {@code widget.alignRight()}: the widget can no longer right-align its text, so it stays left-aligned. */
    public static StringWidget alignRight(StringWidget widget) {
        warnAlignment();
        return widget;
    }

    private static void warnAlignment() {
        LegacyLog.warnOnce("legacy string widget alignment " + LegacySmallClientCalls.class.getName(), "An old mod asked a"
                + " text label to center or right-align its text. Minecraft no longer offers that, so the label is drawn"
                + " left-aligned.");
    }
}
