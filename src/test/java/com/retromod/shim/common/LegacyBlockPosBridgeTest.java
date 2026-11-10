/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_26_2_to_26_3;
import com.retromod.shim.forge.Forge_26_2_to_26_3;
import com.retromod.shim.neoforge.NeoForge_26_2_to_26_3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** {@code new BlockPos(vec)} and a subclass's {@code super(vec)} on 26.3, which removed that constructor. */
class LegacyBlockPosBridgeTest {

    private static final String CALLS = "mod/Positions";
    private static final String CORNER = "mod/Corner";
    private static final String OLD_CTOR = LegacyBlockPosBridge.BLOCK_POS + ".<init>" + LegacyBlockPosBridge.OLD_CTOR;

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mod;
    private static Map<String, byte[]> host263;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        String vec3i = """
                package net.minecraft.core;
                public class Vec3i {
                    private final int x, y, z;
                    public Vec3i(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
                    public int getX() { return x; }
                    public int getY() { return y; }
                    public int getZ() { return z; }
                    public String toString() { return x + "," + y + "," + z; }
                }""";
        mod = only(compile(dir.resolve("old"), Map.of(
                "net/minecraft/core/Vec3i.java", vec3i,
                "net/minecraft/core/BlockPos.java", """
                        package net.minecraft.core;
                        public class BlockPos extends Vec3i {
                            public BlockPos(int x, int y, int z) { super(x, y, z); }
                            public BlockPos(Vec3i v) { this(v.getX(), v.getY(), v.getZ()); }
                        }""",
                "mod/Corner.java", """
                        package mod;
                        public class Corner extends net.minecraft.core.BlockPos {
                            public Corner(net.minecraft.core.Vec3i v) { super(v); }
                        }""",
                "mod/Positions.java", """
                        package mod;
                        import net.minecraft.core.*;
                        public class Positions {
                            public static String copy(int x, int y, int z) {
                                return new BlockPos(new Vec3i(x, y, z)).toString();
                            }
                            public static String corner(int x, int y, int z) {
                                return new Corner(new Vec3i(x, y, z)).toString();
                            }
                        }""")), "mod/");
        host263 = compile(dir.resolve("host"), Map.of(
                "net/minecraft/core/Vec3i.java", vec3i,
                "net/minecraft/core/BlockPos.java", """
                        package net.minecraft.core;
                        public class BlockPos extends Vec3i {
                            public BlockPos(int x, int y, int z) { super(x, y, z); }
                        }"""));
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("on 26.3 both constructions fail as written and copy the coordinates once bridged")
    void bridgedConstructionsCopyTheCoordinates() throws Throwable {
        Run old = new Run(host263, mod);
        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class, () -> old.call(CALLS, "copy", 1, 2, 3)));
        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class, () -> old.call(CALLS, "corner", 1, 2, 3)));

        Run bridged = new Run(host263, transform("26.3", LegacyBlockPosBridge::register, mod));
        assertEquals("1,2,3", bridged.call(CALLS, "copy", 1, 2, 3));
        assertEquals("-4,5,6", bridged.call(CALLS, "corner", -4, 5, 6), "the subclass's super(vec) unpacks it");
    }

    @Test
    @DisplayName("each loader's 26.2 to 26.3 shim bridges both")
    void everyLoaderBridgesThem() {
        for (VersionShim shim : List.of(new NeoForge_26_2_to_26_3(), new Forge_26_2_to_26_3(),
                new Fabric_26_2_to_26_3())) {
            Map<String, byte[]> out = transform("26.3", shim::registerRedirects, mod);
            assertFalse(calls(out.get(CALLS), "copy").contains(OLD_CTOR), shim.getShimName());
            assertFalse(calls(out.get(CORNER), "<init>").contains(OLD_CTOR), shim.getShimName());
        }
    }

    @Test
    @DisplayName("a 26.2 host, which still has the constructor, keeps both")
    void gatedOnTheHost() {
        Map<String, byte[]> out = transform("26.2", LegacyBlockPosBridge::register, mod);
        assertTrue(calls(out.get(CALLS), "copy").contains(OLD_CTOR));
        assertTrue(calls(out.get(CORNER), "<init>").contains(OLD_CTOR));
        assertFalse(LegacyBlockPosBridge.lostVec3iConstructor(pos("(III)V", LegacyBlockPosBridge.OLD_CTOR)), "26.2");
        assertTrue(LegacyBlockPosBridge.lostVec3iConstructor(pos("(III)V")), "26.3");
    }

    private static ClassNode pos(String... constructors) {
        ClassNode node = new ClassNode();
        for (String desc : constructors) node.methods.add(new MethodNode(1, "<init>", desc, null, null));
        return node;
    }
}
