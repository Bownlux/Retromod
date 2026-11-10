/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.shim.common.LegacyRegistryMapCodecBridge;
import com.retromod.shim.forge.embedded.LegacyLootModifierCodecs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #283: Scorched Guns registers its global loot modifier serializers as Forge 1.20.1 {@code Codec}s.
 * NeoForge 1.21 and Forge 26.x read that registry, and the other modifier, chunk generator, biome
 * source and surface rule registries, as {@code MapCodec}s, so the serializers are converted where the
 * mod registers them.
 */
class LegacyLootModifierCodecsTest {

    private static final Function<Object, Object> MARK = value -> List.of("converted", value);

    @ParameterizedTest
    @ValueSource(strings = {"forge:biome_modifier_serializers", "forge:global_loot_modifier_serializers",
            "forge:structure_modifier_serializers", "neoforge:biome_modifier_serializers",
            "neoforge:global_loot_modifier_serializers", "neoforge:structure_modifier_serializers",
            "neoforge:condition_codecs", "minecraft:worldgen/chunk_generator", "minecraft:worldgen/biome_source",
            "minecraft:worldgen/density_function_type", "minecraft:worldgen/material_rule",
            "minecraft:worldgen/material_condition_type"})
    @DisplayName("registries that hold map codecs since 1.20.5 convert what the mod's supplier builds")
    void mapCodecRegistriesConvert(String registry) {
        Object codec = new Object();
        Object registered = LegacyLootModifierCodecs.serializer("ResourceKey[minecraft:root / " + registry + "]",
                () -> codec, MARK).get();
        assertEquals(List.of("converted", codec), registered);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ResourceKey[minecraft:root / minecraft:block]",
            "ResourceKey[forge:biome_modifier_serializers / balm:balm]", "forge:biome_modifier_serializers", "null"})
    @DisplayName("other registries, entry keys and other strings keep the mod's supplier")
    void otherRegistriesAreUntouched(String key) {
        Supplier<Object> blocks = Object::new;
        assertSame(blocks, LegacyLootModifierCodecs.serializer(key, blocks, MARK));
    }

    @Test
    @DisplayName("a deferred register's supplier is converted for its registry, and the register's result comes back")
    void deferredRegisterConvertsBeforeRegistering() throws Throwable {
        FakeDeferredRegister register = new FakeDeferredRegister("ResourceKey[minecraft:root / forge:biome_modifier_serializers]");
        Object codec = new Object();

        Object holder = LegacyLootModifierCodecs.registerDeferred(register, "balm", () -> codec, MARK);

        assertEquals("holder of balm", holder);
        assertEquals(List.of("converted", codec), register.supplied.get());
    }

    @Test
    @DisplayName("a register event's helper converts each value, through its default methods too")
    void registerEventHelperConverts() throws Throwable {
        FakeEvent event = new FakeEvent();
        Object codec = new Object();

        LegacyLootModifierCodecs.registerEach(event, "ResourceKey[minecraft:root / forge:biome_modifier_serializers]",
                helper -> {
                    RegisterEvent.RegisterHelper typed = (RegisterEvent.RegisterHelper) helper;
                    typed.register((Object) "cobblemon:biome_modifier", codec);
                    typed.register("spawns", codec);
                }, MARK);

        assertEquals(List.of("cobblemon:biome_modifier=[converted, " + codec + "]",
                "mod:spawns=[converted, " + codec + "]"), event.registered);
    }

    @Test
    @DisplayName("a register event for another registry hands the mod the loader's own helper")
    void otherRegisterEventsAreUntouched() throws Throwable {
        FakeEvent event = new FakeEvent();
        List<Object> helpers = new ArrayList<>();

        LegacyLootModifierCodecs.registerEach(event, "ResourceKey[minecraft:root / minecraft:block]", helpers::add, MARK);

        assertEquals(List.of(event.helper), helpers);
    }

    @Test
    @DisplayName("every register wrapper converts the supplier before the loader sees it")
    void wrappersConvertBeforeRegistering() {
        Map<String, byte[]> wrappers = Map.of("register", RegistryIdBridgeSynthetic.generate(),
                "register ", RegistryIdBridgeSynthetic.generateBiomeRecorder(true),
                "registerForge", RegistryIdBridgeSynthetic.generateForge());
        for (Map.Entry<String, byte[]> wrapper : wrappers.entrySet()) {
            ClassNode node = new ClassNode();
            new ClassReader(wrapper.getValue()).accept(node, 0);
            String name = wrapper.getKey().trim();
            MethodNode register = node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
            int converted = -1;
            int registered = -1;
            AbstractInsnNode[] insns = register.instructions.toArray();
            for (int i = 0; i < insns.length; i++) {
                if (insns[i] instanceof MethodInsnNode call) {
                    if (call.owner.equals(LegacyRegistryMapCodecBridge.CLASS) && call.name.equals("serializer")
                            && insns[i + 1] instanceof VarInsnNode store && store.var == 2) {
                        converted = i;
                    }
                    if (call.name.equals("register") && call.owner.endsWith("DeferredRegister") && registered < 0) {
                        registered = i;
                    }
                }
            }
            assertTrue(converted >= 0, node.name + " must replace the supplier with the converted one");
            assertTrue(converted < registered, node.name + " must convert before DeferredRegister.register");
        }
    }

    /** Forge's DeferredRegister: its registry key and register(name, supplier). */
    public static final class FakeDeferredRegister {
        private final String key;
        Supplier<?> supplied;

        FakeDeferredRegister(String key) {
            this.key = key;
        }

        public Object getRegistryKey() {
            return key;
        }

        public Object register(String name, Supplier<?> supplier) {
            supplied = supplier;
            return "holder of " + name;
        }
    }

    /** A loader's RegisterEvent, whose helper records what it registers. */
    public static final class FakeEvent {
        final List<String> registered = new ArrayList<>();
        final RegisterEvent.RegisterHelper helper = (id, value) -> registered.add(id + "=" + value);

        public void register(Object key, Consumer<Object> consumer) {
            consumer.accept(helper);
        }
    }

    /** Named as the loaders name it, so the helper's interface is found. */
    public static final class RegisterEvent {
        public interface RegisterHelper {
            void register(Object id, Object value);

            /** Like Forge's register(String, value), which names the entry in the active mod's namespace. */
            default void register(String name, Object value) {
                register((Object) ("mod:" + name), value);
            }
        }
    }
}
