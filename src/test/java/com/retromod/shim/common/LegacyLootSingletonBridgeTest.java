/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.embedded.LegacyLootSerializers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** 26.3 removed LootPoolSingletonContainer; a 1.21.1 mod's own single loot entry extends the rebuilt one instead. */
class LegacyLootSingletonBridgeTest {

    private static final String ENTRIES = LegacyLootSingletonBridge.ENTRIES;
    private static final String SINGLETON = LegacyLootSingletonBridge.SINGLETON;
    private static final String UNIFORM = LegacyLootSingletonBridge.UNIFORM;
    private static final String BASE = LegacyLootSingletonBridge.BASE;
    private static final String LIST_CONSTRUCTOR = "(IILjava/util/List;Ljava/util/List;)V";

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
    @DisplayName("a 1.21.1 entry extends the rebuilt base and keeps its constructor, codec fields and builder")
    void oldEntryExtendsTheRebuiltBase() throws Exception {
        Map<String, byte[]> out = transform("26.3", "1.21.1", oldMod());
        ClassNode entry = node(out.get("mod/NamedItemEntry"));

        assertEquals(BASE, entry.superName);
        assertEquals(List.of(BASE + ".<init>" + LIST_CONSTRUCTOR), BridgeHarness.calls(out.get("mod/NamedItemEntry"), "<init>"));
        // javac names the entry itself as the owner of its inherited helpers, so they resolve through the base.
        assertEquals(List.of("mod/NamedItemEntry.singletonFields" + LegacyLootSingletonBridge.FIELDS_DESC),
                BridgeHarness.calls(out.get("mod/NamedItemEntry"), "fields"));
        assertEquals(List.of("mod/NamedItemEntry.simpleBuilder" + LegacyLootSingletonBridge.BUILDER_DESC),
                BridgeHarness.calls(out.get("mod/NamedItemEntry"), "named").stream()
                        .filter(call -> call.contains("simpleBuilder")).toList());
        InvokeDynamicInsnNode lambda = indy(entry, "named");
        assertEquals("(Ljava/lang/String;)L" + BASE + "$EntryConstructor;", lambda.desc);
        assertEquals("(IILjava/util/List;Ljava/util/List;)L" + UNIFORM + ";", ((Type) lambda.bsmArgs[0]).getDescriptor(),
                "the lambda implements the rebuilt constructor interface, which returns 26.3's uniform base");
    }

    @Test
    @DisplayName("elsewhere the old base is 26.3's uniform base, so a cast or a weight read works on every entry")
    void otherReferencesNameTheUniformBase() throws Exception {
        Map<String, byte[]> out = transform("26.3", "1.21.1", oldMod());
        byte[] reader = out.get("mod/EntryReader");

        assertEquals(List.of(UNIFORM + ".weight:I"), BridgeHarness.fields(reader, "weightOf"));
        assertTrue(types(reader, "weightOf").stream().allMatch(UNIFORM::equals), types(reader, "weightOf").toString());
        assertEquals(List.of(BASE + ".simpleBuilder" + LegacyLootSingletonBridge.BUILDER_DESC),
                BridgeHarness.calls(reader, "explicit"), "a call written on the old base reaches the rebuilt one");
    }

    @Test
    @DisplayName("a builder of the mod's own extends the rebuilt builder, which still hands out condition and function lists")
    void oldBuilderExtendsTheRebuiltBuilder() throws Exception {
        Map<String, byte[]> out = transform("26.3", "1.21.1", oldMod());
        ClassNode builder = node(out.get("mod/CountedBuilder"));

        assertEquals(BASE + "$Builder", builder.superName);
        assertEquals(List.of(BASE + "$Builder.<init>()V"), BridgeHarness.calls(out.get("mod/CountedBuilder"), "<init>"));
        assertTrue(BridgeHarness.calls(out.get("mod/CountedBuilder"), "build")
                .containsAll(List.of("mod/CountedBuilder.getConditions()Ljava/util/List;",
                        "mod/CountedBuilder.getFunctions()Ljava/util/List;")));
    }

