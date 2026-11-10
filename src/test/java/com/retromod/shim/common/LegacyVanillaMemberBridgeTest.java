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
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A 1.21.1 mod's player, entity, item and server calls answer on a host shaped like 26.3. */
class LegacyVanillaMemberBridgeTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The inventory's offhand and armor lists read and write that inventory's slots, without equip events")
    void inventoryListsAreSlotViews(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir);
        assertEquals("offhand=shield armor=[boots, legs, chest, helmet] third=chest after=totem events=0", run.call("mod/Calls", "inventory"));
        assertEquals("copy=empty copyFeet=spare playerFeet=boots", run.call("mod/Calls", "inventoryCopy"),
                "a copied inventory keeps its own armor and leaves the player's alone");
    }

    @Test
    @DisplayName("Hand slots, permissions, levels, tags and riding answer through their 26.x forms")
    void entityMembersAnswer(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir);
        assertEquals("slot=OFFHAND op2=true op4=false op5=false plain0=true plain1=false level=overworld tags=[boss] ride=true",
                run.call("mod/Calls", "entity"));
    }

    @Test
    @DisplayName("Cooldowns, spawn egg types, block motion and the singleplayer owner take their new arguments")
    void itemAndServerMembersAnswer(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir);
        assertEquals("cooldown=true egg=pig motion=false owner=true", run.call("mod/Calls", "items"));
    }

    @Test
    @DisplayName("An old fall damage override still runs, its super call reaches the host once, and old callers widen the distance")
    void fallDamageOverridesAndCallsWiden(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir);
        assertEquals("mod(3.0)>host(3.0)|mod(5.0)>host(5.0)", run.call("mod/Calls", "fall"));
    }

    @Test
    @DisplayName("An entity built on Entity, directly or through its own base class, gets hurtServer, which runs its old hurt override once")
    void directEntitiesGetHurtServer(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir);
        assertEquals("hit:true calls=1 plain:false part:true", run.call("mod/Calls", "hurt"));
    }

    @Test
    @DisplayName("An old hurt override that calls super runs once, whether a mod calls it or the game deals the damage")
    void hurtOverrideRunsOncePerHit(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir);
        assertEquals("direct calls=1 marks=1 dealt calls=1 marks=1 hit=false", run.call("mod/Calls", "hurtOnce"));
    }

    @Test
    @DisplayName("A Fabric mod's old hurt override, which arrives named hurtClient, also runs once per hit")
    void fabricNamedHurtOverrideRunsOncePerHit(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir);
        assertEquals("direct calls=1 marks=1 dealt calls=1 marks=1 hit=false", run.call("mod/Calls", "fabricHurtOnce"));
    }

    @Test
    @DisplayName("A mixin into an entity gains no members, since Mixin would merge them over the target's own")
    void mixinsGainNoMembers() {
        byte[] mixin = entityWithOldOverrides("mod/EntityMixin", true);
        assertTrue(mixin == LegacyEntityOverrideAdapter.apply(mixin, true, true, (owner, parent) -> true, name -> null),
                "the mixin is left as it is");
        ClassNode plain = new ClassNode();
        new ClassReader(LegacyEntityOverrideAdapter.apply(entityWithOldOverrides("mod/Plain", false), true, true,
                (owner, parent) -> true, name -> null)).accept(plain, 0);
        assertTrue(plain.methods.stream().anyMatch(m -> m.name.equals("hurtServer")), "a plain entity gets hurtServer");
        assertTrue(plain.methods.stream().anyMatch(m -> m.desc.equals(LegacyEntityOverrideAdapter.NEW_FALL)),
                "a plain entity gets the new fall damage");
    }

    @Test
    @DisplayName("A fake player's old super constructor drops the position and yaw")
    void fakePlayerConstructorFits(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir);
        assertEquals("fake:Steve", run.call("mod/Calls", "fakePlayer"));
    }

    @Test
    @DisplayName("A host that still has the old shapes registers nothing, and an unknown source is never adapted")
    void olderHostsAndUnknownSourcesAreLeftAlone(@TempDir Path dir) throws Exception {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), oldWorld());
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyVanillaMemberBridge.register(t, reader(old));
        assertFalse(t.getSyntheticClasses().containsKey(LegacyVanillaMemberBridge.INVENTORIES), "the old host keeps its lists");
        byte[] mob = old.get("mod/FallingMob");
        assertTrue(mob == LegacyEntityOverrideAdapter.apply(mob, true, true, (String) null), "an unknown source is never old");
        assertTrue(mob == LegacyEntityOverrideAdapter.apply(mob, true, true, "26.3"), "a 26.3 mod keeps its shapes");
    }

    @Test
    @DisplayName("A class built on a library's class, whose superclass chain cannot be read, is left alone")
    void libraryChainsAreLeftAlone() {
        byte[] onLibrary = hurtOverride("mod/LibraryMob", "lib/LibraryEntity");
        assertTrue(onLibrary == LegacyEntityOverrideAdapter.apply(onLibrary, false, true, (owner, parent) -> false, name -> null),
                "an unreadable superclass is not Entity");
    }

    /** {@code class name extends Entity} with old hurt and fall damage overrides, annotated {@code @Mixin} when asked. */
    private static byte[] entityWithOldOverrides(String name, boolean mixin) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, name, null, LegacyVanillaMemberBridge.ENTITY, null);
        if (mixin) writer.visitAnnotation(LegacyEntityOverrideAdapter.MIXIN, false).visitEnd();
        for (String[] method : new String[][] {{"hurt", LegacyEntityOverrideAdapter.OLD_HURT},
                {"causeFallDamage", LegacyEntityOverrideAdapter.OLD_FALL}}) {
            MethodVisitor body = writer.visitMethod(Opcodes.ACC_PUBLIC, method[0], method[1], null, null);
            body.visitCode();
            body.visitInsn(Opcodes.ICONST_0);
            body.visitInsn(Opcodes.IRETURN);
            body.visitMaxs(0, 0);
            body.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** {@code class name extends superName} with an old {@code hurt(DamageSource, float)} override. */
    private static byte[] hurtOverride(String name, String superName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, null);
        MethodVisitor hurt = writer.visitMethod(Opcodes.ACC_PUBLIC, "hurt", LegacyEntityOverrideAdapter.OLD_HURT, null, null);
        hurt.visitCode();
        hurt.visitInsn(Opcodes.ICONST_0);
        hurt.visitInsn(Opcodes.IRETURN);
        hurt.visitMaxs(0, 0);
        hurt.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static BridgeHarness.Run run(Path dir) throws Exception {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), oldWorld());
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("new"), newWorld());
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", t -> {
            // The 1.21.2 bridge sends old hurt calls, and an override's super call, to hurtOrSimulate, as a game's shims do.
            Legacy1212MemberBridge.registerRedirects(t, new Legacy1212MemberBridge.Members(false, false, false, true));
            LegacyVanillaMemberBridge.register(t, reader(host));
            t.setModSourceVersion("1.21.1");
        }, BridgeHarness.only(old, "mod/"), host);
        return new BridgeHarness.Run(host, mod);
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

    // 1.21.1's shapes of the members the mod uses.
    private static Map<String, String> oldWorld() {
        Map<String, String> s = new HashMap<>(shared());
        s.put("net/minecraft/world/entity/Entity.java", """
                package net.minecraft.world.entity;
                public abstract class Entity {
                    public net.minecraft.world.level.Level getCommandSenderWorld() { return null; }
                    public java.util.Set<String> getTags() { return null; }
                    public boolean startRiding(Entity vehicle, boolean force) { return false; }
                    public boolean causeFallDamage(float distance, float multiplier, net.minecraft.world.damagesource.DamageSource source) { return false; }
                    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) { return false; }
                    // The same method as a Fabric mod built for 1.21.1 names it once the 26.x name table has mapped it.
                    public boolean hurtClient(net.minecraft.world.damagesource.DamageSource source, float amount) { return false; }
                    public int marks;
                    public boolean hasPermissions(int level) { return false; }
                }""");
        s.put("net/minecraft/world/entity/LivingEntity.java", """
                package net.minecraft.world.entity;
                public abstract class LivingEntity extends Entity {
                    public int equipChanges;
                    public static EquipmentSlot getSlotForHand(net.minecraft.world.InteractionHand hand) { return null; }
                    public net.minecraft.world.item.ItemStack getItemBySlot(EquipmentSlot slot) { return null; }
                }""");
        s.put("net/minecraft/world/entity/player/Player.java", """
                package net.minecraft.world.entity.player;
                public abstract class Player extends net.minecraft.world.entity.LivingEntity {
                    public Player(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, float yaw, com.mojang.authlib.GameProfile profile) {}
                    public String name() { return ""; }
                    public Inventory getInventory() { return null; }
                }""");
        s.put("net/minecraft/world/entity/player/Inventory.java", """
                package net.minecraft.world.entity.player;
                import net.minecraft.core.NonNullList;
                import net.minecraft.world.item.ItemStack;
                public class Inventory {
                    public final NonNullList<ItemStack> armor = null;
                    public final NonNullList<ItemStack> offhand = null;
                    public Inventory(Player player) {}
                    public ItemStack getArmor(int index) { return null; }
                    public ItemStack getItem(int slot) { return null; }
                }""");
        s.put("net/minecraft/server/level/HostDamage.java", """
                package net.minecraft.server.level;
                public final class HostDamage { public static boolean hurtServer(net.minecraft.world.entity.Entity e) { return false; } }""");
        s.put("net/minecraft/world/item/ItemCooldowns.java", """
                package net.minecraft.world.item;
                public class ItemCooldowns { public boolean isOnCooldown(Item item) { return false; } }""");
        s.put("net/minecraft/world/item/SpawnEggItem.java", """
                package net.minecraft.world.item;
                public class SpawnEggItem extends Item { public SpawnEggItem(String name) { super(name); }
                    public net.minecraft.world.entity.EntityType<?> getType(ItemStack stack) { return null; } }""");
        s.put("net/minecraft/world/level/block/state/BlockState.java", """
                package net.minecraft.world.level.block.state;
                public class BlockState { public boolean blocksMotion() { return false; } }""");
        s.put("net/minecraft/server/MinecraftServer.java", """
                package net.minecraft.server;
                public abstract class MinecraftServer { public boolean isSingleplayerOwner(com.mojang.authlib.GameProfile profile) { return false; } }""");
        s.put("mod/Calls.java", """
                package mod;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.entity.player.*;
                import net.minecraft.world.item.*;
                public final class Calls {
                    public static String inventory() {
                        Steve steve = new Steve();
                        Inventory inventory = steve.getInventory();
                        String offhand = inventory.offhand.get(0).toString();
                        StringBuilder armor = new StringBuilder();
                        for (ItemStack stack : inventory.armor) armor.append(armor.length() == 0 ? "" : ", ").append(stack);
                        String third = inventory.getArmor(2).toString();
                        inventory.offhand.set(0, new ItemStack("totem"));
                        return "offhand=" + offhand + " armor=[" + armor + "] third=" + third + " after=" + steve.getItemBySlot(EquipmentSlot.OFFHAND)
                                + " events=" + steve.equipChanges;
                    }
                    public static String inventoryCopy() {
                        Steve steve = new Steve();
                        Inventory copy = new Inventory(steve);
                        String empty = copy.getItem(40).toString();
                        copy.armor.set(0, new ItemStack("spare"));
                        return "copy=" + empty + " copyFeet=" + copy.armor.get(0) + " playerFeet=" + steve.getItemBySlot(EquipmentSlot.FEET);
                    }
                    public static String entity() {
                        Steve steve = new Steve();
                        return "slot=" + LivingEntity.getSlotForHand(net.minecraft.world.InteractionHand.OFF_HAND)
                                + " op2=" + steve.hasPermissions(2) + " op4=" + steve.hasPermissions(4) + " op5=" + steve.hasPermissions(5)
                                + " plain0=" + new Plain().hasPermissions(0) + " plain1=" + new Plain().hasPermissions(1)
                                + " level=" + steve.getCommandSenderWorld() + " tags=" + steve.getTags()
                                + " ride=" + steve.startRiding(new Steve(), true);
                    }
                    public static String items() {
                        ItemCooldowns cooldowns = new ItemCooldowns();
                        SpawnEggItem egg = new SpawnEggItem("pig");
                        return "cooldown=" + cooldowns.isOnCooldown(new Item("ender_pearl")) + " egg=" + egg.getType(new ItemStack("pig"))
                                + " motion=" + new net.minecraft.world.level.block.state.BlockState().blocksMotion()
                                + " owner=" + new Server().isSingleplayerOwner(new com.mojang.authlib.GameProfile("Steve"));
                    }
                    public static String fall() {
                        FallingMob mob = new FallingMob();
                        mob.causeFallDamage(3.0f, 1.0f, null);
                        String first = FallLog.LOG.toString();
                        FallLog.LOG.setLength(0);
                        Entity asEntity = mob;
                        asEntity.causeFallDamage(5.0f, 1.0f, null);
                        return first + "|" + FallLog.LOG;
                    }
                    public static String hurt() {
                        Target target = new Target();
                        boolean hit = net.minecraft.server.level.HostDamage.hurtServer(target);
                        return "hit:" + hit + " calls=" + target.calls + " plain:" + net.minecraft.server.level.HostDamage.hurtServer(new Plain())
                                + " part:" + net.minecraft.server.level.HostDamage.hurtServer(new Part());
                    }
                    public static String hurtOnce() {
                        Hitter direct = new Hitter();
                        direct.hurt(new net.minecraft.world.damagesource.DamageSource(), 1f);
                        Hitter dealt = new Hitter();
                        boolean hit = net.minecraft.server.level.HostDamage.hurtServer(dealt);
                        return "direct calls=" + direct.calls + " marks=" + direct.marks + " dealt calls=" + dealt.calls + " marks=" + dealt.marks + " hit=" + hit;
                    }
                    public static String fabricHurtOnce() {
                        FabricHitter direct = new FabricHitter();
                        direct.hurtClient(new net.minecraft.world.damagesource.DamageSource(), 1f);
                        FabricHitter dealt = new FabricHitter();
                        boolean hit = net.minecraft.server.level.HostDamage.hurtServer(dealt);
                        return "direct calls=" + direct.calls + " marks=" + direct.marks + " dealt calls=" + dealt.calls + " marks=" + dealt.marks + " hit=" + hit;
                    }
                    public static String fakePlayer() { return "fake:" + new Steve().name(); }
                }""");
        s.put("mod/Steve.java", """
                package mod;
                public final class Steve extends net.minecraft.world.entity.player.Player {
                    public Steve() { super(net.minecraft.world.level.Level.OVERWORLD, null, 0f, new com.mojang.authlib.GameProfile("Steve")); }
                }""");
        s.put("mod/Server.java", "package mod; public final class Server extends net.minecraft.server.MinecraftServer {}");
        s.put("mod/FallingMob.java", """
                package mod;
                public class FallingMob extends net.minecraft.world.entity.LivingEntity {
                    @Override public boolean causeFallDamage(float distance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
                        net.minecraft.world.entity.FallLog.LOG.append("mod(").append(distance).append(")>");
                        return super.causeFallDamage(distance, multiplier, source);
                    }
                }""");
        s.put("mod/Target.java", """
                package mod;
                public class Target extends net.minecraft.world.entity.Entity {
                    public int calls;
                    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
                        calls++;
                        return true;
                    }
                }""");
        s.put("mod/Plain.java", "package mod; public class Plain extends net.minecraft.world.entity.Entity {}");
        s.put("mod/FabricHitter.java", """
                package mod;
                public class FabricHitter extends net.minecraft.world.entity.Entity {
                    public int calls;
                    @Override public boolean hurtClient(net.minecraft.world.damagesource.DamageSource source, float amount) {
                        calls++;
                        return super.hurtClient(source, amount);
                    }
                }""");
        s.put("mod/Hitter.java", """
                package mod;
                public class Hitter extends net.minecraft.world.entity.Entity {
                    public int calls;
                    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
                        calls++;
                        return super.hurt(source, amount);
                    }
                }""");
        s.put("mod/PartBase.java", "package mod; public abstract class PartBase extends net.minecraft.world.entity.Entity {}");
        s.put("mod/Part.java", """
                package mod;
                public class Part extends PartBase {
                    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) { return true; }
                }""");
        return s;
    }

    // 26.3's shapes.
    private static Map<String, String> newWorld() {
        Map<String, String> s = new HashMap<>(shared());
        s.put("net/minecraft/world/entity/Entity.java", """
                package net.minecraft.world.entity;
                public abstract class Entity {
                    public boolean rodeWithEvents;
                    public net.minecraft.world.level.Level level() { return net.minecraft.world.level.Level.OVERWORLD; }
                    public java.util.Set<String> entityTags() { return java.util.Set.of("boss"); }
                    public boolean startRiding(Entity vehicle, boolean force, boolean events) { rodeWithEvents = events; return force; }
                    public boolean causeFallDamage(double distance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
                        FallLog.LOG.append("host(").append((float) distance).append(")");
                        return false;
                    }
                    // The host's own damage entry point: it asks hurtServer, which an old hurt override's super call reaches again.
                    public final boolean hurtOrSimulate(net.minecraft.world.damagesource.DamageSource source, float amount) { return hurtServer(null, source, amount); }
                    // Counts markHurt, which old Entity.hurt called for a source that could touch the entity.
                    public int marks;
                    protected final boolean isInvulnerableToBase(net.minecraft.world.damagesource.DamageSource source) { return false; }
                    protected void markHurt() { marks++; }
                    public abstract boolean hurtServer(net.minecraft.server.level.ServerLevel level, net.minecraft.world.damagesource.DamageSource source, float amount);
                }""");
        s.put("net/minecraft/world/entity/LivingEntity.java", """
                package net.minecraft.world.entity;
                public abstract class LivingEntity extends Entity {
                    protected final EntityEquipment equipment = new EntityEquipment();
                    // Counts writes that would play equip sounds and fire game events in a real game.
                    public int equipChanges;
                    public net.minecraft.world.item.ItemStack getItemBySlot(EquipmentSlot slot) { return equipment.get(slot); }
                    public void setItemSlot(EquipmentSlot slot, net.minecraft.world.item.ItemStack stack) { equipChanges++; equipment.set(slot, stack); }
                    public boolean hurtServer(net.minecraft.server.level.ServerLevel level, net.minecraft.world.damagesource.DamageSource source, float amount) { return false; }
                }""");
        s.put("net/minecraft/world/entity/player/Player.java", """
                package net.minecraft.world.entity.player;
                import net.minecraft.world.entity.EquipmentSlot;
                import net.minecraft.world.item.ItemStack;
                public abstract class Player extends net.minecraft.world.entity.LivingEntity {
                    public final Inventory inventory;
                    private final com.mojang.authlib.GameProfile profile;
                    public Player(net.minecraft.world.level.Level level, com.mojang.authlib.GameProfile profile) {
                        this.profile = profile;
                        this.inventory = new Inventory(this, equipment);
                        equipment.set(EquipmentSlot.OFFHAND, new ItemStack("shield"));
                        equipment.set(EquipmentSlot.FEET, new ItemStack("boots"));
                        equipment.set(EquipmentSlot.LEGS, new ItemStack("legs"));
                        equipment.set(EquipmentSlot.CHEST, new ItemStack("chest"));
                        equipment.set(EquipmentSlot.HEAD, new ItemStack("helmet"));
                    }
                    public String name() { return profile.name(); }
                    public Inventory getInventory() { return inventory; }
                    public net.minecraft.server.permissions.PermissionSet permissions() { return p -> ((net.minecraft.server.permissions.Permission.HasCommandLevel) p).level().id() <= 2; }
                }""");
        s.put("net/minecraft/world/entity/player/Inventory.java", """
                package net.minecraft.world.entity.player;
                import net.minecraft.world.entity.EquipmentSlot;
                public class Inventory {
                    public final Player player;
                    private final net.minecraft.world.entity.EntityEquipment equipment;
                    public Inventory(Player player, net.minecraft.world.entity.EntityEquipment equipment) { this.player = player; this.equipment = equipment; }
                    // 26.3's EQUIPMENT_SLOT_MAPPING: 36 to 39 are the armor, feet first, and 40 is the offhand.
                    private static EquipmentSlot slot(int index) {
                        return switch (index) { case 36 -> EquipmentSlot.FEET; case 37 -> EquipmentSlot.LEGS; case 38 -> EquipmentSlot.CHEST;
                            case 39 -> EquipmentSlot.HEAD; case 40 -> EquipmentSlot.OFFHAND; default -> throw new IllegalArgumentException("slot " + index); };
                    }
                    public net.minecraft.world.item.ItemStack getItem(int index) { return equipment.get(slot(index)); }
                    public void setItem(int index, net.minecraft.world.item.ItemStack stack) { equipment.set(slot(index), stack); }
                }""");
        s.put("net/minecraft/world/entity/EntityEquipment.java", """
                package net.minecraft.world.entity;
                public class EntityEquipment {
                    private final java.util.Map<EquipmentSlot, net.minecraft.world.item.ItemStack> slots = new java.util.EnumMap<>(EquipmentSlot.class);
                    public net.minecraft.world.item.ItemStack get(EquipmentSlot slot) { return slots.getOrDefault(slot, net.minecraft.world.item.ItemStack.EMPTY); }
                    public void set(EquipmentSlot slot, net.minecraft.world.item.ItemStack stack) { slots.put(slot, stack); }
                }""");
        s.put("net/minecraft/server/permissions/PermissionSet.java", """
                package net.minecraft.server.permissions;
                public interface PermissionSet { boolean hasPermission(Permission permission); }""");
        s.put("net/minecraft/server/permissions/Permission.java", """
                package net.minecraft.server.permissions;
                public interface Permission { record HasCommandLevel(PermissionLevel level) implements Permission {} }""");
        s.put("net/minecraft/server/permissions/PermissionLevel.java", """
                package net.minecraft.server.permissions;
                public enum PermissionLevel { ALL, MODERATORS, GAMEMASTERS, ADMINS, OWNERS;
                    public static PermissionLevel byId(int id) { return values()[id]; } public int id() { return ordinal(); } }""");
        s.put("net/minecraft/world/item/ItemCooldowns.java", """
                package net.minecraft.world.item;
                public class ItemCooldowns { public boolean isOnCooldown(ItemStack stack) { return stack.toString().equals("ender_pearl"); } }""");
        s.put("net/minecraft/world/item/SpawnEggItem.java", """
                package net.minecraft.world.item;
                public class SpawnEggItem extends Item { public SpawnEggItem(String name) { super(name); }
                    public static net.minecraft.world.entity.EntityType<?> getType(ItemStack stack) { return new net.minecraft.world.entity.EntityType<>(stack.toString()); } }""");
        s.put("net/minecraft/world/level/block/state/BlockState.java", """
                package net.minecraft.world.level.block.state;
                public class BlockState { public net.minecraft.world.level.block.Block getBlock() { return net.minecraft.world.level.block.Blocks.COBWEB; } public boolean isSolid() { return true; } }""");
        s.put("net/minecraft/world/level/block/Blocks.java", """
                package net.minecraft.world.level.block;
                public final class Blocks { public static final Block COBWEB = new Block(); public static final Block BAMBOO_SAPLING = new Block(); }""");
        s.put("net/minecraft/server/players/NameAndId.java", """
                package net.minecraft.server.players;
                public record NameAndId(String name) { public NameAndId(com.mojang.authlib.GameProfile profile) { this(profile.name()); } }""");
        s.put("net/minecraft/server/MinecraftServer.java", """
                package net.minecraft.server;
                public abstract class MinecraftServer { public boolean isSingleplayerOwner(net.minecraft.server.players.NameAndId owner) { return owner.name().equals("Steve"); } }""");
        s.put("net/minecraft/server/level/HostDamage.java", "package net.minecraft.server.level; public final class HostDamage { public static boolean hurtServer(net.minecraft.world.entity.Entity e) { return e.hurtOrSimulate(null, 1f); } }");
        return s;
    }

    private static Map<String, String> shared() {
        Map<String, String> s = new HashMap<>();
        s.put("net/minecraft/world/InteractionHand.java", """
                package net.minecraft.world;
                public enum InteractionHand { MAIN_HAND, OFF_HAND;
                    public net.minecraft.world.entity.EquipmentSlot asEquipmentSlot() { return this == MAIN_HAND ? net.minecraft.world.entity.EquipmentSlot.MAINHAND : net.minecraft.world.entity.EquipmentSlot.OFFHAND; } }""");
        s.put("net/minecraft/world/entity/FallLog.java", "package net.minecraft.world.entity; public final class FallLog { public static final StringBuilder LOG = new StringBuilder(); }");
        s.put("net/minecraft/world/entity/EquipmentSlot.java", "package net.minecraft.world.entity; public enum EquipmentSlot { MAINHAND, OFFHAND, FEET, LEGS, CHEST, HEAD }");
        s.put("net/minecraft/world/entity/EntityType.java", "package net.minecraft.world.entity; public record EntityType<T>(String name) { public String toString() { return name; } }");
        s.put("net/minecraft/world/level/Level.java", "package net.minecraft.world.level; public class Level { public static final Level OVERWORLD = new Level(); public String toString() { return \"overworld\"; } }");
        s.put("net/minecraft/world/level/block/Block.java", "package net.minecraft.world.level.block; public class Block {}");
        s.put("net/minecraft/world/damagesource/DamageSource.java", "package net.minecraft.world.damagesource; public class DamageSource {}");
        s.put("net/minecraft/server/level/ServerLevel.java", "package net.minecraft.server.level; public class ServerLevel extends net.minecraft.world.level.Level {}");
        s.put("net/minecraft/core/BlockPos.java", "package net.minecraft.core; public class BlockPos {}");
        s.put("com/mojang/authlib/GameProfile.java", "package com.mojang.authlib; public record GameProfile(String name) {}");
        s.put("net/minecraft/world/item/Item.java", """
                package net.minecraft.world.item;
                public class Item { final String name; public Item(String name) { this.name = name; }
                    public ItemStack getDefaultInstance() { return new ItemStack(name); } }""");
        s.put("net/minecraft/world/item/ItemStack.java", """
                package net.minecraft.world.item;
                public final class ItemStack { public static final ItemStack EMPTY = new ItemStack("empty"); final String name;
                    public ItemStack(String name) { this.name = name; } public String toString() { return name; } }""");
        s.put("net/minecraft/core/NonNullList.java", """
                package net.minecraft.core;
                public class NonNullList<E> extends java.util.AbstractList<E> {
                    private final java.util.List<E> list;
                    protected NonNullList(java.util.List<E> list, E defaultValue) { this.list = list; }
                    public E get(int i) { return list.get(i); } public E set(int i, E e) { return list.set(i, e); } public int size() { return list.size(); }
                }""");
        return s;
    }
}
