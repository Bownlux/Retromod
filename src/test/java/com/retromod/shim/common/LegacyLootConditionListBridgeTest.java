/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.embedded.LegacyLootSerializers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** 26.3 holds a conditional loot function's conditions as one optional holder; a 1.21 function still gets its list. */
class LegacyLootConditionListBridgeTest {

    private static final String FUNCTION = LegacyLootConditionListBridge.FUNCTION;
    private static final String FIELDS = LegacyLootConditionListBridge.FIELDS;
    private static final String MOD = "mod/SkilletFunction";
    private static final String COMMON_FIELDS = LegacyLootConditionListBridge.COMMON_FIELDS_DESC;
    private static final String SIMPLE_BUILDER = LegacyLootConditionListBridge.SIMPLE_BUILDER_DESC;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a 1.21.1 function's own commonFields, simpleBuilder and super(list) reach the helpers on 26.3")
    void oldFunctionReachesTheHelpers() {
        byte[] out = transform("26.3", "1.21.1").get(MOD);
        assertEquals(List.of(FIELDS + ".commonFields" + COMMON_FIELDS), BridgeHarness.calls(out, "fields"));
        assertEquals(List.of(FIELDS + ".simpleBuilder" + SIMPLE_BUILDER), BridgeHarness.calls(out, "builder"));
        assertEquals(List.of(LegacyLootConditionListBridge.SUPPORT + ".conditionHolder(Ljava/util/List;)Ljava/util/Optional;",
                FUNCTION + ".<init>(Ljava/util/Optional;)V"), BridgeHarness.calls(out, "<init>"));
    }

    @Test
    @DisplayName("a function built for 26.3 keeps the host's helpers, though it names its own class as javac does")
    void newFunctionKeepsTheHostHelpers() {
        byte[] out = transform("26.3", "26.3").get(MOD);
        assertEquals(List.of(MOD + ".commonFields" + COMMON_FIELDS), BridgeHarness.calls(out, "fields"));
        assertEquals(List.of(MOD + ".simpleBuilder" + SIMPLE_BUILDER), BridgeHarness.calls(out, "builder"));
    }

    @Test
    @DisplayName("26.2 still takes the list, so nothing changes there")
    void listHostsAreLeftAlone() {
        byte[] out = transform("26.2", "1.21.1").get(MOD);
        assertEquals(List.of(MOD + ".commonFields" + COMMON_FIELDS), BridgeHarness.calls(out, "fields"));
        assertEquals(List.of(FUNCTION + ".<init>(Ljava/util/List;)V"), BridgeHarness.calls(out, "<init>"));
    }

    @Test
    @DisplayName("the helper class is a subclass, so it may call the host's protected helpers, and maps the field")
    void helperCallsTheHostAndMaps() {
        ClassNode node = new ClassNode();
        new ClassReader(LegacyLootConditionListBridge.generateFields()).accept(node, 0);
        assertEquals(FUNCTION, node.superName);
        assertTrue((node.access & ACC_ABSTRACT) != 0, "never constructed");
        List<String> fields = BridgeHarness.calls(LegacyLootConditionListBridge.generateFields(), "commonFields");
        assertTrue(fields.contains(FUNCTION + ".commonFields" + COMMON_FIELDS), fields.toString());
        assertTrue(fields.stream().anyMatch(call -> call.startsWith(LegacyLootConditionListBridge.INSTANCE + ".map(")), fields.toString());
        List<String> builder = BridgeHarness.calls(LegacyLootConditionListBridge.generateFields(), "simpleBuilder");
        assertTrue(builder.contains(FUNCTION + ".simpleBuilder" + SIMPLE_BUILDER), builder.toString());
    }

