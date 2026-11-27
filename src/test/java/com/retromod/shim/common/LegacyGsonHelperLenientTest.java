/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.HostLibraryNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** An old lenient {@code GsonHelper.parse} call on 26.x still reads comments and trailing commas, and leaves strings as written. */
class LegacyGsonHelperLenientTest {

    private static final String GSON_HELPER = LegacyVanillaGapsBridge.GSON_HELPER;
    private static final String JSON_OBJECT = HostLibraryNames.GSON_PACKAGE + "/JsonObject";
    // The lenient reads, lifted out of the rebuilt class, whose other members need game classes this test lacks.
    private static final String LIFTED = "test/gaps/LenientJson";
    private static final Set<String> LENIENT_READS = Set.of("parse", "readAll", "withoutTrailingCommas");

    private static final String OLD_JSON = """
            {
              // a line comment
              "url": "https://example.com/a//b",
              "note": "/* kept */ and // kept",
              "quote": "a \\" // still the string",
              /* a block comment, with a comma, */
              "list": [1, 2, 3,],
              "nested": {"a": "x", /* last */ },
            }
            """;

    private static final String SAME_JSON_STRICT = "{\"url\":\"https://example.com/a//b\",\"note\":\"/* kept */ and // kept\","
            + "\"quote\":\"a \\\" // still the string\",\"list\":[1,2,3],\"nested\":{\"a\":\"x\"}}";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("A 1.21.1 mod's lenient GsonHelper.parse reaches the rebuilt read on a 26.3 host")
    void lenientParseReachesTheRebuiltRead() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        transformer.clearRedirectsForTesting();
        LegacyVanillaGapsBridge.register(transformer, name -> null, true);
        transformer.setModSourceVersion("1.21.1");
        String lenient = "(Ljava/lang/String;Z)L" + JSON_OBJECT + ";";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/gaps/ConfigReader", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "(Ljava/lang/String;)L" + JSON_OBJECT + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, GSON_HELPER, "parse", lenient, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/gaps/ConfigReader")).accept(out, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : out.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        assertEquals(List.of(LegacyVanillaGapsBridge.WORLD + ".parse" + lenient), calls);
    }

    @Test
    @DisplayName("Comments and trailing commas are dropped, and a // or /* inside a string stays")
    void commentsAndTrailingCommasAreDropped() throws Throwable {
        JsonObject expected = JsonParser.parseString(SAME_JSON_STRICT).getAsJsonObject();
        Class<?> reads = liftedReads();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();

        MethodHandle fromText = lookup.findStatic(reads, "parse", MethodType.methodType(JsonObject.class, String.class, boolean.class));
        assertEquals(expected, (JsonObject) fromText.invoke(OLD_JSON, true));
        MethodHandle fromReader = lookup.findStatic(reads, "parse", MethodType.methodType(JsonObject.class, Reader.class, boolean.class));
        assertEquals(expected, (JsonObject) fromReader.invoke(new StringReader(OLD_JSON), true), "a reader is read the same way");
    }

    @Test
    @DisplayName("Only commas that close an object or array go; a comma inside a string or a comment stays")
    void onlyClosingCommasGo() throws Exception {
        Method strip = liftedReads().getDeclaredMethod("withoutTrailingCommas", String.class);
        strip.setAccessible(true);
        assertEquals("{\"a\": \"x,}\" /* y, */ }", strip.invoke(null, "{\"a\": \"x,}\", /* y, */ }"));
        assertEquals("[1, 2 // three,\n]", strip.invoke(null, "[1, 2, // three,\n]"));
    }

    /** The rebuilt class's lenient reads alone, under a test name, so no game class is loaded. */
    private static Class<?> liftedReads() throws Exception {
        String rebuilt = LegacyVanillaGapsBridge.WORLD;
        ClassNode source = new ClassNode();
        try (InputStream in = LegacyGsonHelperLenientTest.class.getClassLoader().getResourceAsStream(rebuilt + ".class")) {
            new ClassReader(in.readAllBytes()).accept(source, 0);
        }
        ClassNode lifted = new ClassNode();
        lifted.visit(source.version, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, rebuilt, null, "java/lang/Object", null);
        for (MethodNode method : source.methods) {
            if (LENIENT_READS.contains(method.name)) method.accept(lifted);
        }
        lifted.visitEnd();
        ClassWriter writer = new ClassWriter(0);
        lifted.accept(new ClassRemapper(writer, new SimpleRemapper(rebuilt, LIFTED)));
        byte[] bytes = writer.toByteArray();
        ClassLoader loader = new ClassLoader(LegacyGsonHelperLenientTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.equals(LIFTED.replace('/', '.'))) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        return loader.loadClass(LIFTED.replace('/', '.'));
    }
}
