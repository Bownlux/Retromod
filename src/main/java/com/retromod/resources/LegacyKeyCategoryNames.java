/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Names the key binding categories that older mods made from a plain string. */
public final class LegacyKeyCategoryNames {

    /** Namespace of the categories Retromod registers for old category strings. */
    public static final String NAMESPACE = "retromod_compat";
    private static final String DEFAULT_LANGUAGE = "en_us";
    private static final Pattern LANG_FILE = Pattern.compile("assets/[^/]+/lang/([a-z0-9_]+)\\.json");
    private static final Set<String> KEY_MAPPING_OWNERS = Set.of("net/minecraft/class_304",
            "net/minecraft/client/KeyMapping", "net/minecraft/client/settings/KeyBinding",
            "net/minecraft/client/option/KeyBinding");
    private static final Set<String> VANILLA_CATEGORIES = Set.of("key.categories.movement",
            "key.categories.misc", "key.categories.multiplayer", "key.categories.gameplay",
            "key.categories.inventory", "key.categories.creative");
    private static final Gson GSON = new Gson();

    private LegacyKeyCategoryNames() {}

    /**
     * The path of the category id for an old category string. {@code KeyBindingShim} and
     * {@code RetroKeyMapping} derive the same path; they are embedded or loaded without this
     * class, so they keep their own copy.
     */
    public static String categoryPath(String category) {
        String path = category.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return path.isEmpty() ? "custom" : path;
    }

    /** Whether the host titles categories by id, which began in 1.21.9. */
    public static boolean appliesTo(String targetMcVersion) {
        return targetMcVersion != null && !RetromodVersion.mcVersionExceeds("1.21.9", targetMcVersion);
    }

    /**
     * The language files to add to a jar with these entries, keyed by entry name. Empty when the
     * mod names no string category or the jar already has the files.
     */
    public static Map<String, byte[]> entries(Collection<String> entryNames, Function<String, byte[]> read) {
        Set<String> categories = new LinkedHashSet<>();
        Map<String, JsonObject> languages = new LinkedHashMap<>();
        for (String name : entryNames) {
            if (name.endsWith(".class")) {
                byte[] bytes = read.apply(name);
                if (bytes != null && mentionsKeyBinding(bytes)) collectCategories(bytes, categories);
                continue;
            }
            Matcher lang = LANG_FILE.matcher(name);
            if (lang.matches() && !name.startsWith("assets/" + NAMESPACE + "/")) {
                JsonObject parsed = parse(read.apply(name));
                if (parsed != null) {
                    languages.computeIfAbsent(lang.group(1), code -> new JsonObject());
                    parsed.entrySet().forEach(e -> languages.get(lang.group(1)).add(e.getKey(), e.getValue()));
                }
            }
        }
        categories.removeIf(category -> category.contains(":") || VANILLA_CATEGORIES.contains(category));
        if (categories.isEmpty()) return Map.of();
        languages.computeIfAbsent(DEFAULT_LANGUAGE, code -> new JsonObject());

        // 1.21.9 titles a category with the translation of key.category.<namespace>.<path>, and
        // nothing translated the retromod_compat keys, so the Controls screen showed them raw.
        // Write a retromod_compat lang file for each language the mod ships.
        Map<String, byte[]> files = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> language : languages.entrySet()) {
            String file = "assets/" + NAMESPACE + "/lang/" + language.getKey() + ".json";
            if (entryNames.contains(file)) continue;
            JsonObject names = new JsonObject();
            for (String category : categories) {
                JsonElement translated = language.getValue().get(category);
                String key = "key.category." + NAMESPACE + "." + categoryPath(category);
                // The text the screen showed before 1.21.9: the string's translation, or the
                // string itself when it had none.
                if (translated != null && translated.isJsonPrimitive()) {
                    names.addProperty(key, translated.getAsString());
                } else if (language.getKey().equals(DEFAULT_LANGUAGE)) {
                    names.addProperty(key, category);
                }
            }
            if (names.size() > 0) files.put(file, GSON.toJson(names).getBytes(StandardCharsets.UTF_8));
        }
        return files;
    }

    /** Writes {@link #entries} into an extracted jar. Returns how many files it wrote. */
    public static int writeTree(Path root, String targetMcVersion) throws IOException {
        if (!appliesTo(targetMcVersion)) return 0;
        return writeTree(DirectoryResourceTree.open(root), targetMcVersion, java.util.List.of(), name -> null);
    }

    /** Writes {@link #entries} into a tree. Returns how many files it wrote. */
    public static int writeTree(ResourceTree tree, String targetMcVersion,
            Collection<String> classNames, Function<String, byte[]> classReader) throws IOException {
        if (!appliesTo(targetMcVersion)) return 0;
        // The streaming paths keep classes out of their tree, so they pass the class entries
        // separately. An extracted tree holds its classes itself.
        Set<String> names = new LinkedHashSet<>(tree.names());
        names.addAll(classNames);
        Map<String, byte[]> files = entries(names, name -> {
            if (tree.exists(name)) {
                try {
                    return tree.read(name);
                } catch (IOException unreadable) {
                    return null;
                }
            }
            return classReader.apply(name);
        });
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            tree.write(file.getKey(), file.getValue());
        }
        return files.size();
    }

    /** A cheap check before parsing: the class names a key binding owner or factory. */
    private static boolean mentionsKeyBinding(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        return text.contains("class_304") || text.contains("KeyMapping") || text.contains("KeyBinding");
    }

    /** A constant string passed as the last argument of a key binding constructor or factory. */
    private static void collectCategories(byte[] classBytes, Set<String> categories) {
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        } catch (RuntimeException malformed) {
            return;
        }
        // Only constant strings show up here; a category the mod builds at run time stays raw.
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && isKeyBindingCall(call)
                        && previousReal(call) instanceof LdcInsnNode ldc && ldc.cst instanceof String category) {
                    categories.add(category);
                }
            }
        }
    }

    private static boolean isKeyBindingCall(MethodInsnNode call) {
        Type[] arguments = Type.getArgumentTypes(call.desc);
        if (arguments.length < 2 || !arguments[arguments.length - 1].getDescriptor().equals("Ljava/lang/String;")) {
            return false;
        }
        if (call.name.equals("<init>")) return KEY_MAPPING_OWNERS.contains(call.owner);
        return call.owner.endsWith("/KeyBindingShim") || call.owner.endsWith("/RetroKeyMapping");
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    private static JsonObject parse(byte[] bytes) {
        if (bytes == null) return null;
        try {
            JsonElement element = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
