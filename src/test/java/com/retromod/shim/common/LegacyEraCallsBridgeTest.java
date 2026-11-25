/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.retromod.shim.common.BridgeHarness.*;
import static com.retromod.shim.common.LegacyEraCallsBridge.*;
import static org.junit.jupiter.api.Assertions.*;

/** Members mods for 1.19.4 and older call reach their replacements on 26.x, on every loader. */
class LegacyEraCallsBridgeTest {

    private static final String CALLS = "mod/Calls";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mod;
    private static Map<String, byte[]> host;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        Map<String, String> old = new HashMap<>();
        old.put("net/minecraft/network/chat/Component.java", "package net.minecraft.network.chat; public interface Component {"
                + " String getContents(); }");
        old.put("net/minecraft/world/item/enchantment/Enchantment.java", "package net.minecraft.world.item.enchantment; public class Enchantment {"
                + " public String getDescriptionId() { return null; } }");
        old.put("net/minecraft/world/item/enchantment/EnchantmentHelper.java", "package net.minecraft.world.item.enchantment; public class EnchantmentHelper {"
                + " public static java.util.Map<Enchantment, Integer> getEnchantments(net.minecraft.world.item.ItemStack s) { return null; } }");
        old.put("net/minecraft/world/level/biome/Biome.java", "package net.minecraft.world.level.biome; public class Biome {"
                + " public enum Precipitation { NONE, RAIN, SNOW } public Precipitation getPrecipitation() { return null; } }");
        old.put("net/minecraft/core/Vec3i.java", "package net.minecraft.core; public class Vec3i { public Vec3i(double x, double y, double z) {} }");
        old.put("net/minecraft/commands/CommandSourceStack.java", "package net.minecraft.commands; public class CommandSourceStack {"
                + " public void sendSuccess(net.minecraft.network.chat.Component c, boolean b) {} }");
        old.put("net/minecraft/world/item/ItemStack.java", "package net.minecraft.world.item; public class ItemStack {"
                + " public boolean sameItem(ItemStack other) { return false; } }");
        old.put("net/minecraft/world/entity/player/Inventory.java", "package net.minecraft.world.entity.player; public class Inventory {"
                + " public net.minecraft.world.item.ItemStack getCarried() { return null; } }");
        old.put("mod/Calls.java", "package mod;\n"
                + "public class Calls {\n"
                + "    public static String run(net.minecraft.commands.CommandSourceStack source, net.minecraft.network.chat.Component message,\n"
                + "            net.minecraft.world.item.ItemStack a, net.minecraft.world.item.ItemStack b, net.minecraft.world.entity.player.Inventory inventory) {\n"
                + "        source.sendSuccess(message, true);\n"
                + "        return a.sameItem(b) + \" \" + (inventory.getCarried() == a);\n"
                + "    }\n"
                + "    public static Object more(net.minecraft.network.chat.Component c, net.minecraft.world.item.ItemStack s,\n"
                + "            net.minecraft.world.level.biome.Biome biome) {\n"
                + "        Object found = biome.getPrecipitation();\n"
                + "        for (net.minecraft.world.item.enchantment.Enchantment e : net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(s).keySet()) found = e.getDescriptionId();\n"
                + "        return c.getContents() + new net.minecraft.core.Vec3i(1.5, 2.0, 3.0) + found;\n"
                + "    }\n"
                + "}");
        mod = only(compile(dir.resolve("old"), old), "mod/");

