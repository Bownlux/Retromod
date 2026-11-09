// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.client.resources.model.sprite;

import net.minecraft.client.resources.model.ModelDebugName;

// Abstract on Minecraft 26.2 and concrete on 26.3; both have this member.
public class MaterialBaker {
    private MaterialBaker() {}
    public Material.Baked get(Material material, ModelDebugName name) { throw new UnsupportedOperationException(); }
}
