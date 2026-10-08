/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.objectweb.asm.Opcodes.*;

/**
 * Registers banner patterns that a pre-1.19 Forge mod creates with {@code BannerPattern.create}.
 *
 * <p>Forge 1.18 extended the {@code BannerPattern} enum. 1.19 made patterns registry entries and
 * gave each pattern item a tag of patterns instead of one pattern, so the mod's static initializer
 * failed and took its item registration with it. The old call now builds a pattern and registers
 * it under the old file name, which is also where 1.19 looks for its texture. The pattern item gets
 * the {@code pattern_item/<name>} tag that vanilla items use, and that tag, plus the
 * {@code no_item_required} entry for patterns that never had an item, is written into the mod's
 * data from the constant arguments of each call. A call whose arguments are not constants still
 * registers its pattern, but its item shows no pattern in the loom.
 *
 * <p>Hosts from 1.19.3 up to 1.20.4 only: 1.20.5 moved patterns into data packs.
 */
public final class LegacyBannerPatternBridge {

    static final String SYNTH = "com/retromod/generated/LegacyBannerPatterns";

    private static final String PATTERN = "net/minecraft/world/level/block/entity/BannerPattern";
    private static final String ITEM = "net/minecraft/world/item/BannerPatternItem";
    private static final String PROPERTIES = "net/minecraft/world/item/Item$Properties";
    private static final String LOCATION = "net/minecraft/resources/ResourceLocation";
    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String TAG_KEY = "net/minecraft/tags/TagKey";
    private static final String CREATE_DESC =
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)L" + PATTERN + ";";

    private LegacyBannerPatternBridge() {
    }

    /** Whether {@code host} keeps banner patterns in a built-in registry. */
    public static boolean appliesTo(String host) {
        return RetromodVersion.compareMcVersions(host, "1.19.3") >= 0
                && RetromodVersion.compareMcVersions(host, "1.20.5") < 0;
    }

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(SYNTH, generate());
        transformer.registerMethodRedirect(PATTERN, "create", CREATE_DESC, SYNTH, "create", CREATE_DESC);
        transformer.registerConstructorRedirect(ITEM, "(L" + PATTERN + ";L" + PROPERTIES + ";)V",
                SYNTH, "item", "(L" + PATTERN + ";L" + PROPERTIES + ";)L" + ITEM + ";");
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SYNTH, null, "java/lang/Object", null);

        // Registration fails once the registry is frozen. The pattern still works as an object.
        MethodVisitor create = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create", CREATE_DESC, null, null);
        create.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label failed = new Label();
        Label done = new Label();
        create.visitTypeInsn(NEW, PATTERN);
        create.visitInsn(DUP);
        create.visitVarInsn(ALOAD, 2);
        create.visitMethodInsn(INVOKESPECIAL, PATTERN, "<init>", "(Ljava/lang/String;)V", false);
        create.visitVarInsn(ASTORE, 4);
        create.visitTryCatchBlock(start, end, failed, "java/lang/RuntimeException");
        create.visitLabel(start);
        builtInPatterns(create);
        create.visitTypeInsn(NEW, LOCATION);
        create.visitInsn(DUP);
        create.visitVarInsn(ALOAD, 1);
        create.visitMethodInsn(INVOKESPECIAL, LOCATION, "<init>", "(Ljava/lang/String;)V", false);
        create.visitVarInsn(ALOAD, 4);
        create.visitMethodInsn(INVOKESTATIC, REGISTRY, "register",
                "(L" + REGISTRY + ";L" + LOCATION + ";Ljava/lang/Object;)Ljava/lang/Object;", true);
        create.visitInsn(POP);
        create.visitLabel(end);
        create.visitJumpInsn(GOTO, done);
        create.visitLabel(failed);
        create.visitVarInsn(ASTORE, 5);
        create.visitFieldInsn(GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
        create.visitLdcInsn("[Retromod] Could not register the old banner pattern ");
        create.visitVarInsn(ALOAD, 1);
        create.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        create.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        create.visitLabel(done);
        create.visitVarInsn(ALOAD, 4);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();

        MethodVisitor item = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "item",
                "(L" + PATTERN + ";L" + PROPERTIES + ";)L" + ITEM + ";", null, null);
        item.visitCode();
        Label known = new Label();
        builtInPatterns(item);
        item.visitVarInsn(ALOAD, 0);
        item.visitMethodInsn(INVOKEINTERFACE, REGISTRY, "getKey", "(Ljava/lang/Object;)L" + LOCATION + ";", true);
        item.visitVarInsn(ASTORE, 2);
        item.visitVarInsn(ALOAD, 2);
        item.visitJumpInsn(IFNONNULL, known);
        item.visitTypeInsn(NEW, LOCATION);
        item.visitInsn(DUP);
        item.visitLdcInsn("retromod");
        item.visitLdcInsn("unregistered");
        item.visitMethodInsn(INVOKESPECIAL, LOCATION, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        item.visitVarInsn(ASTORE, 2);
        item.visitLabel(known);
        item.visitTypeInsn(NEW, ITEM);
        item.visitInsn(DUP);
        item.visitFieldInsn(GETSTATIC, "net/minecraft/core/registries/Registries", "BANNER_PATTERN",
                "Lnet/minecraft/resources/ResourceKey;");
        item.visitTypeInsn(NEW, LOCATION);
        item.visitInsn(DUP);
        item.visitVarInsn(ALOAD, 2);
        item.visitMethodInsn(INVOKEVIRTUAL, LOCATION, "getNamespace", "()Ljava/lang/String;", false);
        item.visitLdcInsn("pattern_item/");
        item.visitVarInsn(ALOAD, 2);
        item.visitMethodInsn(INVOKEVIRTUAL, LOCATION, "getPath", "()Ljava/lang/String;", false);
        item.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
        item.visitMethodInsn(INVOKESPECIAL, LOCATION, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        item.visitMethodInsn(INVOKESTATIC, TAG_KEY, "create",
                "(Lnet/minecraft/resources/ResourceKey;L" + LOCATION + ";)L" + TAG_KEY + ";", false);
        item.visitVarInsn(ALOAD, 1);
        item.visitMethodInsn(INVOKESPECIAL, ITEM, "<init>", "(L" + TAG_KEY + ";L" + PROPERTIES + ";)V", false);
        item.visitInsn(ARETURN);
        item.visitMaxs(0, 0);
        item.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void builtInPatterns(MethodVisitor mv) {
        mv.visitFieldInsn(GETSTATIC, "net/minecraft/core/registries/BuiltInRegistries", "BANNER_PATTERN",
                "L" + REGISTRY + ";");
    }

    /**
     * Writes the item tag of every pattern the extracted, already transformed mod creates from
     * constants, and lists the patterns without an item in {@code no_item_required}.
     * Returns the number of patterns found.
     */
    public static int writeTags(Path modRoot) throws IOException {
        Map<String, Boolean> patterns = new TreeMap<>();
        List<Path> classes;
        try (Stream<Path> walk = Files.walk(modRoot)) {
            classes = walk.filter(p -> p.toString().endsWith(".class")).toList();
        }
        for (Path file : classes) {
            byte[] bytes = Files.readAllBytes(file);
            if (!new String(bytes, StandardCharsets.ISO_8859_1).contains(SYNTH)) continue;
            patterns.putAll(constantPatterns(bytes));
        }
        if (patterns.isEmpty()) return 0;

        List<String> withoutItem = new ArrayList<>();
        for (Map.Entry<String, Boolean> pattern : patterns.entrySet()) {
            String id = pattern.getKey().contains(":") ? pattern.getKey() : "minecraft:" + pattern.getKey();
            if (!pattern.getValue()) {
                withoutItem.add(id);
                continue;
            }
            String namespace = id.substring(0, id.indexOf(':'));
            String path = id.substring(id.indexOf(':') + 1);
            Path tag = modRoot.resolve("data").resolve(namespace).resolve("tags/banner_pattern/pattern_item")
                    .resolve(path + ".json");
            Files.createDirectories(tag.getParent());
            Files.writeString(tag, "{\n  \"values\": [\"" + id + "\"]\n}\n");
        }
        if (!withoutItem.isEmpty()) {
            Path tag = modRoot.resolve("data/minecraft/tags/banner_pattern/no_item_required.json");
            Files.createDirectories(tag.getParent());
            Files.writeString(tag, "{\n  \"replace\": false,\n  \"values\": [\"" + String.join("\", \"", withoutItem)
                    + "\"]\n}\n");
        }
        return patterns.size();
    }

    /** File name to whether the pattern has an item, for each call made with constant arguments. */
    static Map<String, Boolean> constantPatterns(byte[] classBytes) {
        Map<String, Boolean> found = new TreeMap<>();
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_FRAMES);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != INVOKESTATIC
                        || !call.owner.endsWith(SYNTH) || !call.name.equals("create")
                        || !call.desc.equals(CREATE_DESC)) {
                    continue;
                }
                AbstractInsnNode hasItem = previousReal(call);
                AbstractInsnNode hash = previousReal(hasItem);
                AbstractInsnNode fileName = previousReal(hash);
                if (hasItem instanceof InsnNode flag && (flag.getOpcode() == ICONST_0 || flag.getOpcode() == ICONST_1)
                        && hash instanceof LdcInsnNode
                        && fileName instanceof LdcInsnNode ldc && ldc.cst instanceof String name) {
                    found.put(name, flag.getOpcode() == ICONST_1);
                }
            }
        }
        return found;
    }

    /** The instruction before {@code insn}, skipping the labels and line numbers of a wrapped call. */
    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn == null ? null : insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }
}
