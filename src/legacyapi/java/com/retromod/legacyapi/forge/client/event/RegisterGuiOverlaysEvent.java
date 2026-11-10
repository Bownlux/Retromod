/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.event;

import com.retromod.legacyapi.forge.client.LegacyOverlayHub;
import com.retromod.legacyapi.forge.client.gui.overlay.ForgeGui;
import com.retromod.legacyapi.forge.client.gui.overlay.IGuiOverlay;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.gui.overlay.ForgeLayer;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.event.MutableEvent;
import net.minecraftforge.fml.event.IModBusEvent;

/** Forge 1.20.1's mod-bus event for adding HUD overlays, into one order every mod shares, which Forge 26.2 removed. */
public final class RegisterGuiOverlaysEvent extends MutableEvent implements IModBusEvent {

    // The mod whose bus the event is posted on names the overlays, as Forge 1.20.1's active mod did.
    private final String namespace;
    private final List<Object[]> order;

    public RegisterGuiOverlaysEvent(String namespace, List<Object[]> order) {
        this.namespace = namespace;
        this.order = order;
    }

    /** Posts this mod's event to one mod's bus group, where Forge 1.20.1 posted it to each mod in mod order; false when none listens. */
    public static boolean postTo(Object group, String namespace) {
        EventBus<RegisterGuiOverlaysEvent> bus = IModBusEvent.getBus((BusGroup) group, RegisterGuiOverlaysEvent.class);
        if (!bus.hasListeners()) return false;
        bus.post(new RegisterGuiOverlaysEvent(namespace, LegacyOverlayHub.order()));
        return true;
    }

    public void registerBelowAll(String id, IGuiOverlay overlay) {
        register(true, null, id, overlay);
    }

    public void registerBelow(Identifier other, String id, IGuiOverlay overlay) {
        register(true, other, id, overlay);
    }

    public void registerAbove(Identifier other, String id, IGuiOverlay overlay) {
        register(false, other, id, overlay);
    }

    public void registerAboveAll(String id, IGuiOverlay overlay) {
        register(false, null, id, overlay);
    }

    // Below all goes first and above all goes last; next to an anchor goes right before or right after it.
    private void register(boolean below, Identifier anchor, String path, IGuiOverlay overlay) {
        Identifier id = Identifier.fromNamespaceAndPath(namespace, path);
        String owner = namespace;
        ForgeLayer drawer = (graphics, delta) -> ForgeGui.draw(id, owner, overlay, graphics, delta);
        // Every mod's copy writes into the same list, so the check and the insert happen together.
        synchronized (order) {
            if (LegacyOverlayHub.indexOf(order, id) >= 0) {
                throw new IllegalArgumentException("Overlay already registered: " + id);
            }
            int index;
            if (anchor == null) {
                index = below ? 0 : order.size();
            } else {
                int at = LegacyOverlayHub.indexOf(order, anchor);
                if (at < 0) {
                    throw new IllegalStateException(
                            "Attempted to order against an unregistered overlay. Only order against vanilla's and your own.");
                }
                index = below ? at : at + 1;
            }
            order.add(index, new Object[]{id, drawer, owner});
        }
        LegacyOverlayHub.addOwn(id, overlay);
    }
}