    @Test
    @DisplayName("the list and the one holder convert both ways, and an unbound predicate is read when it is used")
    void conversionsRoundTrip() throws Exception {
        Helpers helpers = new Helpers(dir);
        Object stone = helpers.condition("stone");
        Object held = helpers.direct(stone);

        assertEquals(List.of(stone), helpers.call("conditionList", Object.class, Optional.of(held)));
        assertEquals(List.of(), helpers.call("conditionList", Object.class, Optional.empty()));
        assertEquals(Optional.of(held), helpers.call("conditionHolder", List.class, List.of(stone)));
        assertEquals(Optional.empty(), helpers.call("conditionHolder", List.class, List.of()));
        Optional<?> both = (Optional<?>) helpers.call("conditionHolder", List.class, List.of(stone, helpers.condition("dirt")));
        assertTrue(both.orElseThrow().toString().contains("AllOfCondition"), "several conditions all have to pass: " + both);

        List<?> unbound = (List<?>) helpers.call("conditionList", Object.class, Optional.of(helpers.unbound(stone)));
        assertEquals("stone", unbound.get(0).toString(), "the proxy reads the holder's value when used");

        @SuppressWarnings("unchecked")
        Function<Object, Object> constructor = (Function<Object, Object>) helpers.call("takingConditionList", Function.class,
                (Function<Object, Object>) list -> list);
        assertEquals(List.of(stone), constructor.apply(Optional.of(held)));
    }

    @Test
    @DisplayName("a predicate named by id goes back as the holder it came from, so it is written by id again")
    void namedPredicateKeepsItsHolder() throws Exception {
        Helpers helpers = new Helpers(dir);
        Object pending = helpers.reference("mod:sunny");
        Object proxy = ((List<?>) helpers.call("conditionList", Object.class, Optional.of(pending))).get(0);
        assertSame(pending, ((Optional<?>) helpers.call("conditionHolder", List.class, List.of(proxy))).orElseThrow(),
                "the stand-in goes back as its holder, not as a direct holder around the stand-in");

        Object bound = helpers.reference("mod:stone");
        Object stone = helpers.condition("stone");
        helpers.bind(bound, stone);
        assertEquals(List.of(stone), helpers.call("conditionList", Object.class, Optional.of(bound)));
        assertSame(bound, ((Optional<?>) helpers.call("conditionHolder", List.class, List.of(stone))).orElseThrow());

        Object equal = helpers.condition("stone");
        assertEquals(Optional.of(helpers.direct(equal)), helpers.call("conditionHolder", List.class, List.of(equal)),
                "an equal condition made elsewhere is not the bound one, so it is held directly");
    }

    @Test
    @DisplayName("a predicate used before its registry binds fails with the host's own error")
    void unboundPredicateFailsWithTheHostError() throws Exception {
        Helpers helpers = new Helpers(dir);
        Object pending = helpers.reference("mod:sunny");
        Object proxy = ((List<?>) helpers.call("conditionList", Object.class, Optional.of(pending))).get(0);

        IllegalStateException failure = assertThrows(IllegalStateException.class, proxy::toString);
        assertTrue(failure.getMessage().contains("unbound value 'mod:sunny'"), failure.getMessage());
        assertEquals(System.identityHashCode(proxy), proxy.hashCode(), "the stand-in's identity needs no value");
        helpers.bind(pending, helpers.condition("sunny"));
        assertEquals("sunny", proxy.toString(), "once bound, the stand-in reads the value");
    }

    @Test
    @DisplayName("code outside an entry reads its condition and function lists from the entry base's own holders")
    void outsideReadsUseTheEntryBaseFields() throws Exception {
        Helpers helpers = new Helpers(dir);
        Object stone = helpers.condition("stone");
        Object smelt = helpers.function("smelt");
        Object entry = helpers.loader.loadClass("net.minecraft.world.level.storage.loot.entries.ShadowingEntry")
                .getConstructor(Optional.class, Optional.class)
                .newInstance(Optional.of(helpers.direct(stone)), Optional.of(helpers.direct(smelt)));

        assertEquals(List.of(stone), helpers.call("entryConditions", Object.class, entry),
                "the entry's own field of the same name is not the one 26.3 holds the condition in");
        assertEquals(List.of(smelt), helpers.call("entryFunctions", Object.class, entry));
    }

