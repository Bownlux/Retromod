/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.content;

import com.retromod.loaderapi.fabric.v26.HostLink.Member;
import com.retromod.loaderapi.fabric.v26.HostSurface;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Every host member the content registry helpers call exists on each 26.x host that reaches it. */
class ContentHostMembersTest {

    // Only 26.1 and 26.2 build fuel values in code; only 26.3 and newer have the item components
    // and block transformer helper the other paths use.
    private static final Set<Member> FUEL_EVENT_ONLY = Set.of(LegacyContentRegistries.FUEL_BUILD,
            LegacyContentRegistries.FUEL_ADD, LegacyContentRegistries.FUEL_ADD_TAG,
            LegacyContentRegistries.FUEL_REMOVE_TAG);
    // Only 26.1 and 26.2 have Fabric API's brewing builder, which the 1.20.1 recipes join there.
    private static final Set<Member> BREWING_BUILDER_ONLY = Set.of(LegacyBrewing.HOST_BUILD,
            LegacyBrewing.HOST_POTION_RECIPE, LegacyBrewing.HOST_ITEM_RECIPE);

    /** Every {@link Member} constant of the content helpers, which is every member they call. */
    private static List<Member> members() throws IllegalAccessException {
        List<Member> members = new ArrayList<>();
        for (Class<?> helper : List.of(LegacyContentRegistries.class, LegacyHoeActions.class, LegacyTrades.class,
                LegacyWorldPacks.class, LegacyBrewing.class)) {
            for (Field field : helper.getDeclaredFields()) {
                if (field.getType() == Member.class && Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    members.add((Member) field.get(null));
                }
            }
        }
        return members;
    }

    private static boolean reachedOn(Member member, String line) {
        boolean fuelEventLine = line.equals("26.1") || line.equals("26.2");
        if (FUEL_EVENT_ONLY.contains(member) || BREWING_BUILDER_ONLY.contains(member)) return fuelEventLine;
        // The tilling, compost and fuel component paths run only where 26.3 removed the old registries.
        boolean newerOnly = member.owner().startsWith("net/fabricmc/fabric/api/item/v1/")
                || member.owner().startsWith("net/minecraft/world/item/component/")
                || member.owner().equals("net/minecraft/world/food/VillagerFood")
                || member.owner().startsWith("net/minecraft/world/level/storage/loot/")
                || member.owner().equals("net/minecraft/core/component/DataComponents")
                || member.owner().equals("net/minecraft/core/component/DataComponentMap$Builder")
                || member.owner().equals("net/minecraft/core/registries/Registries");
        return !newerOnly || !fuelEventLine;
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3", "26.4"})
    @DisplayName("The Fabric API members the content helpers call are on every host that reaches them")
    void fabricMembersExist(String line) throws Exception {
        HostSurface surface = HostSurface.forLine(line);
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Member member : members()) {
            if (!member.owner().startsWith("net/fabricmc/fabric/") || !reachedOn(member, line)) continue;
            checked++;
            if (surface.member(member.owner(), member.name(), member.desc()) == HostSurface.Presence.MISSING) {
                missing.add(member.owner() + "." + member.name() + member.desc());
            }
        }
        assertTrue(checked >= 2, "the member list lost its Fabric API members on " + line);
        assertEquals(List.of(), missing, "on " + line);
    }

    @ParameterizedTest(name = "in {0}")
    @ValueSource(strings = {"26.1.2", "26.3"})
    @DisplayName("The Minecraft members the content helpers call are in the local client jars")
    void minecraftMembersExist(String version) throws Exception {
        Path jar = releaseJar(version);
        assumeTrue(jar != null, "no local " + version + " release client jar; it is local test material");
        String line = version.substring(0, version.lastIndexOf('.') > 2 ? version.lastIndexOf('.') : version.length());
        List<String> missing = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Member member : members()) {
                if (!member.owner().startsWith("net/minecraft/") || !reachedOn(member, line)) continue;
                if (!declaredInHierarchy(zip, member.owner(), member)) {
                    missing.add(member.owner() + "." + member.name() + member.desc());
                }
            }
        }
        assertEquals(List.of(), missing, "in " + jar);
    }

    /** The release client jar for {@code version}, from the launcher's library or the test folder, or null. */
    private static Path releaseJar(String version) throws Exception {
        String name = "minecraft-" + version + "-client.jar";
        for (Path candidate : List.of(
                Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries",
                        "com/mojang/minecraft", version, name),
                Path.of("test-jars-mixin", name))) {
            if (!Files.exists(candidate)) continue;
            // A snapshot saved under the release name lacks members the release added.
            try (ZipFile zip = new ZipFile(candidate.toFile())) {
                var entry = zip.getEntry("version.json");
                String json = entry == null ? "" : new String(zip.getInputStream(entry).readAllBytes());
                if (json.contains("\"id\": \"" + version + "\"") || json.contains("\"id\":\"" + version + "\"")) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /** Whether the owner or a supertype in the jar declares the member, as a handle lookup finds it. */
    private static boolean declaredInHierarchy(ZipFile zip, String owner, Member member) throws Exception {
        List<String> pending = new ArrayList<>(List.of(owner));
        while (!pending.isEmpty()) {
            var entry = zip.getEntry(pending.remove(0) + ".class");
            if (entry == null) continue;
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
            boolean found = member.desc().startsWith("(")
                    ? node.methods.stream().anyMatch(m -> m.name.equals(member.name()) && m.desc.equals(member.desc()))
                    : node.fields.stream().anyMatch(f -> f.name.equals(member.name()) && f.desc.equals(member.desc()));
            if (found) return true;
            if (node.superName != null) pending.add(node.superName);
            pending.addAll(node.interfaces);
        }
        return false;
    }
}
