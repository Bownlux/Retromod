package net.minecraft.client.gui.components.debug;

import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/** Public here, as Fabric API 26.1 to 26.3 widen {@code register}. */
public class DebugScreenEntries {
    private static final Map<Identifier, DebugScreenEntry> ENTRIES_BY_ID = new HashMap<>();

    public static Identifier register(Identifier id, DebugScreenEntry entry) {
        if (ENTRIES_BY_ID.putIfAbsent(id, entry) != null) {
            throw new IllegalArgumentException("Duplicate debug entry " + id);
        }
        return id;
    }

    public static DebugScreenEntry getEntry(Identifier id) {
        return ENTRIES_BY_ID.get(id);
    }
}