    @Test
    @DisplayName("reading the same named predicate again keeps one remembered pair")
    void rereadingANamedPredicateRemembersItOnce() throws Exception {
        Helpers helpers = new Helpers(dir);
        Object bound = helpers.reference("mod:stone");
        helpers.bind(bound, helpers.condition("stone"));
        for (int read = 0; read < 3; read++) helpers.call("conditionList", Object.class, Optional.of(bound));

        java.lang.reflect.Field named = helpers.support.getDeclaredField("NAMED");
        named.setAccessible(true);
        assertEquals(1, ((List<?>) named.get(null)).size(), "each decode of the same holder hands out the same element");
    }

    @Test
    @DisplayName("an entry's functions convert both ways, and several run in sequence")
    void functionListsRoundTrip() throws Exception {
        Helpers helpers = new Helpers(dir);
        Object smelt = helpers.function("smelt");
        Object held = helpers.direct(smelt);

        assertEquals(List.of(smelt), helpers.call("functionList", Object.class, Optional.of(held)));
        assertEquals(List.of(), helpers.call("functionList", Object.class, Optional.empty()));
        assertEquals(Optional.of(held), helpers.call("functionHolder", List.class, List.of(smelt)));
        assertEquals(Optional.empty(), helpers.call("functionHolder", List.class, List.of()));
        Optional<?> both = (Optional<?>) helpers.call("functionHolder", List.class, List.of(smelt, helpers.function("count")));
        assertTrue(both.orElseThrow().toString().contains("Sequence"), "several functions run in order: " + both);
    }

    @Test
    @DisplayName("a 1.20.1 serializer reads the one condition 26.3 data holds, and the old list as before")
    void gsonSerializerReadsBothShapes() throws Exception {
        Helpers helpers = new Helpers(dir);
        Object listed = helpers.conditions("{\"conditions\":[{\"type\":\"a\"},{\"type\":\"b\"}]}");
        assertEquals(2, Array.getLength(listed));
        Object single = helpers.conditions("{\"type\":\"mod:mark\",\"condition\":{\"type\":\"a\"}}");
        assertEquals(1, Array.getLength(single));
        assertEquals("a", Array.get(single, 0).toString());
        assertEquals(0, Array.getLength(helpers.conditions("{\"type\":\"mod:mark\"}")));
    }

