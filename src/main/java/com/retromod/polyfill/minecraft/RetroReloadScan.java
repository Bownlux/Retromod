/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill.minecraft;

/**
 * 1.3.0: the scan half of the {@code SimpleJsonResourceReloadListener(Gson, String)} bridge. The
 * 1.21.5 resource-reload refactor deleted the Gson-based constructor (it went Codec-based), so a
 * 1.21.x mod that extends {@code SimpleJsonResourceReloadListener} to load a directory of raw JSON
 * dies {@code NoSuchMethodError} on the {@code super(gson, dir)} call at init (jade's
 * {@code ThemeHelper}, found in-game on 26.2 Fabric). The synthesized superclass
 * {@code RetroSimpleJsonReloadListener} re-implements the old behaviour by delegating its
 * {@code prepare()} here: scan the directory, Gson-parse each {@code .json} to a
 * {@code JsonElement}, and hand back the {@code Map<Identifier, JsonElement>} the subclass'
 * {@code apply(...)} still expects.
 *
 * <p><b>Zero compile-time Minecraft/Gson dependency</b> (Retromod builds without MC; the embedded
 * copy loads with only the mod module's view): every MC and Gson type is reached reflectively, args
 * and result are {@code Object}. <b>Fail-safe:</b> any failure (a bad file, an unresolvable API)
 * skips that entry or returns what was gathered so far (possibly empty), so the reload never crashes
 * construction; the worst case is the feature loading no data, the same soft-fail posture as the
 * rest of Retromod.
 */
public final class RetroReloadScan {

    private static final int MAX_RESOURCES = 10_000;
    private static final long MAX_JSON_CHARS = 2L * 1024 * 1024;
    private static final long MAX_TOTAL_JSON_CHARS = 32L * 1024 * 1024;
    private static final int MAX_JSON_DEPTH = 128;

    private RetroReloadScan() {}

    /**
     * @param gson            the mod's {@code com.google.gson.Gson} (as {@code Object})
     * @param directory       the resource directory the old listener scanned (e.g. {@code
     *                        "jade_themes"})
     * @param resourceManager the {@code net.minecraft.server.packs.resources.ResourceManager}
     * @return a {@code HashMap<Identifier, JsonElement>} (never null)
     */
    public static Object scan(Object gson, String directory, Object resourceManager) {
        java.util.HashMap<Object, Object> out = new java.util.HashMap<>();
        if (directory == null || directory.length() > 1024) return out;
        try {
            Class<?> converterCls = Class.forName("net.minecraft.resources.FileToIdConverter");
            Class<?> rmCls = Class.forName("net.minecraft.server.packs.resources.ResourceManager");
            Class<?> idCls = Class.forName("net.minecraft.resources.Identifier");
            Class<?> gsonCls = Class.forName("com.google.gson.Gson");
            Class<?> jsonElementCls = Class.forName("com.google.gson.JsonElement");
            java.lang.reflect.Method fromJson = Class.forName("net.minecraft.util.GsonHelper")
                    .getMethod("fromJson", gsonCls, java.io.Reader.class, Class.class);
            Object conv = converterCls.getMethod("json", String.class).invoke(null, directory);
            java.lang.reflect.Method fileToId = converterCls.getMethod("fileToId", idCls);
            java.util.Map<?, ?> resources = (java.util.Map<?, ?>) converterCls
                    .getMethod("listMatchingResources", rmCls).invoke(conv, resourceManager);
            if (resources == null || resources.size() > MAX_RESOURCES) return out;
            long[] remainingChars = {MAX_TOTAL_JSON_CHARS};
            for (java.util.Map.Entry<?, ?> e : resources.entrySet()) {
                try {
                    Object id = fileToId.invoke(conv, e.getKey());
                    Object resource = e.getValue();
                    java.io.Reader reader = (java.io.Reader) resource.getClass()
                            .getMethod("openAsReader").invoke(resource);
                    try {
                        String content = readBoundedJson(
                                reader, MAX_JSON_CHARS, MAX_JSON_DEPTH, remainingChars);
                        Object json = fromJson.invoke(null, gson,
                                new java.io.StringReader(content), jsonElementCls);
                        if (json != null) {
                            out.put(id, json);
                        }
                    } finally {
                        reader.close();
                    }
                } catch (Throwable perEntry) {
                    // a single malformed/unreadable file must not sink the whole reload
                }
            }
        } catch (Throwable t) {
            // unresolvable API (or no Minecraft, as in a unit test): hand back what we have
        }
        return out;
    }

    static String readBoundedJson(java.io.Reader reader, long maxChars, int maxDepth,
            long[] remainingChars) throws java.io.IOException {
        if (reader == null) throw new java.io.IOException("JSON reader is missing");
        if (maxChars <= 0 || maxDepth <= 0 || remainingChars == null
                || remainingChars.length != 1 || remainingChars[0] < 0) {
            throw new java.io.IOException("JSON scan limits are invalid");
        }
        StringBuilder content = new StringBuilder();
        char[] buffer = new char[4096];
        long total = 0;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int count;
        while ((count = reader.read(buffer)) != -1) {
            if (count == 0) continue;
            total += count;
            if (total > maxChars) {
                throw new java.io.IOException("JSON resource exceeds its character limit");
            }
            if (count > remainingChars[0]) {
                throw new java.io.IOException("JSON resources exceed their aggregate limit");
            }
            remainingChars[0] -= count;
            for (int index = 0; index < count; index++) {
                char character = buffer[index];
                if (inString) {
                    if (escaped) {
                        escaped = false;
                    } else if (character == '\\') {
                        escaped = true;
                    } else if (character == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (character == '"') {
                    inString = true;
                } else if (character == '{' || character == '[') {
                    if (++depth > maxDepth) {
                        throw new java.io.IOException("JSON resource exceeds its nesting limit");
                    }
                } else if (character == '}' || character == ']') {
                    if (--depth < 0) {
                        throw new java.io.IOException("JSON resource has invalid nesting");
                    }
                }
            }
            content.append(buffer, 0, count);
        }
        return content.toString();
    }

}
