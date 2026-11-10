/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.LegacyValueIoOverrideAdapter.Context;
import com.retromod.shim.common.LegacyValueIoOverrideAdapter.Names;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.21.6 moved block entity and entity saving to ValueOutput and ValueInput. A 1.21.1 mod's
 * {@code saveAdditional(CompoundTag, Provider)} and {@code addAdditionalSaveData(CompoundTag)}
 * overrides were never called again, so Cobblemon, Farmer's Delight and Cataclysm lost their block
 * entity and mob data on 26.3, and their super calls named methods the host no longer has.
 *
 * <p>The tests run the adapted mod classes against stand-ins of a 26.x host and check what the
 * 1.21.1 host did: the old body writes into the saved tag after its parent's data, reads the whole
 * tag back with the registries of the load, and a missing key reads as 0.
 */
class LegacyValueIoOverrideAdapterTest {

    private static final String STAND_INS = "com/retromod/shim/common/ValueIoStandIns$";
    private static final String BLOCK_ENTITY = LegacyValueIoOverrideAdapter.BLOCK_ENTITY;
    private static final String CONTAINER = "net/minecraft/world/level/block/entity/BaseContainerBlockEntity";
    private static final String ENTITY = LegacyValueIoOverrideAdapter.ENTITY;
    private static final String MOB = "net/minecraft/world/entity/Mob";

    /** Each stand-in's Minecraft name. The 1.21.1 classes share the names of their 26.x successors. */
    private static final Map<String, String> HOST_NAMES = new LinkedHashMap<>();
    /** The mods' class names. */
    private static final Map<String, String> MOD_NAMES = Map.of(
            STAND_INS + "CounterChest", "mod/CounterChest",
            STAND_INS + "BigCounterChest", "mod/BigCounterChest",
            STAND_INS + "Golem", "mod/Golem");
    /** Superclasses of the host and mod classes, which the adapter has to prove. */
    private static final Map<String, String> SUPERS = Map.of(
            "mod/BigCounterChest", "mod/CounterChest",
            "mod/CounterChest", CONTAINER,
            CONTAINER, BLOCK_ENTITY,
            "mod/Golem", MOB,
            MOB, ENTITY,
            "mod/mixin/ChestMixin", BLOCK_ENTITY);

    static {
        String[][] names = {
                {"CompoundTag", "net/minecraft/nbt/CompoundTag"},
                {"Codec", "com/mojang/serialization/Codec"},
                {"MapCodec", "com/mojang/serialization/MapCodec"},
                {"Provider", "net/minecraft/core/HolderLookup$Provider"},
                {"RegistryAccess", "net/minecraft/core/RegistryAccess"},
                {"Frozen", "net/minecraft/core/RegistryAccess$Frozen"},
                {"Registry", "net/minecraft/core/Registry"},
                {"BuiltInRegistries", "net/minecraft/core/registries/BuiltInRegistries"},
                {"Level", "net/minecraft/world/level/Level"},
                {"ProblemReporter", "net/minecraft/util/ProblemReporter"},
                {"ValueOutput", "net/minecraft/world/level/storage/ValueOutput"},
                {"ValueInput", "net/minecraft/world/level/storage/ValueInput"},
                {"TagValueOutput", "net/minecraft/world/level/storage/TagValueOutput"},
                {"TagValueInput", "net/minecraft/world/level/storage/TagValueInput"},
                {"StoringOutput", "net/minecraft/world/level/storage/StoringOutput"},
                {"BlockEntity", BLOCK_ENTITY},
                {"BaseContainerBlockEntity", CONTAINER},
                {"Entity", ENTITY},
                {"Mob", MOB},
                {"BlockEntityType", "net/minecraft/world/level/block/entity/BlockEntityType"},
                {"FoodData", "net/minecraft/world/food/FoodData"},
                {"OldFoodData", "net/minecraft/world/food/FoodData"},
                {"OldBlockEntity", BLOCK_ENTITY},
                {"OldBaseContainerBlockEntity", CONTAINER},
                {"OldEntity", ENTITY},
                {"OldMob", MOB}};
        for (String[] name : names) HOST_NAMES.put(STAND_INS + name[0], name[1]);
    }

