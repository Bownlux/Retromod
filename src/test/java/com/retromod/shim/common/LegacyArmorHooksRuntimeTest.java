/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.shim.forge.LegacyArmorOverrideAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Old armor model, texture and disguise overrides, run through the rebuilt hooks against stand-ins for NeoForge 26.3 and Forge 26.3. */
class LegacyArmorHooksRuntimeTest {

    private static final Path COMPILED = Path.of("target", "legacyapi-classes");
    private static final String ROOT = "com.retromod.legacyapi.";
    private static final String HOOKS = ROOT + "neoforge.client.extensions.LegacyArmorHooks";
    private static final String EXTENSIONS = ROOT + "neoforge.client.extensions.LegacyArmorExtensions";

    @Test
    @DisplayName("NeoForge's generic armor hook reaches an old extension's humanoid override through the added hook, and copies the pose onto its model")
    void oldModelOverrideAnswersTheNewHook(@TempDir Path dir) throws Exception {
        Host host = new Host(dir, neoForge(), Set.of("mod/OldArmorProperties"), LegacyArmorOverrideAdapter.NEO_ARMOR);
        Object ext = host.make("mod.OldArmorProperties");
        Object original = host.model("vanilla");
        Object chest = host.stack("mod.PlainItem", "CHEST");

        Object chosen = host.invoke(ext, "getGenericArmorModel", chest, host.layerType("HUMANOID"), original);
        assertEquals("custom", host.name(chosen), "the old override's model is drawn");
        assertEquals(List.of(chosen), host.type("net.neoforged.neoforge.client.ClientHooks").getField("copied").get(null),
                "the host copies its model's pose onto the replacement, as for a native extension");
        assertSame(original, host.invoke(ext, "getGenericArmorModel", chest, host.layerType("WINGS"), original),
                "a wing layer never reached the old hook");
        assertSame(original, host.invoke(ext, "getGenericArmorModel", host.stack("mod.PlainItem", "LEGS"),
                host.layerType("HUMANOID_LEGGINGS"), original), "the override's own answer for the legs slot is the original");
    }

