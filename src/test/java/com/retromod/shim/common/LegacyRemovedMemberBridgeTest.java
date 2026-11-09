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
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Calls a 1.21.1 mod makes to members Minecraft removed by 26.3 run on the rebuilt helpers against a host of that shape. */
class LegacyRemovedMemberBridgeTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Armor slots, owner ids, ABGR colors, nearest directions, block centers and component patches answer on a 26.3 host")
    void oldCallsAnswerOnTheNewHost(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "26.3");
        assertEquals("feet,legs,chest,head", run.call("mod/Calls", "armor"));
        assertEquals("00000000-0000-0001-0000-000000000002", run.call("mod/Calls", "owner"));
        assertEquals(0x80332211, run.call("mod/Calls", "abgr"), "color(alpha, blue, green, red) packs alpha, blue, green, red");
        assertEquals(0x11, run.call("mod/Calls", "abgrRed"));
        assertEquals("UP", run.call("mod/Calls", "nearest"));
        assertEquals("1.5,2.5,3.5", run.call("mod/Calls", "center"));
        assertEquals("set:7 removed:true untouched:true", run.call("mod/Calls", "patch"));
    }

    @Test
    @DisplayName("A render call runs on the client's task queue, names follow the HUD, and GUI lighting setups are dropped")
    void clientCallsAnswerOnTheNewHost(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "26.3");
        assertEquals("queued:ran", run.call("mod/Calls", "renderCall"));
        assertEquals(false, run.call("mod/Calls", "names"), "the fake HUD is hidden");
        assertEquals("lit", run.call("mod/Calls", "lighting"));
    }

    @Test
    @DisplayName("A host that still has a member keeps the mod's call, and offline the target version decides")
    void hostsWithTheMembersAreLeftAlone() {
        ClassNode living = new ClassNode();
        living.methods.add(new org.objectweb.asm.tree.MethodNode(0, "getArmorSlots", "()Ljava/lang/Iterable;", null, null));
        Function<String, ClassNode> oldHost = name -> name.equals(LegacyRemovedMemberBridge.LIVING) ? living : null;
        assertFalse(LegacyRemovedMemberBridge.lacksMethod(oldHost, LegacyRemovedMemberBridge.LIVING, "getArmorSlots",
                "()Ljava/lang/Iterable;", "1.21.11"));
        RetromodVersion.TARGET_MC_VERSION = "1.21.10";
        assertFalse(LegacyRemovedMemberBridge.lacksMethod(name -> null, LegacyRemovedMemberBridge.LIVING, "getArmorSlots",
                "()Ljava/lang/Iterable;", "1.21.11"), "offline below the first checked host");
        assertFalse(LegacyRemovedMemberBridge.lacksClass(name -> null, LegacyRemovedMemberBridge.RENDER_CALL,
                LegacyRemovedMemberBridge.RENDER_SYSTEM, "1.21.11"));
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        assertTrue(LegacyRemovedMemberBridge.lacksClass(name -> null, LegacyRemovedMemberBridge.RENDER_CALL,
                LegacyRemovedMemberBridge.RENDER_SYSTEM, "1.21.11"));
        assertFalse(LegacyRemovedMemberBridge.lacksMethod(name -> null, LegacyRemovedMemberBridge.MINECRAFT, "renderNames",
                "()Z", LegacyRemovedMemberBridge.FIRST_HUD_HOST), "renderNames stays until 26.2");
        assertTrue(LegacyRemovedMemberBridge.lacksClass(name -> name.equals(LegacyRemovedMemberBridge.RENDER_SYSTEM)
                ? new ClassNode() : null, LegacyRemovedMemberBridge.RENDER_CALL, LegacyRemovedMemberBridge.RENDER_SYSTEM, "99.0"),
                "a readable host decides over the version");
    }

    private static BridgeHarness.Run run(Path dir, String target) throws Exception {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), oldWorld());
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("new"), newWorld());
        Function<String, ClassNode> reader = name -> {
            byte[] bytes = host.get(name);
            if (bytes == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
            return node;
        };
        Map<String, byte[]> mod = BridgeHarness.transform(target, t -> LegacyRemovedMemberBridge.register(t, reader),
                BridgeHarness.only(old, "mod/"), host);
        return new BridgeHarness.Run(host, mod);
    }

    // 1.21.1's shape of each member the mod calls.
    private static Map<String, String> oldWorld() {
        Map<String, String> s = new HashMap<>();
        s.put("net/minecraft/world/item/ItemStack.java", """
                package net.minecraft.world.item;
                public final class ItemStack { public String name() { return ""; } }""");
        s.put("net/minecraft/world/entity/LivingEntity.java", """
                package net.minecraft.world.entity;
                public class LivingEntity {
                    public Iterable<net.minecraft.world.item.ItemStack> getArmorSlots() { return null; }
                    public static LivingEntity get() { return null; }
                }""");
        s.put("net/minecraft/world/entity/OwnableEntity.java", """
                package net.minecraft.world.entity;
                public interface OwnableEntity {
                    java.util.UUID getOwnerUUID();
                    static OwnableEntity get() { return null; }
                }""");
        s.put("net/minecraft/world/phys/Vec3.java", """
                package net.minecraft.world.phys;
                public class Vec3 { public Vec3(double x, double y, double z) {} public static Vec3 up() { return null; } }""");
        s.put("net/minecraft/core/Vec3i.java", """
                package net.minecraft.core;
                public class Vec3i { public Vec3i(int x, int y, int z) {} }""");
        s.put("net/minecraft/core/BlockPos.java", """
                package net.minecraft.core;
                public class BlockPos extends Vec3i {
                    public BlockPos(int x, int y, int z) { super(x, y, z); }
                    public net.minecraft.world.phys.Vec3 getCenter() { return null; }
                }""");
        s.put("net/minecraft/core/Direction.java", """
                package net.minecraft.core;
                public enum Direction { DOWN, UP;
                    public static Direction getNearest(net.minecraft.world.phys.Vec3 vec) { return null; } }""");
        s.put("net/minecraft/util/FastColor.java", """
                package net.minecraft.util;
                public class FastColor { public static class ABGR32 {
                    public static int color(int a, int b, int g, int r) { return 0; }
                    public static int red(int color) { return 0; } } }""");
        s.put("com/mojang/blaze3d/pipeline/RenderCall.java", """
                package com.mojang.blaze3d.pipeline;
                public interface RenderCall { void execute(); }""");
        s.put("com/mojang/blaze3d/systems/RenderSystem.java", """
                package com.mojang.blaze3d.systems;
                public final class RenderSystem { public static void recordRenderCall(com.mojang.blaze3d.pipeline.RenderCall call) {} }""");
        s.put("com/mojang/blaze3d/platform/Lighting.java", """
                package com.mojang.blaze3d.platform;
                public class Lighting { public static void setupFor3DItems() {} }""");
        s.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft { public static boolean renderNames() { return true; } }""");
        s.put("net/minecraft/core/component/DataComponentType.java", """
                package net.minecraft.core.component;
                public interface DataComponentType<T> { static <T> DataComponentType<T> named(String name) { return null; } }""");
        s.put("net/minecraft/core/component/DataComponentPatch.java", """
                package net.minecraft.core.component;
                public final class DataComponentPatch {
                    public <T> java.util.Optional<? extends T> get(DataComponentType<? extends T> type) { return null; }
                    public static DataComponentPatch get() { return null; }
                }""");
        s.put("mod/Calls.java", """
                package mod;
                import net.minecraft.core.component.*;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.item.ItemStack;
                public final class Calls {
                    public static String armor() {
                        StringBuilder names = new StringBuilder();
                        for (ItemStack stack : LivingEntity.get().getArmorSlots()) names.append(names.length() == 0 ? "" : ",").append(stack.name());
                        return names.toString();
                    }
                    public static String owner() { return OwnableEntity.get().getOwnerUUID().toString(); }
                    public static int abgr() { return net.minecraft.util.FastColor.ABGR32.color(0x80, 0x33, 0x22, 0x11); }
                    public static int abgrRed() { return net.minecraft.util.FastColor.ABGR32.red(abgr()); }
                    public static String nearest() { return net.minecraft.core.Direction.getNearest(net.minecraft.world.phys.Vec3.up()).name(); }
                    public static String center() { return new net.minecraft.core.BlockPos(1, 2, 3).getCenter().toString(); }
                    public static String patch() {
                        DataComponentPatch patch = DataComponentPatch.get();
                        return "set:" + patch.get(DataComponentType.named("set")).get()
                                + " removed:" + patch.get(DataComponentType.named("removed")).isEmpty()
                                + " untouched:" + (patch.get(DataComponentType.named("other")) == null);
                    }
                    public static String renderCall() {
                        StringBuilder log = new StringBuilder();
                        com.mojang.blaze3d.systems.RenderSystem.recordRenderCall(() -> log.append("ran"));
                        return "queued:" + log;
                    }
                    public static boolean names() { return net.minecraft.client.Minecraft.renderNames(); }
                    public static String lighting() {
                        com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
                        return "lit";
                    }
                }""");
        return s;
    }

    // 26.3's shape: the members the rebuilt helpers read instead.
    private static Map<String, String> newWorld() {
        Map<String, String> s = new HashMap<>();
        s.put("net/minecraft/world/item/ItemStack.java", """
                package net.minecraft.world.item;
                public final class ItemStack {
                    private final String name;
                    public ItemStack(String name) { this.name = name; }
                    public String name() { return name; }
                }""");
        s.put("net/minecraft/world/entity/EquipmentSlot.java", """
                package net.minecraft.world.entity;
                public enum EquipmentSlot { MAINHAND, OFFHAND, FEET, LEGS, CHEST, HEAD, BODY, SADDLE }""");
        s.put("net/minecraft/world/entity/LivingEntity.java", """
                package net.minecraft.world.entity;
                import net.minecraft.world.item.ItemStack;
                public class LivingEntity implements OwnableEntity {
                    public ItemStack getItemBySlot(EquipmentSlot slot) { return new ItemStack(slot.name().toLowerCase()); }
                    public EntityReference<LivingEntity> getOwnerReference() { return new EntityReference<>(); }
                    public static LivingEntity get() { return new LivingEntity(); }
                }""");
        s.put("net/minecraft/world/entity/EntityReference.java", """
                package net.minecraft.world.entity;
                public final class EntityReference<T> { public java.util.UUID getUUID() { return new java.util.UUID(1L, 2L); } }""");
        s.put("net/minecraft/world/entity/OwnableEntity.java", """
                package net.minecraft.world.entity;
                public interface OwnableEntity {
                    EntityReference<LivingEntity> getOwnerReference();
                    static OwnableEntity get() { return new LivingEntity(); }
                }""");
        s.put("net/minecraft/world/phys/Vec3.java", """
                package net.minecraft.world.phys;
                public class Vec3 {
                    final double x, y, z;
                    public Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
                    public static Vec3 up() { return new Vec3(0, 1, 0); }
                    public static Vec3 atCenterOf(net.minecraft.core.Vec3i pos) { return new Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5); }
                    public String toString() { return x + "," + y + "," + z; }
                }""");
        s.put("net/minecraft/core/Vec3i.java", """
                package net.minecraft.core;
                public class Vec3i { public final int x, y, z; public Vec3i(int x, int y, int z) { this.x = x; this.y = y; this.z = z; } }""");
        s.put("net/minecraft/core/BlockPos.java", """
                package net.minecraft.core;
                public class BlockPos extends Vec3i { public BlockPos(int x, int y, int z) { super(x, y, z); } }""");
        s.put("net/minecraft/core/Direction.java", """
                package net.minecraft.core;
                public enum Direction { DOWN, UP;
                    public static Direction getApproximateNearest(net.minecraft.world.phys.Vec3 vec) { return UP; } }""");
        s.put("com/mojang/blaze3d/systems/RenderSystem.java", """
                package com.mojang.blaze3d.systems;
                public final class RenderSystem { public static boolean isOnRenderThread() { return true; } }""");
        s.put("com/mojang/blaze3d/platform/Lighting.java", """
                package com.mojang.blaze3d.platform;
                public class Lighting { public void setupFor(Object entry) {} }""");
        s.put("net/minecraft/client/gui/Hud.java", """
                package net.minecraft.client.gui;
                public class Hud { public boolean isHidden() { return true; } }""");
        s.put("net/minecraft/client/gui/Gui.java", """
                package net.minecraft.client.gui;
                public class Gui { public Hud hud = new Hud(); }""");
        s.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    private static final Minecraft INSTANCE = new Minecraft();
                    public net.minecraft.client.gui.Gui gui = new net.minecraft.client.gui.Gui();
                    public static Minecraft getInstance() { return INSTANCE; }
                    public void execute(Runnable task) { task.run(); }
                }""");
        s.put("net/minecraft/core/component/DataComponentType.java", """
                package net.minecraft.core.component;
                public interface DataComponentType<T> {
                    static <T> DataComponentType<T> named(String name) { return Types.named(name); }
                }""");
        s.put("net/minecraft/core/component/Types.java", """
                package net.minecraft.core.component;
                final class Types {
                    static final java.util.Map<String, DataComponentType<?>> ALL = new java.util.HashMap<>();
                    @SuppressWarnings("unchecked")
                    static <T> DataComponentType<T> named(String name) { return (DataComponentType<T>) ALL.computeIfAbsent(name, n -> new DataComponentType<Object>() {}); }
                }""");
        s.put("net/minecraft/core/component/DataComponentGetter.java", """
                package net.minecraft.core.component;
                public interface DataComponentGetter { <T> T get(DataComponentType<? extends T> type); }""");
        s.put("net/minecraft/core/component/DataComponentMap.java", """
                package net.minecraft.core.component;
                public interface DataComponentMap extends DataComponentGetter {}""");
        s.put("net/minecraft/core/component/DataComponentPatch.java", """
                package net.minecraft.core.component;
                public final class DataComponentPatch {
                    public record SplitResult(DataComponentMap added, java.util.Set<DataComponentType<?>> removed) {}
                    public SplitResult split() {
                        DataComponentMap added = new DataComponentMap() {
                            @SuppressWarnings("unchecked")
                            public <T> T get(DataComponentType<? extends T> type) { return type == DataComponentType.named("set") ? (T) Integer.valueOf(7) : null; }
                        };
                        return new SplitResult(added, java.util.Set.of(DataComponentType.named("removed")));
                    }
                    public static DataComponentPatch get() { return new DataComponentPatch(); }
                }""");
        return s;
    }
}
