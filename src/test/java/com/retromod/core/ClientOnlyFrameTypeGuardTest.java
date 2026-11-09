/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.core.ClientOnlyTypeFixtures.ServerJarLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

import java.util.Map;
import java.util.function.Function;

import static com.retromod.core.ClientOnlyTypeFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Frame merges on a dedicated server never ask the loader for a client-only Minecraft class. */
class ClientOnlyFrameTypeGuardTest {

    private static final Function<String, ClassNode> SERVER_CLASSES = name -> node(SERVER_JAR.get(name));

    @Test
    @DisplayName("refuses Forge- and NeoForge-marked client classes on a dedicated server")
    void refusesMarkedClientClassesOnServer() {
        assertTrue(RetromodTransformer.refusedOnThisSide(ABSTRACT_CLIENT_PLAYER, SERVER_CLASSES, true),
                "Forge 1.20.1 marks AbstractClientPlayer with @OnlyIn(Dist.CLIENT)");
        assertTrue(RetromodTransformer.refusedOnThisSide(CLIENT_LEVEL, SERVER_CLASSES, true),
                "NeoForge 1.21.1 marks ClientLevel with its own @OnlyIn(Dist.CLIENT)");
        assertFalse(RetromodTransformer.refusedOnThisSide(SERVER_PLAYER, SERVER_CLASSES, true));
        assertFalse(RetromodTransformer.refusedOnThisSide(LEVEL, SERVER_CLASSES, true));
    }

    @Test
    @DisplayName("refuses an unmarked class whose superclass is marked")
    void refusesUnmarkedSubclassOfMarkedClass() {
        assertTrue(RetromodTransformer.refusedOnThisSide(LOCAL_PLAYER, SERVER_CLASSES, true),
                "loading LocalPlayer loads AbstractClientPlayer first, which the cleaner refuses");
    }

    @Test
    @DisplayName("reads the marker inside @OnlyIns too")
    void refusesMarkerInsideOnlyIns() {
        String repeated = "net/minecraft/client/gui/RepeatedMarker";
        Map<String, byte[]> jar = Map.of(repeated, type(repeated, "java/lang/Object", onlyIns()));
        assertTrue(RetromodTransformer.refusedOnThisSide(repeated, name -> node(jar.get(name)), true));
    }

    @Test
    @DisplayName("keeps a class whose marker strips one interface, or that is marked for the server")
    void keepsInterfaceStripAndServerMarkers() {
        String stripped = "net/minecraft/world/entity/StrippedInterface";
        String serverOnly = "net/minecraft/server/ServerOnly";
        Map<String, byte[]> jar = Map.of(
                stripped, type(stripped, "java/lang/Object",
                        onlyInInterface("net/minecraft/client/ClientInterface")),
                serverOnly, type(serverOnly, "java/lang/Object",
                        onlyIn(FORGE_ONLY_IN, FORGE_DIST, "DEDICATED_SERVER")));
        Function<String, ClassNode> classes = name -> node(jar.get(name));

        assertFalse(RetromodTransformer.refusedOnThisSide(stripped, classes, true),
                "_interface removes only that interface, so the class itself still loads");
        assertFalse(RetromodTransformer.refusedOnThisSide(serverOnly, classes, true));
    }

    @Test
    @DisplayName("refuses nothing on a client, offline, or outside Minecraft's packages")
    void refusesNothingOffTheServerOrOutsideMinecraft() {
        assertFalse(RetromodTransformer.refusedOnThisSide(CLIENT_LEVEL, SERVER_CLASSES, false),
                "a client loads its own classes");
        assertFalse(RetromodTransformer.refusedOnThisSide(CLIENT_LEVEL, name -> null, true),
                "offline there is no Minecraft class to read, so nothing is refused");

        String modClass = "com/example/client/ModClientOnly";
        Map<String, byte[]> jar = Map.of(modClass, type(modClass, "java/lang/Object",
                onlyIn(FORGE_ONLY_IN, FORGE_DIST, "CLIENT")));
        assertFalse(RetromodTransformer.refusedOnThisSide(modClass, name -> node(jar.get(name)), true),
                "Forge 26.2 cleans only net/minecraft and com/mojang");
    }

    @Test
    @DisplayName("a frame load never asks the server's loader for a refused class, and still asks for others")
    void loadForFramesSkipsTheLoaderOnlyForRefusedNames() throws Exception {
        ServerJarLoader loader = new ServerJarLoader();
        onSide(true, loader, () -> {
            assertThrows(ClassNotFoundException.class,
                    () -> RetromodTransformer.loadForFrames(CLIENT_LEVEL, loader));
            assertThrows(ClassNotFoundException.class,
                    () -> RetromodTransformer.loadForFrames(SERVER_PLAYER, loader));
            return null;
        });

        assertFalse(loader.requested.contains(CLIENT_LEVEL),
                "the dist cleaner logs an ERROR for every client class it is asked to load");
        assertTrue(loader.requested.contains(SERVER_PLAYER), "other classes still go to the loader");
    }

    @Test
    @DisplayName("the shared writer merges the morehitboxes mixin with ClientLevel without loading ClientLevel")
    void sharedWriterMergesWithoutLoadingClientLevel() throws Exception {
        ServerJarLoader loader = new ServerJarLoader();
        RecordingWriter writer = new RecordingWriter(loader);
        // Both orders: ASM asks the loader for the first type first.
        String[] merged = onSide(true, loader, () -> new String[] {
                writer.merge(MOB_MIXIN, CLIENT_LEVEL), writer.merge(CLIENT_LEVEL, MOB_MIXIN)});

        assertFalse(loader.requested.contains(CLIENT_LEVEL),
                "ClientLevel must not be asked of a dedicated server's loader");
        assertEquals("java/lang/Object", merged[0], "they share only Object");
        assertEquals("java/lang/Object", merged[1]);
    }

    /** The shared writer with its loader replaced, so the test sees every class it asks for. */
    private static final class RecordingWriter extends com.retromod.util.SafeClassWriter {
        private final ClassLoader loader;

        RecordingWriter(ClassLoader loader) {
            super(0);
            this.loader = loader;
        }

        @Override
        protected ClassLoader getClassLoader() {
            return loader;
        }

        String merge(String first, String second) {
            return getCommonSuperClass(first, second);
        }
    }
}