    @Test
    @DisplayName("the rebuilt base, its nested types and the list helpers are registered to travel with the mod")
    void rebuiltClassesAreRegistered() {
        transform("26.3", "1.21.1", Map.of());
        Map<String, byte[]> synthetics = RetromodTransformer.getInstance().getSyntheticClasses();
        for (String name : List.of(BASE, BASE + "$EntryConstructor", BASE + "$Builder", BASE + "$DummyBuilder",
                LegacyLootSerializers.class.getName().replace('.', '/'))) {
            assertNotNull(synthetics.get(name), name);
        }
        ClassNode base = node(synthetics.get(BASE));
        assertEquals(ENTRIES + "SingleEntryContainerBase", base.superName);
    }

    @Test
    @DisplayName("a mod built for 26.3 keeps its own subclass of the uniform base")
    void newModKeepsItsUniformSubclass() {
        Map<String, byte[]> out = transform("26.3", "26.3", Map.of("mod/NativeEntry", nativeEntry()));
        ClassNode entry = node(out.get("mod/NativeEntry"));

        assertEquals(UNIFORM, entry.superName);
        assertEquals(List.of(UNIFORM + ".<init>(IILjava/util/Optional;Ljava/util/Optional;)V"),
                BridgeHarness.calls(out.get("mod/NativeEntry"), "<init>"));
    }

    @Test
    @DisplayName("a mod that declares no version keeps its own uniform subclass, and its old subclass still moves")
    void unknownVersionMovesOnlyOldSubclasses() throws Exception {
        Map<String, byte[]> mod = new java.util.HashMap<>(oldMod());
        mod.put("mod/NativeEntry", nativeEntry());
        Map<String, byte[]> out = transform("26.3", null, mod);

        assertEquals(UNIFORM, node(out.get("mod/NativeEntry")).superName, "it named the uniform base itself");
        assertEquals(List.of(UNIFORM + ".<init>(IILjava/util/Optional;Ljava/util/Optional;)V"),
                BridgeHarness.calls(out.get("mod/NativeEntry"), "<init>"));
        assertEquals(BASE, node(out.get("mod/NamedItemEntry")).superName, "it named the removed base");
    }

    @Test
    @DisplayName("code outside an entry reads its function and condition lists from 26.3's holders")
    void outsideReadsGoThroughTheHolders() throws Exception {
        Map<String, byte[]> out = transform("26.3", "1.21.1", oldMod());
        String support = LegacyLootSerializers.class.getName().replace('.', '/');
        String read = "(Ljava/lang/Object;)Ljava/util/List;";

        assertEquals(List.of(support + ".entryFunctions" + read), BridgeHarness.calls(out.get("mod/EntryReader"), "functionsOf"));
        assertEquals(List.of(support + ".entryConditions" + read), BridgeHarness.calls(out.get("mod/EntryReader"), "conditionsOf"));
        assertEquals(List.of(support + ".entryConditions" + read), BridgeHarness.calls(out.get("mod/EntryReader"), "anyConditionsOf"));
        assertEquals(List.of("mod/NamedItemEntry.functions:Ljava/util/List;"),
                BridgeHarness.fields(out.get("mod/NamedItemEntry"), "ownFunctions"), "an entry keeps reading its own field");
    }

    @Test
    @DisplayName("26.2 still has the old base, so nothing changes there")
    void oldBaseHostsAreLeftAlone() throws Exception {
        Map<String, byte[]> out = transform("26.2", "1.21.1", oldMod());

        assertEquals(SINGLETON, node(out.get("mod/NamedItemEntry")).superName);
        assertEquals(List.of(SINGLETON + ".<init>" + LIST_CONSTRUCTOR), BridgeHarness.calls(out.get("mod/NamedItemEntry"), "<init>"));
        assertFalse(RetromodTransformer.getInstance().getSyntheticClasses().containsKey(BASE));
    }

    /** The mod's classes, transformed for {@code host} as a mod built for {@code source}. */
    private static Map<String, byte[]> transform(String host, String source, Map<String, byte[]> mod) {
        return BridgeHarness.transform(host, transformer -> {
            transformer.setModSourceVersion(source);
            // The builder bridge moves the old builder for vanilla's factories; the singleton bridge builds on it.
            if (LegacyLootSingletonBridge.hostRemovedSingletons()) LegacyLootBuilderBridge.register(transformer);
            LegacyLootSingletonBridge.register(transformer);
        }, mod);
    }

