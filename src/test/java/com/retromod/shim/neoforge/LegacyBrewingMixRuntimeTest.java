/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.testsupport.fakemc.brewing.FakeDataComponentType;
import com.retromod.testsupport.fakemc.brewing.FakeDataComponents;
import com.retromod.testsupport.fakemc.brewing.FakeDirectHolder;
import com.retromod.testsupport.fakemc.brewing.FakeHolder;
import com.retromod.testsupport.fakemc.brewing.FakeItem;
import com.retromod.testsupport.fakemc.brewing.FakeItemStack;
import com.retromod.testsupport.fakemc.brewing.FakePotion;
import com.retromod.testsupport.fakemc.brewing.FakePotionContents;
import com.retromod.testsupport.fakemc.brewing.FakePotions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The rebuilt brewing mixes, run against small stand-ins for Minecraft's stacks, potions, and items. */
class LegacyBrewingMixRuntimeTest {

    private static final Path COMPILED = Path.of("target", "legacyapi-classes");
    private static final String MIX = "com/retromod/legacyapi/neoforge/brewing/LegacyBrewingMix";
    private static final Map<Class<?>, String> NAMES = Map.of(
            FakeHolder.class, "net/minecraft/core/Holder",
            FakeDirectHolder.class, "net/minecraft/core/Holder$Direct",
            FakeItem.class, "net/minecraft/world/item/Item",
            FakeItemStack.class, "net/minecraft/world/item/ItemStack",
            FakePotion.class, "net/minecraft/world/item/alchemy/Potion",
            FakePotionContents.class, "net/minecraft/world/item/alchemy/PotionContents",
            FakePotions.class, "net/minecraft/world/item/alchemy/Potions",
            FakeDataComponentType.class, "net/minecraft/core/component/DataComponentType",
            FakeDataComponents.class, "net/minecraft/core/component/DataComponents");

    private ClassLoader game;