        Map<String, String> modern = new HashMap<>();
        modern.put("net/minecraft/network/chat/Component.java", "package net.minecraft.network.chat; public interface Component {}");
        // Listed so reflection over the mod's methods finds every parameter type.
        modern.put("net/minecraft/world/level/biome/Biome.java", "package net.minecraft.world.level.biome; public class Biome {}");
        modern.put("net/minecraft/network/chat/Text.java", "package net.minecraft.network.chat; public class Text implements Component {"
                + " public String toString() { return \"hello\"; } }");
        modern.put("net/minecraft/commands/CommandSourceStack.java", "package net.minecraft.commands; public class CommandSourceStack {"
                + " public String log = \"\";"
                + " public void sendSuccess(java.util.function.Supplier<net.minecraft.network.chat.Component> m, boolean b) { log += m.get() + \" \" + b; } }");
        modern.put("net/minecraft/world/item/ItemStack.java", "package net.minecraft.world.item; public class ItemStack {"
                + " public final String item; public ItemStack(String item) { this.item = item; }"
                + " public static boolean isSameItem(ItemStack a, ItemStack b) { return a.item.equals(b.item); } }");
        modern.put("net/minecraft/world/inventory/AbstractContainerMenu.java", "package net.minecraft.world.inventory; public class AbstractContainerMenu {"
                + " public net.minecraft.world.item.ItemStack carried; public net.minecraft.world.item.ItemStack getCarried() { return carried; } }");
        modern.put("net/minecraft/world/entity/player/Player.java", "package net.minecraft.world.entity.player; public class Player {"
                + " public net.minecraft.world.inventory.AbstractContainerMenu containerMenu = new net.minecraft.world.inventory.AbstractContainerMenu(); }");
        modern.put("net/minecraft/world/entity/player/Inventory.java", "package net.minecraft.world.entity.player; public class Inventory {"
                + " public final Player player = new Player(); }");
        modern.put("net/minecraft/Game.java", "package net;\n");
        modern.remove("net/minecraft/Game.java");
        modern.put("net/minecraft/server/Game.java", "package net.minecraft.server;\n"
                + "public class Game {\n"
                + "    public static String play() throws Exception {\n"
                + "        net.minecraft.commands.CommandSourceStack source = new net.minecraft.commands.CommandSourceStack();\n"
                + "        net.minecraft.world.item.ItemStack a = new net.minecraft.world.item.ItemStack(\"stone\");\n"
                + "        net.minecraft.world.entity.player.Inventory inventory = new net.minecraft.world.entity.player.Inventory();\n"
                + "        inventory.player.containerMenu.carried = a;\n"
                + "        Object result = Class.forName(\"mod.Calls\").getMethods()[0].getName().equals(\"run\") ? null : null;\n"
                + "        java.lang.reflect.Method run = null;\n"
                + "        for (java.lang.reflect.Method m : Class.forName(\"mod.Calls\").getMethods()) if (m.getName().equals(\"run\")) run = m;\n"
                + "        String out = (String) run.invoke(null, source, new net.minecraft.network.chat.Text(), a, new net.minecraft.world.item.ItemStack(\"stone\"), inventory);\n"
                + "        return source.log + \" | \" + out;\n"
                + "    }\n"
                + "}");
        host = compile(dir.resolve("modern"), modern);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().setModSourceVersion(null);
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("on 26.3 the old command feedback, item comparison and carried stack calls run as they did")
    void oldCallsRun() throws Throwable {
        Map<String, byte[]> out = transform("26.3", t -> LegacyEraCallsBridge.register(t, name -> null), mod);
        Run run = new Run(host, out);
        assertEquals("hello true | true true", run.call("net/minecraft/server/Game", "play"),
                "feedback goes through the supplier, the comparison asks about the item, and the cursor stack comes from the menu");
    }

