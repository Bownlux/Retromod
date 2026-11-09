/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.embedded.LegacyLootEntryTypeSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.objectweb.asm.Opcodes.*;

/** Old loot reads Moonlight and Bookshelf make: entry type constants, getType(), context parameters and the schedule registry. */
class LegacyLootGapsTest {

    private static final String LOOT = "net/minecraft/world/level/storage/loot/";
    private static final String CONTEXT = LOOT + "LootContext";
    private static final String CONTEXT_KEY = "net/minecraft/util/context/ContextKey";
    private static final String GETTER = "(L" + CONTEXT_KEY + ";)Ljava/lang/Object;";
    private static final String TEST = "(L" + CONTEXT_KEY + ";)Z";
    private static final String STAND_IN = LegacyLootEntryTypeBridge.STAND_IN;
    private static final String TYPES = LegacyLootEntryTypeBridge.TYPES;
    private static final String MOD = "mod/LootDescriber";

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
    @DisplayName("a removed entry type constant and getType() on any entry reach the type helpers from 26.1")
    void entryTypeReadsReachTheHelpers() {
        byte[] out = transformEntryTypes("26.3").get(MOD);

        assertEquals(List.of(TYPES + ".ITEM()L" + STAND_IN + ";"), BridgeHarness.calls(out, "itemType"));
        assertEquals(List.of(TYPES + ".typeOf(L" + LegacyLootEntryTypeBridge.CONTAINER + ";)L" + STAND_IN + ";"),
                BridgeHarness.calls(out, "typeOf"));
    }

    @Test
    @DisplayName("1.21.11 still has the constants and getType(), so nothing changes there")
    void entryTypesStayBefore261() {
        byte[] out = transformEntryTypes("1.21.11").get(MOD);

        assertEquals(List.of(LegacyLootEntryTypeBridge.CONSTANTS_OWNER + ".ITEM:L" + LegacyLootEntryTypeBridge.OLD_TYPE + ";"),
                BridgeHarness.fields(out, "itemType"));
        assertTrue(BridgeHarness.calls(out, "typeOf").get(0).contains(".getType()"), BridgeHarness.calls(out, "typeOf").toString());
    }

    @Test
    @DisplayName("a constant and a vanilla entry give the same type, and an old entry gives its own")
    void typesMatchTheRegisteredCodecs() throws Exception {
        Path libraries = Path.of(System.getProperty("user.home"), "Library/Application Support/PrismLauncher/libraries");
        Path dfu = libraries.resolve("com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar");
        Path fastutil = libraries.resolve("it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar");
        assumeTrue(Files.isRegularFile(dfu) && Files.isRegularFile(fastutil), "no local DataFixerUpper 10; it is local test material");
        Map<String, byte[]> classes = new HashMap<>(BridgeHarness.compile(dir, hostWithRegistry(), dfu.toString()));
        classes.put(STAND_IN, WorldgenTypeBridgeSynthetic.generateWrapper(STAND_IN));
        classes.put(TYPES, LegacyLootEntryTypeBridge.generateTypes());
        String support = LegacyLootEntryTypeSupport.class.getName().replace('.', '/');
        try (InputStream in = LegacyLootEntryTypeSupport.class.getResourceAsStream("/" + support + ".class")) {
            classes.put(support, in.readAllBytes());
        }
        ClassLoader loader = new LegacyKeyDispatchCodecBridgeTest.ChildFirstLoader(
                new URL[] {dfu.toUri().toURL(), fastutil.toUri().toURL()}, classes);
        Class<?> types = loader.loadClass(TYPES.replace('/', '.'));
        Class<?> container = loader.loadClass(LegacyLootEntryTypeBridge.CONTAINER.replace('/', '.'));

        Object item = types.getMethod("ITEM").invoke(null);
        assertSame(item, types.getMethod("ITEM").invoke(null), "a constant reads as one object");
        Object vanilla = loader.loadClass("net.minecraft.world.level.storage.loot.entries.LootItem").getConstructor().newInstance();
        assertSame(item, types.getMethod("typeOf", container).invoke(null, vanilla), "a vanilla entry's type is the constant");
        assertSame(loader.loadClass("net.minecraft.world.level.storage.loot.entries.LootItem").getField("CODEC").get(null),
                item.getClass().getMethod("codec").invoke(item), "the type wraps the registered codec");

        // The type an old mod registered is its own copy of the stand-in, which its getType() returns.
        Class<?> mapCodec = loader.loadClass("com.mojang.serialization.MapCodec");
        Object own = loader.loadClass(STAND_IN.replace('/', '.')).getConstructor(mapCodec)
                .newInstance(mapCodec.getMethod("unit", Object.class).invoke(null, "old"));
        Class<?> oldEntry = loader.loadClass("mod.OldEntry");
        oldEntry.getField("TYPE").set(null, own);
        assertSame(own, types.getMethod("typeOf", container).invoke(null, oldEntry.getConstructor().newInstance()),
                "an old entry's own type answers");
    }

