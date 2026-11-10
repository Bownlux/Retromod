/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.rshost;

/** Stand-in for the host AvatarRenderState, which carries its player's entity id. */
public class AvatarRenderState extends EntityRenderState {
    public int id;
}
