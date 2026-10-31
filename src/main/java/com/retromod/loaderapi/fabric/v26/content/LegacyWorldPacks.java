/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.content;

import com.retromod.loaderapi.fabric.v26.HostLink;
import com.retromod.loaderapi.fabric.v26.HostLink.Member;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.retromod.loaderapi.fabric.v26.content.LegacyContentRegistries.call;

/** Writes what old mods registered in code into a world data pack once the server has started. */
final class LegacyWorldPacks {

    /** The namespace every legacy pack writes its own files under. */
    static final String NAMESPACE = "retromod_legacy";

    private static final String LIFECYCLE = "net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents";
    static final Member SERVER_STARTED = new Member(LIFECYCLE, "SERVER_STARTED",
            "Lnet/fabricmc/fabric/api/event/Event;", true);
    static final String SERVER_STARTED_TYPE = LIFECYCLE + "$ServerStarted";
    static final Member WORLD_PATH = Member.virtual("net/minecraft/server/MinecraftServer", "getWorldPath",
            "(Lnet/minecraft/world/level/storage/LevelResource;)Ljava/nio/file/Path;");
    static final Member DATAPACK_DIR = new Member("net/minecraft/world/level/storage/LevelResource",
            "DATAPACK_DIR", "Lnet/minecraft/world/level/storage/LevelResource;", true);

    private LegacyWorldPacks() {}

    /** Runs {@code action} with the server each time one has started. */
    static void onServerStarted(String name, Consumer<Object> action) {
        Object event = call(HostLink.require(SERVER_STARTED));
        call(HostLink.require(LegacyContentRegistries.REGISTER_LISTENER), event,
                proxy(SERVER_STARTED_TYPE, (proxy, method, args) -> {
                    if (method.getName().equals("onServerStarted")) {
                        action.accept(args[0]);
                        return null;
                    }
                    return switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "Retromod " + name + " listener";
                        default -> null;
                    };
                }));
    }

    /** The folder of pack {@code packId} in the world's data pack folder. */
    static Path folder(Object server, String packId) {
        Path datapacks = (Path) call(HostLink.require(WORLD_PATH), server, call(HostLink.require(DATAPACK_DIR)));
        return datapacks.resolve(packId);
    }

    /** A pack.mcmeta that only this game's data pack format reads. */
    static void writePackMeta(Path root, int packFormat, String description) throws IOException {
        writeFile(root.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":" + packFormat + ",\"min_format\":"
                + packFormat + ",\"max_format\":" + packFormat + ",\"description\":\"" + description + "\"}}");
    }

    /** The pack's data files and contents, to tell whether a write changed anything. */
    static String snapshot(Path root) throws IOException {
        Path data = root.resolve("data");
        if (!Files.exists(data)) return "";
        StringBuilder out = new StringBuilder();
        try (Stream<Path> walk = Files.walk(data)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                out.append(data.relativize(file)).append('\n').append(Files.readString(file)).append('\n');
            }
        }
        return out.toString();
    }

    static void writeFile(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static Object proxy(String interfaceName, InvocationHandler handler) {
        Class<?> type = HostLink.type(interfaceName).orElseThrow(() -> new IllegalStateException(
                "This Minecraft host has no " + interfaceName + ". The loader API runtime cannot bridge this call here."));
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
