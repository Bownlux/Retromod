/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 *
 * Polyfill stub for net.minecraft.class_5500 (SimpleOptionsSubScreen).
 *
 * This class was removed in newer MC versions. Mods like No Chat Reports used it as the
 * superclass of a mixin targeting ChatOptionsScreen. The main remap uses this placeholder, then
 * the post-remap compatibility pass rebases the proven constructor shape to OptionsSubScreen.
 *
 * In intermediary mappings:
 *   class_5500 = SimpleOptionsSubScreen
 *   class_404  = ChatOptionsScreen
 */
package com.retromod.polyfill.minecraft.mixin.embedded;

/**
 * Minimal stub for the removed SimpleOptionsSubScreen (class_5500).
 *
 * This is an empty remap placeholder, not a current Minecraft superclass. A safe Mixin shape is
 * rebased before Mixin validates it. Unsupported shapes remain unchanged and fail normally rather
 * than receiving a guessed hierarchy.
 */
public class ChatOptionsScreenStub {
    // Intentionally empty; exists only to satisfy mixin hierarchy validation
}
