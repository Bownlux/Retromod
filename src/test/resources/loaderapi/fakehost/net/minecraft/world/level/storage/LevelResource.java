package net.minecraft.world.level.storage;

public final class LevelResource {
    public static final LevelResource DATAPACK_DIR = new LevelResource("datapacks");
    private final String id;
    private LevelResource(String id) { this.id = id; }
    public String id() { return id; }
}