    @Test
    @DisplayName("a 1.20.1 serializer reads a condition named by id, through the holder codec, and hands its holder back")
    void gsonSerializerReadsANamedCondition() throws Exception {
        Path libraries = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
        Path dfu = libraries.resolve("com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar");
        Path fastutil = libraries.resolve("it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(dfu) && Files.isRegularFile(fastutil),
                "no local DataFixerUpper 10 or fastutil jar; they are local test material");
        Map<String, byte[]> classes = new HashMap<>(BridgeHarness.compile(dir, Map.of(
                "net/minecraft/core/Holder.java", """
                        package net.minecraft.core;
                        public interface Holder<T> {
                            T value();
                            boolean isBound();
                            static <T> Holder<T> direct(T value) { return new Direct<>(value); }
                            record Direct<T>(T value) implements Holder<T> { public boolean isBound() { return true; } }
                            final class Reference<T> implements Holder<T> {
                                private final String id;
                                private T value;
                                public Reference(String id) { this.id = id; }
                                public void bind(T value) { this.value = value; }
                                public boolean isBound() { return value != null; }
                                public java.util.Optional<String> unwrapKey() { return java.util.Optional.of(id); }
                                public T value() {
                                    if (value == null) throw new IllegalStateException("Trying to access unbound value '" + id + "'");
                                    return value;
                                }
                            }
                        }""",
                "net/minecraft/world/level/storage/loot/predicates/LootItemCondition.java", """
                        package net.minecraft.world.level.storage.loot.predicates;
                        import com.mojang.serialization.Codec;
                        import net.minecraft.core.Holder;
                        public interface LootItemCondition {
                            Codec<LootItemCondition> DIRECT_CODEC = Codec.STRING.fieldOf("type").codec()
                                    .xmap(Named::new, condition -> ((Named) condition).name());
                            Codec<Holder<LootItemCondition>> CODEC = Codec.STRING.xmap(Holder.Reference::new, holder -> "?");
                        }""",
                "net/minecraft/world/level/storage/loot/predicates/Named.java", """
                        package net.minecraft.world.level.storage.loot.predicates;
                        public record Named(String name) implements LootItemCondition {
                            @Override public String toString() { return name; }
                        }"""), dfu.toString()));
        String name = LegacyLootSerializers.class.getName().replace('.', '/');
        try (InputStream in = LegacyLootSerializers.class.getResourceAsStream("/" + name + ".class")) {
            classes.put(name, in.readAllBytes());
        }
        ClassLoader loader = new LegacyKeyDispatchCodecBridgeTest.ChildFirstLoader(
                new URL[] {dfu.toUri().toURL(), fastutil.toUri().toURL()}, classes);
        Class<?> support = loader.loadClass(LegacyLootSerializers.class.getName());
        // The Gson context Retromod hands an old serializer, over plain JSON ops.
        Method contextOf = support.getDeclaredMethod("deserializationContext", ClassLoader.class, Object.class);
        contextOf.setAccessible(true);
        Object context = contextOf.invoke(null, loader,
                loader.loadClass("com.mojang.serialization.JsonOps").getField("INSTANCE").get(null));

        Object read = support.getMethod("conditions", Object.class, Object.class)
                .invoke(null, JsonParser.parseString("{\"type\":\"mod:mark\",\"condition\":\"mod:sunny\"}"), context);
        assertEquals(1, Array.getLength(read));
        Object condition = Array.get(read, 0);
        Object holder = ((Optional<?>) support.getMethod("conditionHolder", Object[].class).invoke(null, read)).orElseThrow();
        assertEquals("net.minecraft.core.Holder$Reference", holder.getClass().getName(), "the id stays a reference");
        holder.getClass().getMethod("bind", Object.class).invoke(holder,
                loader.loadClass("net.minecraft.world.level.storage.loot.predicates.Named").getConstructor(String.class).newInstance("sunny"));
        assertEquals("sunny", condition.toString(), "the condition reads the predicate once its registry binds it");
    }

    /** The mod's function class, transformed for {@code host} as a mod built for {@code source}. */
    private static Map<String, byte[]> transform(String host, String source) {
        return BridgeHarness.transform(host, transformer -> {
            transformer.setModSourceVersion(source);
            LegacyLootConditionListBridge.register(transformer);
        }, Map.of(MOD, modFunction()));
    }

