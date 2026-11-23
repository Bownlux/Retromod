/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mixin.MixinCompatibilityTransformer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A 1.21.1 mod's profile cache, flying mob, moss block and saddle code still runs on a host that removed all four. */
class LegacyRemovedVanillaTypesTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The old profile cache adds and finds game profiles through the name and id cache")
    void profileCacheAnswersWithGameProfiles(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1");
        assertEquals("Alex true Alex missing=false", run.call("mod/Profiles", "lookups"));
    }

    @Test
    @DisplayName("A mod's flying mob flies, never falls or climbs, and instanceof still matches the ghast and phantom")
    void flyingMobKeepsItsBase(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1");
        assertEquals("speed=0.02 fell=false climbs=false", run.call("mod/Flying", "fly"));
        assertEquals("ghast=true phantom=true wisp=true zombie=false", run.call("mod/Flying", "checks"));
    }

    @Test
    @DisplayName("A mod's moss blocks place vanilla's moss patch, and instanceof matches vanilla's moss block")
    void mossBlockKeepsItsConstructor(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1");
        assertEquals("plain=placer(moss_patch_bonemeal) glowing=placer(moss_patch_bonemeal) vanilla=true own=true stone=false",
                run.call("mod/Moss", "blocks"));
    }

    @Test
    @DisplayName("Saddle checks and calls work on vanilla's saddled mobs and on the mod's own Saddleable")
    void saddleableAnswersForVanillaAndModMobs(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1");
        assertEquals("bare bare none -> saddled saddled ridden=true,true", run.call("mod/Saddles", "saddles"));
        assertEquals("saddled=true saddleable=true", run.call("mod/Saddles", "vanillaCall"));
        assertEquals("handle=true", run.call("mod/Saddles", "handles"), "a method reference takes the same helper");
        assertEquals("lambda=true,true own=true alt=true,true", run.call("mod/Saddles", "lambdas"),
                "javac lambda bodies take vanilla mobs and mod mounts; a mod method over the interface still links");
    }

    @Test
    @DisplayName("The cast-dropping instanceof helper is refused for a class or an unregistered name")
    void interfaceHelperRefusesClasses() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyRemovedVanillaTypes.register(t, name -> null);
        assertFalse(t.registerInterfaceInstanceofHelper(LegacyRemovedVanillaTypes.LEGACY_FLYING_MOB,
                LegacyRemovedVanillaTypes.LEGACY_FLYING_MOB, "isFlyingMob"), "a rebuilt class keeps its casts");
        assertFalse(t.registerInterfaceInstanceofHelper("mod/NotRegistered", "mod/Helper", "is"));
        assertTrue(t.registerInterfaceInstanceofHelper(LegacyRemovedVanillaTypes.LEGACY_SADDLEABLE,
                LegacyRemovedVanillaTypes.SADDLEABLES, "is"));
    }

    @Test
    @DisplayName("Another mod's embedded copy of a rebuilt type passes the old instanceof checks")
    void instanceofAcceptsAnotherModsCopy(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, "1.21.1");
        Map<String, byte[]> synthetic = RetromodTransformer.getInstance().getSyntheticClasses();
        String prefix = "com/retromod/embedded/other_0/";
        Map<String, String> names = new HashMap<>();
        for (String rebuilt : List.of(LegacyRemovedVanillaTypes.LEGACY_FLYING_MOB, LegacyRemovedVanillaTypes.LEGACY_MOSS_BLOCK,
                LegacyRemovedVanillaTypes.LEGACY_SADDLEABLE)) {
            names.put(rebuilt, prefix + rebuilt);
        }
        Map<String, byte[]> classes = new HashMap<>();
        names.forEach((rebuilt, relocated) -> classes.put(relocated, relocate(synthetic.get(rebuilt), names)));
        // Another mod's flying mob and mount, built on its own copies.
        String entityArgs = "(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V";
        classes.put("other/Wisp", subclass("other/Wisp", prefix + LegacyRemovedVanillaTypes.LEGACY_FLYING_MOB, null, entityArgs));
        classes.put("other/Mount", subclass("other/Mount", "java/lang/Object", prefix + LegacyRemovedVanillaTypes.LEGACY_SADDLEABLE, "()V"));
        ClassLoader other = new ClassLoader(run) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        Class<?> type = run.loadClass("net.minecraft.world.entity.EntityType");
        Class<?> level = run.loadClass("net.minecraft.world.level.Level");
        Object wisp = other.loadClass("other.Wisp").getConstructor(type, level)
                .newInstance(type.getConstructor(Object[].class).newInstance((Object) new Object[0]), level.getConstructor().newInstance());
        Class<?> properties = run.loadClass("net.minecraft.world.level.block.state.BlockBehaviour$Properties");
        Object moss = other.loadClass((prefix + LegacyRemovedVanillaTypes.LEGACY_MOSS_BLOCK).replace('/', '.'))
                .getConstructor(properties).newInstance(properties.getConstructor().newInstance());
        Object mount = other.loadClass("other.Mount").getConstructor().newInstance();
        assertEquals("flying=true moss=true saddle=true", run.call("mod/OtherCopies", "check", wisp, moss, mount));
    }

    @Test
    @DisplayName("Revamped Phantoms' mixin selector on FlyingMob still names Mob, not the rebuilt class")
    void flyingMobSelectorsStillNameMob() throws Exception {
        Path jar = Path.of("test-jars-mixin/revampedphantoms-1.1.2-neoforge.jar");
        Assumptions.assumeTrue(Files.exists(jar), "the Revamped Phantoms fixture is missing");
        byte[] mixin;
        try (JarFile file = new JarFile(jar.toFile())) {
            mixin = file.getInputStream(file.getEntry("dev/lukebemish/revampedphantoms/mixin/PhantomMixin.class")).readAllBytes();
        }
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyRemovedVanillaTypes.register(t, name -> null);
        String constants = new String(new MixinCompatibilityTransformer(t).transformMixinClass(mixin), StandardCharsets.ISO_8859_1);
        assertTrue(constants.contains("Lnet/minecraft/world/entity/Mob;getDefaultDimensions("), "the @At target names Mob");
        assertFalse(constants.contains("LegacyFlyingMob"), "no selector names the rebuilt class");
    }

    @Test
    @DisplayName("A mod built after each removal keeps the names it was built with")
    void newerModsKeepTheirNames(@TempDir Path dir) throws Exception {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), oldWorld());
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("new"), newWorld());
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", t -> {
            LegacyRemovedVanillaTypes.register(t, reader(host));
            t.setModSourceVersion("1.21.9");
        }, BridgeHarness.only(old, "mod/"));
        assertEquals("net/minecraft/world/entity/FlyingMob", node(mod.get("mod/Flying$Wisp")).superName);
        assertTrue(BridgeHarness.calls(mod.get("mod/Profiles"), "lookups")
                .contains("net/minecraft/server/players/GameProfileCache.get(Ljava/lang/String;)Ljava/util/Optional;"));
    }

    @Test
    @DisplayName("Only a host without the removed types gets the bridge; offline the target decides")
    void onlyHostsWithoutTheTypesGetTheBridge() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        LegacyRemovedVanillaTypes.register(t, name -> new ClassNode());
        assertTrue(t.getSyntheticClasses().keySet().stream().noneMatch(n -> n.startsWith(RebuiltApiClasses.ROOT)),
                "a host that still has every type keeps them");
        RetromodVersion.TARGET_MC_VERSION = "1.21.10";
        LegacyRemovedVanillaTypes.register(t, name -> null);
        assertFalse(t.getSyntheticClasses().containsKey(LegacyRemovedVanillaTypes.LEGACY_FLYING_MOB));
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        LegacyRemovedVanillaTypes.register(t, name -> null);
        assertTrue(t.getSyntheticClasses().containsKey(LegacyRemovedVanillaTypes.LEGACY_FLYING_MOB));
        assertTrue(t.getSyntheticClasses().containsKey(LegacyRemovedVanillaTypes.LEGACY_SADDLEABLE));
        assertTrue(t.getSyntheticClasses().containsKey(LegacyRemovedVanillaTypes.PROFILES));
    }

    private static BridgeHarness.Run run(Path dir, String source) throws Exception {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), oldWorld());
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("new"), newWorld());
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", t -> {
            LegacyRemovedVanillaTypes.register(t, reader(host));
            t.setModSourceVersion(source);
        }, BridgeHarness.only(old, "mod/"), host);
        return new BridgeHarness.Run(host, mod);
    }

    private static Function<String, ClassNode> reader(Map<String, byte[]> host) {
        return name -> {
            byte[] bytes = host.get(name);
            return bytes == null ? null : node(bytes);
        };
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
        return node;
    }

    private static byte[] relocate(byte[] bytes, Map<String, String> names) {
        ClassWriter out = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(out, new SimpleRemapper(names)), 0);
        return out.toByteArray();
    }

    // A public class with one constructor that passes its arguments to its superclass's.
    private static byte[] subclass(String name, String superName, String iface, String constructor) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, superName,
                iface == null ? null : new String[] {iface});
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", constructor, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        for (int i = 1; i <= Type.getArgumentTypes(constructor).length; i++) mv.visitVarInsn(Opcodes.ALOAD, i);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", constructor, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        return cw.toByteArray();
    }

    // 1.21.1's shape: the four removed types, and vanilla mobs that implement or extend them.
    private static Map<String, String> oldWorld() {
        Map<String, String> s = new HashMap<>(shared());
        s.put("net/minecraft/server/players/GameProfileCache.java", """
                package net.minecraft.server.players;
                import com.mojang.authlib.GameProfile;
                public class GameProfileCache {
                    public void add(GameProfile p) {}
                    public java.util.Optional<GameProfile> get(String name) { return java.util.Optional.empty(); }
                    public java.util.Optional<GameProfile> get(java.util.UUID id) { return java.util.Optional.empty(); }
                    public java.util.concurrent.CompletableFuture<java.util.Optional<GameProfile>> getAsync(String name) { return null; }
                    public void setExecutor(java.util.concurrent.Executor e) {}
                    public void clearExecutor() {}
                }""");
        s.put("net/minecraft/server/MinecraftServer.java", """
                package net.minecraft.server;
                public class MinecraftServer {
                    public net.minecraft.server.players.GameProfileCache getProfileCache() { return null; }
                }""");
        s.put("net/minecraft/world/entity/FlyingMob.java", """
                package net.minecraft.world.entity;
                public abstract class FlyingMob extends Mob {
                    protected FlyingMob(EntityType<? extends FlyingMob> type, net.minecraft.world.level.Level level) { super(type, level); }
                }""");
        s.put("net/minecraft/world/entity/monster/Ghast.java", """
                package net.minecraft.world.entity.monster;
                public class Ghast extends net.minecraft.world.entity.FlyingMob {
                    public Ghast(net.minecraft.world.entity.EntityType<? extends Ghast> t, net.minecraft.world.level.Level l) { super(t, l); }
                }""");
        s.put("net/minecraft/world/entity/monster/Phantom.java", """
                package net.minecraft.world.entity.monster;
                public class Phantom extends net.minecraft.world.entity.FlyingMob {
                    public Phantom(net.minecraft.world.entity.EntityType<? extends Phantom> t, net.minecraft.world.level.Level l) { super(t, l); }
                }""");
        s.put("net/minecraft/world/level/block/MossBlock.java", """
                package net.minecraft.world.level.block;
                public class MossBlock extends Block {
                    public MossBlock(net.minecraft.world.level.block.state.BlockBehaviour.Properties p) { super(p); }
                }""");
        s.put("net/minecraft/world/level/block/Blocks.java", """
                package net.minecraft.world.level.block;
                public final class Blocks {
                    public static final Block MOSS_BLOCK = new MossBlock(new net.minecraft.world.level.block.state.BlockBehaviour.Properties());
                    public static final Block STONE = new Block(new net.minecraft.world.level.block.state.BlockBehaviour.Properties());
                }""");
        s.put("net/minecraft/world/entity/Saddleable.java", """
                package net.minecraft.world.entity;
                public interface Saddleable {
                    boolean isSaddleable();
                    void equipSaddle(net.minecraft.world.item.ItemStack stack, net.minecraft.sounds.SoundSource source);
                    default net.minecraft.sounds.SoundEvent getSaddleSoundEvent() { return null; }
                    boolean isSaddled();
                }""");
        s.put("net/minecraft/world/entity/monster/Strider.java", """
                package net.minecraft.world.entity.monster;
                import net.minecraft.world.entity.*;
                public class Strider extends Mob implements Saddleable {
                    public Strider(EntityType<? extends Strider> t, net.minecraft.world.level.Level l) { super(t, l); }
                    public boolean isSaddleable() { return true; }
                    public void equipSaddle(net.minecraft.world.item.ItemStack stack, net.minecraft.sounds.SoundSource source) {}
                    public boolean isSaddled() { return false; }
                }""");
        mod(s);
        return s;
    }

    // 26.3's shape: the name and id cache, mobs that extend Mob, the feature placer, and the saddle slot.
    private static Map<String, String> newWorld() {
        Map<String, String> s = new HashMap<>(shared());
        s.put("net/minecraft/server/players/NameAndId.java", """
                package net.minecraft.server.players;
                public final class NameAndId {
                    final java.util.UUID id; final String name;
                    public NameAndId(com.mojang.authlib.GameProfile p) { id = p.getId(); name = p.getName(); }
                    public java.util.UUID id() { return id; }
                    public String name() { return name; }
                }""");
        s.put("net/minecraft/server/players/UserNameToIdResolver.java", """
                package net.minecraft.server.players;
                public interface UserNameToIdResolver {
                    void add(NameAndId entry);
                    java.util.Optional<NameAndId> get(String name);
                    java.util.Optional<NameAndId> get(java.util.UUID id);
                }""");
        s.put("net/minecraft/server/players/CachedUserNameToIdResolver.java", """
                package net.minecraft.server.players;
                import java.util.*;
                public class CachedUserNameToIdResolver implements UserNameToIdResolver {
                    final Map<String, NameAndId> byName = new HashMap<>();
                    public void add(NameAndId entry) { byName.put(entry.name(), entry); }
                    public Optional<NameAndId> get(String name) { return Optional.ofNullable(byName.get(name)); }
                    public Optional<NameAndId> get(UUID id) { return byName.values().stream().filter(e -> e.id().equals(id)).findFirst(); }
                }""");
        s.put("net/minecraft/server/Services.java", """
                package net.minecraft.server;
                public final class Services {
                    final net.minecraft.server.players.UserNameToIdResolver cache = new net.minecraft.server.players.CachedUserNameToIdResolver();
                    public net.minecraft.server.players.UserNameToIdResolver nameToIdCache() { return cache; }
                }""");
        s.put("net/minecraft/server/MinecraftServer.java", """
                package net.minecraft.server;
                public class MinecraftServer {
                    final Services services = new Services();
                    public Services services() { return services; }
                }""");
        s.put("net/minecraft/world/entity/monster/Ghast.java", """
                package net.minecraft.world.entity.monster;
                public class Ghast extends net.minecraft.world.entity.Mob {
                    public Ghast(net.minecraft.world.entity.EntityType<? extends Ghast> t, net.minecraft.world.level.Level l) { super(t, l); }
                }""");
        s.put("net/minecraft/world/entity/monster/Phantom.java", """
                package net.minecraft.world.entity.monster;
                public class Phantom extends net.minecraft.world.entity.Mob {
                    public Phantom(net.minecraft.world.entity.EntityType<? extends Phantom> t, net.minecraft.world.level.Level l) { super(t, l); }
                }""");
        s.put("net/minecraft/world/level/block/BonemealableBlock.java", """
                package net.minecraft.world.level.block;
                public interface BonemealableBlock {}""");
        s.put("net/minecraft/world/level/block/BonemealableFeaturePlacerBlock.java", """
                package net.minecraft.world.level.block;
                public class BonemealableFeaturePlacerBlock extends Block implements BonemealableBlock {
                    final net.minecraft.resources.ResourceKey<?> feature;
                    public BonemealableFeaturePlacerBlock(net.minecraft.resources.ResourceKey<?> feature, net.minecraft.world.level.block.state.BlockBehaviour.Properties p) { super(p); this.feature = feature; }
                    public String toString() { return "placer(" + feature + ")"; }
                }""");
        s.put("net/minecraft/data/worldgen/features/CaveFeatures.java", """
                package net.minecraft.data.worldgen.features;
                public class CaveFeatures {
                    public static final net.minecraft.resources.ResourceKey<?> MOSS_PATCH_BONEMEAL = new net.minecraft.resources.ResourceKey<>("moss_patch_bonemeal");
                }""");
        s.put("net/minecraft/world/level/block/Blocks.java", """
                package net.minecraft.world.level.block;
                public final class Blocks {
                    public static final Block MOSS_BLOCK = new BonemealableFeaturePlacerBlock(net.minecraft.data.worldgen.features.CaveFeatures.MOSS_PATCH_BONEMEAL, new net.minecraft.world.level.block.state.BlockBehaviour.Properties());
                    public static final Block STONE = new Block(new net.minecraft.world.level.block.state.BlockBehaviour.Properties());
                }""");
        s.put("net/minecraft/world/entity/monster/Strider.java", """
                package net.minecraft.world.entity.monster;
                import net.minecraft.world.entity.*;
                public class Strider extends Mob {
                    public Strider(EntityType<? extends Strider> t, net.minecraft.world.level.Level l) { super(t, l); }
                }""");
        s.put("net/minecraft/sounds/SoundEvents.java", """
                package net.minecraft.sounds;
                public class SoundEvents {
                    public static final net.minecraft.core.Holder.Reference<SoundEvent> HORSE_SADDLE = new net.minecraft.core.Holder.Reference<>(new SoundEvent(), java.util.Set.of());
                }""");
        return s;
    }

    // Classes both shapes share, kept to what the mod and the rebuilt classes touch.
    private static Map<String, String> shared() {
        Map<String, String> s = new HashMap<>();
        s.put("com/mojang/authlib/GameProfile.java", """
                package com.mojang.authlib;
                public final class GameProfile {
                    final java.util.UUID id; final String name;
                    public GameProfile(java.util.UUID id, String name) { this.id = id; this.name = name; }
                    public java.util.UUID getId() { return id; }
                    public String getName() { return name; }
                }""");
        s.put("net/minecraft/resources/ResourceKey.java", """
                package net.minecraft.resources;
                public final class ResourceKey<T> {
                    final String name;
                    public ResourceKey(String name) { this.name = name; }
                    public String toString() { return name; }
                }""");
        s.put("net/minecraft/tags/TagKey.java", "package net.minecraft.tags; public final class TagKey<T> {}");
        s.put("net/minecraft/tags/EntityTypeTags.java", """
                package net.minecraft.tags;
                public final class EntityTypeTags {
                    public static final TagKey<net.minecraft.world.entity.EntityType<?>> CAN_EQUIP_SADDLE = new TagKey<>();
                }""");
        s.put("net/minecraft/core/Holder.java", """
                package net.minecraft.core;
                public interface Holder<T> {
                    T value();
                    boolean is(net.minecraft.tags.TagKey<T> tag);
                    class Reference<T> implements Holder<T> {
                        final T value; final java.util.Set<?> tags;
                        public Reference(T value, java.util.Set<?> tags) { this.value = value; this.tags = tags; }
                        public T value() { return value; }
                        public boolean is(net.minecraft.tags.TagKey<T> tag) { return tags.contains(tag); }
                    }
                }""");
        s.put("net/minecraft/core/BlockPos.java", "package net.minecraft.core; public class BlockPos {}");
        s.put("net/minecraft/world/phys/Vec3.java", "package net.minecraft.world.phys; public class Vec3 {}");
        s.put("net/minecraft/world/level/Level.java", "package net.minecraft.world.level; public class Level {}");
        s.put("net/minecraft/world/level/block/state/BlockState.java", "package net.minecraft.world.level.block.state; public class BlockState {}");
        s.put("net/minecraft/world/level/block/state/BlockBehaviour.java", """
                package net.minecraft.world.level.block.state;
                public abstract class BlockBehaviour {
                    public static class Properties {}
                }""");
        s.put("net/minecraft/world/level/block/Block.java", """
                package net.minecraft.world.level.block;
                public class Block extends net.minecraft.world.level.block.state.BlockBehaviour {
                    public Block(net.minecraft.world.level.block.state.BlockBehaviour.Properties p) {}
                    public String toString() { return "block"; }
                }""");
        s.put("net/minecraft/sounds/SoundSource.java", "package net.minecraft.sounds; public enum SoundSource { NEUTRAL }");
        s.put("net/minecraft/sounds/SoundEvent.java", "package net.minecraft.sounds; public final class SoundEvent {}");
        s.put("net/minecraft/world/item/ItemStack.java", """
                package net.minecraft.world.item;
                public final class ItemStack {
                    public static final ItemStack EMPTY = new ItemStack("", 0);
                    final String name; int count;
                    public ItemStack(String name) { this(name, 1); }
                    public ItemStack(String name, int count) { this.name = name; this.count = count; }
                    public boolean isEmpty() { return name.isEmpty() || count <= 0; }
                    public void shrink(int amount) { count -= amount; }
                    public ItemStack copyWithCount(int count) { return new ItemStack(name, count); }
                }""");
        s.put("net/minecraft/world/entity/EquipmentSlot.java", "package net.minecraft.world.entity; public enum EquipmentSlot { MAINHAND, SADDLE }");
        s.put("net/minecraft/world/entity/EntityType.java", """
                package net.minecraft.world.entity;
                public class EntityType<T extends Entity> {
                    public static final EntityType<net.minecraft.world.entity.monster.Strider> STRIDER = new EntityType<>(net.minecraft.tags.EntityTypeTags.CAN_EQUIP_SADDLE);
                    final net.minecraft.core.Holder.Reference<EntityType<?>> holder;
                    public EntityType(Object... tags) { holder = new net.minecraft.core.Holder.Reference<>(this, java.util.Set.of(tags)); }
                    public net.minecraft.core.Holder.Reference<EntityType<?>> builtInRegistryHolder() { return holder; }
                }""");
        s.put("net/minecraft/world/entity/Entity.java", """
                package net.minecraft.world.entity;
                public class Entity {
                    final EntityType<?> type;
                    public Entity(EntityType<?> type) { this.type = type; }
                    public EntityType<?> getType() { return type; }
                }""");
        s.put("net/minecraft/world/entity/LivingEntity.java", """
                package net.minecraft.world.entity;
                import net.minecraft.world.item.ItemStack;
                public class LivingEntity extends Entity {
                    final java.util.Map<EquipmentSlot, ItemStack> slots = new java.util.EnumMap<>(EquipmentSlot.class);
                    public float flyingSpeed = -1; public boolean fell;
                    public LivingEntity(EntityType<?> type) { super(type); }
                    public ItemStack getItemBySlot(EquipmentSlot slot) { return slots.getOrDefault(slot, ItemStack.EMPTY); }
                    public void setItemSlot(EquipmentSlot slot, ItemStack stack) { slots.put(slot, stack); }
                    public boolean canUseSlot(EquipmentSlot slot) { return true; }
                    public void travel(net.minecraft.world.phys.Vec3 input) {}
                    protected void travelFlying(net.minecraft.world.phys.Vec3 input, float speed) { flyingSpeed = speed; }
                    protected void checkFallDamage(double y, boolean onGround, net.minecraft.world.level.block.state.BlockState state, net.minecraft.core.BlockPos pos) { fell = true; }
                    public boolean onClimbable() { return true; }
                }""");
        s.put("net/minecraft/world/entity/Mob.java", """
                package net.minecraft.world.entity;
                public class Mob extends LivingEntity {
                    protected Mob(EntityType<? extends Mob> type, net.minecraft.world.level.Level level) { super(type); }
                    public boolean isSaddled() { return !getItemBySlot(EquipmentSlot.SADDLE).isEmpty(); }
                }""");
        s.put("net/minecraft/world/entity/monster/Zombie.java", """
                package net.minecraft.world.entity.monster;
                public class Zombie extends net.minecraft.world.entity.Mob {
                    public Zombie(net.minecraft.world.entity.EntityType<? extends Zombie> t, net.minecraft.world.level.Level l) { super(t, l); }
                }""");
        return s;
    }

    // The mod, written against 1.21.1.
    private static void mod(Map<String, String> s) {
        s.put("mod/Profiles.java", """
                package mod;
                import com.mojang.authlib.GameProfile;
                import net.minecraft.server.MinecraftServer;
                import net.minecraft.server.players.GameProfileCache;
                public final class Profiles {
                    public static String lookups() {
                        GameProfileCache cache = new MinecraftServer().getProfileCache();
                        java.util.UUID id = new java.util.UUID(1, 2);
                        cache.setExecutor(Runnable::run);
                        cache.add(new GameProfile(id, "Alex"));
                        GameProfile byName = cache.get("Alex").orElseThrow();
                        GameProfile byId = cache.get(id).orElseThrow();
                        GameProfile async = cache.getAsync("Alex").join().orElseThrow();
                        cache.clearExecutor();
                        return byName.getName() + " " + byId.getId().equals(id) + " " + async.getName() + " missing=" + cache.get("Steve").isPresent();
                    }
                }""");
        s.put("mod/Flying.java", """
                package mod;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.entity.monster.*;
                import net.minecraft.world.level.Level;
                public final class Flying {
                    public static class Wisp extends FlyingMob {
                        public Wisp(EntityType<? extends Wisp> t, Level l) { super(t, l); }
                        String land() { checkFallDamage(1.0, true, null, null); return "fell=" + fell; }
                    }
                    public static String fly() {
                        Wisp wisp = new Wisp(new EntityType<>(), new Level());
                        wisp.travel(new net.minecraft.world.phys.Vec3());
                        return "speed=" + wisp.flyingSpeed + " " + wisp.land() + " climbs=" + wisp.onClimbable();
                    }
                    public static String checks() {
                        Level level = new Level();
                        Entity ghast = new Ghast(new EntityType<>(), level), phantom = new Phantom(new EntityType<>(), level);
                        Entity wisp = new Wisp(new EntityType<>(), level), zombie = new Zombie(new EntityType<>(), level);
                        return "ghast=" + (ghast instanceof FlyingMob) + " phantom=" + (phantom instanceof FlyingMob)
                                + " wisp=" + (wisp instanceof FlyingMob) + " zombie=" + (zombie instanceof FlyingMob);
                    }
                }""");
        s.put("mod/Moss.java", """
                package mod;
                import net.minecraft.world.level.block.*;
                import net.minecraft.world.level.block.state.BlockBehaviour;
                public final class Moss {
                    public static class Glowing extends MossBlock { public Glowing(BlockBehaviour.Properties p) { super(p); } }
                    public static String blocks() {
                        java.util.function.Function<BlockBehaviour.Properties, Block> factory = MossBlock::new;
                        Block plain = factory.apply(new BlockBehaviour.Properties());
                        Block glowing = new Glowing(new BlockBehaviour.Properties());
                        return "plain=" + plain + " glowing=" + glowing + " vanilla=" + (Blocks.MOSS_BLOCK instanceof MossBlock)
                                + " own=" + (glowing instanceof MossBlock) + " stone=" + (Blocks.STONE instanceof MossBlock);
                    }
                }""");
        s.put("mod/Saddles.java", """
                package mod;
                import net.minecraft.sounds.SoundSource;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.entity.monster.*;
                import net.minecraft.world.item.ItemStack;
                import net.minecraft.world.level.Level;
                public final class Saddles {
                    public static class Glider extends Mob implements Saddleable {
                        boolean saddled;
                        public Glider(EntityType<? extends Glider> t, Level l) { super(t, l); }
                        public boolean isSaddleable() { return true; }
                        public void equipSaddle(ItemStack stack, SoundSource source) { saddled = true; }
                        public boolean isSaddled() { return saddled; }
                    }
                    static String describe(Entity e) {
                        if (e instanceof Saddleable s && s.isSaddled()) return "saddled";
                        return e instanceof Saddleable ? "bare" : "none";
                    }
                    static boolean ride(Saddleable s) {
                        if (s.isSaddleable()) s.equipSaddle(new ItemStack("saddle"), SoundSource.NEUTRAL);
                        return s.isSaddled();
                    }
                    static Entity strider(Level level) { return new Strider(EntityType.STRIDER, level); }
                    public static String saddles() {
                        Level level = new Level();
                        Entity strider = strider(level), glider = new Glider(new EntityType<>(), level), zombie = new Zombie(new EntityType<>(), level);
                        String before = describe(strider) + " " + describe(glider) + " " + describe(zombie);
                        boolean striderRidden = strider instanceof Saddleable s && ride(s);
                        boolean gliderRidden = ride((Saddleable) glider);
                        return before + " -> " + describe(strider) + " " + describe(glider) + " ridden=" + striderRidden + "," + gliderRidden;
                    }
                    public static String vanillaCall() {
                        Strider strider = (Strider) strider(new Level());
                        ItemStack saddle = new ItemStack("saddle");
                        strider.equipSaddle(saddle, SoundSource.NEUTRAL);
                        // The caller used to hand over its stack and shrink it afterwards.
                        saddle.shrink(1);
                        return "saddled=" + strider.isSaddled() + " saddleable=" + strider.isSaddleable();
                    }
                    static boolean saddledByMod(Saddleable s) { return s.isSaddled(); }
                    public static String lambdas() {
                        Entity strider = strider(new Level());
                        ride((Saddleable) strider);
                        Glider glider = new Glider(new EntityType<>(), new Level());
                        glider.saddled = true;
                        java.util.function.Predicate<Saddleable> body = s -> s.isSaddled();
                        java.util.function.Predicate<Saddleable> own = Saddles::saddledByMod;
                        java.util.function.Predicate<Saddleable> alt = (java.util.function.Predicate<Saddleable> & java.io.Serializable) s -> s.isSaddled();
                        return "lambda=" + body.test((Saddleable) strider) + "," + body.test(glider) + " own=" + own.test(glider)
                                + " alt=" + alt.test((Saddleable) strider) + "," + alt.test(glider);
                    }
                    public static String handles() {
                        Entity strider = strider(new Level());
                        ride((Saddleable) strider);
                        java.util.function.Predicate<Saddleable> saddled = Saddleable::isSaddled;
                        return "handle=" + saddled.test((Saddleable) strider);
                    }
                }""");
        s.put("mod/OtherCopies.java", """
                package mod;
                public final class OtherCopies {
                    public static String check(Object wisp, Object moss, Object mount) {
                        return "flying=" + (wisp instanceof net.minecraft.world.entity.FlyingMob)
                                + " moss=" + (moss instanceof net.minecraft.world.level.block.MossBlock)
                                + " saddle=" + (mount instanceof net.minecraft.world.entity.Saddleable);
                    }
                }""");
    }
}