    /** Moonlight's entry shape, a reader that casts entries as Just Enough Resources does, and an own builder, compiled against 1.21.1. */
    private Map<String, byte[]> oldMod() throws Exception {
        Map<String, String> sources = new java.util.HashMap<>(host121());
        sources.put("mod/NamedItemEntry.java", """
                package mod;
                import com.mojang.datafixers.Products;
                import com.mojang.serialization.codecs.RecordCodecBuilder;
                import java.util.List;
                import java.util.function.Consumer;
                import net.minecraft.world.item.ItemStack;
                import net.minecraft.world.level.storage.loot.LootContext;
                import net.minecraft.world.level.storage.loot.entries.LootPoolEntryType;
                import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
                import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
                import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
                public class NamedItemEntry extends LootPoolSingletonContainer {
                    private final String item;
                    NamedItemEntry(String item, int weight, int quality, List<LootItemCondition> conditions, List<LootItemFunction> functions) {
                        super(weight, quality, conditions, functions);
                        this.item = item;
                    }
                    static Products.P4<RecordCodecBuilder.Mu<NamedItemEntry>, Integer, Integer, List<LootItemCondition>, List<LootItemFunction>>
                            fields(RecordCodecBuilder.Instance<NamedItemEntry> instance) {
                        return singletonFields(instance);
                    }
                    public static LootPoolSingletonContainer.Builder<?> named(String item) {
                        return simpleBuilder((weight, quality, conditions, functions) -> new NamedItemEntry(item, weight, quality, conditions, functions));
                    }
                    java.util.List<?> ownFunctions() { return functions; }
                    @Override public void createItemStack(Consumer<ItemStack> output, LootContext context) {}
                    @Override public LootPoolEntryType getType() { return null; }
                }""");
        sources.put("mod/EntryReader.java", """
                package mod;
                import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
                import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
                public class EntryReader {
                    static int weightOf(LootPoolEntryContainer entry) {
                        return entry instanceof LootPoolSingletonContainer ? ((LootPoolSingletonContainer) entry).weight : -1;
                    }
                    static LootPoolSingletonContainer.Builder<?> explicit(LootPoolSingletonContainer.EntryConstructor constructor) {
                        return LootPoolSingletonContainer.simpleBuilder(constructor);
                    }
                    static java.util.List<?> functionsOf(LootPoolSingletonContainer entry) { return entry.functions; }
                    static java.util.List<?> conditionsOf(LootPoolSingletonContainer entry) { return entry.conditions; }
                    static java.util.List<?> anyConditionsOf(LootPoolEntryContainer entry) { return entry.conditions; }
                }""");
        sources.put("mod/CountedBuilder.java", """
                package mod;
                import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
                import net.minecraft.world.level.storage.loot.entries.LootPoolSingletonContainer;
                public class CountedBuilder extends LootPoolSingletonContainer.Builder<CountedBuilder> {
                    @Override protected CountedBuilder getThis() { return this; }
                    @Override public LootPoolEntryContainer build() {
                        return NamedItemEntry.named("x").build() == null ? null : new NamedItemEntry("x", weight, quality, getConditions(), getFunctions());
                    }
                }""");
        return BridgeHarness.only(BridgeHarness.compile(dir, sources), "mod/");
    }

