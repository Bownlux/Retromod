/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.core;

import net.minecraft.core.Holder;
import net.minecraft.tags.TagKey;

/** {@code Holder.is(tag)} as 1.21.1 answered it: a holder whose tags are not bound yet is in no tag. */
public final class LegacyHolderTags {

    private static final String UNBOUND = "Tags not bound";

    private LegacyHolderTags() {}

    /** The host's answer once tags are bound; before that 1.21.2 and newer throw "Tags not bound", where 1.21.1 said no. */
    public static <T> boolean is(Holder<T> holder, TagKey<T> tag) {
        try {
            return holder.is(tag);
        } catch (IllegalStateException failure) {
            // Only the unbound-tags failure had a 1.21.1 answer; any other failure is the host's own.
            if (UNBOUND.equals(failure.getMessage())) return false;
            throw failure;
        }
    }
}
