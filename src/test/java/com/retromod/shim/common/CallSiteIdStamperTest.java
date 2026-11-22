/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A block built straight into a registration helper with a constant name is built inside that name's id window. */
class CallSiteIdStamperTest {

    private static final String BRIDGE = "com/retromod/test/Bridge";

    @Test
    @DisplayName("register(consumer, new Block(props), \"blood\") is wrapped in enter(bop:blood) and leave, with the helper's namespace")
    void registrationCallIsWrapped(@TempDir Path dir) throws Exception {
        byte[] blocks = BridgeHarness.compile(dir, SOURCES).get("mod/ModBlocks");
        List<String> steps = steps(CallSiteIdStamper.apply(blocks, null, BRIDGE), "registerBlocks");
        assertEquals(List.of("ldc bop", "ldc blood", "Identifier.fromNamespaceAndPath", "Bridge.enter",
                "Properties.of", "Block.<init>", "ldc blood", "ModBlocks.register", "Bridge.leave",
                "Bridge.leave", "ldc stone", "ModBlocks.named"), steps,
                "only the registration with a new block is wrapped; the second leave is the exception handler's");
    }

    @Test
    @DisplayName("register(consumer, log(), \"fir_log\") is wrapped too when log() builds exactly one block, as BOP's helpers do")
    void blockFromAOneObjectHelperIsWrapped(@TempDir Path dir) throws Exception {
        byte[] blocks = BridgeHarness.compile(dir, SOURCES).get("mod/ModBlocks");
        byte[] stamped = CallSiteIdStamper.apply(blocks, null, BRIDGE);
        List<String> steps = steps(stamped, "registerLogs");
        assertEquals(List.of("ldc bop", "ldc fir_log", "Identifier.fromNamespaceAndPath", "Bridge.enter",
                "ModBlocks.log", "ldc fir_log", "ModBlocks.register", "Bridge.leave",
                "Bridge.leave", "ModBlocks.pair", "ldc twin", "ModBlocks.register"), steps,
                "a helper that builds two blocks would give both one id, so it is left alone");
        // A throwing helper restores the id too: the handler's range covers the helper call.
        MethodNode logs = BridgeHarness.method(stamped, "registerLogs");
        int helper = -1;
        for (AbstractInsnNode insn : logs.instructions) {
            if (insn instanceof MethodInsnNode call && call.name.equals("log")) helper = logs.instructions.indexOf(insn);
        }
        int at = helper;
        assertTrue(logs.tryCatchBlocks.stream().anyMatch(block -> logs.instructions.indexOf(block.start) < at
                && at < logs.instructions.indexOf(block.end)), "the helper call is inside the restoring handler's range");
    }

    @Test
    @DisplayName("A class that builds no Properties is left as it is")
    void classesWithoutPropertiesAreLeftAlone(@TempDir Path dir) throws Exception {
        byte[] plain = BridgeHarness.compile(dir, SOURCES).get("net/minecraft/resources/Identifier");
        assertSame(plain, CallSiteIdStamper.apply(plain, "bop", BRIDGE));
    }

    @Test
    @DisplayName("A register call that throws inside the mod's own try/catch leaves the id restored on the thread")
    void throwingRegistrationRestoresTheId(@TempDir Path dir) throws Exception {
        Map<String, String> sources = new java.util.HashMap<>(SOURCES);
        sources.put("com/retromod/test/Bridge.java", """
                package com.retromod.test;
                public final class Bridge {
                    public static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();
                    public static Object enter(net.minecraft.resources.Identifier id) {
                        Object previous = CURRENT.get();
                        CURRENT.set(id);
                        return previous;
                    }
                    public static void leave(Object previous) { CURRENT.set(previous); }
                }""");
        sources.put("mod/Throwing.java", """
                package mod;
                import net.minecraft.world.level.block.Block;
                import net.minecraft.world.level.block.state.BlockBehaviour;
                public class Throwing {
                    public static String run() {
                        try {
                            register(new Block(BlockBehaviour.Properties.of()), "broken");
                        } catch (IllegalStateException expected) {
                            return "caught";
                        }
                        return "not thrown";
                    }
                    static void register(Block block, String name) { throw new IllegalStateException(name); }
                }""");
        Map<String, byte[]> classes = new java.util.HashMap<>(BridgeHarness.compile(dir, sources));
        classes.put("mod/Throwing", CallSiteIdStamper.apply(classes.get("mod/Throwing"), "bop", BRIDGE));
        ClassLoader loader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        assertEquals("caught", loader.loadClass("mod.Throwing").getMethod("run").invoke(null),
                "the mod's own handler still sees the exception");
        ThreadLocal<?> current = (ThreadLocal<?>) loader.loadClass("com.retromod.test.Bridge").getField("CURRENT").get(null);
        assertNull(current.get(), "no id may stay active on the thread after the throw");
    }

    /** The calls and string constants of {@code method}, shortened to owner simple name and method name. */
    private static List<String> steps(byte[] bytes, String method) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        MethodNode m = node.methods.stream().filter(x -> x.name.equals(method)).findFirst().orElseThrow();
        List<String> steps = new ArrayList<>();
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) steps.add("ldc " + text);
            if (insn instanceof MethodInsnNode call) {
                String owner = call.owner.substring(call.owner.lastIndexOf('/') + 1);
                owner = owner.substring(owner.lastIndexOf('$') + 1);
                steps.add(owner + "." + call.name);
            }
        }
        return steps;
    }

    private static final Map<String, String> SOURCES = Map.of(
            "net/minecraft/resources/Identifier.java", """
                    package net.minecraft.resources;
                    public record Identifier(String namespace, String path) {
                        public static Identifier fromNamespaceAndPath(String namespace, String path) { return new Identifier(namespace, path); }
                    }""",
            "net/minecraft/world/level/block/state/BlockBehaviour.java", """
                    package net.minecraft.world.level.block.state;
                    public class BlockBehaviour {
                        public static class Properties { public static Properties of() { return new Properties(); } }
                    }""",
            "net/minecraft/world/level/block/Block.java", """
                    package net.minecraft.world.level.block;
                    import net.minecraft.world.level.block.state.BlockBehaviour;
                    public class Block { public Block(BlockBehaviour.Properties properties) {} }""",
            "mod/ModBlocks.java", """
                    package mod;
                    import java.util.function.BiConsumer;
                    import net.minecraft.resources.Identifier;
                    import net.minecraft.world.level.block.Block;
                    import net.minecraft.world.level.block.state.BlockBehaviour;
                    public class ModBlocks {
                        static void registerBlocks(BiConsumer<Identifier, Block> consumer, Block existing) {
                            register(consumer, new Block(BlockBehaviour.Properties.of()), "blood");
                            named(existing, "stone");
                        }
                        static Block register(BiConsumer<Identifier, Block> consumer, Block block, String name) {
                            consumer.accept(Identifier.fromNamespaceAndPath("bop", name), block);
                            return block;
                        }
                        static Block named(Block block, String name) { return block; }
                        static void registerLogs(BiConsumer<Identifier, Block> consumer) {
                            register(consumer, log(), "fir_log");
                            register(consumer, pair(), "twin");
                        }
                        private static Block log() { return new Block(BlockBehaviour.Properties.of()); }
                        private static Block pair() {
                            Block first = new Block(BlockBehaviour.Properties.of());
                            return first != null ? new Block(BlockBehaviour.Properties.of()) : first;
                        }
                    }""");
}
