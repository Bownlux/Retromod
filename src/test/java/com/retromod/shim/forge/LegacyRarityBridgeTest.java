/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #283: Scorched Guns builds eleven rarities with Forge's {@code Rarity.create(name, color)} in a
 * static initializer. NeoForge 1.21 adds enum values only through extensions declared in the mod's
 * metadata, so the translated jar declares them and the call finds the declared value.
 */
class LegacyRarityBridgeTest {

    private static final String RARITY = LegacyRarityBridge.RARITY;
    private static final String FORMATTING = LegacyRarityBridge.CHAT_FORMATTING;
    private static final String TOML = """
            modLoader="javafml"
            loaderVersion="[1,)"
            license="MIT"
            [[mods]]
            modId="scguns"
            version="0.5.5"
            [[dependencies.scguns]]
            modId="minecraft"
            versionRange="[1.20,)"
            """;

    @Test
    @DisplayName("constant Rarity.create calls become enum extensions in the mod's metadata")
    void constantRaritiesAreDeclared(@TempDir Path jar) throws Exception {
        Files.createDirectories(jar.resolve("top/ribs/scguns/util"));
        Files.write(jar.resolve("top/ribs/scguns/util/Constants.class"), constants());
        Path toml = jar.resolve("META-INF/neoforge.mods.toml");
        Files.createDirectories(toml.getParent());
        Files.writeString(toml, TOML);

        List<LegacyRarityBridge.Extension> found = LegacyRarityBridge.scan(jar);
        assertEquals(2, LegacyRarityBridge.declareInJar(jar, toml, found));

        assertTrue(Files.readString(toml).contains(
                "modId=\"scguns\"\nenumExtensions=\"META-INF/retromod-enumextensions.json\"\n"),
                "the extensions file belongs to the scguns entry");
        JsonObject first = JsonParser.parseString(Files.readString(jar.resolve(LegacyRarityBridge.EXTENSIONS_FILE)))
                .getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals(RARITY, first.get("enum").getAsString());
        assertEquals("SCGUNS_OCEANIC", first.get("name").getAsString(),
                "NeoForge requires the constant to start with the mod id");
        assertEquals("(ILjava/lang/String;L" + FORMATTING + ";)V", first.get("constructor").getAsString());
        assertEquals("top/ribs/scguns/util/RetromodLegacyRarities",
                first.getAsJsonObject("parameters").get("class").getAsString());
        assertTrue(Files.isRegularFile(jar.resolve("top/ribs/scguns/util/RetromodLegacyRarities.class")));
    }

    @Test
    @DisplayName("the parameter class hands NeoForge the prefixed name and the color")
    void parametersSupplyNameAndColor() throws Exception {
        List<LegacyRarityBridge.Extension> extensions = List.of(
                new LegacyRarityBridge.Extension("scguns.oceanic", "BLUE", "test/"));
        Isolated host = new Isolated();
        host.add(FORMATTING, enumClass(FORMATTING, new String[] {"WHITE", "BLUE"}, false));
        host.add("test/RetromodLegacyRarities",
                LegacyRarityBridge.generateParameters("test/RetromodLegacyRarities", "scguns", extensions));

        Method rarity0 = host.load("test/RetromodLegacyRarities").getMethod("rarity0", int.class, Class.class);

        assertEquals("scguns:scguns.oceanic", rarity0.invoke(null, 1, String.class),
                "NeoForge requires the serialized name to start with the mod id and a colon");
        assertEquals("BLUE", ((Enum<?>) rarity0.invoke(null, 2, Object.class)).name());
    }

    @Test
    @DisplayName("the old call finds the declared rarity by its name")
    void createFindsTheDeclaredValue() throws Exception {
        Isolated host = new Isolated();
        host.add(FORMATTING, enumClass(FORMATTING, new String[] {"WHITE", "BLUE"}, false));
        host.add(RARITY, enumClass(RARITY, new String[] {"COMMON", "SCGUNS_OCEANIC"}, true));
        host.add(LegacyRarityBridge.HELPER, resource(LegacyRarityBridge.HELPER));
        host.add(LegacyRarityBridge.CALLS, LegacyRarityBridge.generateCalls());
        Class<?> formatting = host.load(FORMATTING);
        Method create = host.load(LegacyRarityBridge.CALLS).getMethod("create", String.class, formatting);
        Object blue = formatting.getField("BLUE").get(null);

        Object declared = create.invoke(null, "scguns.oceanic", blue);
        Object undeclared = create.invoke(null, "scguns.built_later", blue);

        assertEquals("SCGUNS_OCEANIC", ((Enum<?>) declared).name());
        assertEquals("COMMON", ((Enum<?>) undeclared).name(),
                "a name the jar did not declare falls back to a vanilla rarity");
    }

    @Test
    @DisplayName("only an extensible Rarity without create needs the bridge")
    void hostCheck() {
        ClassNode neo = rarityNode(true, false);
        ClassNode forge = rarityNode(true, true);
        ClassNode vanilla = rarityNode(false, false);

        assertTrue(LegacyRarityBridge.hostUsesEnumExtensions(neo));
        assertFalse(LegacyRarityBridge.hostUsesEnumExtensions(forge), "Forge still has create");
        assertFalse(LegacyRarityBridge.hostUsesEnumExtensions(vanilla), "nothing can extend a plain enum");
    }

