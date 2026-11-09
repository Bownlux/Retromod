/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** An old mod's {@code getTeam()} calls link to the PlayerTeam form 1.20.3 gave Entity. */
class LegacyEntityTeamBridgeTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Calls on an entity, on a player type and on a mod's own entity all read the host's team")
    void oldCallsReadThePlayerTeam(@TempDir Path dir) throws Throwable {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), world("Team"));
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("new"), world("PlayerTeam"));
        Map<String, byte[]> mod = BridgeHarness.transform("26.2", t -> LegacyEntityTeamBridge.register(t, reader(host),
                LegacyEntityTeamBridge.ENTITY, "getTeam", LegacyEntityTeamBridge.OLD_DESC, LegacyEntityTeamBridge.NEW_DESC,
                LegacyEntityTeamBridge.OWNERS), BridgeHarness.only(old, "mod/"), host);
        BridgeHarness.Run run = new BridgeHarness.Run(host, mod);
        assertEquals("red", run.call("mod/Teams", "names"));
    }

    @Test
    @DisplayName("A host whose entity still returns Team keeps the call")
    void olderHostKeepsTheCall(@TempDir Path dir) throws Throwable {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), world("Team"));
        Map<String, byte[]> mod = BridgeHarness.transform("1.20.2", t -> LegacyEntityTeamBridge.register(t, reader(old),
                LegacyEntityTeamBridge.ENTITY, "getTeam", LegacyEntityTeamBridge.OLD_DESC, LegacyEntityTeamBridge.NEW_DESC,
                LegacyEntityTeamBridge.OWNERS), BridgeHarness.only(old, "mod/"), old);
        assertEquals(List.of("net/minecraft/world/entity/Entity.getTeam" + LegacyEntityTeamBridge.OLD_DESC),
                BridgeHarness.calls(mod.get("mod/Teams"), "entity").stream().filter(c -> c.contains("getTeam")).toList());
    }

    private static Function<String, ClassNode> reader(Map<String, byte[]> classes) {
        return name -> {
            byte[] bytes = classes.get(name);
            if (bytes == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            return node;
        };
    }

    // Team and PlayerTeam on both sides; only what Entity.getTeam returns differs.
    private static Map<String, String> world(String returned) {
        Map<String, String> s = new HashMap<>();
        s.put("net/minecraft/world/scores/Team.java", """
                package net.minecraft.world.scores;
                public abstract class Team { public abstract String getName(); }""");
        s.put("net/minecraft/world/scores/PlayerTeam.java", """
                package net.minecraft.world.scores;
                public class PlayerTeam extends Team { public String getName() { return "red"; } }""");
        s.put("net/minecraft/world/entity/Entity.java", """
                package net.minecraft.world.entity;
                public class Entity {
                    public net.minecraft.world.scores.RETURNED getTeam() { return new net.minecraft.world.scores.PlayerTeam(); }
                }""".replace("RETURNED", returned));
        s.put("net/minecraft/client/player/LocalPlayer.java", """
                package net.minecraft.client.player;
                public class LocalPlayer extends net.minecraft.world.entity.Entity {}""");
        s.put("mod/Ghost.java", """
                package mod;
                public class Ghost extends net.minecraft.world.entity.Entity {}""");
        s.put("mod/Teams.java", """
                package mod;
                import net.minecraft.world.scores.Team;
                public final class Teams {
                    public static Team entity(net.minecraft.world.entity.Entity e) { return e.getTeam(); }
                    public static String names() {
                        Team a = entity(new Ghost());
                        Team b = new net.minecraft.client.player.LocalPlayer().getTeam();
                        Team c = new Ghost().getTeam();
                        return a.getName().equals(b.getName()) && b.getName().equals(c.getName()) ? a.getName() : "mismatch";
                    }
                }""");
        return s;
    }
}
