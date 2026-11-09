// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.packs.resources;

// The host interface also extends PreparableReloadListener, whose reload it answers with a default.
public interface ResourceManagerReloadListener {
    void onResourceManagerReload(ResourceManager manager);
}