    @Test
    @DisplayName("Forge 26.x, a plain Rarity without create, gets the same-color fallback")
    void forgeWithoutCreateFallsBack() {
        assertTrue(LegacyRarityBridge.hostDroppedCreate(rarityNode(false, false)));
        assertFalse(LegacyRarityBridge.hostDroppedCreate(rarityNode(true, true)), "Forge 1.20.1 still has create");
        assertFalse(LegacyRarityBridge.hostDroppedCreate(rarityNode(true, false)), "NeoForge declares extensions instead");
    }

    // --- fixtures -----------------------------------------------------------------------------

    /** {@code Constants.<clinit>}: two constant rarities, as Scorched Guns declares them. */
    private static byte[] constants() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "top/ribs/scguns/util/Constants", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        String[][] rarities = {{"scguns.oceanic", "BLUE"}, {"scguns.unique", "GREEN"}};
        for (String[] rarity : rarities) {
            mv.visitLdcInsn(rarity[0]);
            mv.visitFieldInsn(GETSTATIC, FORMATTING, rarity[1], "L" + FORMATTING + ";");
            mv.visitMethodInsn(INVOKESTATIC, RARITY, "create", LegacyRarityBridge.CREATE_DESC, false);
            mv.visitInsn(POP);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode rarityNode(boolean extensible, boolean withCreate) {
        ClassNode node = new ClassNode();
        node.name = RARITY;
        node.interfaces = extensible ? List.of(LegacyRarityBridge.EXTENSIBLE) : List.of();
        node.methods.add(new MethodNode(ACC_PRIVATE, "<init>",
                "(Ljava/lang/String;IILjava/lang/String;L" + FORMATTING + ";)V", null, null));
        if (withCreate) {
            node.methods.add(new MethodNode(ACC_PUBLIC | ACC_STATIC, "create", LegacyRarityBridge.CREATE_DESC, null, null));
        }
        return node;
    }

    /**
     * An enum with the given constants. A rarity variant also answers {@code getSerializedName()}
     * as {@code "minecraft:" + lower name}, or {@code "scguns:scguns.oceanic"} for the mod's one.
     */
    private static byte[] enumClass(String name, String[] constants, boolean rarity) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_ENUM, name, null, "java/lang/Enum", null);
        String self = "L" + name + ";";
        for (String constant : constants) {
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL | ACC_ENUM, constant, self, null, null).visitEnd();
        }
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL | ACC_SYNTHETIC, "$VALUES", "[" + self, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "serialized", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PRIVATE, "<init>", "(Ljava/lang/String;ILjava/lang/String;)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ILOAD, 2);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 3);
        init.visitFieldInsn(PUTFIELD, name, "serialized", "Ljava/lang/String;");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitLdcInsn(constants.length);
        clinit.visitTypeInsn(ANEWARRAY, name);
        for (int i = 0; i < constants.length; i++) {
            clinit.visitInsn(DUP);
            clinit.visitLdcInsn(i);
            clinit.visitTypeInsn(NEW, name);
            clinit.visitInsn(DUP);
            clinit.visitLdcInsn(constants[i]);
            clinit.visitLdcInsn(i);
            clinit.visitLdcInsn(constants[i].startsWith("SCGUNS_") ? "scguns:scguns.oceanic"
                    : "minecraft:" + constants[i].toLowerCase(java.util.Locale.ROOT));
            clinit.visitMethodInsn(INVOKESPECIAL, name, "<init>", "(Ljava/lang/String;ILjava/lang/String;)V", false);
            clinit.visitInsn(DUP);
            clinit.visitFieldInsn(PUTSTATIC, name, constants[i], self);
            clinit.visitInsn(AASTORE);
        }
        clinit.visitFieldInsn(PUTSTATIC, name, "$VALUES", "[" + self);
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        MethodVisitor values = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "values", "()[" + self, null, null);
        values.visitCode();
        values.visitFieldInsn(GETSTATIC, name, "$VALUES", "[" + self);
        values.visitMethodInsn(INVOKEVIRTUAL, "[" + self, "clone", "()Ljava/lang/Object;", false);
        values.visitTypeInsn(CHECKCAST, "[" + self);
        values.visitInsn(ARETURN);
        values.visitMaxs(0, 0);
        values.visitEnd();
        if (rarity) {
            MethodVisitor serialized = cw.visitMethod(ACC_PUBLIC, "getSerializedName", "()Ljava/lang/String;", null, null);
            serialized.visitCode();
            serialized.visitVarInsn(ALOAD, 0);
            serialized.visitFieldInsn(GETFIELD, name, "serialized", "Ljava/lang/String;");
            serialized.visitInsn(ARETURN);
            serialized.visitMaxs(0, 0);
            serialized.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] resource(String internalName) {
        try (InputStream in = LegacyRarityBridgeTest.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new AssertionError("missing " + internalName, e);
        }
    }

    /** Defines only the classes given, apart from the test classpath. */
    private static final class Isolated extends ClassLoader {
        private final Map<String, byte[]> classes = new HashMap<>();
        private final Map<String, Class<?>> defined = new HashMap<>();

        Isolated() {
            super(LegacyRarityBridgeTest.class.getClassLoader());
        }

        void add(String internalName, byte[] bytes) {
            classes.put(internalName.replace('/', '.'), bytes);
        }

        Class<?> load(String internalName) throws ClassNotFoundException {
            return loadClass(internalName.replace('/', '.'));
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) return super.loadClass(name, resolve);
            Class<?> type = defined.get(name);
            if (type == null) {
                type = defineClass(name, bytes, 0, bytes.length);
                defined.put(name, type);
            }
            return type;
        }
    }
}