    @Test
    @DisplayName("An old override that reaches a removed API falls back to the host's model for that item only, and one that calls back into itself does not loop")
    void failingAndReentrantOverridesFallBack(@TempDir Path dir) throws Exception {
        Host host = new Host(dir, neoForge(), Set.of("mod/BrokenArmorProperties", "mod/PartlyBrokenProperties"), LegacyArmorOverrideAdapter.NEO_ARMOR);
        Object original = host.model("vanilla");
        Object chest = host.stack("mod.PlainItem", "CHEST");
        Object broken = host.make("mod.BrokenArmorProperties");
        assertSame(original, host.invoke(broken, "getGenericArmorModel", chest, host.layerType("HUMANOID"), original));
        assertSame(original, host.invoke(broken, "getGenericArmorModel", chest, host.layerType("HUMANOID"), original),
                "it stays on the host's model without calling the broken override again");
        Object partly = host.make("mod.PartlyBrokenProperties");
        assertSame(original, host.invoke(partly, "getGenericArmorModel", host.stack("mod.MaskItem", "CHEST"), host.layerType("HUMANOID"), original));
        assertEquals("custom", host.name(host.invoke(partly, "getGenericArmorModel", chest, host.layerType("HUMANOID"), original)),
                "one item's broken branch leaves the extension's other items working");

        // A forwarding extension that asks the old hook of itself, as a redirected self call does.
        Object forwarding = host.make("mod.Forwarding");
        Method oldCall = host.staticMethod(HOOKS, "getHumanoidArmorModel", 5);
        Object chestSlot = host.slot("CHEST");
        host.type("mod.Forwarding").getField("again").set(null, (Function<Object, Object>) self -> {
            try {
                return oldCall.invoke(null, self, null, chest, chestSlot, original);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
        assertSame(original, oldCall.invoke(null, forwarding, null, chest, chestSlot, original));
    }

    @Test
    @DisplayName("An old generic override that answers a plain model leaves the host's model, since a plain old model draws nothing on 26.x")
    void plainOldModelIsNotDrawn(@TempDir Path dir) throws Exception {
        Host host = new Host(dir, neoForge(), Set.of("mod/PlainModelProperties"), LegacyArmorOverrideAdapter.NEO_ARMOR);
        Object original = host.model("vanilla");
        assertSame(original, host.invoke(host.make("mod.PlainModelProperties"), "getGenericArmorModel", host.stack("mod.PlainItem", "HEAD"),
                host.layerType("HUMANOID"), original));
    }

    @Test
    @DisplayName("An added hook with no old answer asks the parent's own hook, and no texture hook hides a parent's own texture")
    void addedHooksDeferToAParentsOwnAnswer(@TempDir Path dir) throws Exception {
        Host host = new Host(dir, neoForge(), Set.of("mod/SubProperties"), LegacyArmorOverrideAdapter.NEO_ARMOR);
        Object sub = host.make("mod.SubProperties");
        Object original = host.model("vanilla");
        assertEquals("parent", host.name(host.invoke(sub, "getHumanoidArmorModel", host.stack("mod.PlainItem", "HEAD"),
                host.layerType("HUMANOID"), original)), "the old override gave the original, so the parent's model is used");
        assertEquals("custom", host.name(host.invoke(sub, "getHumanoidArmorModel", host.stack("mod.PlainItem", "CHEST"),
                host.layerType("HUMANOID"), original)));
        Object texture = host.invoke(sub, "getArmorTexture", host.stack("mod.ForgeArmorItem", "HEAD"), host.layerType("HUMANOID"),
                host.layer("mod:ruby"), host.id("minecraft:textures/entity/equipment/humanoid/ruby.png"));
        assertEquals("parent:textures/own.png", texture.toString());
    }

    @Test
    @DisplayName("An added hook with no old answer reaches the host's default through the extension's own sub-interface, or past a base no jar ships")
    void addedHooksReachTheDefaultThroughTheirInterface(@TempDir Path dir) throws Exception {
        Host host = new Host(dir, neoForge(), Set.of("mod/SubInterfaceProperties", "mod/UnshippedBaseProperties"), LegacyArmorOverrideAdapter.NEO_ARMOR);
        Object original = host.model("vanilla");
        for (String name : List.of("mod.SubInterfaceProperties", "mod.UnshippedBaseProperties")) {
            Object ext = host.make(name);
            assertSame(original, host.invoke(ext, "getHumanoidArmorModel", host.stack("mod.PlainItem", "HEAD"), host.layerType("HUMANOID"), original),
                    name + " answers the host's default where its old override gives the original");
            assertEquals("custom", host.name(host.invoke(ext, "getHumanoidArmorModel", host.stack("mod.PlainItem", "CHEST"),
                    host.layerType("HUMANOID"), original)), name);
        }
    }

    @Test
    @DisplayName("The generated NeoForge 21.1 armor layer answers its old texture path for the outer and inner layer")
    void generatedLayerTexture(@TempDir Path dir) throws Exception {
        Host host = new Host(dir, common(), Set.of(), null);
        host.classes.put("com.retromod.generated.LegacyArmorLayer", LegacyArmorMaterialRecordBridge.generateLayer());
        Class<?> layer = host.type("com.retromod.generated.LegacyArmorLayer");
        Class<?> id = host.type("net.minecraft.resources.Identifier");
        Method texture = layer.getMethod("texture", boolean.class);
        Object plain = layer.getConstructor(id).newInstance(host.id("mod:ruby"));
        assertEquals("mod:textures/models/armor/ruby_layer_1.png", texture.invoke(plain, false).toString());
        Object suffixed = layer.getConstructor(id, String.class, boolean.class).newInstance(host.id("mod:ruby"), "_overlay", true);
        assertEquals("mod:textures/models/armor/ruby_layer_2_overlay.png", texture.invoke(suffixed, true).toString());
    }

    @Test
    @DisplayName("An item's Forge texture override and its NeoForge 21.1 layer override answer 26.x's texture hook, with overlay and leggings")
    void oldItemTexturesAnswerTheNewHook(@TempDir Path dir) throws Exception {
        Host host = new Host(dir, neoForge(), Set.of(), null);
        Object ext = host.make(EXTENSIONS);
        Object fallback = host.id("minecraft:textures/entity/equipment/humanoid/ruby.png");
        Object layer = host.layer("mod:ruby");
        Object overlayLayer = host.layer("mod:ruby_overlay");

        assertEquals("mod:textures/models/armor/ruby_layer_1.png",
                host.invoke(ext, "getArmorTexture", host.stack("mod.ForgeArmorItem", "HEAD"), host.layerType("HUMANOID"), layer, fallback).toString());
        assertEquals("mod:textures/models/armor/ruby_layer_2.png", host.invoke(ext, "getArmorTexture", host.stack("mod.ForgeArmorItem", "LEGS"),
                host.layerType("HUMANOID_LEGGINGS"), layer, fallback).toString());
        assertEquals("mod:textures/models/armor/ruby_layer_1_overlay.png", host.invoke(ext, "getArmorTexture",
                host.stack("mod.ForgeArmorItem", "CHEST"), host.layerType("HUMANOID"), overlayLayer, fallback).toString(),
                "the overlay layer asks for \"overlay\"");

        assertEquals("mod:textures/models/armor/ruby_layer_2.png", host.invoke(ext, "getArmorTexture", host.stack("mod.LayerArmorItem", "LEGS"),
                host.layerType("HUMANOID_LEGGINGS"), layer, fallback).toString(), "the old layer is built from the host layer's texture id");
        assertEquals("mod:textures/models/armor/ruby_layer_2_overlay.png", host.invoke(ext, "getArmorTexture", host.stack("mod.LayerArmorItem", "LEGS"),
                host.layerType("HUMANOID_LEGGINGS"), overlayLayer, fallback).toString(), "a dyed armor's overlay layer keeps its suffix");
        assertSame(fallback, host.invoke(ext, "getArmorTexture", host.stack("mod.PlainItem", "HEAD"), host.layerType("HUMANOID"), layer, fallback));
    }

    @Test
    @DisplayName("Armor extensions register last in NeoForge's phase order, after a late registration on the last mod's bus, and only for this jar's items")
    void registersAfterEveryOtherRegistration(@TempDir Path dir) throws Exception {
        Host host = new Host(dir, neoForge(), Set.of(), null);
        // An item from another mod's jar: its own class loader stands for its own module.
        Object foreign = host.foreign("other.NativeArmorItem");
        Object ours = host.make("mod.ForgeArmorItem");
        Object late = host.make("mod.ForgeArmorItem");
        Object plain = host.make("mod.PlainItem");
        host.items(List.of(ours, late, plain, foreign));

        // Three mods in load order; the old mod's copy watches from its own bus, and the last mod registers one item itself at LOWEST.
        Class<?> bus = host.type("testsupport.Bus");
        Object oldBus = bus.getConstructor().newInstance(), otherBus = bus.getConstructor().newInstance(), lastBus = bus.getConstructor().newInstance();
        host.type("net.neoforged.fml.ModList").getField("MODS").set(null, List.of(host.container(oldBus), host.container(otherBus), host.container(lastBus)));
        host.staticMethod(EXTENSIONS, "listen", 1).invoke(null, oldBus);
        bus.getMethod("lateRegistration", Object.class).invoke(lastBus, late);
        Object event = host.make("net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent");

        // NeoForge's order: each priority in turn, across the mods in load order. A second registration of an item throws.
        for (Object priority : host.type("net.neoforged.bus.api.EventPriority").getEnumConstants()) {
            for (Object each : List.of(oldBus, otherBus, lastBus)) bus.getMethod("post", Object.class, Object.class).invoke(each, priority, event);
        }
        @SuppressWarnings("unchecked")
        Map<Object, Object> registered = (Map<Object, Object>) event.getClass().getField("registered").get(event);
        assertEquals(Set.of(ours, late), registered.keySet(), "the plain item and the other jar's item are left alone");
        assertEquals("testsupport.LateExtensions", registered.get(late).getClass().getName(), "the late registration keeps its item");
        assertEquals(host.type(EXTENSIONS), registered.get(ours).getClass());
    }

    @Test
    @DisplayName("Without NeoForge's mod list, armor extensions still register from the old mod's own bus")
    void registersFromItsOwnBusWithoutTheModList(@TempDir Path dir) throws Exception {
        Host host = new Host(dir, neoForge(), Set.of(), null);
        Object ours = host.make("mod.ForgeArmorItem");
        host.items(List.of(ours));
        host.type("net.neoforged.fml.ModList").getField("FAIL").set(null, new IllegalStateException("no mod list yet"));
        Class<?> bus = host.type("testsupport.Bus");
        Object own = bus.getConstructor().newInstance();
        host.staticMethod(EXTENSIONS, "listen", 1).invoke(null, own);
        Object event = host.make("net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent");
        for (Object priority : host.type("net.neoforged.bus.api.EventPriority").getEnumConstants()) {
            bus.getMethod("post", Object.class, Object.class).invoke(own, priority, event);
        }
        @SuppressWarnings("unchecked")
        Map<Object, Object> registered = (Map<Object, Object>) event.getClass().getField("registered").get(event);
        assertEquals(Set.of(ours), registered.keySet());
    }

    @Test
    @DisplayName("An item's old isEnderMask answers NeoForge's gaze disguise hook for an enderman and leaves other mobs to the host")
    void oldEnderMaskAnswersTheDisguiseHook(@TempDir Path dir) throws Exception {
        Host host = new Host(dir, neoForge(), Set.of("mod/MaskItem", "mod/BrokenMaskItem"), LegacyArmorOverrideAdapter.NEO_DISGUISE);
        Object stack = host.stack("mod.MaskItem", "HEAD");
        Object player = host.make("net.minecraft.world.entity.player.Player");
        Object enderman = host.make("net.minecraft.world.entity.monster.Enderman");
        Object creaking = host.make("net.minecraft.world.entity.LivingEntity");
        Method hook = host.type("net.minecraft.world.item.Item").getMethod("isGazeDisguise", host.type("net.minecraft.world.item.ItemStack"),
                host.type("net.minecraft.world.entity.player.Player"), host.type("net.minecraft.world.entity.LivingEntity"));
        assertEquals(true, hook.invoke(host.make("mod.MaskItem"), stack, player, enderman));
        assertEquals(false, hook.invoke(host.make("mod.MaskItem"), stack, player, creaking), "another mob gets the host's check");
        assertEquals(false, hook.invoke(host.make("mod.BrokenMaskItem"), stack, player, enderman), "a throwing override gets the host's check");
    }

    @Test
    @DisplayName("On Forge 26.3 an item's old isEnderMask answers the monster disguise hook for an Enderman")
    void forgeEnderMaskAnswersTheMonsterDisguiseHook(@TempDir Path dir) throws Exception {
        Map<String, String> sources = new HashMap<>(common());
        sources.putAll(forgeHost());
        Host host = new Host(dir, sources, Set.of("mod/MaskItem"), LegacyArmorOverrideAdapter.FORGE_DISGUISE);
        Method hook = host.type("net.minecraft.world.item.Item").getMethod("isMonsterDisguise", host.type("net.minecraft.world.item.ItemStack"),
                host.type("net.minecraft.world.entity.player.Player"), host.type("net.minecraft.world.entity.monster.Monster"));
        Object mask = host.make("mod.MaskItem");
        Object player = host.make("net.minecraft.world.entity.player.Player");
        assertEquals(true, hook.invoke(mask, host.stack("mod.MaskItem", "HEAD"), player, host.make("net.minecraft.world.entity.monster.Enderman")));
        assertEquals(false, hook.invoke(mask, host.stack("mod.MaskItem", "HEAD"), player, host.make("net.minecraft.world.entity.monster.Monster")));
    }

    @Test
    @DisplayName("On Forge 26.3 the render state hook hands the old override the state's entity and copies the pose; a state with no entity skips it without disabling it")
    void forgeStateHookUsesTheStatesEntity(@TempDir Path dir) throws Exception {
        Map<String, String> sources = new HashMap<>(common());
        sources.putAll(forgeHost());
        Host host = new Host(dir, sources, Set.of("mod/OldForgeProperties"), LegacyArmorOverrideAdapter.FORGE_ARMOR);
        Object ext = host.make("mod.OldForgeProperties");
        Object original = host.model("vanilla");
        Object head = host.stack("mod.PlainItem", "HEAD");
        Class<?> state = host.type("net.minecraft.client.renderer.entity.state.AvatarRenderState");
        Method stateHook = host.type("net.minecraftforge.client.extensions.common.IClientItemExtensions").getMethod("getHumanoidArmorModel",
                host.type("net.minecraft.client.renderer.entity.state.LivingEntityRenderState"), host.type("net.minecraft.world.item.ItemStack"),
                host.type("net.minecraft.world.entity.EquipmentSlot"), host.type("net.minecraft.client.model.HumanoidModel"));

        // No level yet, so the state names no entity: the host's model, and the override is neither called nor marked broken.
        assertSame(original, stateHook.invoke(ext, state.getConstructor().newInstance(), head, host.slot("HEAD"), original));

        Object player = host.make("net.minecraft.world.entity.player.Player");
        host.type("net.minecraft.world.level.Level").getField("byId").set(null, Map.of(7, player));
        Object minecraft = host.make("net.minecraft.client.Minecraft");
        minecraft.getClass().getField("level").set(minecraft, host.make("net.minecraft.client.multiplayer.ClientLevel"));
        host.type("net.minecraft.client.Minecraft").getField("INSTANCE").set(null, minecraft);
        Object found = state.getConstructor().newInstance();
        state.getField("id").setInt(found, 7);
        state.getField("entityType").set(found, player.getClass().getField("type").get(player));
        Object pose = original.getClass().getField("head").get(original);
        pose.getClass().getField("xRot").setFloat(pose, 0.75f);

        Object chosen = stateHook.invoke(ext, found, head, host.slot("HEAD"), original);
        assertEquals("custom", host.name(chosen));
        assertEquals(List.of(player), host.type("mod.OldForgeProperties").getField("seen").get(null), "the override saw the state's entity");
        Object chosenHead = chosen.getClass().getField("head").get(chosen);
        assertEquals(0.75f, chosenHead.getClass().getField("xRot").getFloat(chosenHead), "the replacement takes the replaced model's pose");
    }

    @Test
    @DisplayName("The rebuilt Forge player render event carries the avatar renderer it was posted with")
    void forgePlayerEventCarriesItsRenderer(@TempDir Path dir) throws Exception {
        Map<String, String> sources = new HashMap<>(common());
        sources.putAll(forgeHost());
        Host host = new Host(dir, sources, Set.of(), null);
        Class<?> event = host.type(ROOT + "forge.client.event.RenderPlayerEvent");
        Method create = event.getDeclaredMethod("create", boolean.class, host.type("net.minecraft.world.entity.player.Player"),
                host.type("net.minecraft.client.renderer.entity.player.AvatarRenderer"), host.type("com.mojang.blaze3d.vertex.PoseStack"),
                float.class, int.class);
        create.setAccessible(true);
        Object renderer = host.make("net.minecraft.client.renderer.entity.player.AvatarRenderer");
        for (boolean pre : new boolean[]{true, false}) {
            Object posted = create.invoke(null, pre, host.make("net.minecraft.world.entity.player.Player"), renderer,
                    host.make("com.mojang.blaze3d.vertex.PoseStack"), 0.5f, 15);
            assertSame(renderer, event.getMethod("getRenderer").invoke(posted), pre ? "Pre" : "Post");
        }
    }

    @Test
    @DisplayName("Forge's avatar event poster hands the rebuilt player event the avatar event's own renderer")
    void forgePosterPassesTheAvatarEventsRenderer() throws Exception {
        int checked = 0;
        try (var files = Files.list(COMPILED.resolve("com/retromod/legacyapi/forge/client/event"))) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith("LegacyForgeClientEvents")).toList()) {
                ClassNode node = new ClassNode();
                new ClassReader(Files.readAllBytes(file)).accept(node, 0);
                for (MethodNode method : node.methods) checked += checkRendererArgument(node, method);
            }
        }
        assertEquals(1, checked, "one call posts the rebuilt player event");
    }

