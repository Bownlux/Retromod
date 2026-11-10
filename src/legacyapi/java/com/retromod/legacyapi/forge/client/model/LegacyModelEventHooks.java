/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.client.model;

import com.retromod.legacyapi.forge.client.LegacyClientRenderBootstrap;
import com.retromod.legacyapi.forge.client.event.LegacyRegisterAdditional;
import com.retromod.legacyapi.forge.eventbus.LegacyEvents;
import com.retromod.shim.common.embedded.LegacyLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

/** This copy's host listeners: the bake BakingCompleted reads, and RegisterAdditional each time a reload reads item models. */
final class LegacyModelEventHooks {

    private static final Set<String> NOTED = ConcurrentHashMap.newKeySet();
    // The folder this copy's pack lives in, chosen once per launch; each reload writes its item models again.
    private static volatile Path root;
    private static volatile boolean temporary;
    // Minecraft lists its packs on its own thread, which must never wait for Forge's common setup.
    private static volatile Thread gameThread;
    private static volatile Set<String> modNamespaces;

    private LegacyModelEventHooks() {}

    static void attach() {
        // BakingCompleted and getModel read the latest bake, which only this event hands over.
        ModelEvent.ModifyBakingResult.BUS.addListener(Priority.HIGHEST, event -> LegacyModelMaps.capture(event.getResults()));
        // Forge 26.2 offers client pack sources right after every mod constructed. Minecraft lists the packs there,
        // before Forge's common setup, and again before every resource reload. Forge 1.20.1 asked mods for extra models
        // while each reload loaded models, beside common setup, so the pack asks when the reload reads its item models.
        AddPackFindersEvent.BUS.addListener(event -> {
            if (event.getPackType() == PackType.CLIENT_RESOURCES) event.addRepositorySource(LegacyModelEventHooks::offer);
        });
    }

    // This runs while Minecraft lists its packs, where a throw would stop the game. It posts nothing: a listener may
    // wait for common setup, which the first listing comes before.
    private static void offer(Consumer<Pack> sink) {
        gameThread = Thread.currentThread();
        try {
            if (listeningMods().isEmpty()) return;
            Path folder = folder();
            writeMeta(folder);
            Pack pack = pack(folder);
            if (pack != null) sink.accept(pack);
        } catch (IOException | RuntimeException | LinkageError failure) {
            LegacyLog.warn("Retromod did not add the extra models of " + LegacyClientRenderBootstrap.namespaces()
                    + ", so ModelManager.getModel answers the missing model for them: " + failure);
        }
    }

    /** Whether Forge's common setup is done, waiting for it off the game's thread; false when it failed. */
    static boolean awaitSetup() {
        return LegacySetupGate.awaitCommonSetup(gameThread);
    }

    /** Posts RegisterAdditional, after common setup, then writes an item model for each model it asked for. */
    static void writeItemModels(Path folder) {
        try {
            writeItems(folder, postRegisterAdditional());
        } catch (IOException | RuntimeException | LinkageError failure) {
            LegacyLog.warn("Retromod did not add the extra models of " + LegacyClientRenderBootstrap.namespaces()
                    + ", so ModelManager.getModel answers the missing model for them: " + failure);
        }
    }

    /** Whether this is the thread Minecraft lists its packs on, while Forge's common setup is still to come. */
    static boolean onGameThreadBeforeSetup() {
        return Thread.currentThread() == gameThread && !LegacySetupGate.commonSetupDone();
    }

    /** Every loaded mod's namespace, where an extra model most likely lives. */
    static Set<String> modNamespaces() {
        Set<String> known = modNamespaces;
        if (known != null) return known;
        Set<String> found = new LinkedHashSet<>();
        try {
            ModList.forEachModInOrder(mod -> found.add(mod.getNamespace()));
        } catch (RuntimeException | LinkageError noModList) {
            found.addAll(LegacyClientRenderBootstrap.namespaces());
        }
        modNamespaces = found;
        return found;
    }

    // This copy's mods that listen for RegisterAdditional, in FML's mod order, as Forge 1.20.1 posted mod-bus events.
    private static List<String> listeningMods() {
        List<String> listening = new ArrayList<>();
        for (String mod : modOrder()) {
            BusGroup group = LegacyClientRenderBootstrap.modBusGroup(mod);
            if (group != null && LegacyEvents.busOf(group, LegacyRegisterAdditional.class).hasListeners()) listening.add(mod);
        }
        return listening;
    }