    @Test
    @DisplayName("each loot context parameter call takes the name the indexed host has, offline as in the game")
    void contextParameterCallsFollowTheHost() throws Exception {
        RetromodTransformer host263 = indexed("263.jar", "getOptional", "hasParameter");
        assertEquals(Map.of("getParam", "requireParameter", "getParamOrNull", "getOptional",
                        "getParameter", "requireParameter", "getOptionalParameter", "getOptional", "hasParam", "hasParameter"),
                contextRenames(host263), "the getters that threw for a missing parameter keep throwing through the helper");
        assertEquals(List.of(LegacyMemberRenames.PARAMETERS + ".requireParameter" + LegacyMemberRenames.REQUIRE_PARAMETER),
                BridgeHarness.calls(host263.transformClass(parameterReader(), "mod/ParameterReader"), "origin"),
                "the instance call becomes the static helper call, with the context as its first argument");
        new ClassReader(host263.getSyntheticClasses().get(LegacyMemberRenames.PARAMETERS))
                .accept(new CheckClassAdapter(new ClassWriter(0), true), 0);

        RetromodTransformer host262 = indexed("262.jar", "getParameter", "getOptionalParameter", "hasParameter");
        assertEquals(Map.of("getParam", "getParameter", "getParamOrNull", "getOptionalParameter", "hasParam", "hasParameter"),
                contextRenames(host262));

        assertEquals(Map.of(), contextRenames(indexed("1211.jar", "getParam", "getParamOrNull", "hasParam")),
                "1.21.1 still has the old names");
    }

    @Test
    @DisplayName("the renames apply from the first host that renamed a loot context call")
    void renamesApplyFrom1212() {
        LegacyMemberRenames shim = new LegacyMemberRenames();
        assertTrue(com.retromod.shim.ShimRegistry.isAvailableOnHost(shim, "1.21.2"));
        assertFalse(com.retromod.shim.ShimRegistry.isAvailableOnHost(shim, "1.21.1"));
    }

    @Test
    @DisplayName("a removed registry and its key read as empty stand-ins on a host without them")
    void removedScheduleRegistryReadsAsStandIns() throws Exception {
        RetromodTransformer removed = indexedRegistries("removed.jar", false);
        new LegacyRemovedRegistryShim().registerRedirects(removed);
        byte[] out = removed.transformClass(scheduleReader(), "mod/ScheduleReader");
        String getters = LegacyRemovedRegistryShim.GETTERS;
        assertEquals(List.of(getters + ".SCHEDULE()Ljava/lang/Object;"), BridgeHarness.calls(out, "registry"));
        assertEquals(List.of(getters + ".SCHEDULE_KEY()Ljava/lang/Object;"), BridgeHarness.calls(out, "key"));
        assertEquals(List.of(LegacyRemovedRegistryShim.SUPPORT + ".registry(Ljava/lang/String;)Ljava/lang/Object;"),
                BridgeHarness.calls(removed.getSyntheticClasses().get(getters), "SCHEDULE"));

        RetromodTransformer kept = indexedRegistries("kept.jar", true);
        new LegacyRemovedRegistryShim().registerRedirects(kept);
        assertEquals(List.of(LegacyRemovedRegistryShim.BUILT_IN + ".SCHEDULE:Lnet/minecraft/core/Registry;"),
                BridgeHarness.fields(kept.transformClass(scheduleReader(), "mod/ScheduleReader"), "registry"),
                "a host that still has the registry keeps the read");
    }

