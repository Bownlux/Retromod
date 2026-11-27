/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.forge.capability.LegacyCapabilitySynthetics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.tools.ToolProvider;
import java.lang.reflect.Array;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #400: every SoniCraft (Forge 1.20.1, MCreator) block entity with an inventory builds its handlers with
 * {@code SidedInvWrapper.create(this, Direction.values())}, which NeoForge does not have. Checked against compiled
 * stand-ins of NeoForge 1.21.1's wrappers, with the Forge capability classes Retromod supplies.
 */
class LegacySidedInvWrapperBridgeTest {

    @TempDir
    Path dir;

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyCapabilitySynthetics.register(transformer);
        LegacySidedInvWrapperBridge.register(transformer);
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("each side gets a lazy sided wrapper, a null side included, as Forge built them")
    void createsOneHandlerPerSide() throws Exception {
        ClassLoader loader = standIns();
        Class<?> direction = loader.loadClass("net.minecraft.core.Direction");
        Object sides = Array.newInstance(direction, 2);
        Array.set(sides, 0, direction.getConstructor(String.class).newInstance("down"));
        Object inventory = loader.loadClass("test.Inventory").getConstructor().newInstance();
        Object handlers = loader.loadClass(LegacySidedInvWrapperBridge.HELPER.replace('/', '.'))
                .getMethod("create", loader.loadClass("net.minecraft.world.WorldlyContainer"), sides.getClass())
                .invoke(null, inventory, sides);

        assertEquals(2, Array.getLength(handlers));
        assertEquals("sided down", resolve(Array.get(handlers, 0)));
        assertEquals("sided null", resolve(Array.get(handlers, 1)));
    }

    @Test
    @DisplayName("a block entity's call names the rebuilt create, whichever owner the class pass left on it")
    void callsTheRebuiltCreate() {
        for (String owner : LegacySidedInvWrapperBridge.OWNERS) {
            ClassNode out = new ClassNode();
            new ClassReader(transformer.transformClass(blockEntity(owner), "mod/ChestBlockEntity")).accept(out, 0);
            assertTrue(calls(out).contains(LegacySidedInvWrapperBridge.HELPER + ".create" + LegacySidedInvWrapperBridge.CREATE),
                    owner + ": " + calls(out));
        }
    }

    private static Object resolve(Object lazy) throws Exception {
        Optional<?> value = (Optional<?>) lazy.getClass().getMethod("resolve").invoke(lazy);
        return value.orElseThrow().toString();
    }

    /** A block entity whose field initializer builds its handlers, as MCreator writes it. */
    private static byte[] blockEntity(String owner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/ChestBlockEntity", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "handlers",
                "(L" + LegacySidedInvWrapperBridge.CONTAINER + ";)[Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ICONST_0);
        mv.visitTypeInsn(ANEWARRAY, LegacySidedInvWrapperBridge.DIRECTION);
        mv.visitMethodInsn(INVOKESTATIC, owner, "create", LegacySidedInvWrapperBridge.CREATE, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** NeoForge 1.21.1's sided wrapper, the container types and a direction, beside Retromod's synthetic classes. */
    private ClassLoader standIns() throws Exception {
        Map<String, String> sources = Map.of(
                "net/minecraft/world/Container.java", "package net.minecraft.world; public interface Container {}",
                "net/minecraft/world/WorldlyContainer.java", "package net.minecraft.world; public interface WorldlyContainer extends Container {}",
                "net/minecraft/core/Direction.java", """
                        package net.minecraft.core;
                        public final class Direction { final String name; public Direction(String name) { this.name = name; }
                            public String toString() { return name; } }""",
                "net/neoforged/neoforge/items/wrapper/SidedInvWrapper.java", """
                        package net.neoforged.neoforge.items.wrapper;
                        public class SidedInvWrapper { final Object side;
                            public SidedInvWrapper(net.minecraft.world.WorldlyContainer inv, net.minecraft.core.Direction side) { this.side = side; }
                            public String toString() { return "sided " + side; } }""",
                "test/Inventory.java", "package test; public class Inventory implements net.minecraft.world.WorldlyContainer {}");
        Path out = Files.createDirectories(dir.resolve("out"));
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-nowarn"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = dir.resolve("src").resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(new String[0])));
        for (Map.Entry<String, byte[]> synthetic : transformer.getSyntheticClasses().entrySet()) {
            Path file = out.resolve(synthetic.getKey() + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, synthetic.getValue());
        }
        return new URLClassLoader(new URL[] {out.toUri().toURL()}, getClass().getClassLoader());
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }
}
