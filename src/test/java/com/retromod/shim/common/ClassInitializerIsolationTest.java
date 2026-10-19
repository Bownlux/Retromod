/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.fabric.FabricRegistryIdAdapter;
import com.retromod.shim.fabric.FabricRegistryIdSynthetic;
import com.retromod.shim.forge.RegistryIdBridgeSynthetic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A stamped statement or a registration call is often the first code to touch another class,
 * which then initializes inside that statement's id window. {@code ModItems.FOO = new
 * BlockItem(ModBlocks.FOO, ...)} runs {@code ModBlocks}' initializer with the item's id set. 26.x
 * never compares a block's properties id with the key it is registered under, so a block there
 * that the jar does not provably register took the item's id silently, and its name and drops
 * with it.
 *
 * <p>Each case compiles a small mod against stand-in Minecraft classes, runs Retromod's pass over
 * it, and initializes it with the real generated bridge.
 */
class ClassInitializerIsolationTest {

    @TempDir
    Path temp;

    @BeforeEach
    @AfterEach
    void clearSharedSlot() {
        System.getProperties().remove(FabricRegistryIdSynthetic.SHARED_ID_PROPERTY_PREFIX + "slot");
    }

    @Test
    @DisplayName("a block the jar cannot prove registered fails loudly instead of taking an item's id")
    void nestedInitializerDoesNotBorrowTheOuterId() throws Exception {
        World world = fabricWorld(Map.of(
                "ModBlocks", """
                        public class ModBlocks {
                            public static final Block FOO = new Block(FabricRegistryIdBridge.blockOf());
                            // Registered under a computed id, which proves nothing.
                            public static final Block THING = new Block(FabricRegistryIdBridge.blockOf());
                        }""",
                "ModItems", """
                        public class ModItems {
                            public static final Item FOO = new BlockItem(ModBlocks.FOO, FabricRegistryIdBridge.itemProps());
                        }""",
                "ModLoader", LOADER + REGISTER_ITEMS + """
                            public static void registerBlocks() {
                                registerBlock("foo", ModBlocks.FOO);
                                Registry.register(BuiltInRegistries.BLOCK, id("thing").withSuffix("_block"), ModBlocks.THING);
                            }
                        }"""));

        ExceptionInInitializerError failure = assertThrows(ExceptionInInitializerError.class,
                () -> world.initialize("ModItems"),
                "THING must not be built under the item's id adv:foo");
        assertEquals("Block id not set", rootCause(failure).getMessage());
    }

