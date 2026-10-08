/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every mod gets its own embedded copy of the registry id bridge. Porting Lib's
 * {@code DeferredRegister} lives in a nested library jar and registers items that Sophisticated
 * Core's own lambda builds, so the library's copy pushed the id and Sophisticated Core's copy read
 * nothing: {@code NullPointerException: Item id not set} (#249). These tests load two relocated
 * copies against stand-in Minecraft classes and check that the id crosses between them.
 */
class FabricRegistryIdSharedChannelTest {

    private static final String STUB = "com/retromod/shim/fabric/mcstub/";
    private static final String COPY_A = "com/retromod/embedded/library/FabricRegistryIdBridge";
    private static final String COPY_B = "com/retromod/embedded/mod/FabricRegistryIdBridge";

    /** Stand-in sources and the Minecraft names the bridge links against. */
    private static final Map<String, String> MINECRAFT_NAMES = Map.of(
            STUB + "StubIdentifier", "net/minecraft/resources/Identifier",
            STUB + "StubResourceKey", "net/minecraft/resources/ResourceKey",
            STUB + "StubRegistries", "net/minecraft/core/registries/Registries",
            STUB + "StubItemProperties", "net/minecraft/world/item/Item$Properties",
            STUB + "StubBlockBehaviour", "net/minecraft/world/level/block/state/BlockBehaviour",
            STUB + "StubBlockBehaviour$Properties",
            "net/minecraft/world/level/block/state/BlockBehaviour$Properties");

    @Test
    void anIdPushedByOneCopyStampsPropertiesBuiltThroughAnother() throws Exception {
        Fixture fixture = new Fixture();
        Object id = fixture.identifier("testmod:soul_bucket");

        fixture.call(COPY_A, "push", id);
        try {
            Object stamped = fixture.call(COPY_B, "itemProps");
            Object key = stamped.getClass().getField("id").get(stamped);
            assertNotNull(key, "the mod's copy must see the id the library's copy pushed");
            Object location = key.getClass().getField("location").get(key);
            assertEquals("testmod:soul_bucket", String.valueOf(location),
                    "the stamped key must carry the pushed id");
        } finally {
            fixture.call(COPY_A, "pop");
        }
    }

    @Test
    void popClearsTheSharedIdSoLaterPropertiesStayUntouched() throws Exception {
        Fixture fixture = new Fixture();
        fixture.call(COPY_A, "push", fixture.identifier("testmod:first"));
        fixture.call(COPY_A, "pop");

        Object untouched = fixture.call(COPY_B, "itemProps");
        assertNull(untouched.getClass().getField("id").get(untouched),
                "outside a registration the bridge must not invent a key");
        assertNull(System.getProperty(FabricRegistryIdSynthetic.SHARED_ID_PROPERTY_PREFIX
                        + Thread.currentThread().getId()),
                "pop must remove the shared property it set");
    }

    /** Two relocated bridge copies and the stand-in Minecraft classes in one loader. */
    private static final class Fixture extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();

        Fixture() throws IOException {
            super(Fixture.class.getClassLoader());
            for (Map.Entry<String, String> stub : MINECRAFT_NAMES.entrySet()) {
                classes.put(stub.getValue(), remap(readResource(stub.getKey()), Map.of()));
            }
            byte[] bridge = FabricRegistryIdSynthetic.generate();
            classes.put(COPY_A, remap(bridge, Map.of(FabricRegistryIdSynthetic.INTERNAL, COPY_A)));
            classes.put(COPY_B, remap(bridge, Map.of(FabricRegistryIdSynthetic.INTERNAL, COPY_B)));
        }

        Object identifier(String value) throws Exception {
            return loadClass("net.minecraft.resources.Identifier")
                    .getMethod("tryParse", String.class).invoke(null, value);
        }

        Object call(String owner, String name, Object... args) throws Exception {
            Class<?> type = loadClass(owner.replace('/', '.'));
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                    return method.invoke(null, args);
                }
            }
            throw new NoSuchMethodException(owner + "." + name);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
                return super.loadClass(name, resolve);
            }
        }

        private static byte[] remap(byte[] bytes, Map<String, String> extra) {
            Map<String, String> names = new HashMap<>(MINECRAFT_NAMES);
            names.putAll(extra);
            ClassWriter writer = new ClassWriter(0);
            new ClassReader(bytes).accept(new ClassRemapper(writer, new SimpleRemapper(names)), 0);
            return writer.toByteArray();
        }

        private static byte[] readResource(String internalName) throws IOException {
            try (InputStream in = Fixture.class.getClassLoader()
                    .getResourceAsStream(internalName + ".class")) {
                assertNotNull(in, internalName + " must be on the test classpath");
                return in.readAllBytes();
            }
        }
    }
}