    /** The 26.x stand-ins a running mod sees. */
    private static final List<String> HOST_CLASSES = List.of("CompoundTag", "Codec", "MapCodec", "Provider",
            "RegistryAccess", "Frozen", "Registry", "BuiltInRegistries", "Level", "ProblemReporter", "ValueOutput",
            "ValueInput", "TagValueOutput", "TagValueInput", "StoringOutput", "BlockEntity",
            "BaseContainerBlockEntity", "Entity", "Mob", "BlockEntityType", "FoodData");

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.clearJarClassBytesProvider();
        transformer.setModSourceVersion(null);
    }

    @Test
    @DisplayName("the host's save runs the old saveAdditional after its parent's, with its level's registries")
    void blockEntitySaveRunsTheOldOverride() throws Exception {
        Host host = host(adapt("CounterChest", true));
        Object registries = host.registries();
        Object chest = host.newInstance("mod/CounterChest");
        set(chest, "items", 3);
        set(chest, "count", 7);
        set(chest, "level", host.level(registries));

        Object tag = call(chest, "saveWithoutMetadata", registries);

        assertEquals(List.of("Items", "Count"), List.copyOf(values(tag).keySet()),
                "the parent's data first, then the mod's, as the old super call wrote it");
        assertEquals(7, values(tag).get("Count"));
        assertEquals(3, values(tag).get("Items"));
        assertSame(registries, get(chest, "savedWith"));
    }

    @Test
    @DisplayName("the host's load runs the old loadAdditional with the whole tag and the load's registries")
    void blockEntityLoadRunsTheOldOverride() throws Exception {
        Host host = host(adapt("CounterChest", true));
        Object registries = host.registries();
        Object tag = host.tag(Map.of("Items", 3, "Count", 7));
        Object chest = host.newInstance("mod/CounterChest");

        call(chest, "loadWithComponents", host.input(registries, tag));

        assertEquals(3, get(chest, "items"), "the parent read its own data through the repaired super call");
        assertEquals(7, get(chest, "count"));
        assertSame(registries, get(chest, "loadedWith"));
    }

    @Test
    @DisplayName("a missing key still reads as 0 in the old body")
    void missingDataReadsAsBefore() throws Exception {
        Host host = host(adapt("CounterChest", true));
        Object chest = host.newInstance("mod/CounterChest");
        set(chest, "count", 9);

        call(chest, "loadWithComponents", host.input(host.registries(), host.tag(Map.of())));

        assertEquals(0, get(chest, "count"));
    }

    @Test
    @DisplayName("an output with no compound behind it receives the saved keys")
    void anyOutputReceivesTheSavedKeys() throws Exception {
        Host host = host(adapt("CounterChest", true));
        Object chest = host.newInstance("mod/CounterChest");
        set(chest, "items", 2);
        set(chest, "count", 5);
        set(chest, "level", host.level(host.registries()));
        Object output = host.newInstance("net/minecraft/world/level/storage/StoringOutput");

        call(chest, "saveInto", output);

        assertEquals(Map.of("Items", 2, "Count", 5), get(output, "stored"));
    }

    @Test
    @DisplayName("a block entity with no level saves with the built-in registries")
    void levelLessBlockEntityUsesBuiltInRegistries() throws Exception {
        Host host = host(adapt("CounterChest", true));
        Object chest = host.newInstance("mod/CounterChest");

        call(chest, "saveWithoutMetadata", host.registries());

        Object frozen = get(chest, "savedWith");
        assertEquals("net.minecraft.core.RegistryAccess$Frozen", frozen.getClass().getName());
        Object builtIns = host.load("net/minecraft/core/registries/BuiltInRegistries").getField("REGISTRY").get(null);
        assertSame(builtIns, get(frozen, "source"));
    }

    @Test
    @DisplayName("an entity's old save and read overrides run inside the host's own")
    void entitySaveAndLoadRunTheOldOverrides() throws Exception {
        Host host = host(adapt("Golem", true));
        Object registries = host.registries();
        Object golem = host.newInstance("mod/Golem");
        set(golem, "level", host.level(registries));
        set(golem, "health", 20);
        set(golem, "anger", 5);
        Object output = host.load("net/minecraft/world/level/storage/TagValueOutput")
                .getConstructor(host.load("net/minecraft/core/HolderLookup$Provider")).newInstance(registries);

        call(golem, "saveWithoutId", output);

        Object tag = call(output, "buildResult");
        assertEquals(Map.of("Air", 300, "Health", 20, "Anger", 5), values(tag));
        Object copy = host.newInstance("mod/Golem");
        set(copy, "level", host.level(registries));
        call(copy, "load", host.input(registries, tag));
        assertEquals(20, get(copy, "health"));
        assertEquals(5, get(copy, "anger"));
    }

    @Test
    @DisplayName("entity overrides of mods built before 1.20.5 stay as they are")
    void olderEntityOverridesAreLeftAlone() throws Exception {
        byte[] golem = mod("Golem");

        assertArrayEquals(golem, LegacyValueIoOverrideAdapter.apply(golem, context(false)));
        assertFalse(LegacyValueIoOverrideAdapter.bridgesEntities("1.20.1"));
        assertTrue(LegacyValueIoOverrideAdapter.bridgesEntities("1.21.1"));
        assertTrue(LegacyValueIoOverrideAdapter.bridgesEntities(null), "an unknown version is bridged");
    }

    @Test
    @DisplayName("a super call that reaches the mod's own old method keeps calling it")
    void superCallToTheModsOwnMethodIsKept() throws Exception {
        ClassNode big = node(adapt("BigCounterChest", true));

        assertTrue(calls(method(big, "saveAdditional", oldBlockEntitySave())).contains(
                "mod/CounterChest.saveAdditional" + oldBlockEntitySave()));
        assertNotNull(method(big, "saveAdditional", "(Lnet/minecraft/world/level/storage/ValueOutput;)V"),
                "the subclass still gets its own new override");

        Host host = new Host(Map.of("mod/CounterChest", adapt("CounterChest", true),
                "mod/BigCounterChest", adapt("BigCounterChest", true)));
        Object chest = host.newInstance("mod/BigCounterChest");
        set(chest, "items", 1);
        set(chest, "count", 2);
        set(chest, "size", 27);
        set(chest, "level", host.level(host.registries()));
        Object tag = call(chest, "saveWithoutMetadata", host.registries());
        assertEquals(List.of("Items", "Count", "Size"), List.copyOf(values(tag).keySet()), "each body ran once, in order");
    }

    @Test
    @DisplayName("a mixin gains the new method only for targets that do not declare it")
    void mixinOverridesFollowTheirTargets() {
        String newSave = "(Lnet/minecraft/world/level/storage/ValueOutput;)V";
        byte[] mixin = chestMixin(false);

        byte[] intoOwnSave = LegacyValueIoOverrideAdapter.apply(mixin, context(true, (owner, name, desc) -> true));
        byte[] intoInherited = LegacyValueIoOverrideAdapter.apply(mixin, context(true, (owner, name, desc) -> false));
        byte[] unknownTarget = LegacyValueIoOverrideAdapter.apply(mixin, context(true, (owner, name, desc) -> null));
        byte[] annotated = LegacyValueIoOverrideAdapter.apply(chestMixin(true), context(true, (owner, name, desc) -> false));

        assertNull(method(node(intoOwnSave), "saveAdditional", newSave), "it would replace the target's own save");
        assertNotNull(method(node(intoInherited), "saveAdditional", newSave));
        assertNull(method(node(unknownTarget), "saveAdditional", newSave), "an unreadable target is not proven");
        assertNull(method(node(annotated), "saveAdditional", newSave), "an annotated mixin method is the mixin's own");
    }

    @Test
    @DisplayName("only a host whose BlockEntity lost the CompoundTag save gets the bridge")
    void gatedOnTheHostShape() {
        Names mojang = LegacyValueIoOverrideAdapter.MOJANG;
        ClassNode before = new ClassNode();
        before.methods.add(new MethodNode(Opcodes.ACC_PROTECTED, "saveAdditional", oldBlockEntitySave(), null, null));
        ClassNode after = new ClassNode();
        after.methods.add(new MethodNode(Opcodes.ACC_PROTECTED, "saveAdditional",
                "(Lnet/minecraft/world/level/storage/ValueOutput;)V", null, null));

        assertFalse(LegacyValueIoOverrideAdapter.hostReplacedShape(before, mojang.shapes().get(0)), "1.21.5 keeps it");
        assertTrue(LegacyValueIoOverrideAdapter.hostReplacedShape(after, mojang.shapes().get(0)));

        // Offline the target version decides: 1.21.6 made the change.
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        RetromodVersion.TARGET_MC_VERSION = "1.21.5";
        transformer.clearRedirectsForTesting();
        LegacyValueIoOverrideAdapter.register(transformer);
        assertFalse(LegacyValueIoOverrideAdapter.isRegistered(transformer.getSyntheticClasses()::containsKey));
        RetromodVersion.TARGET_MC_VERSION = "1.21.6";
        LegacyValueIoOverrideAdapter.register(transformer);
        assertTrue(LegacyValueIoOverrideAdapter.isRegistered(transformer.getSyntheticClasses()::containsKey));
    }

    @Test
    @DisplayName("each loader's 1.21.6 shim registers the helper in the names its mods use, up to 26.3")
    void everyLoaderRegistersIt() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        for (String host : List.of("1.21.6", "1.21.11", "26.1.2", "26.3")) {
            RetromodVersion.TARGET_MC_VERSION = host;
            boolean intermediary = !RetromodVersion.isUnobfuscatedTarget(host);
            for (com.retromod.core.VersionShim shim : List.of(new com.retromod.shim.fabric.Fabric_1_21_5_to_1_21_6(),
                    new com.retromod.shim.forge.Forge_1_21_5_to_1_21_6(),
                    new com.retromod.shim.neoforge.NeoForge_1_21_5_to_1_21_6())) {
                transformer.clearRedirectsForTesting();
                shim.registerRedirects(transformer);
                String expected = intermediary && shim.getModLoaderType().equals("fabric")
                        ? LegacyValueIoOverrideAdapter.INTERMEDIARY_HELPER : LegacyValueIoOverrideAdapter.MOJANG_HELPER;
                assertTrue(transformer.getSyntheticClasses().containsKey(expected), shim.getShimName() + " on " + host);
            }
        }
    }

    @Test
    @DisplayName("the intermediary helper and an adapted Fabric class name only intermediary types")
    void intermediaryNamesForFabricBefore26() throws Exception {
        Names intermediary = LegacyValueIoOverrideAdapter.INTERMEDIARY;
        byte[] helper = LegacyValueIoOverrideAdapter.generateHelper(intermediary);
        // Fabric names every override after the method it overrides, and BaseContainerBlockEntity is class_2624.
        Remapper fabric = new Remapper() {
            @Override
            public String map(String internalName) {
                return internalName.equals(CONTAINER) ? "net/minecraft/class_2624" : intermediary.remapper().map(internalName);
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                return intermediary.remapper().mapMethodName(BLOCK_ENTITY, name, descriptor);
            }
        };
        byte[] chest = remap(mod("CounterChest"), fabric);
        Map<String, String> supers = Map.of("mod/CounterChest", "net/minecraft/class_2624",
                "net/minecraft/class_2624", "net/minecraft/class_2586");
        Context context = new Context(List.of(intermediary), (child, ancestor) -> inherits(supers, child, ancestor),
                name -> null, (owner, name, desc) -> null, true);

        ClassNode adapted = node(LegacyValueIoOverrideAdapter.apply(chest, context));

        MethodNode save = method(adapted, "method_11007", "(Lnet/minecraft/class_11372;)V");
        assertNotNull(save, "saveAdditional keeps its intermediary id with the new parameter");
        assertTrue(calls(save).contains(LegacyValueIoOverrideAdapter.INTERMEDIARY_HELPER
                + ".saveTarget(Lnet/minecraft/class_11372;)Lnet/minecraft/class_2487;"), calls(save).toString());
        for (byte[] bytes : List.of(helper, LegacyValueIoOverrideAdapter.apply(chest, context))) {
            String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            for (String mojang : List.of("net/minecraft/nbt/", "net/minecraft/world/", "net/minecraft/core/",
                    "net/minecraft/util/", LegacyValueIoOverrideAdapter.MOJANG_HELPER + ";")) {
                assertFalse(text.contains(mojang), "no Mojang name " + mojang);
            }
        }
        verify(helper);
    }

    @Test
    @DisplayName("through the transformer, a 1.21.1 mod's saves, loads and old save calls work on 26.3")
    void transformerRunsTheWholeBridge() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyValueIoOverrideAdapter.register(transformer);
        // The host's hierarchy is offered with the jar's classes, as the running game would answer it.
        Map<String, byte[]> visible = new HashMap<>();
        for (String name : HOST_CLASSES) visible.put(HOST_NAMES.get(STAND_INS + name), hostClass(name));
        Map<String, byte[]> mods = new HashMap<>();
        for (String name : List.of("CounterChest", "Golem")) {
            visible.put(MOD_NAMES.get(STAND_INS + name), mod(name));
        }
        transformer.setJarClassBytesProvider(visible::get);
        transformer.setModSourceVersion("1.21.1");
        for (String name : List.of("mod/CounterChest", "mod/Golem")) {
            mods.put(name, transformer.transformClass(visible.get(name), name));
        }
        Host host = new Host(mods, transformer.getSyntheticClasses().get(LegacyValueIoOverrideAdapter.MOJANG_HELPER));
        Object registries = host.registries();

        Object chest = host.newInstance("mod/CounterChest");
        set(chest, "level", host.level(registries));
        set(chest, "count", 4);
        Object saved = call(chest, "saveWithoutMetadata", registries);
        Object copy = host.newInstance("mod/CounterChest");
        call(copy, "copyFrom", saved, registries);
        assertEquals(4, get(copy, "count"), "loadWithComponents(tag, registries) reached the old override");

        Object golem = host.newInstance("mod/Golem");
        set(golem, "level", host.level(registries));
        set(golem, "anger", 8);
        Object snapshot = call(golem, "snapshot");
        assertEquals(Map.of("Air", 300, "Health", 0, "Anger", 8), values(snapshot),
                "saveWithoutId(tag) filled and returned the caller's tag");
        Object restored = host.newInstance("mod/Golem");
        set(restored, "level", host.level(registries));
        call(restored, "restore", snapshot);
        assertEquals(8, get(restored, "anger"), "load(tag) reached the old read override");

        Object type = host.load("net/minecraft/world/level/block/entity/BlockEntityType")
                .getConstructor(int.class).newInstance(42);
        Object forItem = call(chest, "saveForItem", registries, type);
        assertEquals(Map.of("Id", 42, "Items", 0, "Count", 4), values(forItem),
                "saveWithId(registries) returned a new tag, and addEntityType(tag, type) wrote into it");

        Object food = host.newInstance("net/minecraft/world/food/FoodData");
        set(food, "foodLevel", 17);
        Object hunger = host.load("mod/CounterChest").getMethod("copyHunger", food.getClass(),
                host.load("net/minecraft/nbt/CompoundTag")).invoke(null, food, host.tag(Map.of()));
        assertEquals(Map.of("foodLevel", 17), values(hunger), "FoodData.addAdditionalSaveData(tag) filled the tag");
        assertEquals("net.minecraft.core.RegistryAccess$Frozen", get(food, "loadedWith").getClass().getName(),
                "food data loads with the built-in registries");
    }

    // ---- the mods and the host ----

    /** The mod class, adapted with the stand-in hierarchy. */
    private static byte[] adapt(String name, boolean entityShapes) throws IOException {
        return LegacyValueIoOverrideAdapter.apply(mod(name), context(entityShapes));
    }

    private static Context context(boolean entityShapes) {
        return context(entityShapes, (owner, name, desc) -> null);
    }

    private static Context context(boolean entityShapes, LegacyValueIoOverrideAdapter.HostMethods host) {
        return new Context(List.of(LegacyValueIoOverrideAdapter.MOJANG),
                (child, ancestor) -> inherits(SUPERS, child, ancestor), LegacyValueIoOverrideAdapterTest::jarClass,
                host, entityShapes);
    }

    private static boolean inherits(Map<String, String> supers, String child, String ancestor) {
        for (String current = child; current != null; current = supers.get(current)) {
            if (current.equals(ancestor)) return true;
        }
        return false;
    }

    /** The mod jar's own classes, as the transformer's jar provider would answer. */
    private static byte[] jarClass(String name) {
        try {
            for (Map.Entry<String, String> mod : MOD_NAMES.entrySet()) {
                if (mod.getValue().equals(name)) {
                    return mod(mod.getKey().substring(STAND_INS.length()));
                }
            }
            return null;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** A mod class as the 1.21.1 compiler wrote it, under the mod's own name. */
    private static byte[] mod(String name) throws IOException {
        return remap(standIn(name), names());
    }

    private static byte[] hostClass(String name) throws IOException {
        return remap(standIn(name), names());
    }

    private static Remapper names() {
        Map<String, String> all = new HashMap<>(HOST_NAMES);
        all.putAll(MOD_NAMES);
        return new SimpleRemapper(all);
    }

    private static byte[] standIn(String name) throws IOException {
        try (InputStream in = LegacyValueIoOverrideAdapterTest.class.getClassLoader()
                .getResourceAsStream(STAND_INS + name + ".class")) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }

    /** Renames a class and drops its nesting, so each stand-in is a top-level class of its own. */
    private static byte[] remap(byte[] bytes, Remapper remapper) {
        ClassWriter writer = new ClassWriter(0);
        ClassVisitor flat = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public void visitNestHost(String nestHost) {}
            @Override public void visitNestMember(String nestMember) {}
            @Override public void visitInnerClass(String name, String outerName, String innerName, int access) {}
            @Override public void visitOuterClass(String owner, String name, String descriptor) {}
        };
        new ClassReader(bytes).accept(new ClassRemapper(flat, remapper), 0);
        return writer.toByteArray();
    }

    /** {@code @Mixin(ChestBlockEntity.class) class ChestMixin extends BlockEntity { void saveAdditional(tag, registries) {} }} */
    private static byte[] chestMixin(boolean annotatedMethod) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "mod/mixin/ChestMixin", null, BLOCK_ENTITY, null);
        AnnotationVisitor mixin = cw.visitAnnotation(LegacyValueIoOverrideAdapter.MIXIN, false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType("net/minecraft/world/level/block/entity/ChestBlockEntity"));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor save = cw.visitMethod(Opcodes.ACC_PROTECTED, "saveAdditional", oldBlockEntitySave(), null, null);
        if (annotatedMethod) {
            save.visitAnnotation("Lorg/spongepowered/asm/mixin/Overwrite;", false).visitEnd();
        }
        save.visitCode();
        save.visitInsn(Opcodes.RETURN);
        save.visitMaxs(0, 0);
        save.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String oldBlockEntitySave() {
        return "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V";
    }

    /** The 26.x stand-ins, the Mojang-named helper and the given mod classes, in one loader. */
    private static final class Host {
        private final HostStubs stubs = new HostStubs();

        Host(Map<String, byte[]> mods) throws IOException {
            this(mods, LegacyValueIoOverrideAdapter.generateHelper(LegacyValueIoOverrideAdapter.MOJANG));
        }

        Host(Map<String, byte[]> mods, byte[] helper) throws IOException {
            for (String name : HOST_CLASSES) stubs.add(hostClass(name));
            stubs.add(LegacyValueIoOverrideAdapter.MOJANG_HELPER, helper);
            mods.forEach(stubs::add);
        }

        Class<?> load(String name) throws ClassNotFoundException {
            return stubs.load(name);
        }

        Object newInstance(String name) throws Exception {
            return load(name).getConstructor().newInstance();
        }

        Object registries() throws Exception {
            return load("net/minecraft/core/RegistryAccess$Frozen")
                    .getConstructor(load("net/minecraft/core/Registry")).newInstance((Object) null);
        }

        Object level(Object registries) throws Exception {
            return load("net/minecraft/world/level/Level")
                    .getConstructor(load("net/minecraft/core/RegistryAccess")).newInstance(registries);
        }

        Object tag(Map<String, Integer> values) throws Exception {
            Object tag = newInstance("net/minecraft/nbt/CompoundTag");
            for (Map.Entry<String, Integer> value : values.entrySet()) call(tag, "putInt", value.getKey(), value.getValue());
            return tag;
        }

        Object input(Object registries, Object tag) throws Exception {
            return load("net/minecraft/world/level/storage/TagValueInput")
                    .getConstructor(load("net/minecraft/core/HolderLookup$Provider"), load("net/minecraft/nbt/CompoundTag"))
                    .newInstance(registries, tag);
        }
    }

    private static Host host(byte[] adaptedMod) throws IOException {
        String name = new ClassReader(adaptedMod).getClassName();
        return new Host(Map.of(name, adaptedMod));
    }

    // ---- reflection and bytecode reading ----

    /** Calls the public method of that name whose parameter count matches. */
    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                return method.invoke(target, args);
            }
        }
        throw new AssertionError("no " + name + " on " + target.getClass().getName());
    }

    private static Object get(Object target, String field) throws Exception {
        return target.getClass().getField(field).get(target);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field found = target.getClass().getField(field);
        found.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(Object tag) throws Exception {
        return (Map<String, Object>) get(tag, "values");
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    private static List<String> calls(MethodNode method) {
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static void verify(byte[] bytes) throws Exception {
        ClassNode node = node(bytes);
        for (MethodNode method : node.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }
}
