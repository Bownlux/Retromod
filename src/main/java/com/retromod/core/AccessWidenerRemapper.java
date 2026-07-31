/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.mapping.IntermediaryToMojangMapper;

/**
 * Updates Fabric access wideners for hosts that use Mojang names. Fabric reads
 * these files before mod construction, so a stale intermediary namespace can
 * prevent the mod from loading at all.
 */
public final class AccessWidenerRemapper {

    private AccessWidenerRemapper() {}

    /** Remaps intermediary content and leaves every other namespace unchanged. */
    public static String remapToOfficial(String content, IntermediaryToMojangMapper mapper) {
        try {
            if (content == null || !content.contains("intermediary")) return content;
            String[] lines = content.split("\n", -1);
            StringBuilder patched = new StringBuilder(content.length() + 16);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                String out;
                if (i == 0) {
                    // The header declares the namespace Fabric should expect below.
                    out = line.replace("intermediary", "official");
                } else if (line.isEmpty() || line.startsWith("#")) {
                    out = line;
                } else {
                    out = mapper.remapString(line);
                }
                patched.append(out);
                if (i < lines.length - 1) patched.append("\n");
            }
            return patched.toString();
        } catch (Throwable t) {
            return content;
        }
    }
}
