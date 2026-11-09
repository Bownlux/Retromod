/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticLibraries;
import com.retromod.shim.forge.LegacyBlockEntityDataBridge;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Forge 1.20.1 kept a persistent compound on every block entity and saved it under {@code ForgeData}; Forge 26.2
 * dropped both. A 1.20.1 chest is translated for a Forge 26.2 host and run against stand-ins of its ValueIO saves.
 */
class LegacyBlockEntityDataTest {

    private static final String STAND_INS = "com/retromod/shim/common/ValueIoStandIns$";
    private static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    private static final String CONTAINER = "net/minecraft/world/level/block/entity/BaseContainerBlockEntity";
    // A package of its own, since one test puts the translated shelf where the test class loader finds it.
    private static final String LIBRARY_SHELF = "retromodtest/library/LibraryShelf";

    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    // The 26.x stand-ins a running mod sees.
    private static final List<String> HOST = List.of("CompoundTag", "Codec", "MapCodec", "Provider", "RegistryAccess",
            "Frozen", "Registry", "BuiltInRegistries", "Level", "ProblemReporter", "ValueOutput", "ValueInput",
            "TagValueOutput", "TagValueInput", "StoringOutput", "BlockEntity", "BaseContainerBlockEntity", "Tag", "Component", "DynamicOps", "ListTag", "StringTag");

    static {
        String[][] names = {
                {"CompoundTag", "net/minecraft/nbt/CompoundTag"}, {"Codec", "com/mojang/serialization/Codec"},
                {"Tag", "net/minecraft/nbt/Tag"}, {"Component", "net/minecraft/network/chat/Component"},
                {"DynamicOps", "com/mojang/serialization/DynamicOps"}, {"ListTag", "net/minecraft/nbt/ListTag"},
                {"StringTag", "net/minecraft/nbt/StringTag"},
                {"MapCodec", "com/mojang/serialization/MapCodec"}, {"Provider", "net/minecraft/core/HolderLookup$Provider"},
                {"RegistryAccess", "net/minecraft/core/RegistryAccess"}, {"Frozen", "net/minecraft/core/RegistryAccess$Frozen"},
                {"Registry", "net/minecraft/core/Registry"},
                {"BuiltInRegistries", "net/minecraft/core/registries/BuiltInRegistries"},
                {"Level", "net/minecraft/world/level/Level"}, {"ProblemReporter", "net/minecraft/util/ProblemReporter"},
                {"ValueOutput", "net/minecraft/world/level/storage/ValueOutput"},
                {"ValueInput", "net/minecraft/world/level/storage/ValueInput"},
                {"TagValueOutput", "net/minecraft/world/level/storage/TagValueOutput"},
                {"TagValueInput", "net/minecraft/world/level/storage/TagValueInput"},
                {"StoringOutput", "net/minecraft/world/level/storage/StoringOutput"},
                {"BlockEntity", BLOCK_ENTITY}, {"BaseContainerBlockEntity", CONTAINER},
                {"LegacyBlockEntity", BLOCK_ENTITY}, {"LegacyContainerBlockEntity", CONTAINER},
                {"MarkedChest", "mod/MarkedChest"}, {"BigMarkedChest", "mod/BigMarkedChest"}, {"Lamp", "mod/Lamp"},
                {"TwinLamp", "mod/TwinLamp"}, {"LibraryShelf", LIBRARY_SHELF}, {"BookPile", "mod/BookPile"}};
        for (String[] name : names) NAMES.put(STAND_INS + name[0], name[1]);
    }

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;
    private final boolean previousForge = McReflect.isForceForge();