    @Test
    @DisplayName("on 26.3 a stand-in yields to the feature type rename and the rebuilt armor material registry, whichever registers first")
    void standInsYieldToTheRealBridges() throws Exception {
        // The feature and armor bridges read the target version where the host's classes cannot be read.
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        String registry = "Lnet/minecraft/core/Registry;";
        String key = "Lnet/minecraft/resources/ResourceKey;";
        String builtIn = LegacyRemovedRegistryShim.BUILT_IN;
        String keys = LegacyRemovedRegistryShim.KEYS;
        String armor = LegacyArmorMaterialRecordBridge.CALLS;
        // Farmer's Delight Refabricated: its own feature classes mark the jar, and RegUtils registers into FEATURE.
        byte[] fdRegistration = registryReader("fdshape/RegUtils", new String[]{"features", builtIn, registry, "FEATURE"});
        LegacyFeatureBridge.markOldModelJar(List.of(fdRegistration, featureSubclass("fdshape/RockFeature")));
        // Bookshelf's codec helpers read every built-in registry, from a jar with no feature class.
        byte[] bookshelf = registryReader("bookshelfshape/MapCodecs",
                new String[]{"features", builtIn, registry, "FEATURE"},
                new String[]{"schedules", builtIn, registry, "SCHEDULE"},
                new String[]{"armor", builtIn, registry, "ARMOR_MATERIAL"});
        // Quark reads the armor material key by name, and Puzzles Lib on Fabric by its intermediary id.
        byte[] quark = registryReader("quarkshape/BackpackItem",
                new String[]{"armorKey", keys, key, "ARMOR_MATERIAL"},
                new String[]{"intermediaryKey", keys, key, "field_48977"},
                new String[]{"intermediaryRegistry", builtIn, registry, "field_48976"});

        for (boolean standInFirst : List.of(true, false)) {
            String order = standInFirst ? "stand-in first" : "stand-in last";
            RetromodTransformer t = indexedRegistries("host263-" + standInFirst + ".jar", false);
            if (standInFirst) new LegacyRemovedRegistryShim().registerRedirects(t);
            LegacyFeatureBridge.register(t);
            LegacyArmorMaterialRecordBridge.register(t);
            if (!standInFirst) new LegacyRemovedRegistryShim().registerRedirects(t);

            byte[] fd = t.transformClass(fdRegistration, "fdshape/RegUtils");
            assertEquals(List.of(builtIn + ".FEATURE_TYPE:" + registry), BridgeHarness.fields(fd, "features"),
                    order + ": an old-model jar registers its features as feature types");
            assertEquals(List.of(), BridgeHarness.calls(fd, "features"), order);

            byte[] shelf = t.transformClass(bookshelf, "bookshelfshape/MapCodecs");
            assertEquals(List.of(builtIn + ".FEATURE_TYPE:" + registry), BridgeHarness.fields(shelf, "features"),
                    order + ": a jar with no feature class reads the renamed registry too");
            assertEquals(List.of(LegacyRemovedRegistryShim.GETTERS + ".SCHEDULE()Ljava/lang/Object;"),
                    BridgeHarness.calls(shelf, "schedules"), order + ": a registry with no replacement reads as the stand-in");
            assertEquals(List.of(armor + ".armorMaterialRegistry()" + registry), BridgeHarness.calls(shelf, "armor"),
                    order + ": the rebuilt armor material registry wins over the stand-in");

            byte[] backpack = t.transformClass(quark, "quarkshape/BackpackItem");
            assertEquals(List.of(armor + ".armorMaterialKey()" + key), BridgeHarness.calls(backpack, "armorKey"), order);
            assertEquals(List.of(armor + ".armorMaterialKey()" + key), BridgeHarness.calls(backpack, "intermediaryKey"),
                    order + ": the intermediary key id reaches the rebuilt registry's key");
            assertEquals(List.of(armor + ".armorMaterialRegistry()" + registry), BridgeHarness.calls(backpack, "intermediaryRegistry"),
                    order + ": the intermediary registry id reaches the rebuilt registry");
        }
    }

