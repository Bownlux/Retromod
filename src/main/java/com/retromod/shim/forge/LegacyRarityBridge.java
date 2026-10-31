/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodVersion;
import com.retromod.util.McReflect;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyRarities;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Forge's {@code Rarity.create(String, ChatFormatting)}, its extensible-enum factory. NeoForge 1.21
 * removed it: a mod adds an enum value through an enum extension that NeoForge reads from the mod's
 * metadata and applies before {@code Rarity} loads. A 1.20.1 mod that builds its rarities in a
 * static initializer therefore stopped its item registry with {@code NoSuchMethodError}.
 *
 * <p>Two parts restore it. When a jar is translated, each {@code Rarity.create} call whose name and
 * color are constants is declared as an enum extension of that mod, with a generated class in the
 * mod's own package supplying the color. At run time the call finds the declared value by name.
 * A name built at run time cannot be declared ahead and gets the vanilla rarity of the same color.
 * Applies only where the host's {@code Rarity} is extensible and has no {@code create}.
 */
public final class LegacyRarityBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String RARITY = "net/minecraft/world/item/Rarity";
    static final String CHAT_FORMATTING = "net/minecraft/ChatFormatting";
    static final String CREATE_DESC = "(Ljava/lang/String;L" + CHAT_FORMATTING + ";)L" + RARITY + ";";
    static final String CALLS = "com/retromod/generated/LegacyRarityCalls";
    static final String HELPER = "com/retromod/shim/forge/embedded/LegacyRarities";
    static final String EXTENSIBLE = "net/neoforged/fml/common/asm/enumextension/IExtensibleEnum";
    /** The extension constructor, as NeoForge names it: id, serialized name, color. */
    static final String EXTENSION_CONSTRUCTOR = "(ILjava/lang/String;L" + CHAT_FORMATTING + ";)V";
    /** The same constructor with the enum's own name and ordinal, as the class file declares it. */
    private static final String DECLARED_CONSTRUCTOR = "(Ljava/lang/String;IILjava/lang/String;L" + CHAT_FORMATTING + ";)V";
    public static final String EXTENSIONS_FILE = "META-INF/retromod-enumextensions.json";
    static final String PARAMETERS_CLASS = "RetromodLegacyRarities";

    /**
     * One constant {@code Rarity.create(name, ChatFormatting.COLOR)} call, with the package of the
     * class that makes it.
     */
    public record Extension(String name, String color, String ownerPackage) {}

    private LegacyRarityBridge() {}

    public static void register(RetromodTransformer transformer) {
        ClassNode rarity = ClassResourceInspector.read(RARITY);
        if (!hostUsesEnumExtensions(rarity) && !hostDroppedCreate(rarity)) return;
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyRarities.class);
        transformer.registerSyntheticClass(CALLS, generateCalls());
        transformer.registerMethodRedirect(RARITY, "create", CREATE_DESC, CALLS, "create", CREATE_DESC);
    }

    /**
     * Forge 26.x: a plain enum with no {@code create} and no way to add a value, so every call gets
     * the vanilla rarity of the same color. Offline, a Forge target from 26.1.
     */
    static boolean hostDroppedCreate(ClassNode rarity) {
        if (McReflect.isNeoForge()) return false;
        if (rarity == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0;
        }
        boolean extensible = rarity.interfaces != null && rarity.interfaces.contains(EXTENSIBLE);
        return !extensible && rarity.methods.stream().noneMatch(m -> m.name.equals("create"));
    }

    /** Whether this host adds rarities by enum extension, so a jar's constant rarities need declaring. */
    public static boolean hostNeedsDeclarations() {
        return hostUsesEnumExtensions(ClassResourceInspector.read(RARITY));
    }

    /** Whether rarities are added by enum extension here: extensible, with no {@code create}. */
    public static boolean hostUsesEnumExtensions(ClassNode rarity) {
        if (rarity == null || rarity.interfaces == null || !rarity.interfaces.contains(EXTENSIBLE)) return false;
        boolean hasCreate = false;
        boolean hasExtensionConstructor = false;
        for (MethodNode method : rarity.methods) {
            if (method.name.equals("create") && method.desc.equals(CREATE_DESC)) hasCreate = true;
            if (method.name.equals("<init>") && method.desc.equals(DECLARED_CONSTRUCTOR)) hasExtensionConstructor = true;
        }
        return !hasCreate && hasExtensionConstructor;
    }

    /**
     * Declares the jar's constant rarities as enum extensions of the first mod in its metadata.
     * Returns how many were declared.
     */
    public static int declareInJar(Path classRoot, Path modsToml, List<Extension> extensions) throws IOException {
        if (extensions.isEmpty() || !Files.isRegularFile(modsToml)) return 0;
        String toml = Files.readString(modsToml, StandardCharsets.UTF_8);
        String modId = firstModId(toml);
        if (modId == null || toml.contains("enumExtensions")) return 0;
        // In the mod's own package, which no other module shares.
        String parameters = extensions.get(0).ownerPackage() + PARAMETERS_CLASS;
        Path parametersFile = classRoot.resolve(parameters + ".class");
        Files.createDirectories(parametersFile.getParent());
        Files.write(parametersFile, generateParameters(parameters, modId, extensions));
        Path json = classRoot.resolve(EXTENSIONS_FILE);
        Files.createDirectories(json.getParent());
        Files.writeString(json, extensionsJson(modId, parameters, extensions), StandardCharsets.UTF_8);
        Files.writeString(modsToml, withEnumExtensions(toml, modId), StandardCharsets.UTF_8);
        LOGGER.info("Declared {} rarities of {} as NeoForge enum extensions", extensions.size(), modId);
        return extensions.size();
    }

    /**
     * Finds {@code Rarity.create(name, ChatFormatting.COLOR)} calls whose name and color are
     * constants, in the jar's original classes. Each name is kept once.
     */
    public static List<Extension> scan(Path classRoot) throws IOException {
        Map<String, Extension> found = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(classRoot)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".class")
                    && !classRoot.relativize(p).toString().startsWith("META-INF")).toList()) {
                scanClass(Files.readAllBytes(file), found);
            }
        }
        return new ArrayList<>(found.values());
    }

    static void scanClass(byte[] bytes, Map<String, Extension> found) {
        if (!new String(bytes, StandardCharsets.ISO_8859_1).contains(CREATE_DESC)) return;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        String ownerPackage = node.name.contains("/") ? node.name.substring(0, node.name.lastIndexOf('/') + 1) : "";
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(RARITY)
                        || !call.name.equals("create") || !call.desc.equals(CREATE_DESC)) {
                    continue;
                }
                AbstractInsnNode color = previous(call);
                AbstractInsnNode name = color == null ? null : previous(color);
                if (color instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                        && field.owner.equals(CHAT_FORMATTING)
                        && name instanceof LdcInsnNode ldc && ldc.cst instanceof String text) {
                    found.putIfAbsent(text, new Extension(text, field.name, ownerPackage));
                }
            }
        }
    }

    private static AbstractInsnNode previous(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    /** NeoForge's enum constant name: the mod id first, as its loader requires. */
    static String constantName(String modId, String name) {
        String upperId = modId.toUpperCase(Locale.ROOT);
        String constant = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        return constant.startsWith(upperId + "_") ? constant : upperId + "_" + constant;
    }

    /** The serialized name, which NeoForge requires to start with the mod id and a colon. */
    static String serializedName(String modId, String name) {
        return name.startsWith(modId + ":") ? name : modId + ":" + name;
    }

    static String extensionsJson(String modId, String parametersClass, List<Extension> extensions) {
        StringBuilder json = new StringBuilder("{\"entries\":[");
        for (int i = 0; i < extensions.size(); i++) {
            if (i > 0) json.append(',');
            json.append("{\"enum\":\"").append(RARITY)
                    .append("\",\"name\":\"").append(constantName(modId, extensions.get(i).name()))
                    .append("\",\"constructor\":\"").append(EXTENSION_CONSTRUCTOR)
                    .append("\",\"parameters\":{\"class\":\"").append(parametersClass)
                    .append("\",\"method\":\"rarity").append(i).append("\"}}");
        }
        return json.append("]}").toString();
    }

    private static final Pattern MODS_BLOCK = Pattern.compile("(?m)^\\s*\\[\\[mods]]\\s*$");
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");

    static String firstModId(String toml) {
        Matcher block = MODS_BLOCK.matcher(toml);
        if (!block.find()) return null;
        Matcher id = MOD_ID.matcher(toml);
        return id.find(block.end()) ? id.group(1) : null;
    }

    /** Adds the extensions file to the {@code [[mods]]} entry of {@code modId}. */
    static String withEnumExtensions(String toml, String modId) {
        Matcher id = MOD_ID.matcher(toml);
        while (id.find()) {
            if (id.group(1).equals(modId)) {
                int lineEnd = toml.indexOf('\n', id.end());
                String line = "enumExtensions=\"" + EXTENSIONS_FILE + "\"";
                return lineEnd < 0 ? toml + "\n" + line + "\n"
                        : toml.substring(0, lineEnd + 1) + line + "\n" + toml.substring(lineEnd + 1);
            }
        }
        return toml;
    }

    /**
     * {@code static Object rarityN(int index, Class type)} per rarity: the serialized name at index
     * 1 and the color at index 2. NeoForge assigns the id at index 0 itself.
     */
    static byte[] generateParameters(String internalName, String modId, List<Extension> extensions) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                internalName, null, "java/lang/Object", null);
        for (int i = 0; i < extensions.size(); i++) {
            Extension extension = extensions.get(i);
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "rarity" + i,
                    "(ILjava/lang/Class;)Ljava/lang/Object;", null, null);
            mv.visitCode();
            Label notName = new Label();
            Label notColor = new Label();
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, notName);
            mv.visitLdcInsn(serializedName(modId, extension.name()));
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(notName);
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitInsn(Opcodes.ICONST_2);
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, notColor);
            mv.visitFieldInsn(Opcodes.GETSTATIC, CHAT_FORMATTING, extension.color(), "L" + CHAT_FORMATTING + ";");
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitLabel(notColor);
            mv.visitInsn(Opcodes.ICONST_M1);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static Rarity create(String, ChatFormatting)}, finding the declared value. */
    static byte[] generateCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                CALLS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create", CREATE_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "find",
                "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, RARITY);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
