/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Key binding categories from AutoClicky (a plain name) and PhantomShapes (a translation key). */
class LegacyKeyCategoryNamesTest {

    private static final String KEY_MAPPING = "net/minecraft/class_304";

    @Test
    void namesEachStringCategoryInTheModsLanguages() {
        Map<String, byte[]> jar = new LinkedHashMap<>();
        jar.put("autoclicky/Keys.class", keyBindingClass("autoclicky/Keys", "AutoClicky"));
        jar.put("phantomshapes/Keys.class", keyBindingClass("phantomshapes/Keys", "category.phantomshapes.controls"));
        jar.put("vanilla/Keys.class", keyBindingClass("vanilla/Keys", "key.categories.misc"));
        jar.put("assets/phantomshapes/lang/en_us.json", utf8("{\"category.phantomshapes.controls\":\"Phantom Shapes\"}"));
        jar.put("assets/phantomshapes/lang/de_de.json", utf8("{\"category.phantomshapes.controls\":\"Phantomformen\"}"));

        Map<String, byte[]> added = LegacyKeyCategoryNames.entries(jar.keySet(), jar::get);

        JsonObject english = json(added.get("assets/retromod_compat/lang/en_us.json"));
        assertEquals("AutoClicky", english.get("key.category.retromod_compat.autoclicky").getAsString(),
                "a plain name is shown as written, as before 1.21.9");
        assertEquals("Phantom Shapes",
                english.get("key.category.retromod_compat.category_phantomshapes_controls").getAsString());
        assertEquals(2, english.size(), "a vanilla category keeps its own name");

        JsonObject german = json(added.get("assets/retromod_compat/lang/de_de.json"));
        assertEquals("Phantomformen",
                german.get("key.category.retromod_compat.category_phantomshapes_controls").getAsString());
        assertFalse(german.has("key.category.retromod_compat.autoclicky"),
                "an untranslated name falls back to English, as the game does");
    }

    @Test
    void appliesFromTheVersionThatTitlesCategoriesById() {
        assertFalse(LegacyKeyCategoryNames.appliesTo("1.21.8"));
        assertTrue(LegacyKeyCategoryNames.appliesTo("1.21.9"));
        assertTrue(LegacyKeyCategoryNames.appliesTo("26.3"));
    }

    @Test
    void categoryPathIsAValidIdentifierPath() {
        assertEquals("my_mod_s_keys", LegacyKeyCategoryNames.categoryPath("My Mod's Keys"));
        assertEquals("custom", LegacyKeyCategoryNames.categoryPath(""));
    }

    private static byte[] keyBindingClass(String name, String category) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, KEY_MAPPING);
        mv.visitInsn(DUP);
        mv.visitLdcInsn("key.example.toggle");
        mv.visitInsn(ACONST_NULL);
        mv.visitIntInsn(BIPUSH, 82);
        mv.visitLdcInsn(category);
        mv.visitMethodInsn(INVOKESPECIAL, KEY_MAPPING, "<init>",
                "(Ljava/lang/String;Lnet/minecraft/class_3675$class_307;ILjava/lang/String;)V", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject json(byte[] bytes) {
        assertNotNull(bytes, "the language file was written");
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