    private static Set<Identifier> postRegisterAdditional() {
        Set<Identifier> models = new LinkedHashSet<>();
        for (String mod : listeningMods()) {
            BusGroup group = LegacyClientRenderBootstrap.modBusGroup(mod);
            try {
                LegacyEvents.post(group, new LegacyRegisterAdditional(models));
            } catch (RuntimeException | LinkageError failure) {
                LegacyLog.warn("A RegisterAdditional listener of " + mod + " threw; Retromod went on with the next mod: "
                        + failure);
            }
        }
        return models;
    }

    // The pack's description, which the pack list reads while it lists packs.
    private static void writeMeta(Path folder) throws IOException {
        int format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
        // 1.21.9 replaced pack_format with a format range; each version reads its own key.
        write(folder.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":" + format + ",\"min_format\":" + format
                + ",\"max_format\":" + format + ",\"description\":\"Models old mods registered on their own\"}}");
    }

    /** One item definition per extra model, so Minecraft bakes each as an item model. */
    static void writeItems(Path folder, Set<Identifier> models) throws IOException {
        clear(folder.resolve("assets"));
        for (Identifier location : models) {
            // One bad location costs only its own model.
            try {
                LegacyModelMaps.Extra extra = LegacyModelMaps.registerAdditional(location);
                if (extra == null) {
                    note(location, "Retromod reads the extra model " + location + " from its block state, as Forge 1.20.1"
                            + " did, so it writes no item model for it.");
                    continue;
                }
                // Only the plain model type, which every host has, so a definition cannot fail to parse.
                Identifier item = extra.item();
                write(folder.resolve("assets").resolve(item.getNamespace()).resolve("items").resolve(item.getPath() + ".json"),
                        "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + extra.model() + "\"}}");
            } catch (IOException | RuntimeException failure) {
                LegacyLog.warnOnce("legacy extra model " + location, "Retromod did not add the extra model " + location
                        + ", so ModelManager.getModel answers the missing model for it: " + failure);
            }
        }
    }

    private static Pack pack(Path folder) {
        try {
            PackLocationInfo location = new PackLocationInfo(packId(), Component.literal("Retromod extra models"),
                    PackSource.BUILT_IN, Optional.empty());
            return Pack.readMetaAndCreate(location, LegacyExtraModelPack.supplier(folder), PackType.CLIENT_RESOURCES,
                    new PackSelectionConfig(true, Pack.Position.TOP, false));
        } catch (RuntimeException | LinkageError failure) {
            LegacyLog.warn("Retromod could not open its extra model pack " + folder + ": " + failure);
            return null;
        }
    }

    // A stable id per copy, so the options file keeps the same pack from launch to launch.
    private static String packId() {
        return "retromod_extra_models_" + key();
    }

    private static String key() {
        List<String> mods = LegacyClientRenderBootstrap.namespaces();
        String first = mods.isEmpty() ? "unknown" : mods.get(0);
        return first.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    // retromod-cache/extra-models/<mod> in the game folder; a private temporary folder when that one cannot be used.
    private static synchronized Path folder() throws IOException {
        Path known = root;
        if (known != null) return known;
        try {
            Path cache = FMLPaths.GAMEDIR.get().resolve("retromod-cache");
            Path models = cache.resolve("extra-models");
            if (Files.isSymbolicLink(cache) || Files.isSymbolicLink(models)) throw new IOException(cache + " is a link");
            known = Files.createDirectories(models.resolve(key()));
        } catch (IOException | RuntimeException | LinkageError unusable) {
            known = Files.createTempDirectory("retromod-extra-models");
            known.toFile().deleteOnExit();
            temporary = true;
        }
        root = known;
        return known;
    }

    // Each reload writes every item model again, so a model no mod registers any more is gone.
    private static void clear(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!file.equals(folder)) Files.deleteIfExists(file);
            }
        }
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
        if (temporary) forgetOnExit(file);
    }

    // The JVM deletes in the reverse order of these notes, so each folder is noted before what it holds.
    private static void forgetOnExit(Path file) {
        Path folder = root;
        for (Path name : folder.relativize(file.getParent())) {
            folder = folder.resolve(name);
            folder.toFile().deleteOnExit();
        }
        file.toFile().deleteOnExit();
    }

    private static void note(Identifier location, String message) {
        if (NOTED.add(location.toString())) LegacyLog.debug(message);
    }

    // FML's mod order among this copy's mods; the order they installed in when FML cannot give it.
    private static List<String> modOrder() {
        List<String> mine = LegacyClientRenderBootstrap.namespaces();
        List<String> ordered = new ArrayList<>();
        try {
            ModList.forEachModInOrder(mod -> {
                if (mine.contains(mod.getNamespace())) ordered.add(mod.getNamespace());
            });
        } catch (RuntimeException | LinkageError noModList) {
            ordered.clear();
        }
        for (String mod : mine) {
            if (!ordered.contains(mod)) ordered.add(mod);
        }
        return ordered;
    }
}
