/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The bundled forge-eventbus7-cancellable.tsv is what scripts/ForgeEventTable.java reads from each Forge's jars. */
class ForgeEventTableTest {

    private static final String TABLE = "retromod/forge-eventbus7-cancellable.tsv";
    private static final Path SCRIPT = Path.of("scripts", "ForgeEventTable.java");
    private static final String PLAYER = "net/minecraftforge/event/entity/player/";
    private static final String CLIENT = "net/minecraftforge/client/event/";
    private static final String EVENT = "net/minecraftforge/event/";
    // The parents with no bus that Forge 26.2 has besides the four the first table named, read from its jars.
    private static final List<String> NAMED_SINCE = List.of(CLIENT + "ContainerScreenEvent",
            CLIENT + "ContainerScreenEvent$Render", CLIENT + "CustomizeGuiOverlayEvent", CLIENT + "EntityRenderersEvent",
            CLIENT + "InputEvent", CLIENT + "InputEvent$MouseButton", CLIENT + "RegisterColorHandlersEvent", CLIENT + "RenderAvatarEvent", CLIENT + "RenderLivingEvent",
            CLIENT + "ScreenEvent", CLIENT + "ScreenEvent$CharacterTyped", CLIENT + "ScreenEvent$KeyInput",
            CLIENT + "ScreenEvent$KeyPressed", CLIENT + "ScreenEvent$KeyReleased", CLIENT + "ScreenEvent$MouseButtonPressed",
            CLIENT + "ScreenEvent$MouseButtonReleased", CLIENT + "ScreenEvent$MouseDragged", CLIENT + "ScreenEvent$MouseInput",
            CLIENT + "ScreenEvent$MouseScrolled", CLIENT + "ScreenEvent$Render", CLIENT + "TextureStitchEvent",
            CLIENT + "ViewportEvent", CLIENT + "sound/SoundEvent", EVENT + "AttachCapabilitiesEvent", EVENT + "TickEvent",
            EVENT + "TickEvent$ClientTickEvent", EVENT + "TickEvent$LevelTickEvent", EVENT + "TickEvent$PlayerTickEvent",
            EVENT + "TickEvent$RenderTickEvent", EVENT + "TickEvent$ServerTickEvent", EVENT + "entity/EntityEvent",
            EVENT + "entity/item/ItemEvent", EVENT + "entity/living/LivingEvent", PLAYER + "AdvancementEvent",
            PLAYER + "PlayerEvent", PLAYER + "PlayerXpEvent", EVENT + "level/BlockEvent", EVENT + "level/PistonEvent",
            "net/minecraftforge/registries/DataPackRegistryEvent");

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("Every parent with no bus is named with the events below it that hand on its listeners, nested ones included")
    void everyParentWithNoBusIsNamed(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        Function<String, ClassNode> offline = name -> null;
        Map<String, List<String>> busless = LegacyCancelBridge.section(host).busless();

        for (String parent : NAMED_SINCE) assertTrue(busless.containsKey(parent), parent + " has a row");
        assertEquals(Set.of(PLAYER + "AdvancementEvent$AdvancementEarnEvent", PLAYER + "AdvancementEvent$AdvancementProgressEvent"),
                LegacyCancelBridge.buslessPlainChildren(PLAYER + "AdvancementEvent", offline));
        // PlayerEvent reaches PlayerLoggedInEvent through no bus, and RightClickBlock through PlayerInteractEvent's.
        List<String> player = busless.get(PLAYER + "PlayerEvent");
        assertTrue(player.contains(PLAYER + "PlayerEvent$PlayerLoggedInEvent"));
        assertTrue(player.contains(PLAYER + "PlayerInteractEvent"));
        assertFalse(player.contains(PLAYER + "PlayerInteractEvent$RightClickBlock"), "its listeners reach it through PlayerInteractEvent");
        // EntityEvent names what LivingEvent and PlayerEvent name, so a handler on it reaches them too.
        assertTrue(busless.get(EVENT + "entity/EntityEvent").containsAll(busless.get(EVENT + "entity/living/LivingEvent")));
        assertTrue(busless.get(EVENT + "entity/living/LivingEvent").containsAll(player));
        // Forge 1.20.1 posted the sound engine's load on the mod bus, so an old game-bus SoundEvent listener never heard it.
        assertFalse(busless.get(CLIENT + "sound/SoundEvent").contains(CLIENT + "sound/SoundEngineLoadEvent"));
        assertTrue(busless.get(CLIENT + "sound/SoundEvent").contains(CLIENT + "sound/PlaySoundEvent"));
        // Every ModelEvent event Forge 1.20.1 had sat on the mod bus, so an old game-bus listener on it heard nothing.
        assertFalse(busless.containsKey(CLIENT + "ModelEvent"));
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("A cancellable event names the cancellable events below it that take its listeners, for their monitors")
    void heirsAreNamed(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        Function<String, ClassNode> offline = name -> null;
        String teleport = EVENT + "entity/EntityTeleportEvent";

        assertTrue(LegacyCancelBridge.heirs(teleport, offline).containsAll(Set.of(teleport + "$ChorusFruit", teleport + "$EnderPearl")));
        assertEquals(Set.of(CLIENT + "ClientChatReceivedEvent$Player"), LegacyCancelBridge.heirs(CLIENT + "ClientChatReceivedEvent", offline));
        assertEquals(Set.of(), LegacyCancelBridge.heirs(PLAYER + "PlayerInteractEvent$RightClickBlock", offline));
    }

    @ParameterizedTest(name = "Forge {0}")
    @ValueSource(strings = {"26.2", "26.3"})
    @DisplayName("The bus bridge's table fits in one class file constant")
    void bridgeTableFitsOneConstant(String host) {
        int bytes = LegacyCancelBridge.buslessTable(host).getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes > 0 && bytes < 65_535, "a constant holds at most 65535 bytes, the table has " + bytes);
    }

