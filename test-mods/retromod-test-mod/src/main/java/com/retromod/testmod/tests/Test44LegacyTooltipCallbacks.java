/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.tests;

import com.retromod.testmod.Test;
import com.retromod.testmod.TestResult;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Old three-argument tooltip callbacks, fired through the old invoker that reaches the host's event. */
public final class Test44LegacyTooltipCallbacks implements Test {

    static final String LAMBDA = "retromod-test tooltip lambda";
    static final String CLASS = "retromod-test tooltip class";
    static final String V0 = "retromod-test tooltip v0";

    // The listeners stay registered, so they add lines only while this test fires the event.
    private static boolean firing;

    @Override
    public String description() {
        return "old three-argument tooltip callbacks reach the host tooltip event";
    }

    @Override
    @SuppressWarnings("deprecation")
    public TestResult run() {
        // Runs on both sides at mod initialization, because the tooltip event is common code on 26.x.
        ItemTooltipCallback.EVENT.register((stack, context, lines) -> {
            if (firing) lines.add(Text.literal(LAMBDA));
        });
        ItemTooltipCallback.EVENT.register(new ClassTooltip());
        net.fabricmc.fabric.api.event.client.ItemTooltipCallback.EVENT.register((stack, context, lines) -> {
            if (firing) lines.add(Text.literal(V0));
        });

        List<Text> lines = new ArrayList<>();
        firing = true;
        try {
            ItemTooltipCallback.EVENT.invoker().getTooltip(ItemStack.EMPTY, null, lines);
        } finally {
            firing = false;
        }
        List<String> texts = new ArrayList<>();
        for (Text line : lines) texts.add(line.getString());
        return texts.contains(LAMBDA) && texts.contains(CLASS) && texts.contains(V0) ? TestResult.success()
                : TestResult.fail("the old tooltip callbacks added " + texts);
    }

    /** The old callback as a class, which keeps its three-argument method. */
    static final class ClassTooltip implements ItemTooltipCallback {
        @Override
        public void getTooltip(ItemStack stack, TooltipContext context, List<Text> lines) {
            if (firing) lines.add(Text.literal(CLASS));
        }
    }
}
