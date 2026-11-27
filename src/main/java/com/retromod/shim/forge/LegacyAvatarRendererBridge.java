/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.RenderBufferSynthetics;

import java.util.List;

/** Names 1.21.1's PlayerRenderer by its 1.21.9 name, AvatarRenderer, for old Forge and NeoForge mods, with the members it dropped. */
public final class LegacyAvatarRendererBridge {

    public static final String PLAYER_RENDERER = "net/minecraft/client/renderer/entity/player/PlayerRenderer";
    public static final String AVATAR_RENDERER = "net/minecraft/client/renderer/entity/player/AvatarRenderer";
    /** The release that renamed the class; a mod built for it or later names the new class itself. */
    static final String RENAMED = "1.21.9";
    static final String MEMBERS = RebuiltApiClasses.ROOT + "minecraft/client/renderer/entity/LegacyAvatarRenderers";

    private static final String PLAYER = "Lnet/minecraft/client/player/AbstractClientPlayer;";
    private static final String VEC = "Lnet/minecraft/world/phys/Vec3;";

    private LegacyAvatarRendererBridge() {}

    /** Registers the rename and the member helpers; the player render event bridges then leave the event's own renderer in place. */
    public static void register(RetromodTransformer t) {
        t.registerClassRedirect(PLAYER_RENDERER, AVATAR_RENDERER);
        t.limitClassRedirectToModsBefore(PLAYER_RENDERER, RENAMED);
        // A mixin on the old class stays inert, as it was while the class could not be found: its handlers target old methods.
        t.markMojangClassRename(PLAYER_RENDERER);
        RebuiltApiClasses.registerTree(t, MEMBERS);
        String self = "(L" + AVATAR_RENDERER + ";";
        // The class redirect above makes each of these match under AvatarRenderer too, which is how the calls arrive.
        for (String buffers : List.of("net/minecraft/client/renderer/MultiBufferSource", RenderBufferSynthetics.MBS)) {
            String draw = "(Lcom/mojang/blaze3d/vertex/PoseStack;L" + buffers + ";I" + PLAYER + ")V";
            for (String hand : List.of("renderRightHand", "renderLeftHand")) {
                t.registerMethodRedirect(PLAYER_RENDERER, hand, draw, MEMBERS, hand, self + "Ljava/lang/Object;Ljava/lang/Object;I" + PLAYER + ")V", true);
            }
        }
        t.registerMethodRedirect(PLAYER_RENDERER, "getRenderOffset", "(" + PLAYER + "F)" + VEC, MEMBERS, "getRenderOffset",
                self + PLAYER + "F)" + VEC, true);
    }
}