    @Test
    @DisplayName("an item gets its id back after its statement initializes a class that registers in place")
    void outerIdSurvivesAnInClassRegistration() throws Exception {
        World world = fabricWorld(Map.of(
                "ModBlocks", """
                        public class ModBlocks {
                            public static final Block FOO = Registry.register(BuiltInRegistries.BLOCK,
                                    Identifier.fromNamespaceAndPath("adv", "foo"), new Block(FabricRegistryIdBridge.blockOf()));
                        }""",
                "ModItems", """
                        public class ModItems {
                            public static final Item FOO = new BlockItem(ModBlocks.FOO, FabricRegistryIdBridge.itemProps());
                        }""",
                "ModLoader", LOADER + REGISTER_ITEMS + "}"));

        world.initialize("ModItems");

        assertEquals("minecraft:item / adv:foo", world.builtId("ModItems", "FOO"),
                "the block's registration clears the slot when it returns, so the item needs the id it had before");
        assertEquals("minecraft:block / adv:foo", world.builtId("ModBlocks", "FOO"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {RegistryIdBridgeSynthetic.INTERNAL, RegistryIdBridgeSynthetic.FORGE_INTERNAL})
    @DisplayName("NeoForge and Forge: an item whose constructor arguments were spilled still gets its id")
    void spilledConstructorArgumentsStayInsideTheIdWindow(String bridge) throws Throwable {
        // A NeoForge 26.3 client run of the test mods: a constructor redirect on BlockItem's other
        // overload made the transformer rebuild NEW below the evaluated arguments, so the id window
        // opened after itemProps() had already run and the item died with Item id not set. These
        // bridges stamp only in the Properties factory, so the window has to cover the arguments.
        String bridgeName = bridge.substring(bridge.lastIndexOf('/') + 1);
        World world = world(bridge, Map.of(
                "ModBlocks", """
                        public class ModBlocks {
                            public static final Block THING = new Block(%s.blockOf());
                        }""".formatted(bridgeName),
                "ModItems", """
                        public class ModItems {
                            public static final Item FOO = new BlockItem(ModBlocks.THING, %s.itemProps());
                        }""".formatted(bridgeName),
                "ModLoader", LOADER + REGISTER_ITEMS + """
                            public static void registerBlocks() { registerBlock("thing", ModBlocks.THING); }
                        }"""),
                (bytes, ids) -> StaticFieldIdStamper.apply(spillBlockItemArguments(bytes), ids, bridge));

        world.initialize("ModItems");

        assertEquals("minecraft:item / adv:foo", world.builtId("ModItems", "FOO"));
        assertEquals("minecraft:block / adv:thing", world.builtId("ModBlocks", "THING"));
    }

    /**
     * Rewrites each {@code new BlockItem(...)} the way {@code RetromodTransformer} rebuilds a
     * deferred {@code NEW} whose overload matched no constructor redirect: the evaluated arguments
     * are stored from {@link RetromodTransformer#CONSTRUCTOR_ARG_SPILL_SLOT} up in reverse, then
     * {@code NEW} and {@code DUP}, then the arguments are loaded back in order.
     */
    private static byte[] spillBlockItemArguments(byte[] classBytes) {
        String blockItem = "net/minecraft/world/item/BlockItem";
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof TypeInsnNode created)
                        || created.getOpcode() != Opcodes.NEW || !created.desc.equals(blockItem)) {
                    continue;
                }
                AbstractInsnNode dup = created.getNext();
                AbstractInsnNode call = dup.getNext();
                while (!(call instanceof MethodInsnNode init && init.owner.equals(blockItem)
                        && init.name.equals("<init>"))) {
                    call = call.getNext();
                }
                Type[] arguments =
                        Type.getArgumentTypes(((MethodInsnNode) call).desc);
                InsnList rebuilt = new InsnList();
                for (int i = arguments.length - 1; i >= 0; i--) {
                    rebuilt.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ISTORE),
                            RetromodTransformer.CONSTRUCTOR_ARG_SPILL_SLOT + i));
                }
                rebuilt.add(new TypeInsnNode(Opcodes.NEW, blockItem));
                rebuilt.add(new InsnNode(Opcodes.DUP));
                for (int i = 0; i < arguments.length; i++) {
                    rebuilt.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ILOAD),
                            RetromodTransformer.CONSTRUCTOR_ARG_SPILL_SLOT + i));
                }
                method.instructions.insertBefore(call, rebuilt);
                method.instructions.remove(created);
                method.instructions.remove(dup);
                changed = true;
            }
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    @Test
    @DisplayName("a class initializer that throws still gives the caller its id back")
    void failedInitializerRestoresTheCallersId() throws Exception {
        World world = fabricWorld(Map.of(
                "Broken", """
                        public class Broken {
                            static { if (Boolean.parseBoolean("true")) throw new IllegalStateException("optional compat missing"); }
                        }""",
                "ModItems", """
                        public class ModItems {
                            public static final Item FOO = new Item(afterOptionalCompat());
                            static Item.Properties afterOptionalCompat() {
                                try {
                                    Class.forName("adv.Broken");
                                } catch (Throwable missing) {
                                    // the mod carries on without it
                                }
                                return FabricRegistryIdBridge.itemProps();
                            }
                        }""",
                "ModLoader", LOADER + REGISTER_ITEMS + "}"));

        world.initialize("ModItems");

        assertEquals("minecraft:item / adv:foo", world.builtId("ModItems", "FOO"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {RegistryIdBridgeSynthetic.INTERNAL, RegistryIdBridgeSynthetic.FORGE_INTERNAL})
    @DisplayName("NeoForge and Forge: a class initialized inside a registration cannot borrow its id")
    void forgeFamilyInitializerDoesNotBorrowTheOuterId(String bridge) throws Throwable {
        String bridgeName = bridge.substring(bridge.lastIndexOf('/') + 1);
        World world = world(bridge, Map.of("ModBlocks", """
                public class ModBlocks {
                    public static final Block THING = new Block(%s.blockOf());
                }""".formatted(bridgeName)),
                (bytes, ids) -> StaticFieldIdStamper.apply(bytes, ids, bridge));
        Object outer = world.identifier("adv", "outer");
        // As a deferred register's supplier does while it builds its own object.
        Object previous = world.enter(outer);

        ExceptionInInitializerError failure = assertThrows(ExceptionInInitializerError.class,
                () -> world.initialize("ModBlocks"));
        assertEquals("Block id not set", rootCause(failure).getMessage());
        assertSame(outer, world.slot(), "the registration that ran the initializer keeps its own id");
        world.leave(previous);
    }

    @Test
    @DisplayName("a single block registered through the mod's id helper still loads with its own id")
    void singletonRegisteredThroughTheIdHelperKeepsItsId() throws Throwable {
        // The registration is the first code to touch FooBlock, so its initializer runs inside the
        // registration. It now starts with no id, so FOO's id has to come from the jar index.
        World world = fabricWorld(Map.of(
                "FooBlock", """
                        public class FooBlock extends Block {
                            public static final FooBlock INSTANCE = new FooBlock();
                            private FooBlock() { super(FabricRegistryIdBridge.blockOf()); }
                        }""",
                "ModLoader", LOADER + """
                            public static void registerBlocks() {
                                Registry.register(BuiltInRegistries.BLOCK, id("foo"), FooBlock.INSTANCE);
                            }
                        }"""));

        world.call("ModLoader", "registerBlocks");

        assertEquals("minecraft:block / adv:foo", world.builtId("FooBlock", "INSTANCE"));
    }

    // The mod's loader class, as the multiloader template writes it: helpers that register
    // under the mod's namespace. Each case adds its own registrations and closes the class.
    private static final String REGISTER_ITEMS = """
                public static void registerItems() { registerItem("foo", ModItems.FOO); }
            """;
    private static final String LOADER = """
            public class ModLoader {
                static Identifier id(String path) { return Identifier.fromNamespaceAndPath("adv", path); }
                static Block registerBlock(String name, Block block) {
                    return Registry.register(BuiltInRegistries.BLOCK, Identifier.fromNamespaceAndPath("adv", name), block);
                }
                static Item registerItem(String name, Item item) {
                    return Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath("adv", name), item);
                }
            """;

    private World fabricWorld(Map<String, String> mod) throws IOException {
        return world(FabricRegistryIdSynthetic.INTERNAL, mod, FabricRegistryIdAdapter::apply);
    }

    /**
     * Compiles {@code mod} (package {@code adv}) against the stand-ins, indexes it as the
     * transformer does, runs {@code pass} over each class, and loads the result beside the real
     * bridge generated for {@code bridge}.
     */
    private World world(String bridge, Map<String, String> mod,
                        BiFunction<byte[], StaticFieldRegistryIds, byte[]> pass) throws IOException {
        String bridgeName = bridge.substring(bridge.lastIndexOf('/') + 1);
        String bridgePackage = bridge.substring(0, bridge.lastIndexOf('/')).replace('/', '.');
        Map<String, String> sources = new LinkedHashMap<>(STAND_INS);
        // Only for compiling the mod; the generated bridge replaces it at run time.
        sources.put(bridge + ".java", """
                package %s;
                public final class %s {
                    public static net.minecraft.world.level.block.state.BlockBehaviour.Properties blockOf() { return null; }
                    public static net.minecraft.world.item.Item.Properties itemProps() { return null; }
                }""".formatted(bridgePackage, bridgeName));
        mod.forEach((name, body) -> sources.put("adv/" + name + ".java", """
                package adv;
                import %s.%s;
                import net.minecraft.core.Registry;
                import net.minecraft.core.registries.BuiltInRegistries;
                import net.minecraft.resources.Identifier;
                import net.minecraft.world.item.BlockItem;
                import net.minecraft.world.item.Item;
                import net.minecraft.world.level.block.Block;
                """.formatted(bridgePackage, bridgeName) + body));
        Map<String, byte[]> compiled = compile(sources);

        Map<String, byte[]> modClasses = new HashMap<>();
        compiled.forEach((name, bytes) -> {
            if (name.startsWith("adv/")) modClasses.put(name, bytes);
        });
        StaticFieldRegistryIds.Builder index = StaticFieldRegistryIds.builder(modClasses.keySet());
        for (byte[] bytes : modClasses.values()) {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, 0);
            index.add(node);
        }
        StaticFieldRegistryIds ids = index.build();

        Map<String, byte[]> runtime = new HashMap<>();
        compiled.forEach((name, bytes) -> {
            if (name.startsWith("net/minecraft/")) runtime.put(name, bytes);
        });
        modClasses.forEach((name, bytes) -> runtime.put(name, pass.apply(bytes, ids)));
        runtime.put(bridge, bridge.equals(FabricRegistryIdSynthetic.INTERNAL) ? FabricRegistryIdSynthetic.generate()
                : bridge.equals(RegistryIdBridgeSynthetic.INTERNAL) ? RegistryIdBridgeSynthetic.generate()
                : RegistryIdBridgeSynthetic.generateForge());
        return new World(runtime, bridge);
    }

    private Map<String, byte[]> compile(Map<String, String> sources) throws IOException {
        Path src = temp.resolve("src");
        Path out = temp.resolve("out");
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "--release", "17", "-nowarn"));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the test needs a JDK to compile its stand-ins");
        assertEquals(0, compiler.run(null, null, null, args.toArray(new String[0])), "the fixtures must compile");
        Map<String, byte[]> classes = new HashMap<>();
        try (var files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = out.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                classes.put(name.substring(0, name.length() - ".class".length()), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause;
    }

    /** The transformed mod, the stand-ins, and the bridge, loaded on their own. */
    private static final class World extends ClassLoader {
        private final Map<String, byte[]> classes;
        private final String bridge;

        World(Map<String, byte[]> classes, String bridge) {
            super(ClassInitializerIsolationTest.class.getClassLoader());
            this.classes = classes;
            this.bridge = bridge.replace('/', '.');
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) loaded = defineClass(name, bytes, 0, bytes.length);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        void initialize(String modClass) throws ClassNotFoundException {
            Class.forName("adv." + modClass, true, this);
        }

        void call(String modClass, String method) throws Throwable {
            MethodHandles.lookup().findStatic(Class.forName("adv." + modClass, true, this), method,
                    MethodType.methodType(void.class)).invoke();
        }

        /** The key a block or item in {@code modClass.field} was built with. */
        String builtId(String modClass, String field) throws ReflectiveOperationException {
            Object built = Class.forName("adv." + modClass, true, this).getField(field).get(null);
            return String.valueOf(built.getClass().getField("builtId").get(built));
        }

        Object identifier(String namespace, String path) throws ReflectiveOperationException {
            return Class.forName("net.minecraft.resources.Identifier", true, this)
                    .getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, namespace, path);
        }

        // Method handles link one member each. Reflection over the whole bridge would resolve
        // every signature in it, and the loader classes those name do not exist here.

        Object enter(Object id) throws Throwable {
            Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", false, this);
            return bridgeLookup().findStatic(bridgeClass(), "enter", MethodType.methodType(Object.class, identifier))
                    .invoke(id);
        }

        void leave(Object previous) throws Throwable {
            bridgeLookup().findStatic(bridgeClass(), "leave", MethodType.methodType(void.class, Object.class))
                    .invoke(previous);
        }

        Object slot() throws Throwable {
            return ((ThreadLocal<?>) bridgeLookup().findStaticGetter(bridgeClass(), "CURRENT", ThreadLocal.class)
                    .invoke()).get();
        }

        private Class<?> bridgeClass() throws ClassNotFoundException {
            return Class.forName(bridge, true, this);
        }

        private MethodHandles.Lookup bridgeLookup() throws ReflectiveOperationException {
            return MethodHandles.privateLookupIn(bridgeClass(), MethodHandles.lookup());
        }
    }

    /** Just enough of 26.x for a block or item to refuse construction without an id. */
    private static final Map<String, String> STAND_INS = Map.of(
            "net/minecraft/resources/Identifier.java", """
                    package net.minecraft.resources;
                    public final class Identifier {
                        private final String text;
                        private Identifier(String text) { this.text = text; }
                        public static Identifier fromNamespaceAndPath(String namespace, String path) {
                            return new Identifier(namespace + ":" + path);
                        }
                        public Identifier withSuffix(String suffix) { return new Identifier(text + suffix); }
                        @Override public String toString() { return text; }
                    }""",
            "net/minecraft/resources/ResourceKey.java", """
                    package net.minecraft.resources;
                    public final class ResourceKey<T> {
                        private final Object registry;
                        private final Identifier location;
                        private ResourceKey(Object registry, Identifier location) { this.registry = registry; this.location = location; }
                        public static <T> ResourceKey<T> create(ResourceKey<?> registry, Identifier location) {
                            return new ResourceKey<>(registry == null ? null : registry.location, location);
                        }
                        @Override public String toString() { return registry + " / " + location; }
                    }""",
            "net/minecraft/core/registries/Registries.java", """
                    package net.minecraft.core.registries;
                    import net.minecraft.resources.*;
                    public final class Registries {
                        public static final ResourceKey<?> BLOCK = ResourceKey.create(null, Identifier.fromNamespaceAndPath("minecraft", "block"));
                        public static final ResourceKey<?> ITEM = ResourceKey.create(null, Identifier.fromNamespaceAndPath("minecraft", "item"));
                    }""",
            "net/minecraft/core/Registry.java", """
                    package net.minecraft.core;
                    import net.minecraft.resources.Identifier;
                    public interface Registry<T> {
                        static <V, T extends V> T register(Registry<V> registry, Identifier id, T value) { return value; }
                    }""",
            "net/minecraft/core/registries/BuiltInRegistries.java", """
                    package net.minecraft.core.registries;
                    import net.minecraft.core.Registry;
                    public final class BuiltInRegistries {
                        public static final Registry<Object> BLOCK = new Registry<>() {};
                        public static final Registry<Object> ITEM = new Registry<>() {};
                    }""",
            "net/minecraft/world/level/block/state/BlockBehaviour.java", """
                    package net.minecraft.world.level.block.state;
                    import net.minecraft.resources.ResourceKey;
                    public abstract class BlockBehaviour {
                        public final ResourceKey<?> builtId;
                        protected BlockBehaviour(Properties properties) {
                            if (properties.id == null) throw new NullPointerException("Block id not set");
                            builtId = properties.id;
                        }
                        public static class Properties {
                            ResourceKey<?> id;
                            public static Properties of() { return new Properties(); }
                            public static Properties ofFullCopy(BlockBehaviour block) { return new Properties(); }
                            public static Properties ofLegacyCopy(BlockBehaviour block) { return new Properties(); }
                            public Properties setId(ResourceKey<?> id) { this.id = id; return this; }
                        }
                    }""",
            "net/minecraft/world/level/block/Block.java", """
                    package net.minecraft.world.level.block;
                    import net.minecraft.world.level.block.state.BlockBehaviour;
                    public class Block extends BlockBehaviour {
                        public Block(BlockBehaviour.Properties properties) { super(properties); }
                    }""",
            "net/minecraft/world/item/Item.java", """
                    package net.minecraft.world.item;
                    import net.minecraft.resources.ResourceKey;
                    public class Item {
                        public final ResourceKey<?> builtId;
                        public Item(Properties properties) {
                            if (properties.id == null) throw new NullPointerException("Item id not set");
                            builtId = properties.id;
                        }
                        public static class Properties {
                            ResourceKey<?> id;
                            public Properties setId(ResourceKey<?> id) { this.id = id; return this; }
                        }
                    }""",
            "net/minecraft/world/item/BlockItem.java", """
                    package net.minecraft.world.item;
                    import net.minecraft.world.level.block.Block;
                    public class BlockItem extends Item {
                        public BlockItem(Block block, Item.Properties properties) { super(properties); }
                    }""");
}
