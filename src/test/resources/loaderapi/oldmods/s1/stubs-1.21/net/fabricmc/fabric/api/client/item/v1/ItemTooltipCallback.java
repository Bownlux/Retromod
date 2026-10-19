package net.fabricmc.fabric.api.client.item.v1;

import net.fabricmc.fabric.api.event.Event;
import net.minecraft.class_1792;
import net.minecraft.class_1799;
import net.minecraft.class_1836;
import net.minecraft.class_2561;

import java.util.List;

/** Compile-only copy of the Fabric API 0.116 (1.21.1) signature. */
@FunctionalInterface
public interface ItemTooltipCallback {
    Event<ItemTooltipCallback> EVENT = null;

    void getTooltip(class_1799 stack, class_1792.class_9635 tooltipContext, class_1836 tooltipType,
            List<class_2561> lines);
}
