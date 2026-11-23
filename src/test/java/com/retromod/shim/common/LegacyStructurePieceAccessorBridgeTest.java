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
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.retromod.shim.common.LegacyStructurePieceAccessorBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** Structure pieces written for 26.2's StructurePieceAccessor build their children on 26.3, which deleted it. */
class LegacyStructurePieceAccessorBridgeTest {

    private static final String HOST = "net/minecraft/world/level/levelgen/structure/Host";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Without the bridge the mod's piece code cannot link on 26.3, which has no StructurePieceAccessor")
    void oldPieceFailsWithoutTheBridge(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, t -> {});
        assertThrows(NoClassDefFoundError.class, () -> run.call("mod/Pieces", "start"));
    }

    @Test
    @DisplayName("The host's addChildren reaches the old override, which adds and finds pieces on the builder")
    void oldPieceBuildsItsChildren(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, t -> register(t, name -> null));
        assertEquals("start,hall,room", run.call(HOST, "build", run.call("mod/Pieces", "start")),
                "the override ran through the new descriptor, and the mod's own helper took the builder");
    }

    @Test
    @DisplayName("The move registers only where the host's builder no longer implements the accessor; offline the target decides")
    void followsTheHost() {
        ClassNode oldBuilder = new ClassNode();
        oldBuilder.interfaces = new java.util.ArrayList<>(List.of(ACCESSOR));
        assertFalse(hostDeletedIt(name -> name.equals(BUILDER) ? oldBuilder : null), "26.2");
        assertTrue(hostDeletedIt(name -> name.equals(BUILDER) ? new ClassNode() : null), "26.3");
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        assertFalse(hostDeletedIt(name -> null));
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertTrue(hostDeletedIt(name -> null));
    }

    private BridgeHarness.Run run(Path dir, Consumer<RetromodTransformer> register) throws Exception {
        Consumer<RetromodTransformer> all = register.andThen(t -> t.setModSourceVersion("1.21.1"));
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", all,
                BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), withShared(OLD_262)), "mod/"));
        return new BridgeHarness.Run(BridgeHarness.compile(dir.resolve("host"), withShared(HOST_263)), mod);
    }

    private static Map<String, String> withShared(Map<String, String> sources) {
        Map<String, String> all = new HashMap<>(SHARED);
        all.putAll(sources);
        return all;
    }

    /** Types that are the same on 26.2 and 26.3, and the mod's pieces, which only name the piece type. */
    private static final Map<String, String> SHARED = Map.of(
            "net/minecraft/util/RandomSource.java", """
                    package net.minecraft.util;
                    public interface RandomSource {}""",
            "net/minecraft/world/level/levelgen/structure/BoundingBox.java", """
                    package net.minecraft.world.level.levelgen.structure;
                    public record BoundingBox(int x) {}""");

    /** 26.2's accessor and builder, and a mod written like YUNG's Better Dungeons. */
    private static final Map<String, String> OLD_262 = Map.of(
            "net/minecraft/world/level/levelgen/structure/StructurePieceAccessor.java", """
                    package net.minecraft.world.level.levelgen.structure;
                    public interface StructurePieceAccessor {
                        void addPiece(StructurePiece piece);
                        StructurePiece findCollisionPiece(BoundingBox box);
                    }""",
            "net/minecraft/world/level/levelgen/structure/StructurePiece.java", """
                    package net.minecraft.world.level.levelgen.structure;
                    import net.minecraft.util.RandomSource;
                    public abstract class StructurePiece {
                        public void addChildren(StructurePiece start, StructurePieceAccessor accessor, RandomSource random) {}
                    }""",
            "mod/RoomPiece.java", """
                    package mod;
                    import net.minecraft.util.RandomSource;
                    import net.minecraft.world.level.levelgen.structure.BoundingBox;
                    import net.minecraft.world.level.levelgen.structure.StructurePiece;
                    import net.minecraft.world.level.levelgen.structure.StructurePieceAccessor;
                    public class RoomPiece extends StructurePiece {
                        private final String name;
                        RoomPiece(String name) { this.name = name; }
                        @Override public void addChildren(StructurePiece start, StructurePieceAccessor accessor, RandomSource random) {
                            if (!name.equals("start")) return;
                            accessor.addPiece(new RoomPiece("hall"));
                            if (accessor.findCollisionPiece(new BoundingBox(1)) == null) Pieces.place(accessor, "room");
                        }
                        @Override public String toString() { return name; }
                    }""",
            "mod/Pieces.java", """
                    package mod;
                    import net.minecraft.world.level.levelgen.structure.StructurePieceAccessor;
                    public class Pieces {
                        public static Object start() { return new RoomPiece("start"); }
                        public static void place(StructurePieceAccessor accessor, String name) { accessor.addPiece(new RoomPiece(name)); }
                    }""");

    /** 26.3's piece and builder, with no accessor, and a host class that builds a structure as the game does. */
    private static final Map<String, String> HOST_263 = Map.of(
            "net/minecraft/world/level/levelgen/structure/StructurePiece.java", """
                    package net.minecraft.world.level.levelgen.structure;
                    import net.minecraft.util.RandomSource;
                    import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
                    public abstract class StructurePiece {
                        public void addChildren(StructurePiece start, StructurePiecesBuilder builder, RandomSource random) {}
                    }""",
            "net/minecraft/world/level/levelgen/structure/pieces/StructurePiecesBuilder.java", """
                    package net.minecraft.world.level.levelgen.structure.pieces;
                    import java.util.ArrayList;
                    import java.util.List;
                    import net.minecraft.world.level.levelgen.structure.BoundingBox;
                    import net.minecraft.world.level.levelgen.structure.StructurePiece;
                    public class StructurePiecesBuilder {
                        public final List<StructurePiece> pieces = new ArrayList<>();
                        public void addPiece(StructurePiece piece) { pieces.add(piece); }
                        public StructurePiece findCollisionPiece(BoundingBox box) { return null; }
                    }""",
            "net/minecraft/world/level/levelgen/structure/Host.java", """
                    package net.minecraft.world.level.levelgen.structure;
                    import java.util.stream.Collectors;
                    import java.util.stream.Stream;
                    import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
                    public final class Host {
                        public static String build(Object start) {
                            StructurePiece piece = (StructurePiece) start;
                            StructurePiecesBuilder builder = new StructurePiecesBuilder();
                            builder.addPiece(piece);
                            piece.addChildren(piece, builder, null);
                            return builder.pieces.stream().map(Object::toString).collect(Collectors.joining(","));
                        }
                    }""");
}
