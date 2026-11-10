/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.eventbus;

import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.common.util.Result;

/** Forge 1.20.1's name tag result on Forge 26.2's name tag event, whose renderer skips a tag with no text. Client only. */
final class NameTagResults {

    private NameTagResults() {}

    /** A denial clears the tag and its score; another result after a denial puts back the text the event started with. */
    static void apply(Object event, Result previous, Result result) {
        RenderNameTagEvent tag = (RenderNameTagEvent) event;
        if (result == Result.DENY) {
            tag.setContent(null);
            tag.setScoreContent(null);
        } else if (previous == Result.DENY) {
            // ALLOW cannot show a tag vanilla hid: Forge 26.2 decides that before the event, which carries no entity.
            tag.setContent(tag.getOriginalContent());
            tag.setScoreContent(tag.getOriginalScore());
        }
    }
}