    // ---- stand-ins ----

    private static Map<String, String> common() {
        Map<String, String> s = new HashMap<>();
        s.put("net/minecraft/resources/Identifier.java", """
                package net.minecraft.resources;
                public final class Identifier {
                    private final String namespace, path;
                    private Identifier(String namespace, String path) { this.namespace = namespace; this.path = path; }
                    public static Identifier tryParse(String id) { int c = id.indexOf(':'); return c < 0 ? null : new Identifier(id.substring(0, c), id.substring(c + 1)); }
                    public String getNamespace() { return namespace; }
                    public String getPath() { return path; }
                    public Identifier withPath(String path) { return new Identifier(namespace, path); }
                    public String toString() { return namespace + ":" + path; }
                }""");
        s.put("net/minecraft/world/entity/EntityType.java", "package net.minecraft.world.entity; public final class EntityType<T> {}");
        s.put("net/minecraft/world/entity/Entity.java", """
                package net.minecraft.world.entity;
                public class Entity {
                    public double x, y, z, xOld, yOld, zOld;
                    public int tickCount;
                    public EntityType<?> type = new EntityType<>();
                    public final double getX() { return x; }
                    public final double getY() { return y; }
                    public final double getZ() { return z; }
                    public EntityType<?> getType() { return type; }
                }""");
        s.put("net/minecraft/world/entity/LivingEntity.java", "package net.minecraft.world.entity; public class LivingEntity extends Entity {}");
        s.put("net/minecraft/world/entity/player/Player.java", "package net.minecraft.world.entity.player; public class Player extends net.minecraft.world.entity.LivingEntity {}");
        s.put("net/minecraft/world/entity/monster/Monster.java", "package net.minecraft.world.entity.monster; public class Monster extends net.minecraft.world.entity.LivingEntity {}");
        s.put("net/minecraft/world/entity/monster/Enderman.java", "package net.minecraft.world.entity.monster; public class Enderman extends Monster {}");
        s.put("net/minecraft/world/entity/EquipmentSlot.java", "package net.minecraft.world.entity; public enum EquipmentSlot { MAINHAND, OFFHAND, FEET, LEGS, CHEST, HEAD, BODY, SADDLE }");
        s.put("net/minecraft/world/item/Item.java", """
                package net.minecraft.world.item;
                import net.minecraft.world.entity.player.Player;
                // NeoForge's gaze disguise hook and Forge's monster disguise hook, which old items never declared.
                public class Item {
                    public boolean isGazeDisguise(ItemStack stack, Player player, net.minecraft.world.entity.LivingEntity target) { return false; }
                    public boolean isMonsterDisguise(ItemStack stack, Player player, net.minecraft.world.entity.monster.Monster target) { return false; }
                }""");
        s.put("net/minecraft/core/component/DataComponentType.java", "package net.minecraft.core.component; public final class DataComponentType<T> {}");
        s.put("net/minecraft/core/component/DataComponents.java", """
                package net.minecraft.core.component;
                public final class DataComponents {
                    public static final DataComponentType<net.minecraft.world.item.equipment.Equippable> EQUIPPABLE = new DataComponentType<>();
                }""");
        s.put("net/minecraft/world/item/equipment/Equippable.java",
                "package net.minecraft.world.item.equipment; public record Equippable(net.minecraft.world.entity.EquipmentSlot slot) {}");
        s.put("net/minecraft/world/item/ItemStack.java", """
                package net.minecraft.world.item;
                public final class ItemStack {
                    private final Item item;
                    private final Object equippable;
                    public ItemStack(Item item, Object equippable) { this.item = item; this.equippable = equippable; }
                    public Item getItem() { return item; }
                    public boolean isEmpty() { return false; }
                    @SuppressWarnings("unchecked")
                    public <T> T get(net.minecraft.core.component.DataComponentType<? extends T> type) { return (T) equippable; }
                }""");
        s.put("net/minecraft/client/model/geom/PartPose.java", "package net.minecraft.client.model.geom; public record PartPose(float xRot) {}");
        s.put("net/minecraft/client/model/geom/ModelPart.java", """
                package net.minecraft.client.model.geom;
                public final class ModelPart {
                    public boolean visible = true;
                    public float xRot;
                    public PartPose storePose() { return new PartPose(xRot); }
                    public void loadPose(PartPose pose) { xRot = pose.xRot(); }
                }""");
        s.put("net/minecraft/client/model/Model.java", """
                package net.minecraft.client.model;
                public class Model { public final String name; public Model(String name) { this.name = name; } }""");
        s.put("net/minecraft/client/model/HumanoidModel.java", """
                package net.minecraft.client.model;
                import net.minecraft.client.model.geom.ModelPart;
                public class HumanoidModel extends Model {
                    public final ModelPart head = new ModelPart(), hat = new ModelPart(), body = new ModelPart(), rightArm = new ModelPart(),
                            leftArm = new ModelPart(), rightLeg = new ModelPart(), leftLeg = new ModelPart();
                    public HumanoidModel(String name) { super(name); }
                }""");
        // Old mod items, compiled as the old jar declared them.
        s.put("mod/PlainItem.java", "package mod; public class PlainItem extends net.minecraft.world.item.Item {}");
        s.put("mod/MaskItem.java", """
                package mod;
                public class MaskItem extends net.minecraft.world.item.Item {
                    public boolean isEnderMask(net.minecraft.world.item.ItemStack stack, net.minecraft.world.entity.player.Player player,
                            net.minecraft.world.entity.monster.Enderman enderman) { return true; }
                }""");
        s.put("mod/BrokenMaskItem.java", """
                package mod;
                public class BrokenMaskItem extends net.minecraft.world.item.Item {
                    public boolean isEnderMask(net.minecraft.world.item.ItemStack stack, net.minecraft.world.entity.player.Player player,
                            net.minecraft.world.entity.monster.Enderman enderman) { throw new NoSuchMethodError("ItemStack.is(Item)"); }
                }""");
        return s;
    }

