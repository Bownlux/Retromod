/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.shim.fabric.embedded.LegacyArmorMaterialSupport;
import com.retromod.shim.fabric.embedded.LegacyParticleSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The runtime helpers call a mod's old methods by intermediary name because each mod carries its
 * own relocated copy of the generated interfaces. These fixtures stand in for mod classes.
 */
class LegacyReflectiveSupportTest {

    /** Shaped like an anonymous pre-1.20.5 ParticleOptions.Deserializer. */
    static class FireDeserializer {
        public Object method_10297(Object type, Object buf) {
            return "read " + buf;
        }
    }

    /** Shaped like a generated LegacyParticleType carrying the mod's deserializer. */
    public static class FireParticleType {
        public Object retromod$legacyDeserializer = new FireDeserializer();
    }

    /** Shaped like a pre-1.20.5 ParticleOptions with writeToNetwork. */
    public static class FireOptions {
        Object written;
        public void method_10294(Object buf) {
            written = buf;
        }
    }

    /** Shaped like a library's old ArmorMaterial implementation. */
    static class LibraryArmorMaterial {
        public int method_48403(Object type) {
            return 6;
        }
        public float method_7700() {
            return 2.5f;
        }
    }

    @Test
    @DisplayName("A legacy particle type reads network data through its own deserializer")
    void particleReadsThroughModDeserializer() {
        assertEquals("read buf", LegacyParticleSupport.readFromNetwork(new FireParticleType(), "buf"));
    }

    @Test
    @DisplayName("Legacy particle options write themselves through writeToNetwork")
    void particleWritesThroughModOptions() {
        FireOptions options = new FireOptions();
        LegacyParticleSupport.writeToNetwork(options, "buf");
        assertEquals("buf", options.written);
    }

    @Test
    @DisplayName("A type without a legacy deserializer fails with a message naming it")
    void particleWithoutDeserializerExplainsFailure() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> LegacyParticleSupport.readFromNetwork("plain", "buf"));
        assertTrue(failure.getMessage().contains("plain"), failure.getMessage());
    }

    @Test
    @DisplayName("Armor material values are read from a package-private library class")
    void armorMaterialReadAcrossModCopies() {
        LibraryArmorMaterial material = new LibraryArmorMaterial();
        assertEquals(6, LegacyArmorMaterialSupport.intValue(material, "method_48403", "HELMET"));
        assertEquals(2.5f, LegacyArmorMaterialSupport.floatValue(material, "method_7700"));
        assertNull(LegacyArmorMaterialSupport.viewHolder(material),
                "a mod material is not a vanilla view and must be built into a record");
    }
}