    @Test
    @DisplayName("Each section of the bundled table is what scripts/ForgeEventTable.java reads from that Forge's jars")
    void tableMatchesTheForgeJars() throws Exception {
        Map<String, List<Path>> sections = new LinkedHashMap<>();
        String libraries = System.getProperty("user.home") + "/Library/Application Support/PrismLauncher/libraries/net/minecraftforge/";
        // Forge 1.20.1's jars tell which events old mods heard on the mod bus.
        addIfPresent(sections, "old", Path.of(libraries, "forge/1.20.1-47.4.10/forge-1.20.1-47.4.10-universal.jar"),
                Path.of(libraries, "fmlcore/1.20.1-47.4.10/fmlcore-1.20.1-47.4.10.jar"));
        Assumptions.assumeTrue(sections.containsKey("old"), "no Forge 1.20.1 jars to tell the old buses");
        List<Path> old = sections.remove("old");
        addIfPresent(sections, "26.2", Path.of(libraries, "forge/26.2-65.0.0/forge-26.2-65.0.0-universal.jar"),
                Path.of(libraries, "fmlcore/26.2-65.0.0/fmlcore-26.2-65.0.0.jar"), Path.of(libraries, "eventbus/7.0.1/eventbus-7.0.1.jar"));
        Path forge263 = Path.of(System.getProperty("retromod.test.forge263",
                System.getProperty("user.home") + "/.claude/jobs/3da2a019/tmp/forge263"));
        addIfPresent(sections, "26.3", forge263.resolve("forge-26.3-66.1.2-universal.jar"),
                forge263.resolve("fmlcore-26.3-66.1.2.jar"), forge263.resolve("eventbus-7.0.6.jar"));
        Assumptions.assumeFalse(sections.isEmpty(), "no Forge 26.2 or 26.3 jars to read the table from");

        List<String> generated = runScript(old, sections);
        List<String> bundled = rows(readBundled());
        for (String version : sections.keySet()) {
            assertEquals(rowsOf(bundled, version), rowsOf(generated, version), "the " + version + " section; rerun the script");
        }
    }

    // A section is read only when all of its jars are there.
    private static void addIfPresent(Map<String, List<Path>> sections, String version, Path... jars) {
        for (Path jar : jars) {
            if (!Files.isRegularFile(jar)) return;
        }
        sections.put(version, List.of(jars));
    }

    /** The script's output, run as its usage says, with this build's ASM. */
    private static List<String> runScript(List<Path> old, Map<String, List<Path>> sections) throws Exception {
        Path asm = Path.of(ClassReader.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", asm.toString(), SCRIPT.toString(), "--old"));
        old.forEach(jar -> command.add(jar.toString()));
        sections.forEach((version, jars) -> {
            command.add("--section");
            command.add(version);
            jars.forEach(jar -> command.add(jar.toString()));
        });
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "the script finished");
        assertEquals(0, process.exitValue(), "the script ran");
        return rows(output);
    }

    private static String readBundled() throws IOException {
        try (InputStream in = LegacyCancelBridge.class.getClassLoader().getResourceAsStream(TABLE)) {
            assertNotNull(in, TABLE + " is bundled");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // The data rows, without the comments that name the jars read.
    private static List<String> rows(String table) {
        return table.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
    }

    private static List<String> rowsOf(List<String> rows, String version) {
        return rows.stream().filter(row -> row.startsWith(version + "\t")).toList();
    }
}
