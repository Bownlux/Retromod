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

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A mod's item sub-predicate from before 1.21.5, translated and run on stand-ins for 26.3's component predicates. */
class LegacyItemSubPredicateRuntimeTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("new ItemSubPredicate.Type(codec) builds the host's concrete type, and the registry field takes its new name")
    void typeAndRegistryReachTheHost() throws Exception {
        BridgeHarness.Run run = run();
        Class<?> setup = run.loadClass("mod.Setup");
        Class<?> codecType = run.loadClass("com.mojang.serialization.Codec");
        Object codec = Proxy.newProxyInstance(run, new Class<?>[] {codecType}, (proxy, method, args) -> null);
        Object type = setup.getMethod("type", codecType).invoke(null, codec);
        assertEquals("net.minecraft.core.component.predicates.DataComponentPredicate$ConcreteType", type.getClass().getName());
        assertSame(codec, type.getClass().getMethod("codec").invoke(type), "the codec the mod passed");
        assertEquals("new registry", setup.getMethod("registry").invoke(null));
    }

    @Test
    @DisplayName("The host's matches(DataComponentGetter) reaches the mod's matches(ItemStack), and any other holder fails it")
    void hostMatchReachesTheModsTest() throws Exception {
        BridgeHarness.Run run = run();
        Object predicate = run.loadClass("mod.NamedPredicate").getConstructor(String.class).newInstance("minecraft:stone");
        Class<?> getter = run.loadClass("net.minecraft.core.component.DataComponentGetter");
        Method matches = run.loadClass("net.minecraft.core.component.predicates.DataComponentPredicate")
                .getMethod("matches", getter);
        Class<?> stack = run.loadClass("net.minecraft.world.item.ItemStack");
        assertEquals(true, matches.invoke(predicate, stack.getConstructor(String.class).newInstance("minecraft:stone")));
        assertEquals(false, matches.invoke(predicate, stack.getConstructor(String.class).newInstance("minecraft:dirt")));
        Object entity = Proxy.newProxyInstance(run, new Class<?>[] {getter}, (proxy, method, args) -> null);
        assertEquals(false, matches.invoke(predicate, entity), "an entity's components cannot answer an item test");
    }

    @Test
    @DisplayName("A mod built for 1.21.5 or newer keeps implementing DataComponentPredicate itself")
    void newerModKeepsTheCurrentInterface() throws Exception {
        Map<String, byte[]> current = BridgeHarness.compile(dir.resolve("current"), Map.of(
                "net/minecraft/core/component/DataComponentGetter.java",
                "package net.minecraft.core.component; public interface DataComponentGetter {}",
                "net/minecraft/core/component/predicates/DataComponentPredicate.java", """
                        package net.minecraft.core.component.predicates;
                        public interface DataComponentPredicate { boolean matches(net.minecraft.core.component.DataComponentGetter components); }""",
                "mod/Current.java", """
                        package mod;
                        import net.minecraft.core.component.DataComponentGetter;
                        import net.minecraft.core.component.predicates.DataComponentPredicate;
                        public record Current(String id) implements DataComponentPredicate {
                            public boolean matches(DataComponentGetter components) { return true; }
                        }"""));
        for (String[] source : new String[][] {{"1.21.5", "net/minecraft/core/component/predicates/DataComponentPredicate"},
                {"1.21.4", LegacyItemSubPredicateBridge.HELPER}}) {
            Map<String, byte[]> mod = BridgeHarness.transform("26.3", t -> {
                LegacyItemSubPredicateBridge.register(t);
                t.setModSourceVersion(source[0]);
            }, BridgeHarness.only(current, "mod/"));
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(mod.get("mod/Current")).accept(node, 0);
            assertEquals(java.util.List.of(source[1]), node.interfaces, "a mod built for " + source[0]);
        }
    }

    private BridgeHarness.Run run() throws Exception {
        Map<String, byte[]> old = BridgeHarness.compile(dir.resolve("old"), OLD);
        Map<String, byte[]> host = BridgeHarness.compile(dir.resolve("host"), HOST);
        Map<String, byte[]> mod = BridgeHarness.transform("26.3", t -> {
            // The outer move comes from the Mojang rename table in a real translation.
            t.registerClassRedirect(LegacyItemSubPredicateBridge.OLD, LegacyItemSubPredicateBridge.PREDICATE);
            LegacyItemSubPredicateBridge.register(t);
        }, BridgeHarness.only(old, "mod/"));
        return new BridgeHarness.Run(host, mod);
    }

    /** 1.21.1's shapes and a mod written against them, as Bookshelf's namespace predicate is. */
    private static final Map<String, String> OLD = Map.of(
            "com/mojang/serialization/Codec.java", "package com.mojang.serialization; public interface Codec<A> {}",
            "net/minecraft/world/item/ItemStack.java", """
                    package net.minecraft.world.item;
                    public final class ItemStack { public final String id; public ItemStack(String id) { this.id = id; } }""",
            "net/minecraft/advancements/critereon/ItemSubPredicate.java", """
                    package net.minecraft.advancements.critereon;
                    import com.mojang.serialization.Codec;
                    import net.minecraft.world.item.ItemStack;
                    public interface ItemSubPredicate {
                        boolean matches(ItemStack stack);
                        record Type<T extends ItemSubPredicate>(Codec<T> codec) {}
                    }""",
            "net/minecraft/core/registries/BuiltInRegistries.java", """
                    package net.minecraft.core.registries;
                    public final class BuiltInRegistries { public static final Object ITEM_SUB_PREDICATE_TYPE = "old registry"; }""",
            "mod/NamedPredicate.java", """
                    package mod;
                    import net.minecraft.advancements.critereon.ItemSubPredicate;
                    import net.minecraft.world.item.ItemStack;
                    public record NamedPredicate(String id) implements ItemSubPredicate {
                        public boolean matches(ItemStack stack) { return stack.id.equals(id); }
                    }""",
            "mod/Setup.java", """
                    package mod;
                    import com.mojang.serialization.Codec;
                    import net.minecraft.advancements.critereon.ItemSubPredicate;
                    import net.minecraft.core.registries.BuiltInRegistries;
                    public final class Setup {
                        public static Object type(Codec<NamedPredicate> codec) { return new ItemSubPredicate.Type<>(codec); }
                        public static Object registry() { return BuiltInRegistries.ITEM_SUB_PREDICATE_TYPE; }
                    }""");

    /** 26.3's shapes: the predicate takes any component holder, and its type is an interface with a concrete class. */
    private static final Map<String, String> HOST = Map.of(
            "com/mojang/serialization/Codec.java", "package com.mojang.serialization; public interface Codec<A> {}",
            "net/minecraft/core/component/DataComponentGetter.java",
            "package net.minecraft.core.component; public interface DataComponentGetter {}",
            "net/minecraft/world/item/ItemStack.java", """
                    package net.minecraft.world.item;
                    public final class ItemStack implements net.minecraft.core.component.DataComponentGetter {
                        public final String id;
                        public ItemStack(String id) { this.id = id; }
                    }""",
            "net/minecraft/core/component/predicates/DataComponentPredicate.java", """
                    package net.minecraft.core.component.predicates;
                    import com.mojang.serialization.Codec;
                    import net.minecraft.core.component.DataComponentGetter;
                    public interface DataComponentPredicate {
                        boolean matches(DataComponentGetter components);
                        interface Type<T extends DataComponentPredicate> { Codec<T> codec(); }
                        abstract class TypeBase<T extends DataComponentPredicate> implements Type<T> {
                            private final Codec<T> codec;
                            public TypeBase(Codec<T> codec) { this.codec = codec; }
                            public Codec<T> codec() { return codec; }
                        }
                        final class ConcreteType<T extends DataComponentPredicate> extends TypeBase<T> {
                            public ConcreteType(Codec<T> codec) { super(codec); }
                        }
                    }""",
            "net/minecraft/core/registries/BuiltInRegistries.java", """
                    package net.minecraft.core.registries;
                    public final class BuiltInRegistries { public static final Object DATA_COMPONENT_PREDICATE_TYPE = "new registry"; }""");
}
