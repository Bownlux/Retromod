/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodTransformer.MethodKey;
import com.retromod.core.RetromodTransformer.MethodTarget;
import com.retromod.core.RetromodVersion;
import com.retromod.loaderapi.LoaderApiTestSupport;
import com.retromod.shim.fabric.Fabric_1_20_4_to_1_20_5;
import com.retromod.shim.fabric.Fabric_1_21_1_to_1_21_2;
import com.retromod.shim.forge.Forge_1_20_to_NeoForge_1_21;
import com.retromod.shim.forge.Forge_1_21_1_to_1_21_2;
import com.retromod.shim.neoforge.NeoForge_1_20_2_to_1_20_3;
import com.retromod.shim.neoforge.NeoForge_1_20_4_to_1_20_5;
import com.retromod.shim.neoforge.NeoForge_1_21_1_to_1_21_2;
import com.retromod.util.RetromodLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;

/** A bridge that two shim chains call in one registration pass registers both times but logs once. */
class SharedBridgeNoticeTest {

    private static final String CODEC_NOTICE = "worldgen types whose codec became a MapCodec";
    private static final String STRUCTURE_TYPE = "net/minecraft/world/level/levelgen/structure/StructureType";

    // The notices of the bridges NeoForge's own chain and the Forge chain both call.
    private static final List<String> SHARED_NOTICES = List.of(
            "removed food members",
            "attribute and mob effect",
            "item attribute modifier overrides",
            "removed 1.20.5 members",
            "Bridged 1.21.2 members",
            "Bridged the removed AbstractTreeGrower",
            "pre-1.20.5 entity data definitions",
            CODEC_NOTICE);

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void pinNewestHost() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        // The notices are detail lines; this writes them where captureErr can count them.
        RetromodLog.setDetailInGameLogForTesting(true);
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
        RetromodLog.setDetailInGameLogForTesting(null);
    }

    @Test
    @DisplayName("NeoForge's and Forge's 1.21.2 shims log the 1.21.2 member bridge once and keep its rows")
    void neoForgeAndForge1212ShimsLogTheMemberBridgeOnce() throws Exception {
        Legacy1212MemberBridge.register(transformer);
        Map<MethodKey, MethodTarget> bridgeRows = Map.copyOf(transformer.getMethodRedirects());
        transformer.clearRedirectsForTesting();

        String log = LoaderApiTestSupport.captureErr(() -> {
            new NeoForge_1_21_1_to_1_21_2().registerRedirects(transformer);
            new Forge_1_21_1_to_1_21_2().registerRedirects(transformer);
        });

        assertEquals(1, count(log, "Bridged 1.21.2 members"), log);
        assertFalse(bridgeRows.isEmpty(), "the bridge registers nothing on this host");
        Map<MethodKey, MethodTarget> registered = transformer.getMethodRedirects();
        bridgeRows.forEach((key, target) -> assertEquals(target, registered.get(key),
                "the bridge's redirect for " + key + " must still be registered"));
    }

    @ParameterizedTest(name = "host {0}")
    @ValueSource(strings = {"1.21.1", "26.3"})
    @DisplayName("NeoForge's 1.20.3 and 1.20.5 shims with the Forge 1.20 shim log each shared bridge as often as alone")
    void neoForgeAndForge1205ShimsLogEachSharedBridgeOnce(String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        String alone = LoaderApiTestSupport.captureErr(() -> {
            new NeoForge_1_20_2_to_1_20_3().registerRedirects(transformer);
            new NeoForge_1_20_4_to_1_20_5().registerRedirects(transformer);
        });
        transformer.clearRedirectsForTesting();
        String both = LoaderApiTestSupport.captureErr(() -> {
            new NeoForge_1_20_2_to_1_20_3().registerRedirects(transformer);
            new NeoForge_1_20_4_to_1_20_5().registerRedirects(transformer);
            new Forge_1_20_to_NeoForge_1_21().registerRedirects(transformer);
        });

        int printed = 0;
        for (String notice : SHARED_NOTICES) {
            assertEquals(count(alone, notice), count(both, notice),
                    "'" + notice + "' on " + host + ":\n" + both);
            printed += count(both, notice);
        }
        assertTrue(printed > 0, "no shared bridge registered on " + host + ":\n" + both);
    }

    @Test
    @DisplayName("On 26.3, NeoForge's 1.20.5 shim with the Forge 1.20 shim logs the worldgen codec bridge once")
    void neoForgeAndForge1205ShimsLogTheCodecBridgeOnceOnTheNewestHost() throws Exception {
        // Serve StructureType as 26.3 has it, so the bridge reads the host class instead of the version.
        String log = withContextLoader(new MapCodecHost(), () -> LoaderApiTestSupport.captureErr(() -> {
            new NeoForge_1_20_4_to_1_20_5().registerRedirects(transformer);
            new Forge_1_20_to_NeoForge_1_21().registerRedirects(transformer);
        }));

        assertEquals(1, count(log, CODEC_NOTICE), log);
    }

    @Test
    @DisplayName("Fabric's init pass stays quiet after pre-launch logged the notices, and the 1.20.5 helper still wins its key")
    void fabricInitPassStaysQuietAndKeepsPrecedence() throws Exception {
        MethodKey setSecondsOnFire = new MethodKey("net/minecraft/world/entity/Entity", "setSecondsOnFire", "(I)V");
        for (String pass : new String[] {"pre-launch", "init"}) {
            // Pre-launch already logged the notices, so init registers again on the same transformer without logging.
            String log = LoaderApiTestSupport.captureErr(() -> {
                new Fabric_1_20_4_to_1_20_5().registerRedirects(transformer);
                new Fabric_1_21_1_to_1_21_2().registerRedirects(transformer);
            });

            int expected = pass.equals("pre-launch") ? 1 : 0;
            assertEquals(expected, count(log, "removed 1.20.5 members"), pass + ":\n" + log);
            assertEquals(expected, count(log, "Bridged 1.21.2 members"), pass + ":\n" + log);
            // The 1.20.5 shim maps it to igniteForSeconds(I)V, which 26.x no longer has.
            assertEquals(Legacy1205MemberBridge.HELPER,
                    transformer.getMethodRedirects().get(setSecondsOnFire).owner(),
                    "the bridge registered after the plain rename must still win on " + pass);
        }
    }

    private static int count(String log, String notice) {
        return (int) log.lines().filter(line -> line.contains(notice)).count();
    }

    private static <T> T withContextLoader(ClassLoader loader, Callable<T> body) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return body.call();
        } finally {
            thread.setContextClassLoader(saved);
        }
    }

    /** Serves {@code StructureType} as a class resource whose {@code codec()} returns a {@code MapCodec}. */
    private static final class MapCodecHost extends ClassLoader {
        MapCodecHost() {
            super(SharedBridgeNoticeTest.class.getClassLoader());
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (!name.equals(STRUCTURE_TYPE + ".class")) return super.getResourceAsStream(name);
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                    STRUCTURE_TYPE, null, "java/lang/Object", null);
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "codec",
                    "()Lcom/mojang/serialization/MapCodec;", null, null).visitEnd();
            cw.visitEnd();
            return new ByteArrayInputStream(cw.toByteArray());
        }
    }
}
