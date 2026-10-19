package net.fabricmc.fabric.api.client.item.v1;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

@FunctionalInterface
public interface ItemTooltipCallback {
    Event<ItemTooltipCallback> EVENT = EventFactory.createArrayBacked(ItemTooltipCallback.class,
            listeners -> (stack, context, flag, lines) -> {
                for (ItemTooltipCallback listener : listeners) listener.getTooltip(stack, context, flag, lines);
            });

    void getTooltip(ItemStack stack, Item.TooltipContext context, TooltipFlag flag, List<Component> lines);
}
