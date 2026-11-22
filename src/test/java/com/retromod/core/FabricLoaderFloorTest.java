/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A translated Fabric mod keeps its own fabricloader floor, which sets the Mixin compatibility of its mixins. */
class FabricLoaderFloorTest {

    // Farmer's Delight Refabricated 3.3.6 for 1.21.1 needs 0.19 for its enum extensions.
    private static final String REFABRICATED = "{\"schemaVersion\":1,\"id\":\"farmersdelight\",\"version\":\"3.3.6\","
            + "\"depends\":{\"fabricloader\":\">=0.19\",\"fabric-api\":\"*\",\"minecraft\":\">=1.21 <=1.21.1\"}}";

    @Test
    void readsTheLowestLoaderOfEachPredicateForm() {
        assertEquals("0.15.11", FabricMetadataCompat.lowerBound(">=0.15.11 <0.17"));
        assertEquals("0.14.21", FabricMetadataCompat.lowerBound("^0.14.21"));
        assertEquals("0.14", FabricMetadataCompat.lowerBound("0.14.x"));
        assertEquals("0.16.5", FabricMetadataCompat.lowerBound("~0.16.5"));
        assertNull(FabricMetadataCompat.lowerBound("*"));
        assertNull(FabricMetadataCompat.lowerBound("<0.16"));
    }

    @Test
    void keepsAFloorTheHostMeetsAndDropsUpperBounds() {
        assertEquals(">=0.19", loader(keep(REFABRICATED, "0.19.3")), "26.3 meets 0.19, so the enum extensions stay on");
        assertEquals(">=0.14.21", loader(keep(withLoader("\"^0.14.21\""), "0.19.3")), "the caret's upper bound would refuse 0.19");
        assertEquals(">=0.15", loader(keep(withLoader("[\"0.15.x\", \">=0.16\"]"), "0.19.3")), "the lowest alternative");
        assertEquals("*", loader(keep(withLoader("\"<0.16\""), "0.19.3")), "no floor stays no floor");
    }

    @Test
    void bringsAFloorAboveTheHostDownToItOnly() {
        assertEquals(">=0.18.4", loader(keep(withLoader("\">=0.19.4\""), "0.18.4")));
        assertEquals(">=0.19.4", loader(keep(withLoader("\">=0.19.4\""), null)), "an unknown host keeps the floor");
    }

    @Test
    void leavesAModWithoutALoaderDependencyAlone() {
        byte[] json = "{\"id\":\"x\",\"depends\":{\"minecraft\":\"*\"}}".getBytes(StandardCharsets.UTF_8);
        assertEquals(json, FabricMetadataCompat.keepLoaderFloor(json, "0.19.3"));
    }

    @Test
    void offlineCeilingsFollowFabricApisLoaderForEachLine() {
        assertEquals("0.19.3", FabricMetadataCompat.loaderCeiling("26.3"));
        assertEquals("0.18.4", FabricMetadataCompat.loaderCeiling("26.1.2"));
        assertEquals("0.17.3", FabricMetadataCompat.loaderCeiling("1.21.11"));
        assertNull(FabricMetadataCompat.loaderCeiling("1.20.1"), "a line not listed relaxes nothing");
    }

    @Test
    void everyPathKeepsTheFloor() throws Exception {
        String saved = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        try {
            byte[] input = REFABRICATED.getBytes(StandardCharsets.UTF_8);
            assertEquals(">=0.19", loader(staticRelax("com.retromod.cli.RetromodCli", input)), "the CLI");
            assertEquals(">=0.19", loader(staticRelax("com.retromod.aot.AotCompiler", input)), "AOT");
            Method runtime = FabricModTransformer.class.getDeclaredMethod("updateVersionRequirements", String.class, String.class);
            runtime.setAccessible(true);
            String json = (String) runtime.invoke(new FabricModTransformer("26.3"), REFABRICATED, "1.21.1");
            assertEquals(">=0.19", loader(json.getBytes(StandardCharsets.UTF_8)), "the game's own translation");
        } finally {
            RetromodVersion.TARGET_MC_VERSION = saved;
        }
    }

    private static byte[] staticRelax(String owner, byte[] json) throws Exception {
        Method relax = Class.forName(owner).getDeclaredMethod("relaxFabricModDependencies", byte[].class);
        relax.setAccessible(true);
        return (byte[]) relax.invoke(null, (Object) json);
    }

    private static byte[] keep(String json, String ceiling) {
        return FabricMetadataCompat.keepLoaderFloor(json.getBytes(StandardCharsets.UTF_8), ceiling);
    }

    private static String withLoader(String value) {
        return "{\"id\":\"x\",\"depends\":{\"fabricloader\":" + value + "}}";
    }

    private static String loader(byte[] json) {
        return JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonObject("depends").get("fabricloader").getAsString();
    }
}