    @Test
    @DisplayName("a stand-in yields to an accessor redirect of the same field, whichever registers first")
    void standInYieldsToAnAccessorRedirect() throws Exception {
        String builtIn = LegacyRemovedRegistryShim.BUILT_IN;
        for (boolean standInFirst : List.of(true, false)) {
            String order = standInFirst ? "stand-in first" : "stand-in last";
            RetromodTransformer t = indexedRegistries("accessor-" + standInFirst + ".jar", false);
            if (standInFirst) new LegacyRemovedRegistryShim().registerRedirects(t);
            t.registerFieldAccessorRedirect(builtIn, "SCHEDULE", "schedule", "()Lnet/minecraft/core/Registry;", null, null);
            if (!standInFirst) new LegacyRemovedRegistryShim().registerRedirects(t);

            byte[] out = t.transformClass(scheduleReader(), "mod/ScheduleReader");
            assertEquals(List.of(builtIn + ".SCHEDULE:Lnet/minecraft/core/Registry;"), BridgeHarness.fields(out, "registry"),
                    order + ": the static read is not the accessor's to change, and the stand-in no longer claims it");
            assertEquals(List.of(), BridgeHarness.calls(out, "registry"), order + ": no call to the stand-in getter");
        }
    }

    @Test
    @DisplayName("no removed registry is also renamed by the 26.3 key renames, which the stand-in guard does not see")
    void removedRegistriesAreNotRenamedRegistries() {
        java.util.Set<String> renamed = new java.util.TreeSet<>();
        for (Map<String, String> table : List.of(LegacyRegistryKeyRenames263.REGISTRY_KEYS,
                LegacyRegistryKeyRenames263.BUILT_IN_REGISTRIES, LegacyFeatureBridge.OLD_MODEL_KEYS)) {
            renamed.addAll(table.keySet());
            renamed.addAll(table.values());
        }
        for (String[] removed : LegacyRemovedRegistryShim.REMOVED) {
            assertFalse(renamed.contains(removed[0]), removed[0] + " is both a removed registry and a renamed one; "
                    + "the class-level rename runs apart from the field redirects, so the stand-in would never yield to it");
        }
    }