    private static Map<String, String> neoForge() {
        Map<String, String> s = common();
        s.put("net/minecraft/client/resources/model/EquipmentClientInfo.java", """
                package net.minecraft.client.resources.model;
                public record EquipmentClientInfo() {
                    public enum LayerType { HUMANOID, HUMANOID_LEGGINGS, HUMANOID_BABY, WINGS }
                    public record Layer(net.minecraft.resources.Identifier textureId) {}
                }""");
        s.put("net/neoforged/neoforge/client/ClientHooks.java", """
                package net.neoforged.neoforge.client;
                public final class ClientHooks {
                    public static final java.util.List<Object> copied = new java.util.ArrayList<>();
                    public static void copyModelProperties(net.minecraft.client.model.HumanoidModel from, net.minecraft.client.model.HumanoidModel to) { copied.add(to); }
                }""");
        s.put("net/neoforged/neoforge/client/extensions/common/IClientItemExtensions.java", """
                package net.neoforged.neoforge.client.extensions.common;
                import net.minecraft.client.model.*;
                import net.minecraft.client.resources.model.EquipmentClientInfo;
                import net.minecraft.resources.Identifier;
                import net.minecraft.world.item.ItemStack;
                public interface IClientItemExtensions {
                    static IClientItemExtensions of(ItemStack stack) { return new IClientItemExtensions() {}; }
                    default Model getHumanoidArmorModel(ItemStack stack, EquipmentClientInfo.LayerType type, Model original) { return original; }
                    // As the host's default: the humanoid hook, with the replaced model's pose copied over.
                    default Model getGenericArmorModel(ItemStack stack, EquipmentClientInfo.LayerType type, Model original) {
                        Model chosen = getHumanoidArmorModel(stack, type, original);
                        if (chosen != original && original instanceof HumanoidModel from && chosen instanceof HumanoidModel to) {
                            net.neoforged.neoforge.client.ClientHooks.copyModelProperties(from, to);
                        }
                        return chosen;
                    }
                    default Identifier getArmorTexture(ItemStack stack, EquipmentClientInfo.LayerType type, EquipmentClientInfo.Layer layer, Identifier fallback) { return fallback; }
                    default int getArmorLayerTintColor(ItemStack stack, EquipmentClientInfo.Layer layer, int index, int fallback) { return fallback; }
                }""");
        s.put("net/neoforged/neoforge/client/extensions/common/RegisterClientExtensionsEvent.java", """
                package net.neoforged.neoforge.client.extensions.common;
                public class RegisterClientExtensionsEvent {
                    public final java.util.Map<Object, Object> registered = new java.util.HashMap<>();
                    public boolean isItemRegistered(net.minecraft.world.item.Item item) { return registered.containsKey(item); }
                    public void registerItem(IClientItemExtensions ext, net.minecraft.world.item.Item... items) {
                        for (Object item : items) if (registered.put(item, ext) != null) throw new IllegalStateException("Duplicate");
                    }
                }""");
        s.put("net/minecraft/core/DefaultedRegistry.java", "package net.minecraft.core; public interface DefaultedRegistry<T> extends Iterable<T> {}");
        s.put("net/minecraft/core/registries/BuiltInRegistries.java", """
                package net.minecraft.core.registries;
                public class BuiltInRegistries {
                    public static net.minecraft.core.DefaultedRegistry<Object> ITEM;
                }""");
        // NeoForge's bus and mod list, with a bus that posts one priority at a time as NeoForge's mod loader asks.
        s.put("net/neoforged/bus/api/EventPriority.java", "package net.neoforged.bus.api; public enum EventPriority { HIGHEST, HIGH, NORMAL, LOW, LOWEST }");
        s.put("net/neoforged/bus/api/IEventBus.java", """
                package net.neoforged.bus.api;
                public interface IEventBus {
                    <T> void addListener(EventPriority priority, Class<T> type, java.util.function.Consumer<T> listener);
                }""");
        s.put("net/neoforged/fml/ModContainer.java", "package net.neoforged.fml; public abstract class ModContainer { public abstract net.neoforged.bus.api.IEventBus getEventBus(); }");
        s.put("net/neoforged/fml/ModList.java", """
                package net.neoforged.fml;
                public class ModList {
                    public static java.util.List<ModContainer> MODS = java.util.List.of();
                    public static RuntimeException FAIL;
                    public static ModList get() { return new ModList(); }
                    public java.util.List<ModContainer> getSortedMods() { if (FAIL != null) throw FAIL; return MODS; }
                }""");
        s.put("testsupport/Bus.java", """
                package testsupport;
                import java.util.*;
                import java.util.function.Consumer;
                import net.neoforged.bus.api.EventPriority;
                import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
                public class Bus implements net.neoforged.bus.api.IEventBus {
                    private final Map<EventPriority, List<Consumer<Object>>> listeners = new EnumMap<>(EventPriority.class);
                    @SuppressWarnings("unchecked")
                    public <T> void addListener(EventPriority priority, Class<T> type, Consumer<T> listener) {
                        listeners.computeIfAbsent(priority, p -> new ArrayList<>()).add((Consumer<Object>) listener);
                    }
                    public void post(Object priority, Object event) {
                        for (Consumer<Object> listener : new ArrayList<>(listeners.getOrDefault(priority, List.of()))) listener.accept(event);
                    }
                    // A mod that registers an extension for an old mod's item itself, at the lowest priority.
                    public void lateRegistration(Object item) {
                        listeners.computeIfAbsent(EventPriority.LOWEST, p -> new ArrayList<>()).add(event ->
                                ((RegisterClientExtensionsEvent) event).registerItem(new LateExtensions(), (net.minecraft.world.item.Item) item));
                    }
                }""");
        s.put("testsupport/LateExtensions.java",
                "package testsupport; public class LateExtensions implements net.neoforged.neoforge.client.extensions.common.IClientItemExtensions {}");
        s.put("testsupport/Container.java", """
                package testsupport;
                public class Container extends net.neoforged.fml.ModContainer {
                    private final net.neoforged.bus.api.IEventBus bus;
                    public Container(net.neoforged.bus.api.IEventBus bus) { this.bus = bus; }
                    public net.neoforged.bus.api.IEventBus getEventBus() { return bus; }
                }""");
        // The per-mod copy of NeoForge 21.1's armor layer that the armor material bridge generates.
        s.put("com/retromod/generated/LegacyArmorLayer.java", """
                package com.retromod.generated;
                import net.minecraft.resources.Identifier;
                public final class LegacyArmorLayer {
                    private final Identifier assetName;
                    private final String suffix;
                    public LegacyArmorLayer(Identifier assetName) { this(assetName, "", false); }
                    public LegacyArmorLayer(Identifier assetName, String suffix, boolean dyeable) { this.assetName = assetName; this.suffix = suffix; }
                    public Identifier assetName() { return assetName; }
                    public Identifier texture(boolean inner) {
                        return assetName.withPath("textures/models/armor/" + assetName.getPath() + "_layer_" + (inner ? 2 : 1) + suffix + ".png");
                    }
                }""");
        s.put("mod/OldArmorProperties.java", """
                package mod;
                import net.minecraft.client.model.HumanoidModel;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.item.ItemStack;
                public class OldArmorProperties implements net.neoforged.neoforge.client.extensions.common.IClientItemExtensions {
                    public static final HumanoidModel CUSTOM = new HumanoidModel("custom");
                    public HumanoidModel getHumanoidArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel original) {
                        return slot == EquipmentSlot.LEGS ? original : CUSTOM;
                    }
                }""");
        s.put("mod/BrokenArmorProperties.java", """
                package mod;
                import net.minecraft.client.model.HumanoidModel;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.item.ItemStack;
                public class BrokenArmorProperties implements net.neoforged.neoforge.client.extensions.common.IClientItemExtensions {
                    public HumanoidModel getHumanoidArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel original) {
                        throw new NoSuchMethodError("Minecraft.getEntityModels()");
                    }
                }""");
        s.put("mod/PartlyBrokenProperties.java", """
                package mod;
                import net.minecraft.client.model.HumanoidModel;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.item.ItemStack;
                public class PartlyBrokenProperties implements net.neoforged.neoforge.client.extensions.common.IClientItemExtensions {
                    public HumanoidModel getHumanoidArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel original) {
                        if (stack.getItem() instanceof MaskItem) throw new NoSuchFieldError("AbstractClientPlayer.elytraRotX");
                        return OldArmorProperties.CUSTOM;
                    }
                }""");
        s.put("mod/PlainModelProperties.java", """
                package mod;
                import net.minecraft.client.model.*;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.item.ItemStack;
                public class PlainModelProperties implements net.neoforged.neoforge.client.extensions.common.IClientItemExtensions {
                    public Model getGenericArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel original) {
                        return new Model("plain");
                    }
                }""");
        // A mod's own extension with 26.x hooks, and an old subclass of it that only declares the old model hook.
        s.put("mod/NativeParentProperties.java", """
                package mod;
                import net.minecraft.client.model.*;
                import net.minecraft.client.resources.model.EquipmentClientInfo;
                import net.minecraft.resources.Identifier;
                import net.minecraft.world.item.ItemStack;
                public class NativeParentProperties implements net.neoforged.neoforge.client.extensions.common.IClientItemExtensions {
                    public Model getHumanoidArmorModel(ItemStack stack, EquipmentClientInfo.LayerType type, Model original) { return new HumanoidModel("parent"); }
                    public Identifier getArmorTexture(ItemStack stack, EquipmentClientInfo.LayerType type, EquipmentClientInfo.Layer layer, Identifier fallback) {
                        return Identifier.tryParse("parent:textures/own.png");
                    }
                }""");
        s.put("mod/SubProperties.java", """
                package mod;
                import net.minecraft.client.model.HumanoidModel;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.item.ItemStack;
                public class SubProperties extends NativeParentProperties {
                    public HumanoidModel getHumanoidArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel original) {
                        return slot == EquipmentSlot.HEAD ? original : OldArmorProperties.CUSTOM;
                    }
                }""");
        // An extension reached only through the mod's own sub-interface, and one whose base comes from a jar nobody reads.
        s.put("mod/IModArmorExtensions.java",
                "package mod; public interface IModArmorExtensions extends net.neoforged.neoforge.client.extensions.common.IClientItemExtensions {}");
        s.put("mod/PlainBase.java", "package mod; public class PlainBase {}");
        s.put("lib/LibraryBase.java", "package lib; public class LibraryBase {}");
        s.put("mod/SubInterfaceProperties.java", """
                package mod;
                import net.minecraft.client.model.HumanoidModel;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.item.ItemStack;
                public class SubInterfaceProperties extends PlainBase implements IModArmorExtensions {
                    public HumanoidModel getHumanoidArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel original) {
                        return slot == EquipmentSlot.HEAD ? original : OldArmorProperties.CUSTOM;
                    }
                }""");
        s.put("mod/UnshippedBaseProperties.java", """
                package mod;
                import net.minecraft.client.model.HumanoidModel;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.item.ItemStack;
                public class UnshippedBaseProperties extends lib.LibraryBase implements net.neoforged.neoforge.client.extensions.common.IClientItemExtensions {
                    public HumanoidModel getHumanoidArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel original) {
                        return slot == EquipmentSlot.HEAD ? original : OldArmorProperties.CUSTOM;
                    }
                }""");
        s.put("mod/Forwarding.java", """
                package mod;
                import net.minecraft.client.model.HumanoidModel;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.item.ItemStack;
                public class Forwarding implements net.neoforged.neoforge.client.extensions.common.IClientItemExtensions {
                    public static java.util.function.Function<Object, Object> again;
                    public HumanoidModel getHumanoidArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel original) {
                        return (HumanoidModel) again.apply(this);
                    }
                }""");
        s.put("mod/ForgeArmorItem.java", """
                package mod;
                import net.minecraft.world.entity.*;
                public class ForgeArmorItem extends net.minecraft.world.item.Item {
                    public String getArmorTexture(net.minecraft.world.item.ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
                        return "mod:textures/models/armor/ruby_layer_" + (slot == EquipmentSlot.LEGS ? 2 : 1) + (type == null ? "" : "_" + type) + ".png";
                    }
                }""");
        s.put("mod/LayerArmorItem.java", """
                package mod;
                import com.retromod.generated.LegacyArmorLayer;
                import net.minecraft.resources.Identifier;
                import net.minecraft.world.entity.*;
                public class LayerArmorItem extends net.minecraft.world.item.Item {
                    public Identifier getArmorTexture(net.minecraft.world.item.ItemStack stack, Entity entity, EquipmentSlot slot, LegacyArmorLayer layer, boolean inner) {
                        return layer.texture(inner);
                    }
                }""");
        s.put("other/NativeArmorItem.java", "package other; public class NativeArmorItem extends mod.ForgeArmorItem {}");
        return s;
    }

