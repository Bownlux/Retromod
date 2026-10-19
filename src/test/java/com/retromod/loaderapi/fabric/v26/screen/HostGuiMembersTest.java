/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.screen;

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
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Every host member the HUD, screen, tooltip and debug text helpers call exists on each 26.x host they serve. */
class HostGuiMembersTest {

    private static final String IDENTIFIER_DESC = "Lnet/minecraft/resources/Identifier;";

    /** Every {@link Member} constant of {@link HostGui}, plus the vanilla HUD ids the layer helpers read. */
    private static List<Member> members() throws IllegalAccessException {
        List<Member> members = new ArrayList<>();
        for (Field field : HostGui.class.getDeclaredFields()) {
            if (field.getType() == Member.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                members.add((Member) field.get(null));
            }
        }
        // The removed ids map onto these elements.
        for (String name : List.of("SCOREBOARD", "DEMO_TIMER", "MOB_EFFECTS", "INFO_BAR", "HOTBAR")) {
            members.add(new Member(HostGui.VANILLA_HUD, name, IDENTIFIER_DESC, true));
        }
        for (String name : LegacyHudLayers.HOTBAR_GROUP) {
            members.add(new Member(HostGui.VANILLA_HUD, name, IDENTIFIER_DESC, true));
        }
        return members;
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"26.1", "26.2", "26.3", "26.4"})
    @DisplayName("The Fabric API members the screen helpers call are on every indexed host")
    void fabricMembersExist(String line) throws Exception {
        HostSurface surface = HostSurface.forLine(line);
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Member member : members()) {
            if (!member.owner().startsWith("net/fabricmc/fabric/")) continue;
            checked++;
            if (surface.member(member.owner(), member.name(), member.desc()) == HostSurface.Presence.MISSING) {
                missing.add(member.owner() + "." + member.name() + member.desc());
            }
        }
        assertTrue(checked >= 15, "the member list lost its Fabric API members");
        assertEquals(List.of(), missing, "on " + line);
    }

    @ParameterizedTest(name = "in {0}")
    @ValueSource(strings = {"minecraft-26.1.2-client.jar", "minecraft-26.3-client.jar"})
    @DisplayName("The Minecraft members the screen helpers call are in the local client jars")
    void minecraftMembersExist(String jarName) throws Exception {
        Path jar = Path.of("test-jars-mixin", jarName);
        assumeTrue(Files.exists(jar), jar + " is local test material and is not checked in");
        List<String> missing = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Member member : members()) {
                if (!member.owner().startsWith("net/minecraft/")) continue;
                var entry = zip.getEntry(member.owner() + ".class");
                if (entry == null) {
                    missing.add(member.owner());
                    continue;
                }
                ClassNode node = new ClassNode();
                new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_CODE);
                // DebugScreenEntries.register is private in the jar; Fabric API widens it on 26.1 to 26.3.
                boolean found = member.desc().startsWith("(")
                        ? node.methods.stream().anyMatch(m -> m.name.equals(member.name()) && m.desc.equals(member.desc()))
                        : node.fields.stream().anyMatch(f -> f.name.equals(member.name()) && f.desc.equals(member.desc()));
                if (!found) missing.add(member.owner() + "." + member.name() + member.desc());
            }
        }
        assertEquals(List.of(), missing, "in " + jarName);
    }
}
