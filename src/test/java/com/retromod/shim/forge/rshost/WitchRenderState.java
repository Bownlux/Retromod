/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.rshost;

/** Stand-in for the host WitchRenderState, which carries its witch's entity id. */
public class WitchRenderState extends EntityRenderState {
    public int entityId;
}