    @BeforeEach
    void loadGame() throws IOException {
        Map<String, String> rename = new HashMap<>();
        NAMES.forEach((fake, name) -> rename.put(fake.getName().replace('.', '/'), name));
        SimpleRemapper remapper = new SimpleRemapper(rename);
        Map<String, byte[]> classes = new HashMap<>();
        for (Map.Entry<Class<?>, String> fake : NAMES.entrySet()) {
            classes.put(fake.getValue().replace('/', '.'), remap(bytesOf(fake.getKey()), remapper));
        }
        classes.put(MIX.replace('/', '.'), Files.readAllBytes(COMPILED.resolve(MIX + ".class")));
        game = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    @Test
    @DisplayName("addStartMix turns water into mundane and awkward into the potion, in the same bottle")
    void startMixFromWaterAndAwkward() throws Exception {
        Object splash = item("splash_potion");
        Object reagent = item("glow_berries");
        Object glowing = holder("glowing");

        Object fromWater = mix("start", reagent, glowing, false);
        Object mundane = output(fromWater, bottle(splash, vanilla("WATER")), stack(reagent));
        assertSame(splash, itemOf(mundane), "the bottle stays a splash potion");
        assertSame(value(vanilla("MUNDANE")), potionOf(mundane));

        Object fromAwkward = mix("start", reagent, glowing, true);
        assertFalse(isInput(fromAwkward, bottle(splash, vanilla("WATER"))), "only awkward takes the potion");
        Object result = output(fromAwkward, bottle(splash, vanilla("AWKWARD")), stack(reagent));
        assertSame(value(glowing), potionOf(result));
    }

    @Test
    @DisplayName("addMix turns one potion into another and keeps the bottle")
    void potionMixKeepsTheBottle() throws Exception {
        Object lingering = item("lingering_potion");
        Object reagent = item("redstone");
        Object weak = holder("weak");
        Object strong = holder("strong");
        Object mix = mix("potion", weak, reagent, strong);

        Object result = output(mix, bottle(lingering, weak), stack(reagent));
        assertSame(lingering, itemOf(result));
        assertSame(value(strong), potionOf(result));
        assertFalse(isInput(mix, bottle(lingering, strong)), "another potion is not this mix's input");
        assertSame(empty(), output(mix, bottle(lingering, weak), stack(item("sugar"))), "another reagent brews nothing");
    }

    @Test
    @DisplayName("addContainerRecipe turns one bottle into another and keeps the potion")
    void containerRecipeKeepsThePotion() throws Exception {
        Object potion = item("potion");
        Object splash = item("splash_potion");
        Object gunpowder = item("gunpowder");
        Object speed = holder("swiftness");
        Object mix = mix("container", potion, gunpowder, splash);

        Object result = output(mix, bottle(potion, speed), stack(gunpowder));
        assertSame(splash, itemOf(result));
        assertSame(value(speed), potionOf(result));
        assertFalse(isInput(mix, bottle(splash, speed)), "a splash bottle is not this recipe's input");
    }

    private Object mix(String kind, Object... arguments) throws Exception {
        Class<?> type = type(MIX);
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(kind) && method.getParameterCount() == arguments.length) {
                method.setAccessible(true);
                return method.invoke(null, arguments);
            }
        }
        throw new AssertionError("no " + kind + " factory");
    }

    private boolean isInput(Object mix, Object stack) throws Exception {
        return (Boolean) mix.getClass().getMethod("isInput", type("net/minecraft/world/item/ItemStack")).invoke(mix, stack);
    }

    private Object output(Object mix, Object input, Object reagent) throws Exception {
        Class<?> stack = type("net/minecraft/world/item/ItemStack");
        return mix.getClass().getMethod("getOutput", stack, stack).invoke(mix, input, reagent);
    }

    private Object item(String name) throws Exception {
        return type("net/minecraft/world/item/Item").getConstructor(String.class).newInstance(name);
    }

    private Object holder(String potion) throws Exception {
        Object value = type("net/minecraft/world/item/alchemy/Potion").getConstructor(String.class).newInstance(potion);
        return type("net/minecraft/core/Holder$Direct").getConstructor(Object.class).newInstance(value);
    }

    private Object vanilla(String field) throws Exception {
        return type("net/minecraft/world/item/alchemy/Potions").getField(field).get(null);
    }

    private Object bottle(Object item, Object potion) throws Exception {
        Class<?> contents = type("net/minecraft/world/item/alchemy/PotionContents");
        return contents.getMethod("createItemStack", type("net/minecraft/world/item/Item"), type("net/minecraft/core/Holder"))
                .invoke(null, item, potion);
    }

    private Object stack(Object item) throws Exception {
        Class<?> stack = type("net/minecraft/world/item/ItemStack");
        return stack.getConstructor(type("net/minecraft/world/item/Item"), type("net/minecraft/world/item/alchemy/PotionContents"))
                .newInstance(item, null);
    }

    private Object empty() throws Exception {
        return type("net/minecraft/world/item/ItemStack").getField("EMPTY").get(null);
    }

    private Object itemOf(Object stack) throws Exception {
        return stack.getClass().getMethod("getItem").invoke(stack);
    }

    private Object potionOf(Object stack) throws Exception {
        Object components = type("net/minecraft/core/component/DataComponents").getField("POTION_CONTENTS").get(null);
        Object contents = stack.getClass().getMethod("get", components.getClass()).invoke(stack, components);
        Optional<?> potion = (Optional<?>) contents.getClass().getMethod("potion").invoke(contents);
        return value(potion.orElseThrow());
    }

    private Object value(Object holder) throws Exception {
        return type("net/minecraft/core/Holder").getMethod("value").invoke(holder);
    }

    private Class<?> type(String internalName) throws ClassNotFoundException {
        return Class.forName(internalName.replace('/', '.'), true, game);
    }

    private static byte[] remap(byte[] bytes, SimpleRemapper remapper) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(bytes).accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    private static byte[] bytesOf(Class<?> type) throws IOException {
        try (InputStream in = type.getClassLoader().getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            return in.readAllBytes();
        }
    }
}
