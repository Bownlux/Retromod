package net.minecraft.server;

public class MinecraftServer {
    // Where the fake world lives; tests point it at a temporary folder.
    public static java.nio.file.Path worldDirectory;

    public java.nio.file.Path getWorldPath(net.minecraft.world.level.storage.LevelResource resource) {
        return worldDirectory.resolve(resource.id());
    }
}
