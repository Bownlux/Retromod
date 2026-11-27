/*
 * Retromod Test Mod (Forge)
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package software.bernie.geckolib.cache.object;

import software.bernie.geckolib.core.animatable.model.CoreGeoBone;

/** Compile-only stand-in for GeckoLib 4.8's bone class, for the #404 case. It is not packaged. */
public class GeoBone implements CoreGeoBone {
    public GeoBone(GeoBone parent, String name, Boolean mirror, Double inflate, Boolean dontRender, Boolean reset) {
    }

    @Override
    public float getRotX() {
        return 0;
    }

    @Override
    public void setRotX(float value) {
    }
}