    @Test
    @DisplayName("a read-only bridge leaves a write as written, for an entry's list and for a removed registry")
    void readOnlyBridgesLeaveWritesAlone() throws Exception {
        String container = LOOT + "entries/LootPoolEntryContainer";
        RetromodTransformer t = indexedRegistries("writes.jar", false);
        t.registerExactTypedFieldStaticBridge(container, "conditions", "Ljava/util/List;", "support/Lists",
                "read", "(Ljava/lang/Object;)Ljava/util/List;", null, null);
        new LegacyRemovedRegistryShim().registerRedirects(t);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/Writer", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "write", "(L" + container + ";Ljava/util/List;Lnet/minecraft/core/Registry;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(PUTFIELD, container, "conditions", "Ljava/util/List;");
        mv.visitVarInsn(ALOAD, 2);
        mv.visitFieldInsn(PUTSTATIC, LegacyRemovedRegistryShim.BUILT_IN, "SCHEDULE", "Lnet/minecraft/core/Registry;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = t.transformClass(cw.toByteArray(), "mod/Writer");
        assertEquals(List.of(container + ".conditions:Ljava/util/List;",
                        LegacyRemovedRegistryShim.BUILT_IN + ".SCHEDULE:Lnet/minecraft/core/Registry;"),
                BridgeHarness.fields(out, "write"));
        assertEquals(List.of(), BridgeHarness.calls(out, "write"), "no call to a setter the bridges do not have");
    }

    @Test
    @DisplayName("the item sub-predicate registry reads as the data component predicate registry that replaced it")
    void subPredicateRegistryFollowsItsRename() throws Exception {
        ClassWriter host = new ClassWriter(0);
        host.visit(V21, ACC_PUBLIC, LegacyRemovedRegistryShim.BUILT_IN, null, "java/lang/Object", null);
        host.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "DATA_COMPONENT_PREDICATE_TYPE", "Lnet/minecraft/core/Registry;", null, null).visitEnd();
        host.visitEnd();
        RetromodTransformer renamed = indexedHost("renamed.jar", Map.of(LegacyRemovedRegistryShim.BUILT_IN, host.toByteArray()));
        new LegacyMemberRenames().registerRedirects(renamed);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/PredicateReader", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "registry", "()Lnet/minecraft/core/Registry;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, LegacyRemovedRegistryShim.BUILT_IN, "ITEM_SUB_PREDICATE_TYPE", "Lnet/minecraft/core/Registry;");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        assertEquals(List.of(LegacyRemovedRegistryShim.BUILT_IN + ".DATA_COMPONENT_PREDICATE_TYPE:Lnet/minecraft/core/Registry;"),
                BridgeHarness.fields(renamed.transformClass(cw.toByteArray(), "mod/PredicateReader"), "registry"));
    }

    @Test
    @DisplayName("the registry codec factories that 26.3 moved reach the new ones and cast back to the old types")
    void registryCodecFactoriesReachTheMovedOnes() {
        String old = "net/minecraft/resources/";
        String moved = "net/minecraft/core/registries/codec/";
        String key = "Lnet/minecraft/resources/ResourceKey;";
        String codec = "Lcom/mojang/serialization/Codec;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/Codecs", null, "java/lang/Object", null);
        String[][] calls = {{"fixed", old + "RegistryFixedCodec", "create", "(" + key + ")L" + old + "RegistryFixedCodec;", "1"},
                {"file", old + "RegistryFileCodec", "create", "(" + key + codec + ")L" + old + "RegistryFileCodec;", "2"},
                {"list", "net/minecraft/core/RegistryCodecs", "homogeneousList", "(" + key + ")" + codec, "1"}};
        for (String[] call : calls) {
            int arguments = Integer.parseInt(call[4]);
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, call[0], call[3], null, null);
            mv.visitCode();
            for (int slot = 0; slot < arguments; slot++) mv.visitVarInsn(ALOAD, slot);
            mv.visitMethodInsn(INVOKESTATIC, call[1], call[2], call[3], false);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        byte[] out = BridgeHarness.transform("26.3", transformer -> {
            for (String name : new String[] {"RegistryFixedCodec", "RegistryFileCodec"}) {
                transformer.registerClassRedirect(old + name, moved + name);
            }
            transformer.registerClassRedirect("net/minecraft/core/RegistryCodecs", moved + "RegistryCodecs");
            Mc26_2To26_3CoreMoves.registerRegistryCodecFactories(transformer);
        }, Map.of("mod/Codecs", cw.toByteArray())).get("mod/Codecs");