    @Test
    @DisplayName("each old call reaches its helper or the member that replaced it")
    void everyCallIsBridged() {
        Map<String, byte[]> out = transform("26.3", t -> LegacyEraCallsBridge.register(t, name -> null), mod);
        List<String> calls = calls(out.get(CALLS), "run");
        assertTrue(calls.contains(WORLD + ".sendSuccess(L" + SOURCE + ";Lnet/minecraft/network/chat/Component;Z)V"), calls.toString());
        assertTrue(calls.contains(ITEM_STACK + ".isSameItem" + STATIC_SAME_ITEM), calls.toString());
        assertTrue(calls.contains(WORLD + ".getCarried(L" + INVENTORY + ";)" + L_STACK), calls.toString());
        List<String> more = calls(out.get(CALLS), "more");
        assertTrue(more.contains(TEXTS + ".contentsText(L" + COMPONENT + ";)Ljava/lang/String;"), more.toString());
        assertTrue(more.contains(WORLD + ".vec3i(DDD)L" + VEC3I + ";"), more.toString());
        assertTrue(more.contains(WORLD + ".getEnchantments" + ENCHANTMENTS), more.toString());
        assertTrue(more.contains(WORLD + ".getPrecipitation(L" + BIOME + ";)L" + BIOME + "$Precipitation;"), more.toString());
        assertTrue(more.contains(WORLD + ".enchantmentDescriptionId(L" + ENCHANTMENT + ";)Ljava/lang/String;"), more.toString());
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        for (String helper : List.of(WORLD, CLIENT)) {
            assertTrue(transformer.getSyntheticClasses().containsKey(helper), helper + " must ship with the mod");
        }
    }

    @Test
    @DisplayName("a mod built after the change keeps its call")
    void newerModsKeepTheirCalls() {
        Map<String, byte[]> out = transform("26.3", t -> {
            LegacyEraCallsBridge.register(t, name -> null);
            t.setModSourceVersion("1.20.1");
        }, mod);
        List<String> calls = calls(out.get(CALLS), "run");
        assertTrue(calls.contains(SOURCE + ".sendSuccess" + SEND_SUCCESS), calls.toString());
        assertTrue(calls.contains(ITEM_STACK + ".sameItem" + SAME_ITEM), calls.toString());
    }

    @Test
    @DisplayName("a member the host still has is left alone, and an older target registers nothing")
    void gatedOnTheHost() {
        Function<String, ClassNode> keepsFeedback = name -> name.equals(SOURCE) ? withMethod("sendSuccess", SEND_SUCCESS) : null;
        List<String> calls = calls(transform("26.3", t -> LegacyEraCallsBridge.register(t, keepsFeedback), mod).get(CALLS), "run");
        assertTrue(calls.contains(SOURCE + ".sendSuccess" + SEND_SUCCESS), calls.toString());

        calls = calls(transform("1.21.11", t -> LegacyEraCallsBridge.register(t, name -> null), mod).get(CALLS), "run");
        assertTrue(calls.contains(ITEM_STACK + ".sameItem" + SAME_ITEM), calls.toString());
    }

    @Test
    @DisplayName("the tutorial toast id, the 1.19 literal contents and the ambient occlusion option are bridged only where the host lacks them")
    void fieldAndClassGates() {
        ClassNode toastWithHint = new ClassNode();
        toastWithHint.fields.add(new FieldNode(0, "TUTORIAL_HINT", "L" + TOAST_ID + ";", null, null));
        assertFalse(lacksField(toastWithHint, "TUTORIAL_HINT"));
        assertTrue(lacksField(new ClassNode(), "TUTORIAL_HINT"));
        assertTrue(lacksField(null, "TUTORIAL_HINT"), "an unreadable 26.x class counts as lacking it");

        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyEraCallsBridge.register(transformer, name -> null);
        assertEquals(LITERAL, transformer.getClassRedirects().get(OLD_LITERAL));
        assertFalse(transformer.getClassRedirectsForHostCode().containsKey(OLD_LITERAL),
                "Retromod's own helpers name the host's literal contents, so they keep the name");
        assertEquals("java/lang/Boolean", transformer.getClassRedirects().get(AO_STATUS),
                "the ambient occlusion option holds a Boolean from 1.19, which an old mod stores and restores");
    }

    private static ClassNode withMethod(String name, String desc) {
        ClassNode node = new ClassNode();
        node.methods.add(new org.objectweb.asm.tree.MethodNode(1, name, desc, null, null));
        return node;
    }
}
