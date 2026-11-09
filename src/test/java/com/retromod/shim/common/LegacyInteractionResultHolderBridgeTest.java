/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_1_21_1_to_1_21_2;
import com.retromod.shim.forge.Forge_1_21_1_to_1_21_2;
import com.retromod.shim.neoforge.NeoForge_1_21_1_to_1_21_2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** A 1.21.1 item's {@code InteractionResultHolder} results on a 1.21.2 or newer host, which folded the holder into the result. */
class LegacyInteractionResultHolderBridgeTest {

    private static final String ITEMS = "mod/Items";
    private static final String MOJANG_HOLDER = "net/minecraft/world/InteractionResultHolder";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mojangMod;
    private static Map<String, byte[]> intermediaryMod;
    private static Map<String, byte[]> mojangHost;
    private static Map<String, byte[]> intermediaryHost;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        mojangMod = only(compile(dir.resolve("mojang-old"), Map.of(
                "net/minecraft/world/item/ItemStack.java", "package net.minecraft.world.item; public class ItemStack {}",
                "net/minecraft/world/InteractionResult.java", """
                        package net.minecraft.world;
                        public enum InteractionResult { SUCCESS, CONSUME, PASS, FAIL }""",
                "net/minecraft/world/InteractionResultHolder.java", oldHolder("net.minecraft.world",
                        "InteractionResultHolder", "InteractionResult", "success", "consume", "pass", "fail",
                        "sidedSuccess", "getResult", "getObject"),
                "mod/Items.java", """
                        package mod;
                        import net.minecraft.world.*;
                        import net.minecraft.world.item.ItemStack;
                        public class Items {
                            public static Object success(ItemStack s) { return InteractionResultHolder.success(s); }
                            public static Object consume(ItemStack s) { return InteractionResultHolder.consume(s); }
                            public static Object sided(ItemStack s, boolean client) { return InteractionResultHolder.sidedSuccess(s, client); }
                            public static Object pass(ItemStack s) { return InteractionResultHolder.pass(s); }
                            public static Object fail(ItemStack s) { return InteractionResultHolder.fail(s); }
                            public static Object entity(Object e) { return InteractionResultHolder.success(e); }
                            public static Object built(ItemStack s) { return new InteractionResultHolder<>(InteractionResult.SUCCESS, s); }
                            public static Object builtPass(ItemStack s) { return new InteractionResultHolder<>(InteractionResult.PASS, s); }
                            public static boolean plainSuccess(ItemStack s) {
                                return InteractionResultHolder.success(s).getResult() == InteractionResult.SUCCESS;
                            }
                            public static Object object(ItemStack s, boolean success) {
                                return (success ? InteractionResultHolder.success(s) : InteractionResultHolder.pass(s)).getObject();
                            }
                        }""")), "mod/");
        mojangHost = compile(dir.resolve("mojang-host"), Map.of(
                "net/minecraft/world/item/ItemStack.java", "package net.minecraft.world.item; public class ItemStack {}",
                "net/minecraft/world/InteractionResult.java", newResult("net.minecraft.world", "InteractionResult",
                        "net.minecraft.world.item.ItemStack", "Success", "Pass", "Fail", "SUCCESS", "CONSUME",
                        "SUCCESS_SERVER", "PASS", "FAIL", "heldItemTransformedTo", "heldItemTransformedTo")));
        intermediaryMod = only(compile(dir.resolve("intermediary-old"), Map.of(
                "net/minecraft/class_1799.java", "package net.minecraft; public class class_1799 {}",
                "net/minecraft/class_1269.java", """
                        package net.minecraft;
                        public enum class_1269 {
                            field_5812, field_21466, field_5811, field_5814;
                            public static class_1269 method_29236(boolean client) { return field_5812; }
                        }""",
                "net/minecraft/class_1271.java", oldHolder("net.minecraft", "class_1271", "class_1269",
                        "method_22427", "method_22428", "method_22430", "method_22431", "method_29237", "method_5467",
                        "method_5466"),
                "mod/Items.java", """
                        package mod;
                        import net.minecraft.*;
                        public class Items {
                            public static Object success(class_1799 s) { return class_1271.method_22427(s); }
                            public static Object pass(class_1799 s) { return class_1271.method_22430(s); }
                            public static Object object(class_1799 s) { return class_1271.method_22427(s).method_5466(); }
                            public static Object enumSided() { return class_1269.method_29236(true); }
                        }""")), "mod/");
        intermediaryHost = compile(dir.resolve("intermediary-host"), Map.of(
                "net/minecraft/class_1799.java", "package net.minecraft; public class class_1799 {}",
                "net/minecraft/class_1269.java", newResult("net.minecraft", "class_1269", "net.minecraft.class_1799",
                        "class_9860", "class_9859", "class_9857", "field_5812", "field_21466", "field_52422",
                        "field_5811", "field_5814", "method_61393", "method_61396")));
    }

    /** The 1.21.1 holder, which only has to compile the mod. */
    private static String oldHolder(String pkg, String name, String result, String success, String consume,
            String pass, String fail, String sided, String getResult, String getObject) {
        return "package " + pkg + ";\n"
                + "public class " + name + "<T> {\n"
                + "    public " + name + "(" + result + " result, T object) {}\n"
                + "    public " + result + " " + getResult + "() { return null; }\n"
                + "    public T " + getObject + "() { return null; }\n"
                + "    public static <T> " + name + "<T> " + success + "(T o) { return null; }\n"
                + "    public static <T> " + name + "<T> " + consume + "(T o) { return null; }\n"
                + "    public static <T> " + name + "<T> " + pass + "(T o) { return null; }\n"
                + "    public static <T> " + name + "<T> " + fail + "(T o) { return null; }\n"
                + "    public static <T> " + name + "<T> " + sided + "(T o, boolean client) { return null; }\n"
                + "}";
    }

    /** The 1.21.2 result: an interface whose success record can carry the item handed back. */
    private static String newResult(String pkg, String name, String stack, String success, String pass, String fail,
            String successField, String consumeField, String serverField, String passField, String failField,
            String withItem, String heldItem) {
        return "package " + pkg + ";\n"
                + "public interface " + name + " {\n"
                + "    " + success + " " + successField + " = new " + success + "(\"client\", true, null);\n"
                + "    " + success + " " + serverField + " = new " + success + "(\"server\", true, null);\n"
                + "    " + success + " " + consumeField + " = new " + success + "(\"none\", true, null);\n"
                + "    " + pass + " " + passField + " = new " + pass + "();\n"
                + "    " + fail + " " + failField + " = new " + fail + "();\n"
                + "    record " + success + "(String swing, boolean item, " + stack + " held) implements " + name + " {\n"
                + "        public " + success + " " + withItem + "(" + stack + " s) { return new " + success
                + "(swing, true, s); }\n"
                + "        public " + stack + " " + heldItem + "() { return held; }\n"
                + "    }\n"
                + "    record " + pass + "() implements " + name + " {}\n"
                + "    record " + fail + "() implements " + name + " {}\n"
                + "}";
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("on a NeoForge 1.21.4 host the holder calls fail as written and give 1.21.1's results once bridged")
    void mojangResults() throws Throwable {
        Run old = new Run(mojangHost, mojangMod);
        Object stack = old.loadClass("net.minecraft.world.item.ItemStack").getConstructor().newInstance();
        assertInstanceOf(LinkageError.class, assertThrows(Throwable.class, () -> old.call(ITEMS, "success", stack)));

        Run run = new Run(mojangHost, transform("1.21.4",
                t -> new NeoForge_1_21_1_to_1_21_2().registerRedirects(t), mojangMod));
        Object held = run.loadClass("net.minecraft.world.item.ItemStack").getConstructor().newInstance();
        assertSuccess(run.call(ITEMS, "success", held), "client", held, "success(stack) hands the stack back");
        assertSuccess(run.call(ITEMS, "consume", held), "none", held, "consume(stack) consumes without a swing");
        assertSuccess(run.call(ITEMS, "sided", held, true), "client", held, "sidedSuccess is a client swing");
        assertSuccess(run.call(ITEMS, "sided", held, false), "client", held, "on both sides, as vanilla migrated it");
        assertSuccess(run.call(ITEMS, "built", held), "client", held, "the constructor pairs a success with its stack");
        assertSuccess(run.call(ITEMS, "entity", "an entity"), "client", null, "only an item stack can be handed back");
        assertEquals("Pass", simpleName(run.call(ITEMS, "pass", held)));
        assertEquals("Fail", simpleName(run.call(ITEMS, "fail", held)));
        assertEquals("Pass", simpleName(run.call(ITEMS, "builtPass", held)), "a pass carries no item");
        assertEquals(true, run.call(ITEMS, "plainSuccess", held), "getResult() is the plain SUCCESS constant again");
        assertSame(held, run.call(ITEMS, "object", held, true), "getObject() reads the stack back");
        assertNull(run.call(ITEMS, "object", held, false), "a pass has no object, so null leaves the hand alone");
    }

    @Test
    @DisplayName("on a Fabric 1.21.4 host the intermediary holder and the enum's sidedSuccess are bridged")
    void intermediaryResults() throws Throwable {
        Run run = new Run(intermediaryHost, transform("1.21.4",
                t -> new Fabric_1_21_1_to_1_21_2().registerRedirects(t), intermediaryMod));
        Object held = run.loadClass("net.minecraft.class_1799").getConstructor().newInstance();
        assertSuccess(run.call(ITEMS, "success", held), "client", held, "success(stack)");
        assertEquals("class_9859", simpleName(run.call(ITEMS, "pass", held)));
        assertSame(held, run.call(ITEMS, "object", held));
        assertSuccess(run.call(ITEMS, "enumSided"), "client", null, "the 1.21.1 enum's sidedSuccess(true)");
    }

    @Test
    @DisplayName("each loader's 1.21.2 shim retypes the holder and bridges its calls")
    void everyLoaderBridgesIt() {
        List<VersionShim> shims = List.of(new NeoForge_1_21_1_to_1_21_2(), new Forge_1_21_1_to_1_21_2(),
                new Fabric_1_21_1_to_1_21_2());
        for (VersionShim shim : shims) {
            // Fabric reaches the Mojang names on 26.x, after the remap.
            String target = shim instanceof Fabric_1_21_1_to_1_21_2 ? "26.3" : "1.21.4";
            byte[] items = transform(target, shim::registerRedirects, mojangMod).get(ITEMS);
            assertFalse(new String(items, java.nio.charset.StandardCharsets.ISO_8859_1).contains(MOJANG_HOLDER),
                    shim.getShimName() + " leaves no InteractionResultHolder reference");
            assertTrue(calls(items, "success").contains(LegacyInteractionResultHolderBridge.MOJANG.helper()
                    + ".success(Ljava/lang/Object;)Lnet/minecraft/world/InteractionResult;"), shim.getShimName());
        }
    }

    @Test
    @DisplayName("a 1.21.1 host, which still has the holder, keeps every call")
    void gatedOnTheHost() {
        byte[] items = transform("1.21.1", LegacyInteractionResultHolderBridge::register, mojangMod).get(ITEMS);
        assertTrue(calls(items, "success").contains(MOJANG_HOLDER
                + ".success(Ljava/lang/Object;)L" + MOJANG_HOLDER + ";"));
        items = transform("1.21.1", LegacyInteractionResultHolderBridge::registerFabric, intermediaryMod).get(ITEMS);
        assertTrue(calls(items, "success").contains("net/minecraft/class_1271.method_22427"
                + "(Ljava/lang/Object;)Lnet/minecraft/class_1271;"));
    }

    private static void assertSuccess(Object result, String swing, Object held, String message) throws Exception {
        assertTrue(simpleName(result).equals("Success") || simpleName(result).equals("class_9860"), message);
        assertEquals(swing, result.getClass().getMethod("swing").invoke(result), message);
        assertSame(held, result.getClass().getMethod("held").invoke(result), message);
    }

    private static String simpleName(Object value) {
        return value.getClass().getSimpleName();
    }
}
