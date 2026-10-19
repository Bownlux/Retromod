package net.fabricmc.fabric.api.event.client;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.class_1799;
import net.minecraft.class_1836;
import net.minecraft.class_2561;

import java.util.List;

/** Compile-only copy of the deprecated v0 signature, which 0.92 still shipped. */
@FunctionalInterface
public interface ItemTooltipCallback {
    Event<ItemTooltipCallback> EVENT = null;

    void getTooltip(class_1799 stack, class_1836 context, List<class_2561> lines);
}
