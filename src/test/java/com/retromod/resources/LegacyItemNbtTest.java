/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Old item NBT read with its types, and written so every later parser reads the same values. */
class LegacyItemNbtTest {

    @Test
    @DisplayName("Numbers keep the type their suffix or their shape gave them")
    void numbersKeepTheirTypes() {
        LegacyItemNbt.Compound tag = LegacyItemNbt.parseCompound(
                "{a:1b,b:2s,c:3,d:4L,e:5.5f,f:6.25,g:true,h:-7}");

        assertNotNull(tag);
        assertEquals("{a:1b,b:2s,c:3,d:4L,e:5.5f,f:6.25d,g:1b,h:-7}", LegacyItemNbt.write(tag));
    }

    @Test
    @DisplayName("Quoted keys and strings, lists and typed arrays survive a round trip")
    void structuresRoundTrip() {
        String snbt = "{\"odd key\":\"text with \\\"quotes\\\"\",list:[1,2],ints:[I;1,-2],nested:{x:'single'}}";

        LegacyItemNbt.Compound tag = LegacyItemNbt.parseCompound(snbt);

        assertEquals("{\"odd key\":\"text with \\\"quotes\\\"\",list:[1,2],ints:[I;1,-2],nested:{x:\"single\"}}",
                LegacyItemNbt.write(tag));
        assertEquals(LegacyItemNbt.write(tag), LegacyItemNbt.write(LegacyItemNbt.parseCompound(LegacyItemNbt.write(tag))));
    }

    @Test
    @DisplayName("An object nbt field is its own JSON text read as SNBT, as Forge read it")
    void objectFieldReadsAsSnbt() {
        LegacyItemNbt.Compound tag = LegacyItemNbt.parseField(JsonParser.parseString("{\"Damage\":5,\"Scale\":1.5}"));

        assertEquals("{Damage:5,Scale:1.5d}", LegacyItemNbt.write(tag));
    }

    @Test
    @DisplayName("Text the old parser rejected comes back as null")
    void malformedIsNull() {
        assertNull(LegacyItemNbt.parseCompound("{a:1"));
        assertNull(LegacyItemNbt.parseCompound("[1,2]"));
        assertNull(LegacyItemNbt.parseCompound("{a:[I;1b]}"));
    }

    @Test
    @DisplayName("Only values whose JSON form narrows back to the same type count as JSON-safe")
    void jsonKeepsTypes() {
        assertTrue(LegacyItemNbt.jsonKeepsTypes(LegacyItemNbt.parseCompound("{a:3b,b:\"x\",c:{d:300s}}")));
        assertFalse(LegacyItemNbt.jsonKeepsTypes(LegacyItemNbt.parseCompound("{a:3}")),
                "an int 3 would come back a byte");
        assertFalse(LegacyItemNbt.jsonKeepsTypes(LegacyItemNbt.parseCompound("{a:[I;1]}")));
    }
}