    /** The 1.21.1 shape of the loot entry classes a mod compiles against, with fields widened as access transformers do. */
    private static Map<String, String> host121() {
        String loot = "net/minecraft/world/level/storage/loot/";
        return Map.of(
                "com/mojang/datafixers/Products.java", """
                        package com.mojang.datafixers;
                        public interface Products { final class P4<F, T1, T2, T3, T4> {} }""",
                "com/mojang/serialization/codecs/RecordCodecBuilder.java", """
                        package com.mojang.serialization.codecs;
                        public final class RecordCodecBuilder<O, F> {
                            public static final class Mu<O> {}
                            public static final class Instance<O> {}
                        }""",
                "net/minecraft/world/item/ItemStack.java", "package net.minecraft.world.item; public final class ItemStack {}",
                loot + "LootContext.java", "package net.minecraft.world.level.storage.loot; public class LootContext {}",
                loot + "functions/LootItemFunction.java", "package net.minecraft.world.level.storage.loot.functions; public interface LootItemFunction {}",
                loot + "predicates/LootItemCondition.java", "package net.minecraft.world.level.storage.loot.predicates; public interface LootItemCondition {}",
                loot + "entries/LootPoolEntryType.java", "package net.minecraft.world.level.storage.loot.entries; public record LootPoolEntryType(Object codec) {}",
                loot + "entries/LootPoolEntryContainer.java", """
                        package net.minecraft.world.level.storage.loot.entries;
                        import java.util.List;
                        import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
                        public abstract class LootPoolEntryContainer {
                            public final List<LootItemCondition> conditions = List.of();
                            protected LootPoolEntryContainer(List<LootItemCondition> conditions) {}
                            public abstract LootPoolEntryType getType();
                            public abstract static class Builder<T extends Builder<T>> {
                                protected abstract T getThis();
                                protected List<LootItemCondition> getConditions() { return List.of(); }
                                public abstract LootPoolEntryContainer build();
                            }
                        }""",
                loot + "entries/LootPoolSingletonContainer.java", """
                        package net.minecraft.world.level.storage.loot.entries;
                        import com.mojang.datafixers.Products;
                        import com.mojang.serialization.codecs.RecordCodecBuilder;
                        import java.util.List;
                        import java.util.function.Consumer;
                        import net.minecraft.world.item.ItemStack;
                        import net.minecraft.world.level.storage.loot.LootContext;
                        import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
                        import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
                        public abstract class LootPoolSingletonContainer extends LootPoolEntryContainer {
                            public final int weight = Integer.parseInt("1");
                            public final List<LootItemFunction> functions = List.of();
                            protected LootPoolSingletonContainer(int weight, int quality, List<LootItemCondition> conditions, List<LootItemFunction> functions) {
                                super(conditions);
                            }
                            protected abstract void createItemStack(Consumer<ItemStack> output, LootContext context);
                            protected static <T extends LootPoolSingletonContainer> Products.P4<RecordCodecBuilder.Mu<T>, Integer, Integer,
                                    List<LootItemCondition>, List<LootItemFunction>> singletonFields(RecordCodecBuilder.Instance<T> instance) { return null; }
                            public static Builder<?> simpleBuilder(EntryConstructor constructor) { return null; }
                            public abstract static class Builder<T extends Builder<T>> extends LootPoolEntryContainer.Builder<T> {
                                protected int weight = 1;
                                protected int quality;
                                protected List<LootItemFunction> getFunctions() { return List.of(); }
                            }
                            @FunctionalInterface
                            public interface EntryConstructor {
                                LootPoolSingletonContainer build(int weight, int quality, List<LootItemCondition> conditions, List<LootItemFunction> functions);
                            }
                        }""");
    }

    /** {@code class NativeEntry extends UniformContainerBase}, as a mod built for 26.3 writes it. */
    private static byte[] nativeEntry() {
        String init = "(IILjava/util/Optional;Ljava/util/Optional;)V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER | ACC_ABSTRACT, "mod/NativeEntry", null, UNIFORM, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", init, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKESPECIAL, UNIFORM, "<init>", init, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static InvokeDynamicInsnNode indy(ClassNode node, String method) {
        for (AbstractInsnNode insn : node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow().instructions) {
            if (insn instanceof InvokeDynamicInsnNode call && ((Handle) call.bsmArgs[1]).getName().startsWith("lambda$")) return call;
        }
        throw new AssertionError(node.name + "." + method + " makes no lambda");
    }

    /** The types {@code method} checks or casts to. */
    private static List<String> types(byte[] bytes, String method) {
        List<String> types = new ArrayList<>();
        for (AbstractInsnNode insn : BridgeHarness.method(bytes, method).instructions) {
            if (insn instanceof TypeInsnNode type) types.add(type.desc);
        }
        return types;
    }
}
