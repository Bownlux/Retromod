/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.testmod.forge;

import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;

/** #404: turns a bone through GeckoLib 4.0's CoreGeoBone interface, as 1.20.1 models do. Loaded only with GeckoLib 4. */
final class GeckoLibBoneCalls {

    private GeckoLibBoneCalls() {}

    static String turnBone() {
        CoreGeoBone bone = new GeoBone(null, "head", false, 0.0, false, false);
        bone.setRotX(0.5F);
        return bone.getRotX() == 0.5F ? "success" : "fail: the bone turned to " + bone.getRotX();
    }
}
