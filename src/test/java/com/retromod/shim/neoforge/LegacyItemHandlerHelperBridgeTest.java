/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * {@code ItemHandlerHelper.giveItemToPlayer}, which NeoForge 26.3 deleted, as a Forge or NeoForge
 * mod calls it to hand back a bowl or jar. Found on S33R More Food (Forge 1.19.2) on NeoForge 26.3,
 * where placing a jar failed with {@code NoClassDefFoundError}. The mod's calls are transformed and
 * run against stand-ins with the 26.3 shape of the vanilla classes the replacement uses.
 */
class LegacyItemHandlerHelperBridgeTest {

    private static final String MOD = "mod/JarProcedure";
    private static final String PLAYER = LegacyItemHandlerHelperBridge.PLAYER;
    private static final String ITEM_STACK = LegacyItemHandlerHelperBridge.ITEM_STACK;

    @TempDir
    static Path stubDir;

    private static Map<String, byte[]> host;

    private RetromodTransformer transformer;

    @BeforeAll
    static void compileHost() throws Exception {
        host = compile(SOURCES);
    }

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyItemHandlerHelperBridge.registerRedirects(transformer,
                List.of(LegacyItemHandlerHelperBridge.NEOFORGE_HELPER, LegacyItemHandlerHelperBridge.FORGE_HELPER));
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("giveItemToPlayer puts a copy into the inventory and plays the pickup sound")
    void giveFillsTheInventory() throws Throwable {
        Run run = new Run();
        Object player = run.player();
        Object bowl = run.stack("bowl", 1);

        run.call("give", player, bowl);
        Object slot = ((Object[]) run.field(run.field(player, "inventory"), "slots"))[0];
        assertEquals("bowl", run.field(slot, "item"));
        assertNotSame(bowl, slot, "the caller's stack is not handed over");
        assertEquals(1, run.field(bowl, "count"), "the caller's stack is not changed");
        Object level = run.field(player, "level");
        assertEquals(1, ((List<?>) run.field(level, "sounds")).size());
        assertEquals(List.of(), run.field(level, "entities"));
    }

    @Test
    @DisplayName("what does not fit is dropped at the player's feet with a pickup delay, without a sound")
    void giveDropsTheRest() throws Throwable {
        Run run = new Run();
        Object player = run.player();
        Object[] slots = (Object[]) run.field(run.field(player, "inventory"), "slots");
        for (int i = 0; i < slots.length; i++) slots[i] = run.stack("stone", 64);

        run.call("giveForge", player, run.stack("bowl", 2));
        Object level = run.field(player, "level");
        List<?> entities = (List<?>) run.field(level, "entities");
        assertEquals(1, entities.size());
        assertEquals(40, run.field(entities.get(0), "pickUpDelay"));
        assertEquals(2, run.field(run.field(entities.get(0), "stack"), "count"));
        assertEquals(0.0, run.field(run.field(entities.get(0), "motion"), "x"), "it only falls");
        assertEquals(List.of(), run.field(level, "sounds"), "nothing went in, so no sound");
    }

    @Test
    @DisplayName("an empty preferred slot takes the stack first")
    void preferredSlot() throws Throwable {
        Run run = new Run();
        Object player = run.player();
        run.call("giveToSlot", player, run.stack("bowl", 1), 5);
        Object[] slots = (Object[]) run.field(run.field(player, "inventory"), "slots");
        assertNull(slots[0]);
        assertEquals("bowl", run.field(slots[5], "item"));
    }

    @Test
    @DisplayName("an empty stack gives nothing")
    void emptyStack() throws Throwable {
        Run run = new Run();
        Object player = run.player();
        run.call("give", player, run.stack("air", 0));
        assertEquals(List.of(), run.field(run.field(player, "level"), "sounds"));
    }

