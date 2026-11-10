/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

/** Opens folders and web links through Minecraft's own openUri, the call launcher sandboxes route. */
public final class McOpener {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    // Before the client runs, calling Util or Blaze3D would initialize them too early, and a
    // dedicated server has no desktop to open anything on.
    private static volatile boolean clientRunning;

    private McOpener() {}

    /** Records that Minecraft's client is running, so later opens may call into it. */
    public static void markClientRunning() {
        clientRunning = true;
    }

    /** Opens a folder in the file manager; false when Retromod could only log its path. */
    public static boolean openFolder(Path folder) {
        Path absolute = folder.toAbsolutePath().normalize();
        return open(folderUri(absolute), "Open this folder in your file manager: " + absolute);
    }

    /** The file URI for a folder, with no host: the shape a sandbox hands to the file manager. */
    static URI folderUri(Path folder) {
        return folder.toAbsolutePath().normalize().toUri();
    }

    /** Opens an http or https link in the browser; false when Retromod could only log it. */
    public static boolean openLink(String url) {
        String instead = "Open this link in your browser: " + url;
        URI uri = webUri(url);
        if (uri == null) {
            LOGGER.warn("Retromod opens only http and https links, so it did not open {}", url);
            return false;
        }
        return open(uri, instead);
    }

    private static boolean open(URI uri, String instead) {
        if (!clientRunning) {
            LOGGER.info("Retromod opens folders and links only in a running Minecraft client. {}",
                    instead);
            return false;
        }
        Class<?> blaze3d = McReflect.findClass("com.mojang.blaze3d.Blaze3D");
        // Util moved to net.minecraft.util in 1.21.11.
        Class<?> util = McReflect.findClass("net.minecraft.Util", "net.minecraft.util.Util",
                "net.minecraft.class_156");
        return openWith(uri, instead, blaze3d, util);
    }

    /** Hands the URI to the given Minecraft classes; false, with a log line, when that fails. */
    static boolean openWith(URI uri, String instead, Class<?> blaze3d, Class<?> util) {
        try {
            if (callOpenUri(uri, blaze3d, util)) return true;
            LOGGER.warn("This Minecraft version has no link opener Retromod knows. {}", instead);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            LOGGER.warn("Retromod could not open {}: {}. {}", uri, describe(error), instead);
        }
        return false;
    }

    /** Calls Minecraft's openUri(URI), newest shape first; false when this game has none. */
    static boolean callOpenUri(URI uri, Class<?> blaze3d, Class<?> util)
            throws ReflectiveOperationException {
        // 26.3 moved it to a static Blaze3D.openUri, which hands the URI to SDL.
        Method staticOpenUri = publicMethod(blaze3d, new Class<?>[]{URI.class}, "openUri");
        if (staticOpenUri != null && Modifier.isStatic(staticOpenUri.getModifiers())) {
            staticOpenUri.invoke(null, uri);
            return true;
        }

        // Before 26.3 the platform enum opens it. The names are Mojang's, intermediary (Fabric
        // before 26.1), Yarn (a Fabric dev run), and SRG (Forge 1.20 to 1.20.4, NeoForge 1.20.1).
        Method getPlatform = publicMethod(util, new Class<?>[0],
                "getPlatform", "method_668", "getOperatingSystem", "m_137581_");
        Object platform = getPlatform != null ? getPlatform.invoke(null) : null;
        if (platform == null) return false;

        // Some platform constants are anonymous subclasses, so look up on the public enum.
        Class<?> platformType = platform instanceof Enum<?> constant
                ? constant.getDeclaringClass() : platform.getClass();
        // The exact URI parameter matters: every version also has a String or File overload.
        Method openUri = publicMethod(platformType, new Class<?>[]{URI.class},
                "openUri", "method_673", "open", "m_137648_");
        if (openUri == null) return false;
        openUri.invoke(platform, uri);
        return true;
    }

    /** The parsed link when it is http or https, otherwise null. */
    static URI webUri(String url) {
        if (url == null) return null;
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return scheme.equals("https") || scheme.equals("http") ? uri : null;
        } catch (IllegalArgumentException malformed) {
            return null;
        }
    }

    /** Forgets the running client, for tests. */
    static void forgetClientForTesting() {
        clientRunning = false;
    }

    private static Method publicMethod(Class<?> owner, Class<?>[] parameters, String... names) {
        if (owner == null) return null;
        for (String name : names) {
            try {
                return owner.getMethod(name, parameters);
            } catch (NoSuchMethodException absent) {
                // Try the next name.
            }
        }
        return null;
    }

    private static String describe(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException wrapped
                && wrapped.getCause() != null ? wrapped.getCause() : error;
        return cause.getClass().getSimpleName()
                + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
    }
}
