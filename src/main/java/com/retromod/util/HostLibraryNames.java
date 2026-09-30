/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

/**
 * Names of libraries as Minecraft and mods see them, not as Retromod's relocated copies.
 *
 * <p>The release jar relocates {@code com.google.gson} and {@code org.slf4j} under
 * {@code com.retromod.shaded}, and maven-shade rewrites matching string constants as well as type
 * references. A literal {@code "com/google/gson/Gson"} therefore ships naming Retromod's private
 * copy. In every published 1.3.x jar that broke the reload-listener bridge with a
 * {@code VerifyError}, silently disabled the Patchouli Gson repair, and made the library-prefix
 * filters miss the real packages, while unit tests, which run before shading, passed. These names
 * are assembled at runtime so no constant exists for shade to match. {@code HostLibraryNamesTest}
 * fails if a shade-vulnerable literal comes back.
 */
public final class HostLibraryNames {

    private HostLibraryNames() {}

    /** {@code com/google/gson}, built at runtime. */
    public static final String GSON_PACKAGE = String.join("/", "com", "google", "gson");

    /** {@code org/slf4j}, built at runtime. */
    public static final String SLF4J_PACKAGE = String.join("/", "org", "slf4j");

    public static final String GSON = GSON_PACKAGE + "/Gson";
    public static final String GSON_BUILDER = GSON_PACKAGE + "/GsonBuilder";
    public static final String JSON_ELEMENT = GSON_PACKAGE + "/JsonElement";

    /** The binary name for {@code Class.forName}, e.g. {@code com.google.gson.Gson}. */
    public static String binaryName(String internalName) {
        return internalName.replace('/', '.');
    }
}