        assertEquals(List.of(moved + "RegistryFixedCodec.create(" + key + ")" + codec), BridgeHarness.calls(out, "fixed"));
        assertEquals(List.of(moved + "RegistryCodecs.holder(" + key + codec + ")" + codec), BridgeHarness.calls(out, "file"));
        assertEquals(List.of(moved + "RegistryCodecs.holderSet(" + key + ")" + codec), BridgeHarness.calls(out, "list"));
        assertTrue(types(out, "fixed").contains(moved + "RegistryFixedCodec"), "the result is cast back to the old type");
    }

    /** The types {@code method} casts to. */
    private static List<String> types(byte[] bytes, String method) {
        List<String> types = new java.util.ArrayList<>();
        for (org.objectweb.asm.tree.AbstractInsnNode insn : BridgeHarness.method(bytes, method).instructions) {
            if (insn instanceof org.objectweb.asm.tree.TypeInsnNode type) types.add(type.desc);
        }
        return types;
    }

    /** Old reads of entry types, as a 1.21.1 loot describer such as Bookshelf's makes them, transformed for {@code host}. */
    private static Map<String, byte[]> transformEntryTypes(String host) {
        return BridgeHarness.transform(host, transformer -> {
            transformer.setModSourceVersion("1.21.1");
            if (RetromodVersion.compareMcVersions(host, "26.1") >= 0) WorldgenTypeBridgeSynthetic.registerPre26_2(transformer);
            LegacyLootEntryTypeBridge.register(transformer);
        }, Map.of(MOD, describer()));
    }

    private static byte[] describer() {
        String type = "L" + LegacyLootEntryTypeBridge.OLD_TYPE + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, MOD, null, "java/lang/Object", null);
        MethodVisitor item = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "itemType", "()" + type, null, null);
        item.visitCode();
        item.visitFieldInsn(GETSTATIC, LegacyLootEntryTypeBridge.CONSTANTS_OWNER, "ITEM", type);
        item.visitInsn(ARETURN);
        item.visitMaxs(0, 0);
        item.visitEnd();
        MethodVisitor typeOf = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "typeOf", "(L" + LegacyLootEntryTypeBridge.CONTAINER + ";)" + type, null, null);
        typeOf.visitCode();
        typeOf.visitVarInsn(ALOAD, 0);
        typeOf.visitMethodInsn(INVOKEVIRTUAL, LegacyLootEntryTypeBridge.CONTAINER, "getType", "()" + type, false);
        typeOf.visitInsn(ARETURN);
        typeOf.visitMaxs(0, 0);
        typeOf.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A 26.1 registry with LootItem's codec under minecraft:item, compiled against real DataFixerUpper. */
    private static Map<String, String> hostWithRegistry() {
        return Map.of(
                "net/minecraft/resources/Identifier.java", """
                        package net.minecraft.resources;
                        public record Identifier(String path) {
                            public static Identifier withDefaultNamespace(String path) { return new Identifier(path); }
                        }""",
                "net/minecraft/core/registries/BuiltInRegistries.java", """
                        package net.minecraft.core.registries;
                        public final class BuiltInRegistries {
                            public static final EntryTypes LOOT_POOL_ENTRY_TYPE = new EntryTypes();
                            public static final class EntryTypes {
                                public Object getValue(net.minecraft.resources.Identifier id) {
                                    return id.path().equals("item") ? net.minecraft.world.level.storage.loot.entries.LootItem.CODEC : null;
                                }
                            }
                        }""",
                "net/minecraft/world/level/storage/loot/entries/LootPoolEntryContainer.java", """
                        package net.minecraft.world.level.storage.loot.entries;
                        public abstract class LootPoolEntryContainer {
                            public abstract com.mojang.serialization.MapCodec<?> codec();
                        }""",
                "net/minecraft/world/level/storage/loot/entries/LootItem.java", """
                        package net.minecraft.world.level.storage.loot.entries;
                        public class LootItem extends LootPoolEntryContainer {
                            public static final com.mojang.serialization.MapCodec<LootItem> CODEC = com.mojang.serialization.MapCodec.unit(LootItem::new);
                            @Override public com.mojang.serialization.MapCodec<?> codec() { return CODEC; }
                        }""",
                "mod/OldEntry.java", """
                        package mod;
                        public class OldEntry extends net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer {
                            public static com.mojang.serialization.MapCodec<?> TYPE;
                            public com.mojang.serialization.MapCodec<?> getType() { return TYPE; }
                            @Override public com.mojang.serialization.MapCodec<?> codec() { throw new IllegalStateException("an old entry names its type"); }
                        }""");
    }

    /** The context renames {@code transformer} registered, old name to new. */
    private static Map<String, String> contextRenames(RetromodTransformer transformer) {
        new LegacyMemberRenames().registerRedirects(transformer);
        Map<String, String> renames = new HashMap<>();
        transformer.getMethodRedirects().forEach((key, target) -> {
            if (key.owner().equals(CONTEXT) && (key.desc().equals(GETTER) || key.desc().equals(TEST))) {
                renames.put(key.name(), target.name());
            }
        });
        return renames;
    }

    /** A fresh transformer indexing a host jar whose LootContext declares {@code methods}. */
    private RetromodTransformer indexed(String jar, String... methods) throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V21, ACC_PUBLIC, CONTEXT, null, "java/lang/Object", null);
        for (String method : methods) {
            cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, method, method.startsWith("has") ? TEST : GETTER, null, null).visitEnd();
        }
        cw.visitEnd();
        return indexedHost(jar, Map.of(CONTEXT, cw.toByteArray()));
    }

    /** A fresh transformer indexing a host whose built-in registries do or do not still have SCHEDULE. */
    private RetromodTransformer indexedRegistries(String jar, boolean schedule) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        for (String owner : List.of(LegacyRemovedRegistryShim.BUILT_IN, LegacyRemovedRegistryShim.KEYS)) {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(V21, ACC_PUBLIC, owner, null, "java/lang/Object", null);
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "ACTIVITY", "Ljava/lang/Object;", null, null).visitEnd();
            if (schedule) cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "SCHEDULE", "Ljava/lang/Object;", null, null).visitEnd();
            cw.visitEnd();
            classes.put(owner, cw.toByteArray());
        }
        return indexedHost(jar, classes);
    }

    private RetromodTransformer indexedHost(String jar, Map<String, byte[]> classes) throws Exception {
        Path file = dir.resolve(jar);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey() + ".class"));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        Constructor<RetromodTransformer> constructor = RetromodTransformer.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RetromodTransformer transformer = constructor.newInstance();
        transformer.initFuzzyResolver(file);
        return transformer;
    }

    /** {@code BuiltInRegistries.SCHEDULE} and {@code Registries.SCHEDULE}, read as a 1.21.1 mod reads them. */
    private static byte[] scheduleReader() {
        return registryReader("mod/ScheduleReader",
                new String[]{"registry", LegacyRemovedRegistryShim.BUILT_IN, "Lnet/minecraft/core/Registry;", "SCHEDULE"},
                new String[]{"key", LegacyRemovedRegistryShim.KEYS, "Lnet/minecraft/resources/ResourceKey;", "SCHEDULE"});
    }

    /** A class with one static method per read, {method, owner, descriptor, field}, returning that static field. */
    private static byte[] registryReader(String name, String[]... reads) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        for (String[] read : reads) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, read[0], "()" + read[2], null, null);
            mv.visitCode();
            mv.visitFieldInsn(Opcodes.GETSTATIC, read[1], read[3], read[2]);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mod class extending the old {@code Feature}, which marks its jar as written for the old feature model. */
    private static byte[] featureSubclass(String name) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, name, null, LegacyFeatureBridge.FEATURE, null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code context.getParam(key)}, as a 1.21.1 mod calls it once the parameter type became a context key. */
    private static byte[] parameterReader() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/ParameterReader", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "origin", "(L" + CONTEXT + ";L" + CONTEXT_KEY + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, CONTEXT, "getParam", GETTER, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
