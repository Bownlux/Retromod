/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

/**
 * Deleted-class bridges (#85). The generators reference NeoForge/MC classes absent from the build
 * classpath, so we can't class-load them; instead we check the emitted bytecode is structurally
 * valid and carries the method shapes old mods call.
 */
class ForgeNeoForgeSyntheticsTest {

    private static ClassNode parse(byte[] b) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        return cn;
    }

    private static MethodNode method(ClassNode cn, String name, String desc) {
        return cn.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc))
                .findFirst().orElse(null);
    }

    private static void assertStructurallyValid(byte[] b, String what) {
        StringWriter sw = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(b), false, new PrintWriter(sw));
        assertTrue(sw.toString().isEmpty(), what + " has bytecode errors:\n" + sw);
    }

    /**
     * Structure-only check for a synthetic that branches on an absent NeoForge type.
     * {@link #assertStructurallyValid} runs SimpleVerifier, which loads referenced classes to
     * type-check branch merges, and types like {@code Dist} aren't on the test classpath.
     */
    private static void assertStructurallySound(byte[] b, String what) {
        try {
            new ClassReader(b).accept(new CheckClassAdapter(new ClassWriter(0), false), 0);
        } catch (RuntimeException e) {
            throw new AssertionError(what + " is structurally invalid: " + e, e);
        }
    }

    @Test
    @DisplayName("#85 FMLJavaModLoadingContext bridge: get() + getModEventBus() delegation, valid bytecode")
    void fmlJavaModLoadingContext() {
        byte[] b = ForgeNeoForgeSynthetics.generateFmlJavaModLoadingContext();
        assertStructurallyValid(b, "FMLJavaModLoadingContext");
        ClassNode cn = parse(b);
        assertEquals("net/neoforged/fml/javafmlmod/FMLJavaModLoadingContext", cn.name);
        assertNotNull(method(cn, "get", "()Lnet/neoforged/fml/javafmlmod/FMLJavaModLoadingContext;"),
                "static get() returning self");
        assertNotNull(method(cn, "getModEventBus", "()Lnet/neoforged/bus/api/IEventBus;"),
                "getModEventBus() returning IEventBus");
        MethodNode g = method(cn, "getModEventBus", "()Lnet/neoforged/bus/api/IEventBus;");
        boolean delegates = java.util.Arrays.stream(g.instructions.toArray())
                .anyMatch(in -> in instanceof org.objectweb.asm.tree.MethodInsnNode mi
                        && mi.owner.equals("net/neoforged/fml/ModContainer")
                        && mi.name.equals("getEventBus"));
        assertTrue(delegates, "getModEventBus must delegate to ModContainer.getEventBus()");
    }

    @Test
    @DisplayName("#52 DeferredSpawnEggItem bridge: extends SpawnEggItem, old ctor, valid bytecode")
    void deferredSpawnEggItem() {
        byte[] b = ForgeNeoForgeSynthetics.generateDeferredSpawnEggItem();
        assertStructurallyValid(b, "DeferredSpawnEggItem");
        ClassNode cn = parse(b);
        assertEquals("net/neoforged/neoforge/common/DeferredSpawnEggItem", cn.name);
        assertEquals("net/minecraft/world/item/SpawnEggItem", cn.superName, "must extend SpawnEggItem");
        // MCreator ctor
        assertNotNull(method(cn, "<init>",
                "(Ljava/util/function/Supplier;IILnet/minecraft/world/item/Item$Properties;)V"),
                "the (Supplier,int,int,Properties) constructor");
        boolean usesSpawnEgg = cn.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .anyMatch(in -> in instanceof org.objectweb.asm.tree.MethodInsnNode mi
                        && mi.owner.equals("net/minecraft/world/item/Item$Properties")
                        && mi.name.equals("spawnEgg"));
        assertTrue(usesSpawnEgg, "must set the entity type via Item.Properties.spawnEgg(EntityType)");
    }

    @Test
    @DisplayName("#85 DistExecutor: 9 run/call/forDist static methods, all checking the running side")
    void distExecutor() {
        byte[] b = ForgeNeoForgeSynthetics.generateDistExecutor();
        assertStructurallySound(b, "DistExecutor"); // branches on absent Dist type
        ClassNode cn = parse(b);
        assertEquals("net/minecraftforge/fml/DistExecutor", cn.name);
        String dist = "Lnet/neoforged/api/distmarker/Dist;";
        String sup = "Ljava/util/function/Supplier;";
        for (String n : new String[]{"runWhenOn", "unsafeRunWhenOn", "safeRunWhenOn"}) {
            assertNotNull(method(cn, n, "(" + dist + sup + ")V"), n + " (Dist,Supplier)V");
        }
        for (String n : new String[]{"callWhenOn", "unsafeCallWhenOn", "safeCallWhenOn"}) {
            assertNotNull(method(cn, n, "(" + dist + sup + ")Ljava/lang/Object;"), n + " (Dist,Supplier)Object");
        }
        for (String n : new String[]{"runForDist", "unsafeRunForDist", "safeRunForDist"}) {
            assertNotNull(method(cn, n, "(" + sup + sup + ")Ljava/lang/Object;"), n + " (Supplier,Supplier)Object");
        }
        // Every non-ctor method must consult the current dist, through getDist() or the dist field
        // depending on the host (see distExecutorFollowsTheHost).
        boolean allDelegate = cn.methods.stream().filter(m -> !m.name.equals("<init>")).allMatch(m ->
                java.util.Arrays.stream(m.instructions.toArray()).anyMatch(in ->
                        (in instanceof org.objectweb.asm.tree.MethodInsnNode mi
                                && mi.owner.equals("net/neoforged/fml/loading/FMLEnvironment")
                                && mi.name.equals("getDist"))
                        || (in instanceof org.objectweb.asm.tree.FieldInsnNode fi
                                && fi.owner.equals("net/neoforged/fml/loading/FMLEnvironment")
                                && fi.name.equals("dist"))));
        assertTrue(allDelegate, "every DistExecutor method must check the running side");
    }

    @Test
    @DisplayName("#85 DistExecutor$SafeRunnable: serializable SAM extending Runnable, valid interface")
    void distExecutorSafeRunnable() {
        byte[] b = ForgeNeoForgeSynthetics.markerInterface(
                "net/minecraftforge/fml/DistExecutor$SafeRunnable", "java/lang/Runnable");
        assertStructurallyValid(b, "SafeRunnable");
        ClassNode cn = parse(b);
        assertTrue((cn.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0, "must be an interface");
        assertTrue(cn.interfaces.contains("java/lang/Runnable"), "extends Runnable (its SAM)");
        assertTrue(cn.interfaces.contains("java/io/Serializable"), "serializable (the 'safe' contract)");
    }

    @Test
    @DisplayName("#85 IForgeRegistry marker: empty, valid interface for stray type references")
    void iForgeRegistryMarker() {
        byte[] b = ForgeNeoForgeSynthetics.generateIForgeRegistry();
        assertStructurallyValid(b, "IForgeRegistry");
        ClassNode cn = parse(b);
        assertEquals("net/minecraftforge/registries/IForgeRegistry", cn.name);
        assertTrue((cn.access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0,
                "must be an interface (mods may `implements` it)");
        assertEquals("java/lang/Object", cn.superName);
        // A method here means callers hit NoSuchMethodError at runtime.
        assertTrue(cn.methods.isEmpty(), "marker interface must declare no methods");
    }

    @Test
    @DisplayName("DistExecutor reads the field before NeoForge 1.21.9 and calls getDist() from it")
    void distExecutorFollowsTheHost() {
        String previous = com.retromod.core.RetromodVersion.TARGET_MC_VERSION;
        try {
            // getDist() arrived in 1.21.9; calling it on 1.21.1 was a NoSuchMethodError.
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "1.21.1";
            assertTrue(readsDist(ForgeNeoForgeSynthetics.generateDistExecutor(), false),
                    "1.21.1 only has the dist field");
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = "1.21.9";
            assertTrue(readsDist(ForgeNeoForgeSynthetics.generateDistExecutor(), true),
                    "1.21.9 replaced the field with getDist()");
        } finally {
            com.retromod.core.RetromodVersion.TARGET_MC_VERSION = previous;
        }
    }

    /** Whether every side read uses the getter ({@code viaGetter}) or the field, and never the other. */
    private static boolean readsDist(byte[] bytes, boolean viaGetter) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        int getter = 0, field = 0;
        for (MethodNode m : cn.methods) {
            for (var insn : m.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode c && c.name.equals("getDist")) getter++;
                if (insn instanceof org.objectweb.asm.tree.FieldInsnNode f && f.name.equals("dist")) field++;
            }
        }
        return viaGetter ? getter > 0 && field == 0 : field > 0 && getter == 0;
    }
}
