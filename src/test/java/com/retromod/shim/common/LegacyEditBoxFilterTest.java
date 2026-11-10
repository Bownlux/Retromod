/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.neoforge.NeoForge_1_21_10_to_1_21_11;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static com.retromod.shim.common.BridgeHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/** A config screen's number field, whose {@code EditBox.setFilter} 26.1 removed along with the box's veto. */
class LegacyEditBoxFilterTest {

    private static final String FIELDS = "mod/Fields";
    private static final String BOX = "net/minecraft/client/gui/components/EditBox";
    private static final String SET_FILTER = BOX + ".setFilter(Ljava/util/function/Predicate;)V";
    private static final String SET_RESPONDER = BOX + ".setResponder(Ljava/util/function/Consumer;)V";

    @TempDir
    static Path dir;

    private static Map<String, byte[]> mod;
    private static Map<String, byte[]> host261;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeAll
    static void compileShapes() throws Exception {
        mod = only(compile(dir.resolve("old"), Map.of(
                "net/minecraft/client/gui/components/EditBox.java", """
                        package net.minecraft.client.gui.components;
                        public class EditBox {
                            public void setFilter(java.util.function.Predicate<String> filter) {}
                            public void setResponder(java.util.function.Consumer<String> responder) {}
                            public void setValue(String value) {}
                            public String getValue() { return null; }
                            public void insertText(String text) {}
                            public void setCursorPosition(int position) {}
                            public void setHighlightPos(int position) {}
                            public int getCursorPosition() { return 0; }
                        }""",
                "mod/Fields.java", """
                        package mod;
                        import net.minecraft.client.gui.components.EditBox;
                        public class Fields {
                            static boolean digits(String s) { return s.chars().allMatch(Character::isDigit); }
                            /** Types into a field holding 12, with the cursor after the 1. */
                            public static String type(String typed, boolean responderFirst) {
                                EditBox box = new EditBox();
                                StringBuilder seen = new StringBuilder();
                                box.setValue("12");
                                if (responderFirst) box.setResponder(v -> seen.append(v).append(';'));
                                box.setFilter(Fields::digits);
                                if (!responderFirst) box.setResponder(v -> seen.append(v).append(';'));
                                box.setCursorPosition(1);
                                box.setHighlightPos(1);
                                box.insertText(typed);
                                return box.getValue() + "|" + box.getCursorPosition() + "|" + seen;
                            }
                            /** Sets the text from code, as a reset button does. */
                            public static String set(String value) {
                                EditBox box = new EditBox();
                                StringBuilder seen = new StringBuilder();
                                box.setFilter(Fields::digits);
                                box.setResponder(v -> seen.append(v).append(';'));
                                box.setValue(value);
                                return box.getValue() + "|" + seen;
                            }
                        }""")), "mod/");
        // 26.1's shape: no filter, and every change reaches the responder.
        host261 = compile(dir.resolve("host"), Map.of(
                "net/minecraft/client/gui/components/EditBox.java", """
                        package net.minecraft.client.gui.components;
                        import java.util.function.Consumer;
                        public class EditBox {
                            private String value = "";
                            private int cursorPos;
                            private int highlightPos;
                            private Consumer<String> responder;
                            public void setResponder(Consumer<String> responder) { this.responder = responder; }
                            public String getValue() { return value; }
                            public void setValue(String value) {
                                this.value = value;
                                cursorPos = highlightPos = value.length();
                                onValueChange(value);
                            }
                            public void insertText(String text) {
                                int start = Math.min(cursorPos, highlightPos);
                                int end = Math.max(cursorPos, highlightPos);
                                value = new StringBuilder(value).replace(start, end, text).toString();
                                cursorPos = highlightPos = start + text.length();
                                onValueChange(value);
                            }
                            public void setCursorPosition(int position) { cursorPos = Math.max(0, Math.min(position, value.length())); }
                            public int getCursorPosition() { return cursorPos; }
                            public void setHighlightPos(int position) { highlightPos = Math.max(0, Math.min(position, value.length())); }
                            private void onValueChange(String value) { if (responder != null) responder.accept(value); }
                        }"""));
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("on 26.1 setFilter fails as written")
    void oldCallFails() {
        Run old = new Run(host261, mod);
        assertInstanceOf(NoSuchMethodError.class, assertThrows(Throwable.class, () -> old.call(FIELDS, "type", "3", false)));
    }

    @Test
    @DisplayName("once bridged the filter refuses an edit as 1.21.1 did: text, cursor and responder stay put")
    void filterRefusesEdits() throws Throwable {
        Run run = new Run(host261, transform("26.1", Common_1_21_11_to_26_1_ClassMoves::registerEditBoxFilter, mod));
        assertEquals("132|2|132;", run.call(FIELDS, "type", "3", false), "an accepted edit reaches the responder");
        assertEquals("12|1|", run.call(FIELDS, "type", "x", false), "a refused edit changes nothing");
        assertEquals("12|1|", run.call(FIELDS, "type", "x", true), "a responder set before the filter keeps working");
        assertEquals("152|2|152;", run.call(FIELDS, "type", "5", true));
        assertEquals("|", run.call(FIELDS, "set", "abc"), "refused text from code is ignored, without a responder call");
        assertEquals("42|42;", run.call(FIELDS, "set", "42"));
    }

    @Test
    @DisplayName("the 26.1 shim bridges the calls, the 1.21.11 shim leaves them, and a 26.1 mod's setResponder stays direct")
    void gatedOnTheHostAndTheMod() {
        assertTrue(calls(transform("26.1", t -> new NeoForge_1_21_11_to_26_1().registerRedirects(t), mod).get(FIELDS),
                "type").stream().noneMatch(call -> call.equals(SET_FILTER) || call.equals(SET_RESPONDER)));
        assertTrue(calls(transform("1.21.11", t -> new NeoForge_1_21_10_to_1_21_11().registerRedirects(t), mod)
                .get(FIELDS), "type").containsAll(java.util.List.of(SET_FILTER, SET_RESPONDER)));
        Map<String, byte[]> newerMod = transform("26.2", t -> {
            Common_1_21_11_to_26_1_ClassMoves.registerEditBoxFilter(t);
            t.setModSourceVersion("26.1");
        }, mod);
        assertTrue(calls(newerMod.get(FIELDS), "type").contains(SET_RESPONDER));
    }
}