    /**
     * {@code class SkilletFunction extends LootItemConditionalFunction}, as javac writes it: its own class names the
     * inherited static helpers, and its constructor passes a list.
     */
    private static byte[] modFunction() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, MOD, null, FUNCTION, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/List;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitMethodInsn(INVOKESPECIAL, FUNCTION, "<init>", "(Ljava/util/List;)V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        staticCall(cw, "fields", COMMON_FIELDS, "commonFields");
        staticCall(cw, "builder", SIMPLE_BUILDER, "simpleBuilder");
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void staticCall(ClassWriter cw, String name, String desc, String helper) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, MOD, helper, desc, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The embedded helper in a loader with stand-ins for the few host types it reads by name. */
    private static final class Helpers {
        final ClassLoader loader;
        final Class<?> support;

        Helpers(Path dir) throws Exception {
            Map<String, byte[]> classes = new HashMap<>(BridgeHarness.compile(dir, withEntryBase(Map.of(
                    "net/minecraft/core/Holder.java", """
                            package net.minecraft.core;
                            public interface Holder<T> {
                                T value();
                                boolean isBound();
                                static <T> Holder<T> direct(T value) { return new Direct<>(value); }
                                record Direct<T>(T value) implements Holder<T> { public boolean isBound() { return true; } }
                                record Unbound<T>(T value) implements Holder<T> { public boolean isBound() { return false; } }
                                final class Reference<T> implements Holder<T> {
                                    private final String id;
                                    private T value;
                                    public Reference(String id) { this.id = id; }
                                    public void bind(T value) { this.value = value; }
                                    public boolean isBound() { return value != null; }
                                    public java.util.Optional<String> unwrapKey() { return java.util.Optional.of(id); }
                                    public T value() {
                                        if (value == null) throw new IllegalStateException("Trying to access unbound value '" + id + "'");
                                        return value;
                                    }
                                }
                            }""",
                    "net/minecraft/world/level/storage/loot/predicates/ConditionUserBuilder.java", """
                            package net.minecraft.world.level.storage.loot.predicates;
                            import java.util.List;
                            import java.util.Optional;
                            import net.minecraft.core.Holder;
                            import net.minecraft.core.HolderSet;
                            public interface ConditionUserBuilder {
                                static Optional<Holder<LootItemCondition>> buildCondition(List<Holder<LootItemCondition>> conditions) {
                                    if (conditions.isEmpty()) return Optional.empty();
                                    if (conditions.size() == 1) return Optional.of(conditions.get(0));
                                    return Optional.of(Holder.direct(AllOfCondition.allOf(HolderSet.direct(conditions))));
                                }
                            }""",
                    "net/minecraft/world/level/storage/loot/functions/LootItemFunction.java", """
                            package net.minecraft.world.level.storage.loot.functions;
                            public interface LootItemFunction {}""",
                    "net/minecraft/world/level/storage/loot/functions/NamedFunction.java", """
                            package net.minecraft.world.level.storage.loot.functions;
                            public record NamedFunction(String name) implements LootItemFunction {}""",
                    "net/minecraft/world/level/storage/loot/functions/FunctionUserBuilder.java", """
                            package net.minecraft.world.level.storage.loot.functions;
                            import java.util.List;
                            import java.util.Optional;
                            import net.minecraft.core.Holder;
                            public interface FunctionUserBuilder {
                                record Sequence(List<Holder<LootItemFunction>> functions) implements LootItemFunction {}
                                static Optional<Holder<LootItemFunction>> buildFunction(List<Holder<LootItemFunction>> functions) {
                                    if (functions.isEmpty()) return Optional.empty();
                                    if (functions.size() == 1) return Optional.of(functions.get(0));
                                    return Optional.of(Holder.direct(new Sequence(functions)));
                                }
                            }""",
                    "net/minecraft/core/HolderSet.java", """
                            package net.minecraft.core;
                            public record HolderSet<T>(java.util.List<? extends Holder<T>> list) {
                                public static <T> HolderSet<T> direct(java.util.List<? extends Holder<T>> list) { return new HolderSet<>(list); }
                            }""",
                    "net/minecraft/world/level/storage/loot/predicates/LootItemCondition.java", """
                            package net.minecraft.world.level.storage.loot.predicates;
                            public interface LootItemCondition {}""",
                    "net/minecraft/world/level/storage/loot/predicates/Named.java", """
                            package net.minecraft.world.level.storage.loot.predicates;
                            public record Named(String name) implements LootItemCondition {
                                @Override public String toString() { return name; }
                            }""",
                    "net/minecraft/world/level/storage/loot/predicates/AllOfCondition.java", """
                            package net.minecraft.world.level.storage.loot.predicates;
                            public record AllOfCondition(net.minecraft.core.HolderSet<LootItemCondition> terms) implements LootItemCondition {
                                public static AllOfCondition allOf(net.minecraft.core.HolderSet<LootItemCondition> terms) {
                                    return new AllOfCondition(terms);
                                }
                            }"""))));
            String name = LegacyLootSerializers.class.getName().replace('.', '/');
            try (InputStream in = LegacyLootSerializers.class.getResourceAsStream("/" + name + ".class")) {
                classes.put(name, in.readAllBytes());
            }
            loader = new LegacyKeyDispatchCodecBridgeTest.ChildFirstLoader(new URL[0], classes);
            support = loader.loadClass(LegacyLootSerializers.class.getName());
        }

        /** The 26.3 entry base, which holds the one condition and modifier, and an entry with a field of the same name. */
        private static Map<String, String> withEntryBase(Map<String, String> sources) {
            Map<String, String> all = new HashMap<>(sources);
            all.putAll(Map.of(
                    "net/minecraft/world/level/storage/loot/entries/LootPoolEntryContainer.java", """
                            package net.minecraft.world.level.storage.loot.entries;
                            public abstract class LootPoolEntryContainer {
                                protected final java.util.Optional<Object> condition;
                                protected final java.util.Optional<Object> modifier;
                                protected LootPoolEntryContainer(java.util.Optional<Object> condition, java.util.Optional<Object> modifier) {
                                    this.condition = condition;
                                    this.modifier = modifier;
                                }
                            }""",
                    "net/minecraft/world/level/storage/loot/entries/ShadowingEntry.java", """
                            package net.minecraft.world.level.storage.loot.entries;
                            public class ShadowingEntry extends LootPoolEntryContainer {
                                private final String condition = "its own field";
                                public ShadowingEntry(java.util.Optional<Object> condition, java.util.Optional<Object> modifier) {
                                    super(condition, modifier);
                                }
                            }"""));
            return all;
        }

        Object condition(String name) throws Exception {
            return loader.loadClass("net.minecraft.world.level.storage.loot.predicates.Named")
                    .getConstructor(String.class).newInstance(name);
        }

        Object direct(Object value) throws Exception {
            return loader.loadClass("net.minecraft.core.Holder").getMethod("direct", Object.class).invoke(null, value);
        }

        Object unbound(Object value) throws Exception {
            return loader.loadClass("net.minecraft.core.Holder$Unbound").getConstructor(Object.class).newInstance(value);
        }

        /** A holder named by id, unbound until {@link #bind}. */
        Object reference(String id) throws Exception {
            return loader.loadClass("net.minecraft.core.Holder$Reference").getConstructor(String.class).newInstance(id);
        }

        void bind(Object reference, Object value) throws Exception {
            reference.getClass().getMethod("bind", Object.class).invoke(reference, value);
        }

        Object function(String name) throws Exception {
            return loader.loadClass("net.minecraft.world.level.storage.loot.functions.NamedFunction")
                    .getConstructor(String.class).newInstance(name);
        }

        Object call(String method, Class<?> parameter, Object argument) throws Exception {
            return support.getMethod(method, parameter).invoke(null, argument);
        }

        /** {@code conditions(json, context)} with a Gson context that reads each condition by its type. */
        Object conditions(String json) throws Exception {
            Class<?> condition = loader.loadClass("net.minecraft.world.level.storage.loot.predicates.LootItemCondition");
            JsonDeserializationContext context = new JsonDeserializationContext() {
                @Override
                @SuppressWarnings("unchecked")
                public <T> T deserialize(JsonElement element, Type type) {
                    try {
                        if (type instanceof Class<?> array && array.isArray()) {
                            Object out = Array.newInstance(condition, element.getAsJsonArray().size());
                            for (int i = 0; i < element.getAsJsonArray().size(); i++) {
                                Array.set(out, i, condition(element.getAsJsonArray().get(i).getAsJsonObject().get("type").getAsString()));
                            }
                            return (T) out;
                        }
                        return (T) condition(element.getAsJsonObject().get("type").getAsString());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
            Method read = support.getMethod("conditions", Object.class, Object.class);
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            return read.invoke(null, object, context);
        }
    }
}