    private static Map<String, String> forgeHost() {
        Map<String, String> s = new HashMap<>();
        s.put("net/minecraft/client/renderer/entity/state/EntityRenderState.java", """
                package net.minecraft.client.renderer.entity.state;
                public class EntityRenderState {
                    public net.minecraft.world.entity.EntityType<?> entityType;
                    public double x, y, z;
                    public float ageInTicks;
                    public int lightCoords;
                }""");
        s.put("net/minecraft/client/renderer/entity/state/LivingEntityRenderState.java",
                "package net.minecraft.client.renderer.entity.state; public class LivingEntityRenderState extends EntityRenderState {}");
        s.put("net/minecraft/client/renderer/entity/state/HumanoidRenderState.java",
                "package net.minecraft.client.renderer.entity.state; public class HumanoidRenderState extends LivingEntityRenderState {}");
        s.put("net/minecraft/client/renderer/entity/state/AvatarRenderState.java",
                "package net.minecraft.client.renderer.entity.state; public class AvatarRenderState extends HumanoidRenderState { public int id; }");
        // The render state lookup reads the client level, whose type the verifier checks against Level.
        s.put("net/minecraft/world/level/Level.java", """
                package net.minecraft.world.level;
                public class Level {
                    public static java.util.Map<Integer, Object> byId = java.util.Map.of();
                    public net.minecraft.world.entity.Entity getEntity(int id) { return (net.minecraft.world.entity.Entity) byId.get(id); }
                }""");
        s.put("net/minecraft/client/multiplayer/ClientLevel.java", "package net.minecraft.client.multiplayer; public class ClientLevel extends net.minecraft.world.level.Level {}");
        s.put("net/minecraft/client/Minecraft.java", """
                package net.minecraft.client;
                public class Minecraft {
                    public static Minecraft INSTANCE;
                    public net.minecraft.client.multiplayer.ClientLevel level;
                    public static Minecraft getInstance() { return INSTANCE; }
                }""");
        s.put("net/minecraftforge/client/extensions/common/IClientItemExtensions.java", """
                package net.minecraftforge.client.extensions.common;
                import net.minecraft.client.model.HumanoidModel;
                import net.minecraft.world.item.ItemStack;
                public interface IClientItemExtensions {
                    static IClientItemExtensions of(ItemStack stack) { return new IClientItemExtensions() {}; }
                    default HumanoidModel getHumanoidArmorModel(net.minecraft.client.renderer.entity.state.LivingEntityRenderState state, ItemStack stack,
                            net.minecraft.world.entity.EquipmentSlot slot, HumanoidModel original) { return original; }
                }""");
        s.put("mod/OldForgeProperties.java", """
                package mod;
                import net.minecraft.client.model.HumanoidModel;
                import net.minecraft.world.entity.*;
                import net.minecraft.world.item.ItemStack;
                public class OldForgeProperties implements net.minecraftforge.client.extensions.common.IClientItemExtensions {
                    public static final HumanoidModel CUSTOM = new HumanoidModel("custom");
                    public static final java.util.List<Object> seen = new java.util.ArrayList<>();
                    public HumanoidModel getHumanoidArmorModel(LivingEntity entity, ItemStack stack, EquipmentSlot slot, HumanoidModel original) {
                        // An override that needs the entity, which a missing one would make throw.
                        seen.add(entity.getType() == null ? null : entity);
                        return slot == EquipmentSlot.HEAD ? CUSTOM : original;
                    }
                }""");
        // Forge 26.3's event types under the rebuilt player render event.
        s.put("net/minecraftforge/eventbus/internal/Event.java", "package net.minecraftforge.eventbus.internal; public interface Event {}");
        s.put("net/minecraftforge/eventbus/api/event/MutableEvent.java",
                "package net.minecraftforge.eventbus.api.event; public abstract class MutableEvent implements net.minecraftforge.eventbus.internal.Event {}");
        s.put("net/minecraftforge/eventbus/api/event/InheritableEvent.java",
                "package net.minecraftforge.eventbus.api.event; public interface InheritableEvent extends net.minecraftforge.eventbus.internal.Event {}");
        s.put("net/minecraftforge/common/util/HasResult.java", "package net.minecraftforge.common.util; public interface HasResult {}");
        s.put("net/minecraftforge/common/util/Result.java", "package net.minecraftforge.common.util; public enum Result { DENY, DEFAULT, ALLOW }");
        s.put("net/minecraftforge/event/entity/EntityEvent.java", "package net.minecraftforge.event.entity; public interface EntityEvent {}");
        s.put("net/minecraftforge/event/entity/living/LivingEvent.java",
                "package net.minecraftforge.event.entity.living; public interface LivingEvent extends net.minecraftforge.event.entity.EntityEvent {}");
        s.put("net/minecraftforge/event/entity/player/PlayerEvent.java",
                "package net.minecraftforge.event.entity.player; public interface PlayerEvent extends net.minecraftforge.event.entity.living.LivingEvent {}");
        s.put("net/minecraft/client/renderer/entity/player/AvatarRenderer.java", "package net.minecraft.client.renderer.entity.player; public class AvatarRenderer<T> {}");
        s.put("com/mojang/blaze3d/vertex/PoseStack.java", "package com.mojang.blaze3d.vertex; public class PoseStack {}");
        return s;
    }

