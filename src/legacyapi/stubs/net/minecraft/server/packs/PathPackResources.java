// Compile-only stub of a Forge 26.2 and 26.3 host type for src/legacyapi; never packaged.
package net.minecraft.server.packs;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;

public class PathPackResources implements PackResources {
    public PathPackResources(PackLocationInfo location, Path root) { throw new UnsupportedOperationException(); }
    public IoSupplier<InputStream> getRootResource(String... paths) { throw new UnsupportedOperationException(); }
    public IoSupplier<InputStream> getResource(PackType type, Identifier id) { throw new UnsupportedOperationException(); }
    public void listResources(PackType type, String namespace, String path, PackResources.ResourceOutput output) { throw new UnsupportedOperationException(); }
    public Set<String> getNamespaces(PackType type) { throw new UnsupportedOperationException(); }
    public static void listResources(Path packDir, String namespace, String path, PackResources.ResourceOutput output) { throw new UnsupportedOperationException(); }
    public static Set<String> getNamespaces(Path packDir) { throw new UnsupportedOperationException(); }

    public static class PathResourcesSupplier implements net.minecraft.server.packs.repository.Pack.ResourcesSupplier {
        public PathResourcesSupplier(Path root) { throw new UnsupportedOperationException(); }
    }
}