    @Test
    @DisplayName("the replacement passes the stack verifier")
    void helperVerifies() throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(LegacyItemHandlerHelperBridge.generateHelper()).accept(node, 0);
        for (MethodNode method : node.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    /** The transformed mod, the helper and the stand-ins in one loader. */
    private final class Run extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>(host);
        private final Class<?> procedure;

        Run() throws Exception {
            super(LegacyItemHandlerHelperBridgeTest.class.getClassLoader());
            classes.put(MOD.replace('/', '.'), transformer.transformClass(mod(), MOD + ".class"));
            transformer.getSyntheticClasses().forEach((name, bytes) -> classes.put(name.replace('/', '.'), bytes));
            procedure = loadClass(MOD.replace('/', '.'));
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) return loaded;
            byte[] bytes = classes.get(name);
            if (bytes == null) return super.loadClass(name, resolve);
            return defineClass(name, bytes, 0, bytes.length);
        }

        void call(String name, Object... args) throws Throwable {
            for (java.lang.reflect.Method method : procedure.getMethods()) {
                if (method.getName().equals(name)) {
                    try {
                        method.invoke(null, args);
                        return;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
            }
            throw new AssertionError("no procedure " + name);
        }

        Object player() throws Exception {
            Object player = loadClass(PLAYER.replace('/', '.')).getConstructor().newInstance();
            Object level = loadClass("net.minecraft.world.level.Level").getConstructor().newInstance();
            player.getClass().getField("level").set(player, level);
            return player;
        }

        Object stack(String item, int count) throws Exception {
            return loadClass(ITEM_STACK.replace('/', '.')).getConstructor(String.class, int.class).newInstance(item, count);
        }

        Object field(Object owner, String name) throws Exception {
            return owner.getClass().getField(name).get(owner);
        }
    }

    /** The calls a Forge or NeoForge procedure makes to hand an item back. */
    private static byte[] mod() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD, null, "java/lang/Object", null);
        give(cw, "give", LegacyItemHandlerHelperBridge.NEOFORGE_HELPER, LegacyItemHandlerHelperBridge.GIVE);
        give(cw, "giveForge", LegacyItemHandlerHelperBridge.FORGE_HELPER, LegacyItemHandlerHelperBridge.GIVE);
        give(cw, "giveToSlot", LegacyItemHandlerHelperBridge.NEOFORGE_HELPER, LegacyItemHandlerHelperBridge.GIVE_TO_SLOT);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void give(ClassWriter cw, String name, String owner, String desc) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        if (desc.equals(LegacyItemHandlerHelperBridge.GIVE_TO_SLOT)) mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, owner, "giveItemToPlayer", desc, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static Map<String, byte[]> compile(Map<String, String> sources) throws Exception {
        Path src = stubDir.resolve("src");
        Path out = stubDir.resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-nowarn"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the tests need a JDK to compile the 26.3 stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the 26.3 stand-ins must compile");
        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '.');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    /** Stand-ins with the 26.3 shape of the vanilla classes the replacement calls. */
    private static final Map<String, String> SOURCES = Map.ofEntries(
            Map.entry("net/minecraft/util/RandomSource.java", """
                    package net.minecraft.util;
                    public interface RandomSource { float nextFloat(); }"""),
            Map.entry("net/minecraft/sounds/SoundEvent.java", """
                    package net.minecraft.sounds;
                    public class SoundEvent {}"""),
            Map.entry("net/minecraft/sounds/SoundEvents.java", """
                    package net.minecraft.sounds;
                    public class SoundEvents { public static final SoundEvent ITEM_PICKUP = new SoundEvent(); }"""),
            Map.entry("net/minecraft/sounds/SoundSource.java", """
                    package net.minecraft.sounds;
                    public enum SoundSource { PLAYERS }"""),
            Map.entry("net/minecraft/world/phys/Vec3.java", """
                    package net.minecraft.world.phys;
                    public class Vec3 {
                        public final double x, y, z;
                        public Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
                        public Vec3 multiply(double x, double y, double z) { return new Vec3(this.x * x, this.y * y, this.z * z); }
                    }"""),
            Map.entry("net/minecraft/world/level/Level.java", """
                    package net.minecraft.world.level;
                    import java.util.ArrayList;
                    import java.util.List;
                    import net.minecraft.sounds.SoundEvent;
                    import net.minecraft.sounds.SoundSource;
                    import net.minecraft.util.RandomSource;
                    import net.minecraft.world.entity.Entity;
                    public class Level {
                        public final List<Object> sounds = new ArrayList<>();
                        public final List<Object> entities = new ArrayList<>();
                        public boolean isClientSide() { return false; }
                        public RandomSource getRandom() { return () -> 0.5f; }
                        public void playSound(Entity except, double x, double y, double z, SoundEvent sound,
                                SoundSource source, float volume, float pitch) { sounds.add(sound); }
                        public boolean addFreshEntity(Entity entity) { return entities.add(entity); }
                    }"""),
            Map.entry("net/minecraft/world/entity/Entity.java", """
                    package net.minecraft.world.entity;
                    import net.minecraft.world.level.Level;
                    import net.minecraft.world.phys.Vec3;
                    public class Entity {
                        public Level level;
                        public Vec3 motion = new Vec3(0.3, 0.2, 0.1);
                        public Level level() { return level; }
                        public final double getX() { return 1; }
                        public final double getY() { return 64; }
                        public final double getZ() { return 2; }
                        public Vec3 getDeltaMovement() { return motion; }
                        public void setDeltaMovement(Vec3 motion) { this.motion = motion; }
                    }"""),
            Map.entry("net/minecraft/world/entity/item/ItemEntity.java", """
                    package net.minecraft.world.entity.item;
                    import net.minecraft.world.item.ItemStack;
                    import net.minecraft.world.level.Level;
                    public class ItemEntity extends net.minecraft.world.entity.Entity {
                        public final ItemStack stack;
                        public int pickUpDelay;
                        public ItemEntity(Level level, double x, double y, double z, ItemStack stack) {
                            this.level = level; this.stack = stack;
                        }
                        public void setPickUpDelay(int delay) { pickUpDelay = delay; }
                    }"""),
            Map.entry("net/minecraft/world/entity/player/Player.java", """
                    package net.minecraft.world.entity.player;
                    public class Player extends net.minecraft.world.entity.Entity {
                        public final Inventory inventory = new Inventory();
                        public Inventory getInventory() { return inventory; }
                    }"""),
            Map.entry("net/minecraft/world/entity/player/Inventory.java", """
                    package net.minecraft.world.entity.player;
                    import net.minecraft.world.item.ItemStack;
                    public class Inventory {
                        public static final int INVENTORY_SIZE = 36;
                        public final ItemStack[] slots = new ItemStack[INVENTORY_SIZE];
                        public ItemStack getItem(int slot) { return slots[slot] == null ? new ItemStack("air", 0) : slots[slot]; }
                        public boolean add(int slot, ItemStack stack) {
                            if (slots[slot] != null) return false;
                            slots[slot] = stack.copy();
                            stack.count = 0;
                            return true;
                        }
                        public boolean add(ItemStack stack) {
                            int before = stack.count;
                            for (ItemStack slot : slots) {
                                if (slot != null && slot.item.equals(stack.item) && slot.count < 64) {
                                    int moved = Math.min(64 - slot.count, stack.count);
                                    slot.count += moved; stack.count -= moved;
                                }
                            }
                            for (int i = 0; i < slots.length && stack.count > 0; i++) {
                                if (slots[i] == null) { slots[i] = stack.copy(); stack.count = 0; }
                            }
                            return stack.count < before;
                        }
                    }"""),
            Map.entry("net/minecraft/world/item/ItemStack.java", """
                    package net.minecraft.world.item;
                    public class ItemStack {
                        public final String item;
                        public int count;
                        public ItemStack(String item, int count) { this.item = item; this.count = count; }
                        public boolean isEmpty() { return count <= 0 || item.equals("air"); }
                        public ItemStack copy() { return new ItemStack(item, count); }
                        public int getCount() { return isEmpty() ? 0 : count; }
                    }"""));
}