    /** Checks that the renderer argument of each call that posts the rebuilt player event comes from the avatar event; answers the count. */
    private static int checkRendererArgument(ClassNode node, MethodNode method) throws Exception {
        if (method.instructions.size() == 0) return 0;
        Frame<SourceValue>[] frames = new Analyzer<>(new SourceInterpreter()).analyze(node.name, method);
        int checked = 0;
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call) || !call.owner.endsWith("/RenderPlayerEvent") || !call.name.equals("post")) continue;
            Frame<SourceValue> frame = frames[method.instructions.indexOf(insn)];
            Type[] arguments = Type.getArgumentTypes(call.desc);
            int index = List.of(arguments).indexOf(Type.getObjectType("net/minecraft/client/renderer/entity/player/AvatarRenderer"));
            assertTrue(index >= 0, "the post takes the renderer: " + call.desc);
            SourceValue renderer = frame.getStack(frame.getStackSize() - arguments.length + index);
            assertFalse(renderer.insns.isEmpty());
            for (AbstractInsnNode source : renderer.insns) {
                assertTrue(source instanceof MethodInsnNode read && read.name.equals("getRenderer") && read.owner.endsWith("/RenderAvatarEvent"),
                        "the renderer comes from the avatar event, not a constant");
            }
            checked++;
        }
        return checked;
    }

    /** One host's classes in their own loader, with {@code adapted} mod classes run through the armor adapter under {@code flag}. */
    private static final class Host {
        private final ClassLoader loader;
        private final Map<String, byte[]> classes = new HashMap<>();
        private final Map<String, byte[]> compiled;

        Host(Path dir, Map<String, String> sources, Set<String> adapted, String flag) throws Exception {
            compiled = BridgeHarness.compile(dir, sources);
            // A lib/ class stands for another mod's jar, which the adapter does not read for parents.
            Function<String, byte[]> shipped = name -> name.startsWith("lib/") ? null : compiled.get(name);
            compiled.forEach((name, bytes) -> {
                byte[] adaptedBytes = adapted.contains(name) ? LegacyArmorOverrideAdapter.apply(bytes, Set.of(flag)::contains, shipped) : bytes;
                // Another mod's classes load in a loader of their own.
                if (!name.startsWith("other/")) classes.put(name.replace('/', '.'), adaptedBytes);
            });
            loader = new ClassLoader(LegacyArmorHooksRuntimeTest.class.getClassLoader()) {
                @Override
                protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded != null) return loaded;
                    byte[] bytes = classes.get(name);
                    try {
                        // The rebuilt classes come from their own build output, not the copies on the test classpath.
                        if (bytes == null && name.startsWith(ROOT)) bytes = Files.readAllBytes(COMPILED.resolve(name.replace('.', '/') + ".class"));
                    } catch (java.io.IOException absent) {
                        throw new ClassNotFoundException(name, absent);
                    }
                    // Host types a method names but this test never runs only need to exist for the verifier.
                    if (bytes == null && (name.startsWith("net.minecraft.") || name.startsWith("net.neoforged.") || name.startsWith("net.minecraftforge.")
                            || name.startsWith("com.mojang."))) {
                        bytes = empty(name.replace('.', '/'));
                    }
                    if (bytes == null) return super.loadClass(name, resolve);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
        }

        Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name, true, loader);
        }

        Object make(String name) throws Exception {
            var constructor = type(name).getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        }

        /** An instance of {@code name} defined in a loader of its own, which puts it in a module of its own. */
        Object foreign(String name) throws Exception {
            byte[] bytes = compiled.get(name.replace('.', '/'));
            ClassLoader own = new ClassLoader(loader) {
                @Override
                protected Class<?> findClass(String wanted) throws ClassNotFoundException {
                    if (!wanted.equals(name)) throw new ClassNotFoundException(wanted);
                    return defineClass(wanted, bytes, 0, bytes.length);
                }
            };
            return Class.forName(name, true, own).getConstructor().newInstance();
        }

        /** Makes the host's item registry list {@code items}. */
        void items(List<Object> items) throws Exception {
            Class<?> registry = type("net.minecraft.core.DefaultedRegistry");
            type("net.minecraft.core.registries.BuiltInRegistries").getField("ITEM").set(null, Proxy.newProxyInstance(registry.getClassLoader(),
                    new Class<?>[]{registry}, (proxy, method, args) -> method.getName().equals("iterator") ? items.iterator() : null));
        }

        Object container(Object bus) throws Exception {
            return type("testsupport.Container").getConstructor(type("net.neoforged.bus.api.IEventBus")).newInstance(bus);
        }

        Object model(String name) throws Exception {
            return type("net.minecraft.client.model.HumanoidModel").getConstructor(String.class).newInstance(name);
        }

        String name(Object model) throws Exception {
            return (String) model.getClass().getField("name").get(model);
        }

        Object slot(String name) throws Exception {
            return enumValue("net.minecraft.world.entity.EquipmentSlot", name);
        }

        Object layerType(String name) throws Exception {
            return enumValue("net.minecraft.client.resources.model.EquipmentClientInfo$LayerType", name);
        }

        Object id(String id) throws Exception {
            return type("net.minecraft.resources.Identifier").getMethod("tryParse", String.class).invoke(null, id);
        }

        Object layer(String id) throws Exception {
            return type("net.minecraft.client.resources.model.EquipmentClientInfo$Layer").getConstructor(type("net.minecraft.resources.Identifier"))
                    .newInstance(id(id));
        }

        /** A stack of a new {@code item}, worn in {@code slot}. */
        Object stack(String item, String slot) throws Exception {
            Object equippable = type("net.minecraft.world.item.equipment.Equippable").getConstructor(type("net.minecraft.world.entity.EquipmentSlot"))
                    .newInstance(slot(slot));
            return type("net.minecraft.world.item.ItemStack").getConstructor(type("net.minecraft.world.item.Item"), Object.class)
                    .newInstance(make(item), equippable);
        }

        Object invoke(Object target, String name, Object... args) throws Exception {
            for (Method method : target.getClass().getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length && fits(method, args)) return method.invoke(target, args);
            }
            throw new NoSuchMethodException(name);
        }

        Method staticMethod(String owner, String name, int parameters) throws Exception {
            for (Method method : type(owner).getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameters) {
                    method.setAccessible(true);
                    return method;
                }
            }
            throw new NoSuchMethodException(owner + "." + name);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private Object enumValue(String type, String name) throws Exception {
            return Enum.valueOf((Class) type(type), name);
        }

        private static boolean fits(Method method, Object[] args) {
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                if (args[i] != null && !types[i].isInstance(args[i])) return false;
            }
            return true;
        }
    }

    private static byte[] empty(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
