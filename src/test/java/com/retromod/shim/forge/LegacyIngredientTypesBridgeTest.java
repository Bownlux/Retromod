/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Forge 1.20.1's ingredient types on Forge 26.2: the gate, and the embedded rebuilt classes matching items. */
class LegacyIngredientTypesBridgeTest {

    private static final String HOST = "com/retromod/shim/forge/compoundhost/";
    private static final Map<String, String> HOST_NAMES = Map.ofEntries(
            Map.entry("Ingredient", "net/minecraft/world/item/crafting/Ingredient"),
            Map.entry("AbstractIngredient", LegacyIngredientTypesBridge.HOST + "AbstractIngredient"),
            Map.entry("CompoundIngredient", LegacyIngredientTypesBridge.HOST + "CompoundIngredient"),
            Map.entry("NBTIngredient", LegacyIngredientTypesBridge.HOST + "NBTIngredient"),
            Map.entry("DifferenceIngredient", LegacyIngredientTypesBridge.HOST + "DifferenceIngredient"),
            Map.entry("IIngredientSerializer", LegacyIngredientTypesBridge.HOST + "IIngredientSerializer"),
            Map.entry("ItemStack", "net/minecraft/world/item/ItemStack"),
            Map.entry("Item", "net/minecraft/world/item/Item"),
            Map.entry("Holder", "net/minecraft/core/Holder"),
            Map.entry("CompoundTag", "net/minecraft/nbt/CompoundTag"),
            Map.entry("ItemLike", "net/minecraft/world/level/ItemLike"));

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
        transformer.clearSrgNameMappings();
    }

    @Test
    @DisplayName("Only a Forge target from 26.2 takes the bridge; NeoForge and Forge 26.1 keep the old names")
    void gate() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        LegacyIngredientTypesBridge.register(transformer, name -> null);
        assertFalse(redirected(), "Forge 26.1 was not checked against these shapes");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(true);
        LegacyIngredientTypesBridge.register(transformer, name -> null);
        assertFalse(redirected(), "NeoForge has its own crafting package and a final Ingredient");

        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        LegacyIngredientTypesBridge.register(transformer, name -> null);
        assertTrue(redirected());
    }

    @Test
    @DisplayName("A host that still has the old compound keeps it")
    void hostWithTheOldClass() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        ClassNode present = new ClassNode();
        LegacyIngredientTypesBridge.register(transformer, name -> name.equals(ForgeHostShape.ANCHOR)
                || name.startsWith(LegacyIngredientTypesBridge.HOST) || name.equals(LegacyIngredientTypesBridge.OLD_COMPOUND)
                ? present : null);
        assertFalse(redirected());
    }

    @Test
    @DisplayName("Farmer's Delight's compound subclass, built on the embedded copy, matches what its children match")
    void embeddedCompoundMatchesItsChildren(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("cfix.jar");
        Files.write(input, ConstructionFixtures.modJar());
        ClassLoader mod = translatedMod(ConstructionFixtures.forgeRuntime(transformer, input, dir, "26.2"));

        Class<?> ingredient = mod.loadClass("net.minecraft.world.item.crafting.Ingredient");
        Object carrots = ingredient.getMethod("ofItems", String[].class).invoke(null, (Object) new String[]{"carrot"});
        Object crops = ingredient.getMethod("ofItems", String[].class).invoke(null, (Object) new String[]{"cabbage", "tomato"});
        Constructor<?> subclass = mod.loadClass("cfix.FoodSetup$2").getDeclaredConstructor(List.class);
        subclass.setAccessible(true);
        Object food = subclass.newInstance(List.of(carrots, crops));

        assertTrue(test(mod, food, "cabbage"), "a child's item matches");
        assertTrue(test(mod, food, "carrot"));
        assertFalse(test(mod, food, "stone"), "an item no child names does not");
        assertEquals(Set.of("carrot", "cabbage", "tomato"), itemNames(food));
        assertEquals(2, ((java.util.Collection<?>) food.getClass().getMethod("getChildren").invoke(food)).size());
        assertEquals(true, food.getClass().getMethod("isSimple").invoke(food));
        Object hostSerializer = mod.loadClass(LegacyIngredientTypesBridge.HOST.replace('/', '.') + "CompoundIngredient")
                .getField("SERIALIZER").get(null);
        assertSame(hostSerializer, food.getClass().getMethod("serializer").invoke(food));

        // CompoundIngredient.of gives Forge's own compound, which Forge can write.
        Object array = Array.newInstance(ingredient, 2);
        Array.set(array, 0, carrots);
        Array.set(array, 1, crops);
        Object built = mod.loadClass("cfix.Combined").getMethod("compound", array.getClass()).invoke(null, array);
        assertEquals(LegacyIngredientTypesBridge.HOST.replace('/', '.') + "CompoundIngredient", built.getClass().getName());
        assertTrue(test(mod, built, "tomato"));
    }

    @Test
    @DisplayName("StrictNBTIngredient.of(stack), on the embedded copy, matches the same item with the same components only")
    void embeddedStrictComparesComponents(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("cfix.jar");
        Files.write(input, ConstructionFixtures.modJar());
        ClassLoader mod = translatedMod(ConstructionFixtures.forgeRuntime(transformer, input, dir, "26.3"));

        Object strict = mod.loadClass("cfix.Combined").getMethod("strict", mod.loadClass("net.minecraft.world.item.ItemStack"))
                .invoke(null, stack(mod, "potion", "healing"));
        assertTrue(strict.getClass().getName().endsWith(LegacyIngredientTypesBridge.STRICT.replace('/', '.')),
                "the embedded copy: " + strict.getClass().getName());
        assertTrue(test(mod, strict, "potion", "healing"));
        assertFalse(test(mod, strict, "potion", "harming"), "other data does not match");
        assertFalse(test(mod, strict, "potion"), "no data does not match data");
        assertEquals(Set.of("potion"), itemNames(strict));
    }

    @Test
    @DisplayName("On NeoForge the old compound, intersection and difference factories reach NeoForge's")
    void neoForgeFactories() {
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        ForgeNeoForgeApiBridge.registerCombinedIngredients(transformer, name -> true);
        String ingredient = "net/minecraft/world/item/crafting/Ingredient";
        String array = "([L" + ingredient + ";)L" + ingredient + ";";
        String neo = "net/neoforged/neoforge/common/crafting/";
        assertEquals(neo + "CompoundIngredient", target(LegacyIngredientTypesBridge.OLD + "CompoundIngredient", array));
        assertEquals(neo + "IntersectionIngredient", target(LegacyIngredientTypesBridge.OLD + "IntersectionIngredient", array));
        String old = LegacyIngredientTypesBridge.OLD + "DifferenceIngredient";
        assertEquals(neo + "DifferenceIngredient",
                target(old, "(L" + ingredient + ";L" + ingredient + ";)L" + old + ";"));
        assertEquals(ingredient, transformer.classRedirectForCurrentMod(old),
                "NeoForge's factory returns Ingredient, so the old type does too");
    }

    private String target(String owner, String desc) {
        RetromodTransformer.MethodTarget target = transformer.getMethodRedirects()
                .get(new RetromodTransformer.MethodKey(owner, "of", desc));
        return target == null ? null : target.owner();
    }

    private boolean redirected() {
        return LegacyIngredientTypesBridge.COMPOUND.equals(
                transformer.classRedirectForCurrentMod(LegacyIngredientTypesBridge.OLD_COMPOUND));
    }

    /** The translated jar's classes over the host stand-ins, embedded copies included, in one loader. */
    private static ClassLoader translatedMod(Path jar) throws Exception {
        Map<String, byte[]> classes = new HashMap<>(hostStandIns());
        try (JarFile file = new JarFile(jar.toFile())) {
            for (var entry : java.util.Collections.list(file.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                String name = entry.getName().substring(0, entry.getName().length() - 6);
                classes.put(name, file.getInputStream(entry).readAllBytes());
            }
        }
        return new LegacyEventRuntimeTest.Loader(ClassLoader.getPlatformClassLoader(), classes);
    }

    /** Each stand-in under the host name it serves; its nested and helper classes keep theirs. */
    private static Map<String, byte[]> hostStandIns() throws Exception {
        Remapper names = new Remapper() {
            @Override
            public String map(String internalName) {
                if (!internalName.startsWith(HOST)) return internalName;
                String simple = internalName.substring(HOST.length());
                int nested = simple.indexOf('$');
                String outer = nested < 0 ? simple : simple.substring(0, nested);
                String host = HOST_NAMES.get(outer);
                return host == null ? internalName : host + (nested < 0 ? "" : simple.substring(nested));
            }
        };
        Path compiled = Path.of("target", "test-classes", HOST);
        Map<String, byte[]> classes = new HashMap<>();
        try (Stream<Path> files = Files.list(compiled)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = HOST + file.getFileName().toString().replace(".class", "");
                classes.put(names.map(name), CapabilityHostFixtures.remap(Files.readAllBytes(file), names));
            }
        }
        return classes;
    }

    private static Object stack(ClassLoader mod, String item, String... components) throws Exception {
        return mod.loadClass("net.minecraft.world.item.ItemStack").getMethod("of", String.class, String[].class)
                .invoke(null, item, components);
    }

    private static boolean test(ClassLoader mod, Object ingredient, String item, String... components) throws Exception {
        Class<?> stack = mod.loadClass("net.minecraft.world.item.ItemStack");
        Method test = ingredient.getClass().getMethod("test", stack);
        return (Boolean) test.invoke(ingredient, stack(mod, item, components));
    }

    private static Set<String> itemNames(Object ingredient) throws Exception {
        Stream<?> holders = (Stream<?>) ingredient.getClass().getMethod("items").invoke(ingredient);
        return holders.map(holder -> {
            try {
                Object item = holder.getClass().getMethod("value").invoke(holder);
                return (String) item.getClass().getMethod("name").invoke(item);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }).collect(Collectors.toSet());
    }
}
