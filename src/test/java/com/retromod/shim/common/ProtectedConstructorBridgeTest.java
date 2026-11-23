/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** An old `new` of a game class whose constructor the host made protected builds a generated public subclass. */
class ProtectedConstructorBridgeTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void restore() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("new LilyPad(\"a\") and LilyPad::new work on a host where the constructor is protected; a subclass's super call is left alone")
    void oldNewRunsOnTheNewHost(@TempDir Path dir) throws Throwable {
        Map<String, byte[]> oldMod = BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), withMod(PUBLIC_HOST)), "mod/");
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("host"), PROTECTED_HOST);
        assertThrows(IllegalAccessError.class, () -> new BridgeHarness.Run(host, oldMod).call("mod/Blocks", "make"),
                "the old call cannot reach the protected constructor");

        Map<String, byte[]> bridged = new HashMap<>();
        oldMod.forEach((name, bytes) -> bridged.put(name, ProtectedConstructorBridge.apply(bytes, transformer, owner -> read(host, owner))));
        BridgeHarness.Run run = new BridgeHarness.Run(host, bridged);
        assertEquals("pad:a", run.call("mod/Blocks", "make").toString());
        assertEquals("custom:b", run.call("mod/Blocks", "custom").toString());
        assertEquals("pad:c", run.call("mod/Blocks", "reference").toString(), "a constructor reference links too");
        assertEquals("net/minecraft/world/level/block/LilyPad", new ClassReader(bridged.get("mod/CustomPad")).getSuperName(),
                "a mod's own subclass keeps its super call");
        assertEquals("net/minecraft/world/level/block/LilyPad", constructorReferenceOwner(bridged.get("mod/Blocks"), "serialReference"),
                "a serializable reference stays as written, since its deserializer checks the original constructor");
    }

    @Test
    @DisplayName("A public constructor, as NeoForge's access transformer leaves it, needs nothing")
    void publicConstructorsStay(@TempDir Path dir) throws Exception {
        Map<String, byte[]> oldMod = BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), withMod(PUBLIC_HOST)), "mod/");
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("host"), PUBLIC_HOST_OTHER);
        byte[] blocks = oldMod.get("mod/Blocks");
        assertSame(blocks, ProtectedConstructorBridge.apply(blocks, transformer, owner -> read(host, owner)));
    }

    @Test
    @DisplayName("Generated names are short enough for a file name, and two constructors of one class never share one")
    void namesAreShortAndDistinct() {
        // The longest protected constructor on 26.3, GameTestRunner's, spelled out would make a 399-byte file name.
        String runner = "net/minecraft/gametest/framework/GameTestRunner";
        String longest = "(Lnet/minecraft/gametest/framework/GameTestRunner$GameTestBatcher;Ljava/util/Collection;"
                + "Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/gametest/framework/GameTestTicker;"
                + "Lnet/minecraft/gametest/framework/GameTestRunner$StructureSpawner;"
                + "Lnet/minecraft/gametest/framework/GameTestRunner$StructureSpawner;ZZ)V";
        String name = ProtectedConstructorBridge.className(runner, longest);
        assertTrue((name + ".class").getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 200, name);
        String door = "net/minecraft/world/level/block/DoorBlock";
        assertNotEquals(ProtectedConstructorBridge.className(door, "(Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;)V"),
                ProtectedConstructorBridge.className(door, "(Lnet/minecraft/world/level/block/state/properties/BlockSetType;"
                        + "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;)V"));
    }

    @Test
    @DisplayName("Sealed, final or abstract classes are not subclassed, and an unreadable host is asked again later")
    void unsubclassableAndUnreadable() {
        ClassNode sealed = protectedType("net/minecraft/Sealed");
        sealed.permittedSubclasses = new java.util.ArrayList<>(java.util.List.of("net/minecraft/Sub"));
        assertFalse(ProtectedConstructorBridge.needsBridge(sealed.name, "()V", name -> sealed));
        ClassNode later = protectedType("net/minecraft/Later");
        java.util.concurrent.atomic.AtomicBoolean readable = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Function<String, ClassNode> host = name -> readable.get() ? later : null;
        assertFalse(ProtectedConstructorBridge.needsBridge(later.name, "()V", host));
        readable.set(true);
        assertTrue(ProtectedConstructorBridge.needsBridge(later.name, "()V", host), "a missing answer was not remembered");
    }

    @Test
    @DisplayName("A mixin's own `new` is left alone, since its code is merged into the game class")
    void mixinsStay(@TempDir Path dir) throws Exception {
        Map<String, String> sources = new HashMap<>(PUBLIC_HOST);
        sources.put("mod/PadMixin.java", """
                package mod;
                @org.spongepowered.asm.mixin.Mixin(net.minecraft.world.level.block.LilyPad.class)
                public class PadMixin {
                    public static Object make() { return new net.minecraft.world.level.block.LilyPad("m"); }
                }""");
        Map<String, byte[]> compiled = BridgeHarness.compile(dir.resolve("old"), sources, System.getProperty("java.class.path"));
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("host"), PROTECTED_HOST);
        byte[] mixin = compiled.get("mod/PadMixin");
        assertSame(mixin, ProtectedConstructorBridge.apply(mixin, transformer, owner -> read(host, owner)));
    }

    private static ClassNode protectedType(String name) {
        ClassNode type = new ClassNode();
        type.name = name;
        type.access = org.objectweb.asm.Opcodes.ACC_PUBLIC;
        type.methods.add(new org.objectweb.asm.tree.MethodNode(org.objectweb.asm.Opcodes.ACC_PROTECTED, "<init>", "()V", null, null));
        return type;
    }

    /** The owner of the constructor handle a method's lambda captures. */
    private static String constructorReferenceOwner(byte[] classBytes, String method) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        for (org.objectweb.asm.tree.MethodNode m : node.methods) {
            if (!m.name.equals(method)) continue;
            for (org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions) {
                if (!(insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy)) continue;
                for (Object arg : indy.bsmArgs) {
                    if (arg instanceof org.objectweb.asm.Handle handle && handle.getTag() == org.objectweb.asm.Opcodes.H_NEWINVOKESPECIAL) {
                        return handle.getOwner();
                    }
                }
            }
        }
        return null;
    }

    private static ClassNode read(Map<String, byte[]> classes, String name) {
        byte[] bytes = classes.get(name);
        if (bytes == null) return null;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static Map<String, String> withMod(Map<String, String> host) {
        Map<String, String> all = new HashMap<>(host);
        all.put("mod/Blocks.java", """
                package mod;
                import net.minecraft.world.level.block.LilyPad;
                public class Blocks {
                    public static Object make() { return new LilyPad("a"); }
                    public static Object custom() { return new CustomPad("b"); }
                    public static Object reference() { return ((java.util.function.Function<String, LilyPad>) LilyPad::new).apply("c"); }
                    public static Object serialReference() { return (java.util.function.Function<String, LilyPad> & java.io.Serializable) LilyPad::new; }
                }""");
        all.put("mod/CustomPad.java", """
                package mod;
                public class CustomPad extends net.minecraft.world.level.block.LilyPad {
                    CustomPad(String name) { super(name); }
                    @Override public String toString() { return "custom:" + name; }
                }""");
        return all;
    }

    private static final Map<String, String> PUBLIC_HOST = Map.of("net/minecraft/world/level/block/LilyPad.java", """
            package net.minecraft.world.level.block;
            public class LilyPad {
                protected final String name;
                public LilyPad(String name) { this.name = name; }
                @Override public String toString() { return "pad:" + name; }
            }""");

    private static final Map<String, String> PUBLIC_HOST_OTHER = PUBLIC_HOST;

    private static final Map<String, String> PROTECTED_HOST = Map.of("net/minecraft/world/level/block/LilyPad.java", """
            package net.minecraft.world.level.block;
            public class LilyPad {
                protected final String name;
                protected LilyPad(String name) { this.name = name; }
                @Override public String toString() { return "pad:" + name; }
            }""");
}
