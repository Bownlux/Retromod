/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.polyfill.thirdparty.EmiBridgePolyfill;
import com.retromod.polyfill.thirdparty.TrinketsBridgePolyfill;
import com.retromod.shim.api.common.MixinExtrasApiShim;
import com.retromod.shim.api.fabric.CardinalComponentsApiShim;
import com.retromod.shim.api.fabric.SodiumIrisApiShim;
import com.retromod.shim.api.fabric.TrinketsApiShim;
import com.retromod.shim.api.forge.CuriosApiShim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Third-party moves the current libraries contradict: EMI 1.1.24 still ships EmiTexture and EmiRender, Trinkets
 * 3.x ships SlotType beside SlotReference, every MixinExtras keeps WrapWithCondition under injector/, and Sodium
 * 0.5, CCA 5 and Curios 5 are the libraries their hosts run. No library is on the test class path, so the
 * version tables decide.
 */
class ThirdPartyMoveGateTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("EMI plugins keep EmiTexture, EmiRender and EmiPort on every host")
    void emiNamesStay() {
        for (String host : new String[] {"1.20.1", "1.21.1", "26.3"}) {
            Map<String, String> moves = register(host, () -> new EmiBridgePolyfill().registerPolyfills(transformer));
            assertFalse(moves.keySet().stream().anyMatch(name -> name.startsWith("dev/emi/emi/")), host + ": " + moves);
        }
    }

    @Test
    @DisplayName("Trinkets 3 dependents keep SlotType; 2.x names move only for mods built before 1.17")
    void trinketsSlotTypeStays() {
        Map<String, String> moves = register("1.21.1", () -> {
            new TrinketsApiShim().registerRedirects(transformer);
            new TrinketsBridgePolyfill().registerPolyfills(transformer);
        });
        assertFalse(moves.containsKey("dev/emi/trinkets/api/SlotType"), "SlotType ships in every Trinkets 3.x");
        assertEquals("dev/emi/trinkets/api/SlotType", moves.get("dev/emi/trinkets/api/TrinketSlot"));
        assertFalse(transformer.getMethodRedirects().keySet().stream().anyMatch(key -> key.name().equals("getSlots")),
                "no Trinkets 3.x has getInventory() returning a TrinketInventory");
        transformer.setModSourceVersion("1.20.1");
        assertEquals("dev/emi/trinkets/api/TrinketSlot",
                transformer.classRedirectForCurrentMod("dev/emi/trinkets/api/TrinketSlot"),
                "a 1.20.1 mod was built against Trinkets 3, which has no TrinketSlot to move");
        transformer.setModSourceVersion("1.16.5");
        assertEquals("dev/emi/trinkets/api/SlotType", transformer.classRedirectForCurrentMod("dev/emi/trinkets/api/TrinketSlot"));
    }

    @Test
    @DisplayName("@WrapWithCondition keeps its MixinExtras package")
    void wrapWithConditionStays() {
        Map<String, String> moves = register("26.3", () -> new MixinExtrasApiShim().registerRedirects(transformer));
        assertFalse(moves.containsKey("com/llamalad7/mixinextras/injector/WrapWithCondition"));
        assertEquals("com/llamalad7/mixinextras/injector/wrapoperation/WrapOperation",
                moves.get("com/llamalad7/mixinextras/injector/WrapOperation"), "the 0.0.x names still move");
    }

    @Test
    @DisplayName("Sodium names move only where every Sodium is 0.6 or later, and Iris names from 1.20.5")
    void sodiumAndIrisFollowTheirTables() {
        String chunkBuilder = "me/jellysquid/mods/sodium/client/render/chunk/compile/buffers/ChunkModelBuilder";
        String iris = "net/coderbot/iris/api/v0/IrisApi";
        Map<String, String> v1211 = register("1.21.1", () -> new SodiumIrisApiShim().registerRedirects(transformer));
        assertFalse(v1211.containsKey(chunkBuilder), "Sodium 0.5.11 for 1.21.1 still ships me.jellysquid");
        assertTrue(v1211.containsKey(iris));
        Map<String, String> v1204 = register("1.20.4", () -> new SodiumIrisApiShim().registerRedirects(transformer));
        assertFalse(v1204.containsKey(iris), "Iris 1.6 for 1.20.4 was not checked");
        Map<String, String> v263 = register("26.3", () -> new SodiumIrisApiShim().registerRedirects(transformer));
        assertTrue(v263.containsKey(chunkBuilder));
        assertEquals("net/caffeinemc/mods/sodium/client/SodiumClientMod",
                v263.get("me/jellysquid/mods/sodium/client/SodiumClientMod"), "options() follows its owner");
        assertFalse(v263.containsKey("me/jellysquid/mods/sodium/client/model/vertex/formats/ModelVertexSink"));
    }

    @Test
    @DisplayName("CCA 5 names stay on the hosts CCA 5 is published for")
    void ccaFollowsItsTable() {
        String key = "dev/onyxstudios/cca/api/v3/component/ComponentKey";
        assertFalse(register("1.20.4", () -> new CardinalComponentsApiShim().registerRedirects(transformer))
                .containsKey(key), "CCA 5.4.0 is the CCA for 1.20.4");
        Map<String, String> v1211 = register("1.21.1", () -> new CardinalComponentsApiShim().registerRedirects(transformer));
        assertEquals("org/ladysnake/cca/api/v3/component/ComponentKey", v1211.get(key));
        assertFalse(v1211.containsKey("dev/onyxstudios/cca/api/v3/item/ItemComponentFactoryRegistry"),
                "CCA 6.0.0 has no item factory registry to move to");
    }

    @Test
    @DisplayName("Curios members still in Curios 5 and 9 are bridged only where the Curios lacks them")
    void curiosMembersFollowTheirTables() {
        String helper = "top/theillusivec4/curios/api/type/util/ICuriosHelper";
        String api = "top/theillusivec4/curios/api/CuriosApi";
        register("1.20.1", () -> new CuriosApiShim().registerRedirects(transformer));
        assertFalse(hasRedirect(helper, "getCuriosHandler"), "Curios 5.14.1 still declares getCuriosHandler");
        assertTrue(hasRedirect("top/theillusivec4/curios/api/type/capability/ICurio", "curioTick"),
                "the String-slot callbacks are gone from every checked Curios");
        register("1.21.1", () -> new CuriosApiShim().registerRedirects(transformer));
        assertFalse(hasRedirect(helper, "getCuriosHandler"),
                "getCuriosHandler stays unbridged until a helper returns the LazyOptional old calls expect");
        assertFalse(hasRedirect(api, "getCuriosHelper"), "Curios 9.5.1 still declares getCuriosHelper");
        register("26.3", () -> new CuriosApiShim().registerRedirects(transformer));
        assertTrue(hasRedirect(api, "getCuriosHelper"));
    }

    /** The third-party class moves registered on {@code host}. */
    private Map<String, String> register(String host, Runnable registration) {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        registration.run();
        return Map.copyOf(transformer.getClassRedirects());
    }

    private boolean hasRedirect(String owner, String name) {
        return transformer.getMethodRedirects().keySet().stream()
                .anyMatch(key -> key.owner().equals(owner) && key.name().equals(name));
    }
}