    @BeforeEach
    void forgeHost() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceForge(true);
    }

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        McReflect.setForceForge(previousForge);
        transformer.clearRedirectsForTesting();
        transformer.clearJarClassBytesProvider();
        transformer.clearLinkedLibraries();
        transformer.setModSourceVersion(null);
    }

    @Test
    @DisplayName("the host's save writes the chest's persistent data under ForgeData, beside its own keys")
    void saveWritesForgeData() throws Exception {
        Host host = translate();
        Object registries = host.registries();
        Object chest = host.newInstance("mod/MarkedChest");
        set(chest, "level", host.level(registries));
        set(chest, "items", 2);
        set(chest, "count", 3);
        call(chest, "mark", 5);

        Map<String, Object> saved = values(call(chest, "saveWithoutMetadata", registries));

        assertEquals(2, saved.get("Items"), "the parent's data, through the repaired super call");
        assertEquals(3, saved.get("Count"), "the old saveAdditional ran");
        assertEquals(Map.of("Marked", 5), values(saved.get("ForgeData")));
    }

    @Test
    @DisplayName("a chest saved on 1.20.1 gets its ForgeData back when the host loads it")
    void loadReadsA1201Save() throws Exception {
        Host host = translate();
        Object registries = host.registries();
        Object forgeData = host.tag(Map.of("Marked", 9));
        Object saved = host.tag(Map.of("Items", 4, "Count", 7));
        values(saved).put("ForgeData", forgeData);
        Object chest = host.newInstance("mod/MarkedChest");

        call(chest, "loadWithComponents", host.input(registries, saved));

        assertEquals(9, call(chest, "marked"));
        assertEquals(7, get(chest, "count"));
        assertEquals(4, get(chest, "items"));
    }

    @Test
    @DisplayName("a mod's own call to its old load also reads ForgeData, where its super call reaches Minecraft")
    void directOldLoadReadsForgeData() throws Exception {
        Host host = translate();
        Object saved = host.tag(Map.of("Count", 2));
        values(saved).put("ForgeData", host.tag(Map.of("Marked", 4)));
        Object chest = host.newInstance("mod/MarkedChest");
        Object registries = host.registries();
        // The super call takes the registries of the load in progress, as when another mod copies a chest.
        set(chest, "level", host.level(registries));

        call(chest, "load", saved);

        assertEquals(4, call(chest, "marked"));
        assertEquals(2, get(chest, "count"));
    }

    @Test
    @DisplayName("persistent data survives a save and a load, and each block entity keeps its own")
    void roundTripPerInstance() throws Exception {
        Host host = translate();
        Object registries = host.registries();
        Object first = host.newInstance("mod/MarkedChest");
        set(first, "level", host.level(registries));
        call(first, "mark", 11);
        Object other = host.newInstance("mod/MarkedChest");
        call(other, "mark", 12);

        Object saved = call(first, "saveWithoutMetadata", registries);
        Object loaded = host.newInstance("mod/MarkedChest");
        call(loaded, "loadWithComponents", host.input(registries, saved));

        assertEquals(11, call(loaded, "marked"));
        assertEquals(12, call(other, "marked"), "another chest's data is its own");
    }

    @Test
    @DisplayName("a block entity that never used persistent data saves no ForgeData key")
    void noDataNoKey() throws Exception {
        Host host = translate();
        Object registries = host.registries();
        Object chest = host.newInstance("mod/MarkedChest");
        set(chest, "level", host.level(registries));

        assertFalse(values(call(chest, "saveWithoutMetadata", registries)).containsKey("ForgeData"));
    }

    @Test
    @DisplayName("an addon's block entity over a library's in a linked jar saves and loads both without recursing")
    void parentInALinkedLibrary(@TempDir Path dir) throws Exception {
        byte[] shelf = translatedLibraryShelf();
        // A batch links the library's translated jar to the addon's pass.
        transformer.setLinkedLibraries(linkedJar(dir, LIBRARY_SHELF, shelf));

        assertAddonRoundTrips(shelf);
    }

    @Test
    @DisplayName("an addon's block entity over a library's in the library folder saves and loads both without recursing")
    void parentInTheLibraryFolder(@TempDir Path dir) throws Exception {
        byte[] shelf = translatedLibraryShelf();
        // The game's translation, rewritten or in place, links every translated jar in a folder such as mods/.
        linkedJar(dir, LIBRARY_SHELF, shelf);
        transformer.setLinkedLibraries(SyntheticLibraries.ofFolder(dir));

        assertAddonRoundTrips(shelf);
    }

    @Test
    @DisplayName("an addon's block entity over a library's that only the class loader shows saves and loads both without recursing")
    void parentOnTheClassPath() throws Exception {
        byte[] shelf = translatedLibraryShelf();
        // In the game the translated library is outside the addon's jar, and only the class loader shows it.
        Path root = Path.of(LegacyBlockEntityDataTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assumeTrue(Files.isDirectory(root), "the test classes are a folder the class loader reads");
        Path file = root.resolve(LIBRARY_SHELF + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, shelf);
        try {
            assertAddonRoundTrips(shelf);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
            Files.deleteIfExists(file.getParent().getParent());
        }
    }

    /** The library's shelf, translated first in a pass of its own, as a batch does. */
    private byte[] translatedLibraryShelf() throws Exception {
        Map<String, byte[]> library = new HashMap<>(hostClasses());
        library.put(LIBRARY_SHELF, standIn("LibraryShelf"));
        return translate(library, List.of(LIBRARY_SHELF)).translated.get(LIBRARY_SHELF);
    }

    /** Translates the addon's pile, whose jar does not hold the shelf, and saves and loads it on the host. */
    private void assertAddonRoundTrips(byte[] shelf) throws Exception {
        Map<String, byte[]> addon = new HashMap<>(hostClasses());
        addon.put("mod/BookPile", standIn("BookPile"));
        Host host = translate(addon, List.of("mod/BookPile"));
        host.stubs.add(LIBRARY_SHELF, shelf);
        Object registries = host.registries();
        Object pile = host.newInstance("mod/BookPile");
        set(pile, "level", host.level(registries));
        set(pile, "stored", 3);
        set(pile, "books", 4);
        set(pile, "items", 5);

        Object saved = call(pile, "saveWithoutMetadata", registries);
        Object loaded = host.newInstance("mod/BookPile");
        call(loaded, "loadWithComponents", host.input(registries, saved));

        assertEquals(Map.of("Items", 5, "Stored", 3, "Books", 4), values(saved));
        assertEquals(List.of(3, 4, 5), List.of(get(loaded, "stored"), get(loaded, "books"), get(loaded, "items")));
        ClassNode translated = node(host.translated.get("mod/BookPile"));
        assertEquals(LIBRARY_SHELF + ".load", calls(method(translated, "load", "(Lnet/minecraft/nbt/CompoundTag;)V")).get(0),
                "the addon's super call still reaches the library's own load");
        // Every route bridges the addon alike: the host's load reaches its old body through an override of its own.
        assertNotNull(method(translated, "loadAdditional", "(Lnet/minecraft/world/level/storage/ValueInput;)V"));
    }

    @Test
    @DisplayName("block entities that are equal by their own equals still keep a compound each")
    void dataIsKeyedByIdentity() throws Exception {
        Host host = translate();
        Object first = host.newInstance("mod/TwinLamp");
        Object second = host.newInstance("mod/TwinLamp");
        assertEquals(first, second, "the mod's equals calls any two of them equal");

        call(first, "mark", 1);
        call(second, "mark", 2);

        assertEquals(1, call(first, "marked"));
        assertEquals(2, call(second, "marked"));
    }

    @Test
    @DisplayName("a mod block entity with no save code of its own still saves and loads its persistent data")
    void blockEntityWithoutSaveCode() throws Exception {
        Host host = translate();
        Object registries = host.registries();
        Object lamp = host.newInstance("mod/Lamp");
        call(lamp, "mark", 6);

        Object saved = call(lamp, "saveWithoutMetadata", registries);
        assertEquals(Map.of("Marked", 6), values(values(saved).get("ForgeData")));
    }

    @Test
    @DisplayName("only the first mod class under a Minecraft block entity carries the hook, and mixins none")
    void hookPlacement() throws Exception {
        byte[] big = standIn("BigMarkedChest");
        assertSame(big, LegacyBlockEntityDataBridge.apply(big, this::inherits), "a subclass inherits its parent's hooks");
        byte[] mixin = chestMixin();
        assertSame(mixin, LegacyBlockEntityDataBridge.apply(mixin, this::inherits), "a mixin merges into Minecraft's class");
        ClassNode lamp = node(LegacyBlockEntityDataBridge.apply(standIn("Lamp"), this::inherits));
        MethodNode save = method(lamp, "saveAdditional", "(Lnet/minecraft/world/level/storage/ValueOutput;)V");
        assertNotNull(save, "an override is added where the class had none");
        assertEquals(List.of(BLOCK_ENTITY + ".saveAdditional", "com/retromod/legacyapi/forge/common/LegacyBlockEntityData.save"),
                calls(save), "the parent saves first, then the data");
    }

    @Test
    @DisplayName("NeoForge, which keeps getPersistentData, takes no part of the bridge")
    void neoForgeKeepsItsOwn() {
        McReflect.setForceForge(false);
        transformer.clearRedirectsForTesting();
        LegacyBlockEntityDataBridge.register(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey("com/retromod/legacyapi/forge/common/LegacyBlockEntityData"));
    }

    // ---- translation and the host ----

    /** Registers what a Forge 26.2 host registers for a 1.20.1 mod, then translates the mod's classes. */
    private Host translate() throws IOException, ReflectiveOperationException {
        Map<String, byte[]> visible = new HashMap<>(hostClasses());
        for (String name : List.of("MarkedChest", "BigMarkedChest", "Lamp", "TwinLamp")) {
            visible.put("mod/" + name, standIn(name));
        }
        return translate(visible, List.of("mod/MarkedChest", "mod/Lamp", "mod/TwinLamp"));
    }

    /** Translates {@code classes} of one jar, whose classes and the host's are {@code visible}. */
    private Host translate(Map<String, byte[]> visible, List<String> classes) throws IOException, ReflectiveOperationException {
        transformer.clearRedirectsForTesting();
        LegacyRegistryNbtBridge.registerValueIoRedirects(transformer, false);
        LegacyValueIoOverrideAdapter.register(transformer);
        LegacyBlockEntityDataBridge.register(transformer);
        transformer.setJarClassBytesProvider(visible::get);
        transformer.setModSourceVersion("1.20.1");
        Host host = new Host();
        for (String name : HOST) host.stubs.add(visible.get(NAMES.get(STAND_INS + name)));
        transformer.getSyntheticClasses().forEach(host.stubs::add);
        for (String name : classes) {
            byte[] translated = transformer.transformClass(visible.get(name), name);
            host.translated.put(name, translated);
            host.stubs.add(name, translated);
        }
        return host;
    }

    /** A translated jar holding one class, linked the way a batch links a library the next jar builds on. */
    private static SyntheticLibraries linkedJar(Path dir, String name, byte[] bytes) throws IOException {
        Path jar = dir.resolve("library-translated.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(name + ".class"));
            out.write(bytes);
            out.closeEntry();
        }
        SyntheticLibraries libraries = SyntheticLibraries.create();
        libraries.add(jar);
        return libraries;
    }

    private static Map<String, byte[]> hostClasses() throws IOException {
        Map<String, byte[]> host = new HashMap<>();
        for (String name : HOST) host.put(NAMES.get(STAND_INS + name), standIn(name));
        return host;
    }

    private boolean inherits(String child, String ancestor) {
        Map<String, String> supers = Map.of("mod/BigMarkedChest", "mod/MarkedChest", "mod/MarkedChest", CONTAINER,
                "mod/Lamp", BLOCK_ENTITY, CONTAINER, BLOCK_ENTITY, "mod/mixin/ChestMixin", BLOCK_ENTITY);
        for (String current = child; current != null; current = supers.get(current)) {
            if (current.equals(ancestor)) return true;
        }
        return false;
    }

    /** A stand-in under its Minecraft or mod name, as a top-level class of its own. */
    private static byte[] standIn(String name) throws IOException {
        try (InputStream in = LegacyBlockEntityDataTest.class.getClassLoader()
                .getResourceAsStream(STAND_INS + name + ".class")) {
            assertNotNull(in, name);
            ClassWriter writer = new ClassWriter(0);
            ClassVisitor flat = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override public void visitNestHost(String nestHost) {}
                @Override public void visitNestMember(String nestMember) {}
                @Override public void visitInnerClass(String n, String outer, String inner, int access) {}
                @Override public void visitOuterClass(String owner, String n, String descriptor) {}
            };
            new ClassReader(in.readAllBytes()).accept(new ClassRemapper(flat, new SimpleRemapper(NAMES)), 0);
            return writer.toByteArray();
        }
    }

    /** {@code @Mixin(ChestBlockEntity.class) abstract class ChestMixin extends BlockEntity {}}. */
    private static byte[] chestMixin() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "mod/mixin/ChestMixin", null, BLOCK_ENTITY, null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        mixin.visitArray("value").visitEnd();
        mixin.visitEnd();
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK_ENTITY, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The 26.x stand-ins, the helpers and rebuilt classes the translation registered, and the translated mod. */
    private static final class Host {
        private final HostStubs stubs = new HostStubs();
        private final Map<String, byte[]> translated = new HashMap<>();

        Object newInstance(String name) throws Exception {
            return stubs.load(name).getConstructor().newInstance();
        }

        Object registries() throws Exception {
            return stubs.load("net/minecraft/core/RegistryAccess$Frozen")
                    .getConstructor(stubs.load("net/minecraft/core/Registry")).newInstance((Object) null);
        }

        Object level(Object registries) throws Exception {
            return stubs.load("net/minecraft/world/level/Level")
                    .getConstructor(stubs.load("net/minecraft/core/RegistryAccess")).newInstance(registries);
        }

        Object tag(Map<String, Integer> values) throws Exception {
            Object tag = newInstance("net/minecraft/nbt/CompoundTag");
            for (Map.Entry<String, Integer> value : values.entrySet()) call(tag, "putInt", value.getKey(), value.getValue());
            return tag;
        }

        Object input(Object registries, Object tag) throws Exception {
            return stubs.load("net/minecraft/world/level/storage/TagValueInput")
                    .getConstructor(stubs.load("net/minecraft/core/HolderLookup$Provider"),
                            stubs.load("net/minecraft/nbt/CompoundTag"))
                    .newInstance(registries, tag);
        }
    }

    // ---- reflection and bytecode reading ----

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
        target.getClass().getField(field).set(target, value);
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
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
    }

    private static List<String> calls(MethodNode method) {
        return java.util.Arrays.stream(method.instructions.toArray())
                .filter(insn -> insn instanceof MethodInsnNode)
                .map(insn -> ((MethodInsnNode) insn).owner + "." + ((MethodInsnNode) insn).name).toList();
    }
}
