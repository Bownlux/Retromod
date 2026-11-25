/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.client.resources;

import java.util.Optional;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;

/** {@code AnimationMetadataSection.EMPTY}, which 1.21.4 removed when the section became a record. */
public final class LegacyAnimationSections {

    /** No frames, no frame size, one tick per frame and no interpolation, as the old constant held. */
    public static final AnimationMetadataSection EMPTY =
            new AnimationMetadataSection(Optional.empty(), Optional.empty(), Optional.empty(), 1, false);

    private LegacyAnimationSections() {}
}
