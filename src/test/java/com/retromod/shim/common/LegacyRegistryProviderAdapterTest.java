/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodTransformer.MethodKey;
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
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.retromod.shim.common.LegacyRegistryProviderAdapter.*;
import static org.junit.jupiter.api.Assertions.*;

/** Old command contexts, registry providers and registry access answer what 1.20.5 and 1.21.2 hosts ask them. */
class LegacyRegistryProviderAdapterTest {

    private static final String HOST_CLASS = "net/minecraft/commands/Host";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A 1.20.1 context that filters another one lists only its items on a 26.2 host, with every feature and no registry list")
    void oldContextAnswersTheHost(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, OLD_1201, "1.20.1");
        Object wrapped = run.call("mod/BackpackArgument", "wrap", run.call(HOST_CLASS, "context"));
        assertEquals("backpack_iron", run.call(HOST_CLASS, "items", wrapped), "the item parser reads the filtered items");
        assertEquals("all", run.call(HOST_CLASS, "features", wrapped));
        assertEquals(0L, run.call(HOST_CLASS, "registries", wrapped), "the old context could not list registries");
        assertEquals("backpack_iron,stone", run.call(HOST_CLASS, "items", run.call(HOST_CLASS, "context")),
                "the host's own context is untouched");
    }

    @Test
    @DisplayName("A 1.20.1 context answers an empty lookup for a registry it lacks, where its holderLookup throws")
    void oldContextAnswersEmptyForAMissingRegistry(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, OLD_1201, "1.20.1");
        Object wrapped = run.call("mod/BackpackArgument", "wrap", run.call(HOST_CLASS, "context"));
        assertEquals(true, run.call(HOST_CLASS, "found", wrapped, "minecraft:item"));
        assertEquals(false, run.call(HOST_CLASS, "found", wrapped, "minecraft:block"),
                "the host's lookup contract is an empty Optional, not the old IllegalStateException");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> run.call(HOST_CLASS, "lookupOrThrow", wrapped, "minecraft:block"));
        assertEquals("Missing registry minecraft:block", thrown.getMessage(),
                "the host's lookupOrThrow still reports the missing registry itself");
    }

    @Test
    @DisplayName("A 1.21.1 provider and registry access answer listRegistryKeys, listRegistries and lookup on a 26.2 host")
    void oldProviderAnswersTheHost(@TempDir Path dir) throws Throwable {
        BridgeHarness.Run run = run(dir, OLD_1211, "1.21.1");
        Object context = run.call(HOST_CLASS, "context");
        Object provider = run.call("mod/Providers", "provider", context);
        assertEquals("minecraft:item", run.call(HOST_CLASS, "keys", provider), "its listRegistries now answers the keys");
        assertEquals("minecraft:item", run.call(HOST_CLASS, "lookups", provider), "the host's listRegistries maps them");
        Object access = run.call("mod/Providers", "access", context);
        assertEquals(true, run.call(HOST_CLASS, "found", access, "minecraft:item"));
        assertEquals(false, run.call(HOST_CLASS, "found", access, "minecraft:block"));
        Object wrapped = run.call("mod/Providers", "context", context);
        assertEquals("all", run.call(HOST_CLASS, "features", wrapped), "a 1.21.1 context had no enabledFeatures");
        assertEquals("backpack_iron", run.call(HOST_CLASS, "items", wrapped));
    }

    @Test
    @DisplayName("A mod built for 1.21.2 or later keeps its listRegistries override and its calls")
    void newerModsKeepTheNewMeaning() throws Exception {
        registerAll("26.2");
        transformer.setModSourceVersion("1.21.4");
        byte[] provider = provider("mod/NewProvider", true);
        byte[] out = transformer.transformClass(provider, "mod/NewProvider");
        ClassNode node = node(out);
        assertTrue(declares(node, "listRegistries", STREAM), "the override of the host's default stays");
        assertTrue(BridgeHarness.calls(out, "listRegistries").contains(PROVIDER + ".listRegistries" + STREAM),
                "its call to the new listRegistries stays");
    }

    @Test
    @DisplayName("The 1.20.5 bridge registers only where the host context lost holderLookup; offline the target decides")
    void contextFollowsTheHost() {
        Function<String, ClassNode> old = host(CONTEXT, "holderLookup", HOLDER_LOOKUP, LOOKUP, "filterElements", FILTER);
        Function<String, ClassNode> current = host(CONTEXT, "enabledFeatures", FEATURES, LOOKUP, "listElements", STREAM);
        assertFalse(contextRedirected(old), "a 1.20.4 host still has holderLookup");
        assertTrue(contextRedirected(current));
        RetromodVersion.TARGET_MC_VERSION = "1.20.4";
        assertFalse(contextRedirected(name -> null));
        RetromodVersion.TARGET_MC_VERSION = "1.20.5";
        assertTrue(contextRedirected(name -> null));
    }

    @Test
    @DisplayName("The 1.21.2 bridge renames the key list and drops asGetterLookup only where the host did")
    void providerChangesFollowTheHost() {
        Function<String, ClassNode> host1211 = host(PROVIDER, "listRegistries", STREAM, PROVIDER, "asGetterLookup", GETTER);
        Function<String, ClassNode> host262 = host(PROVIDER, "listRegistryKeys", STREAM, PROVIDER, "listRegistries", STREAM);
        assertEquals(List.of(), providerRedirects(host1211));
        assertEquals(List.of("listRegistryKeys", HELPER + ".asGetterLookup"), providerRedirects(host262));
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertEquals(List.of(), providerRedirects(name -> null));
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertEquals(List.of("listRegistryKeys", HELPER + ".asGetterLookup"), providerRedirects(name -> null));
    }

    @Test
    @DisplayName("Mixins, interfaces and classes that already answer are left as they are, and a second pass changes nothing")
    void leavesOthersAlone() throws Exception {
        Map<String, Boolean> all = Map.of(CONTEXT_FLAG, true, KEYS_FLAG, true, ACCESS_FLAG, true, FEATURES_FLAG, true);
        byte[] mixin = context("mod/ContextMixin", Opcodes.ACC_PUBLIC, "Lorg/spongepowered/asm/mixin/Mixin;");
        assertSame(mixin, apply(mixin, all, "1.20.1"), "a mixin's members would land on the host's context");
        byte[] iface = context("mod/OwnContext", Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, null);
        assertSame(iface, apply(iface, all, "1.20.1"));
        byte[] current = provider("mod/Current", true);
        assertSame(current, apply(current, all, "1.21.1"), "it already declares listRegistryKeys");

        byte[] once = apply(context("mod/Old", Opcodes.ACC_PUBLIC, null), all, "1.20.1");
        assertEquals(List.of("holderLookup", "lookup", "listRegistryKeys", "enabledFeatures"), names(node(once)));
        assertSame(once, apply(once, all, "1.20.1"));
        assertEquals(List.of("holderLookup", "lookup", "listRegistries"),
                names(node(apply(context("mod/Old", Opcodes.ACC_PUBLIC, null), Map.of(CONTEXT_FLAG, true), "1.20.1"))),
                "a 1.20.5 to 1.21.1 host names the key list listRegistries and has no enabledFeatures");
    }

    private BridgeHarness.Run run(Path dir, Map<String, String> old, String modVersion) throws Exception {
        Consumer<RetromodTransformer> register = t -> {
            registerCommandContext(t, name -> null);
            registerProviderChanges(t, name -> null);
            t.setModSourceVersion(modVersion);
        };
        Map<String, byte[]> mod = BridgeHarness.transform("26.2", register,
                BridgeHarness.only(BridgeHarness.compile(dir.resolve("old"), old), "mod/"));
        return new BridgeHarness.Run(BridgeHarness.compile(dir.resolve("host"), HOST_262), mod);
    }

    private void registerAll(String target) {
        RetromodVersion.TARGET_MC_VERSION = target;
        transformer.clearRedirectsForTesting();
        registerCommandContext(transformer, name -> null);
        registerProviderChanges(transformer, name -> null);
    }

    private boolean contextRedirected(Function<String, ClassNode> host) {
        transformer.clearRedirectsForTesting();
        registerCommandContext(transformer, host);
        return transformer.getMethodRedirects().containsKey(new MethodKey(CONTEXT, "holderLookup", HOLDER_LOOKUP));
    }

    private List<String> providerRedirects(Function<String, ClassNode> host) {
        transformer.clearRedirectsForTesting();
        registerProviderChanges(transformer, host);
        var redirects = transformer.getMethodRedirects();
        var keys = redirects.get(new MethodKey(PROVIDER, "listRegistries", STREAM));
        var getter = redirects.get(new MethodKey(PROVIDER, "asGetterLookup", GETTER));
        List<String> found = new java.util.ArrayList<>();
        if (keys != null) found.add(keys.name());
        if (getter != null) found.add(getter.owner() + "." + getter.name());
        return found;
    }

    private static byte[] apply(byte[] bytes, Map<String, Boolean> flags, String version) {
        return LegacyRegistryProviderAdapter.apply(bytes, flag -> flags.getOrDefault(flag, false), version);
    }

    /** A host where each owner declares the listed method, as name and descriptor pairs after the owner. */
    private static Function<String, ClassNode> host(String... ownerNameDesc) {
        Map<String, ClassNode> classes = new java.util.HashMap<>();
        for (int i = 0; i < ownerNameDesc.length; i += 3) {
            ClassNode node = classes.computeIfAbsent(ownerNameDesc[i], name -> {
                ClassNode created = new ClassNode();
                created.name = name;
                return created;
            });
            node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, ownerNameDesc[i + 1],
                    ownerNameDesc[i + 2], null, null));
        }
        return classes::get;
    }

    /** A class implementing the old context with {@code holderLookup}, optionally a mixin or an interface. */
    private static byte[] context(String name, int access, String annotation) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, access, name, null, "java/lang/Object", new String[] {CONTEXT});
        if (annotation != null) cw.visitAnnotation(annotation, false).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "holderLookup", HOLDER_LOOKUP, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A provider with {@code listRegistries} calling the host's, and {@code listRegistryKeys} when {@code current}. */
    private static byte[] provider(String name, boolean current) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[] {PROVIDER});
        for (String method : current ? List.of("listRegistries", "listRegistryKeys") : List.of("listRegistries")) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method, STREAM, null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PROVIDER, method, STREAM, true);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> names(ClassNode node) {
        return node.methods.stream().map(m -> m.name).toList();
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    // 1.20.1: the context answers holderLookup, and HolderLookup filters its own elements.
    private static final Map<String, String> OLD_1201 = Map.of(
            "net/minecraft/resources/ResourceKey.java", """
                    package net.minecraft.resources;
                    public final class ResourceKey<T> {}""",
            "net/minecraft/core/Registry.java", """
                    package net.minecraft.core;
                    public interface Registry<T> {}""",
            "net/minecraft/core/HolderLookup.java", """
                    package net.minecraft.core;
                    public interface HolderLookup<T> {
                        default HolderLookup<T> filterElements(java.util.function.Predicate<T> filter) { return this; }
                    }""",
            "net/minecraft/commands/CommandBuildContext.java", """
                    package net.minecraft.commands;
                    import net.minecraft.core.HolderLookup;
                    import net.minecraft.core.Registry;
                    import net.minecraft.resources.ResourceKey;
                    public interface CommandBuildContext {
                        <T> HolderLookup<T> holderLookup(ResourceKey<? extends Registry<T>> key);
                    }""",
            // Sophisticated Backpacks' item argument context, which keeps only its backpacks.
            "mod/BackpackArgument.java", """
                    package mod;
                    import net.minecraft.commands.CommandBuildContext;
                    import net.minecraft.core.HolderLookup;
                    import net.minecraft.core.Registry;
                    import net.minecraft.resources.ResourceKey;
                    public final class BackpackArgument {
                        static CommandBuildContext backpacks(CommandBuildContext delegate) {
                            return new CommandBuildContext() {
                                @Override
                                public <T> HolderLookup<T> holderLookup(ResourceKey<? extends Registry<T>> key) {
                                    return delegate.holderLookup(key).filterElements(item -> item.toString().startsWith("backpack"));
                                }
                            };
                        }
                        public static Object wrap(Object context) { return backpacks((CommandBuildContext) context); }
                    }""");

    // 1.21.1: providers list their keys through listRegistries, and registry access answers registry(key).
    private static final Map<String, String> OLD_1211 = Map.of(
            "net/minecraft/resources/ResourceKey.java", """
                    package net.minecraft.resources;
                    public final class ResourceKey<T> {}""",
            "net/minecraft/core/Registry.java", """
                    package net.minecraft.core;
                    public interface Registry<T> {}""",
            "net/minecraft/core/HolderLookup.java", """
                    package net.minecraft.core;
                    import java.util.Optional;
                    import java.util.stream.Stream;
                    import net.minecraft.resources.ResourceKey;
                    public interface HolderLookup<T> {
                        interface RegistryLookup<T> extends HolderLookup<T> {
                            default RegistryLookup<T> filterElements(java.util.function.Predicate<T> filter) { return this; }
                        }
                        interface Provider {
                            Stream<ResourceKey<? extends Registry<?>>> listRegistries();
                            <T> Optional<RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key);
                        }
                    }""",
            "net/minecraft/core/RegistryAccess.java", """
                    package net.minecraft.core;
                    import java.util.Optional;
                    import java.util.stream.Stream;
                    import net.minecraft.resources.ResourceKey;
                    public interface RegistryAccess extends HolderLookup.Provider {
                        <E> Optional<Registry<E>> registry(ResourceKey<? extends Registry<? extends E>> key);
                        default Stream<ResourceKey<? extends Registry<?>>> listRegistries() { return Stream.empty(); }
                        default <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
                            return Optional.empty();
                        }
                    }""",
            "net/minecraft/commands/CommandBuildContext.java", """
                    package net.minecraft.commands;
                    public interface CommandBuildContext extends net.minecraft.core.HolderLookup.Provider {}""",
            // Xaero's JSON provider, KubeJS's registry access, and Sophisticated Backpacks' 1.21.1 context.
            "mod/Providers.java", """
                    package mod;
                    import java.util.Optional;
                    import java.util.stream.Stream;
                    import net.minecraft.commands.CommandBuildContext;
                    import net.minecraft.core.HolderLookup;
                    import net.minecraft.core.Registry;
                    import net.minecraft.core.RegistryAccess;
                    import net.minecraft.resources.ResourceKey;
                    public final class Providers {
                        public static Object provider(Object delegate) {
                            HolderLookup.Provider from = (HolderLookup.Provider) delegate;
                            return new HolderLookup.Provider() {
                                public Stream<ResourceKey<? extends Registry<?>>> listRegistries() { return from.listRegistries(); }
                                public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
                                    return from.lookup(key);
                                }
                            };
                        }
                        public static Object access(Object delegate) {
                            HolderLookup.Provider from = (HolderLookup.Provider) delegate;
                            return new RegistryAccess() {
                                public <E> Optional<Registry<E>> registry(ResourceKey<? extends Registry<? extends E>> key) {
                                    return from.lookup(key).map(found -> new Registry<E>() {});
                                }
                            };
                        }
                        public static Object context(Object delegate) {
                            CommandBuildContext from = (CommandBuildContext) delegate;
                            return new CommandBuildContext() {
                                public Stream<ResourceKey<? extends Registry<?>>> listRegistries() { return from.listRegistries(); }
                                public <T> Optional<HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
                                    return from.lookup(key).map(found -> found.filterElements(item -> item.toString().startsWith("backpack")));
                                }
                            };
                        }
                    }""");

    // 26.2: the context is a provider with enabledFeatures, and filterElements lives on registry lookups.
    private static final Map<String, String> HOST_262 = Map.ofEntries(
            Map.entry("com/mojang/serialization/Lifecycle.java", """
                    package com.mojang.serialization;
                    public final class Lifecycle {
                        private static final Lifecycle STABLE = new Lifecycle();
                        public static Lifecycle stable() { return STABLE; }
                    }"""),
            Map.entry("net/minecraft/resources/ResourceKey.java", """
                    package net.minecraft.resources;
                    public final class ResourceKey<T> {
                        private final String name;
                        private ResourceKey(String name) { this.name = name; }
                        public static <T> ResourceKey<T> of(String name) { return new ResourceKey<>(name); }
                        @Override public String toString() { return name; }
                    }"""),
            Map.entry("net/minecraft/tags/TagKey.java", """
                    package net.minecraft.tags;
                    public final class TagKey<T> {}"""),
            Map.entry("net/minecraft/core/Registry.java", """
                    package net.minecraft.core;
                    public interface Registry<T> {}"""),
            Map.entry("net/minecraft/core/Holder.java", """
                    package net.minecraft.core;
                    public interface Holder<T> {
                        T value();
                        class Reference<T> implements Holder<T> {
                            private final T value;
                            public Reference(T value) { this.value = value; }
                            public T value() { return value; }
                        }
                    }"""),
            Map.entry("net/minecraft/core/HolderSet.java", """
                    package net.minecraft.core;
                    public interface HolderSet<T> {
                        class Named<T> implements HolderSet<T> {}
                    }"""),
            Map.entry("net/minecraft/core/HolderGetter.java", """
                    package net.minecraft.core;
                    import java.util.Optional;
                    import net.minecraft.resources.ResourceKey;
                    import net.minecraft.tags.TagKey;
                    public interface HolderGetter<T> {
                        Optional<Holder.Reference<T>> get(ResourceKey<T> key);
                        Optional<HolderSet.Named<T>> get(TagKey<T> tag);
                        interface Provider {
                            <T> Optional<? extends HolderGetter<T>> lookup(ResourceKey<? extends Registry<? extends T>> key);
                        }
                    }"""),
            Map.entry("net/minecraft/core/HolderLookup.java", """
                    package net.minecraft.core;
                    import com.mojang.serialization.Lifecycle;
                    import java.util.Optional;
                    import java.util.function.Predicate;
                    import java.util.stream.Stream;
                    import net.minecraft.resources.ResourceKey;
                    import net.minecraft.tags.TagKey;
                    public interface HolderLookup<T> extends HolderGetter<T> {
                        Stream<Holder.Reference<T>> listElements();
                        Stream<HolderSet.Named<T>> listTags();
                        interface RegistryLookup<T> extends HolderLookup<T> {
                            ResourceKey<? extends Registry<? extends T>> key();
                            Lifecycle registryLifecycle();
                            default RegistryLookup<T> filterElements(Predicate<T> filter) {
                                RegistryLookup<T> parent = this;
                                return new RegistryLookup<T>() {
                                    public ResourceKey<? extends Registry<? extends T>> key() { return parent.key(); }
                                    public Lifecycle registryLifecycle() { return parent.registryLifecycle(); }
                                    public Stream<Holder.Reference<T>> listElements() {
                                        return parent.listElements().filter(holder -> filter.test(holder.value()));
                                    }
                                    public Stream<HolderSet.Named<T>> listTags() { return parent.listTags(); }
                                    public Optional<Holder.Reference<T>> get(ResourceKey<T> key) {
                                        return parent.get(key).filter(holder -> filter.test(holder.value()));
                                    }
                                    public Optional<HolderSet.Named<T>> get(TagKey<T> tag) { return parent.get(tag); }
                                };
                            }
                        }
                        interface Provider extends HolderGetter.Provider {
                            Stream<ResourceKey<? extends Registry<?>>> listRegistryKeys();
                            @SuppressWarnings({"unchecked", "rawtypes"})
                            default Stream<RegistryLookup<?>> listRegistries() {
                                return listRegistryKeys().map(key -> (RegistryLookup<?>) lookupOrThrow((ResourceKey) key));
                            }
                            @Override
                            <T> Optional<? extends RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key);
                            default <T> RegistryLookup<T> lookupOrThrow(ResourceKey<? extends Registry<? extends T>> key) {
                                return lookup(key).orElseThrow(() -> new IllegalStateException("Missing registry " + key));
                            }
                        }
                    }"""),
            Map.entry("net/minecraft/core/RegistryAccess.java", """
                    package net.minecraft.core;
                    import java.util.stream.Stream;
                    import net.minecraft.resources.ResourceKey;
                    public interface RegistryAccess extends HolderLookup.Provider {
                        default Stream<ResourceKey<? extends Registry<?>>> listRegistryKeys() { return Stream.empty(); }
                    }"""),
            Map.entry("net/minecraft/world/flag/FeatureFlagSet.java", """
                    package net.minecraft.world.flag;
                    public final class FeatureFlagSet {
                        public final String name;
                        public FeatureFlagSet(String name) { this.name = name; }
                    }"""),
            Map.entry("net/minecraft/world/flag/FeatureFlagRegistry.java", """
                    package net.minecraft.world.flag;
                    public class FeatureFlagRegistry {
                        public FeatureFlagSet allFlags() { return new FeatureFlagSet("all"); }
                    }"""),
            Map.entry("net/minecraft/world/flag/FeatureFlags.java", """
                    package net.minecraft.world.flag;
                    public class FeatureFlags {
                        public static final FeatureFlagRegistry REGISTRY = new FeatureFlagRegistry();
                    }"""),
            Map.entry("net/minecraft/commands/CommandBuildContext.java", """
                    package net.minecraft.commands;
                    public interface CommandBuildContext extends net.minecraft.core.HolderLookup.Provider {
                        net.minecraft.world.flag.FeatureFlagSet enabledFeatures();
                    }"""),
            // The host's own context, and the reads ItemParser, GameRuleCommand and RegistryOps make of one.
            Map.entry("net/minecraft/commands/Host.java", """
                    package net.minecraft.commands;
                    import com.mojang.serialization.Lifecycle;
                    import java.util.Optional;
                    import java.util.stream.Collectors;
                    import java.util.stream.Stream;
                    import net.minecraft.core.Holder;
                    import net.minecraft.core.HolderLookup;
                    import net.minecraft.core.HolderSet;
                    import net.minecraft.core.Registry;
                    import net.minecraft.resources.ResourceKey;
                    import net.minecraft.tags.TagKey;
                    import net.minecraft.world.flag.FeatureFlagSet;
                    public final class Host {
                        static final ResourceKey<Registry<String>> ITEMS = ResourceKey.of("minecraft:item");
                        public static Object context() {
                            HolderLookup.RegistryLookup<String> items = new HolderLookup.RegistryLookup<>() {
                                public ResourceKey<? extends Registry<? extends String>> key() { return ITEMS; }
                                public Lifecycle registryLifecycle() { return Lifecycle.stable(); }
                                public Stream<Holder.Reference<String>> listElements() {
                                    return Stream.of("backpack_iron", "stone").map(Holder.Reference::new);
                                }
                                public Stream<HolderSet.Named<String>> listTags() { return Stream.empty(); }
                                public Optional<Holder.Reference<String>> get(ResourceKey<String> key) {
                                    return listElements().filter(holder -> holder.value().equals(key.toString())).findFirst();
                                }
                                public Optional<HolderSet.Named<String>> get(TagKey<String> tag) { return Optional.empty(); }
                            };
                            return new CommandBuildContext() {
                                public Stream<ResourceKey<? extends Registry<?>>> listRegistryKeys() { return Stream.of(ITEMS); }
                                @SuppressWarnings("unchecked")
                                public <T> Optional<? extends HolderLookup.RegistryLookup<T>> lookup(ResourceKey<? extends Registry<? extends T>> key) {
                                    return key.toString().equals(ITEMS.toString())
                                            ? Optional.of((HolderLookup.RegistryLookup<T>) (Object) items) : Optional.empty();
                                }
                                public FeatureFlagSet enabledFeatures() { return new FeatureFlagSet("vanilla"); }
                            };
                        }
                        public static String items(Object context) {
                            return ((CommandBuildContext) context).lookupOrThrow(ITEMS).listElements()
                                    .map(holder -> String.valueOf(holder.value())).collect(Collectors.joining(","));
                        }
                        public static String features(Object context) { return ((CommandBuildContext) context).enabledFeatures().name; }
                        public static Long registries(Object context) { return ((CommandBuildContext) context).listRegistries().count(); }
                        public static String keys(Object provider) {
                            return ((HolderLookup.Provider) provider).listRegistryKeys().map(String::valueOf).collect(Collectors.joining(","));
                        }
                        public static String lookups(Object provider) {
                            return ((HolderLookup.Provider) provider).listRegistries().map(lookup -> String.valueOf(lookup.key()))
                                    .collect(Collectors.joining(","));
                        }
                        public static Boolean found(Object provider, String key) {
                            return ((HolderLookup.Provider) provider).lookup(ResourceKey.of(key)).isPresent();
                        }
                        public static String lookupOrThrow(Object provider, String key) {
                            return String.valueOf(((HolderLookup.Provider) provider).lookupOrThrow(ResourceKey.of(key)).key());
                        }
                    }"""));
}
