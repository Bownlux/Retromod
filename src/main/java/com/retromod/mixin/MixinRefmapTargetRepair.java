/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.core.FuzzyMethodResolver;
import com.retromod.util.JsonSecurity;
import com.retromod.util.RetromodLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Points a refmap entry at the host method its finished {@code @Inject} handler fits. */
public final class MixinRefmapTargetRepair {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Refmap");
    private static final String MIXIN_PACKAGE = "Lorg/spongepowered/asm/mixin/";
    private static final String MIXINEXTRAS_PACKAGE = "Lcom/llamalad7/mixinextras/";
    /** Member annotations Mixin resolves by the member's own name when they give none. */
    private static final java.util.Set<String> NAMED_BY_MEMBER = java.util.Set.of(
            "Lorg/spongepowered/asm/mixin/Shadow;", "Lorg/spongepowered/asm/mixin/Overwrite;",
            "Lorg/spongepowered/asm/mixin/gen/Accessor;", "Lorg/spongepowered/asm/mixin/gen/Invoker;");
    private static final Type CALLBACK_INFO =
            Type.getObjectType("org/spongepowered/asm/mixin/injection/callback/CallbackInfo");
    private static final Type CALLBACK_INFO_RETURNABLE =
            Type.getObjectType("org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable");

    private MixinRefmapTargetRepair() {}

    /** A retypable handler's layout; {@code null} in a key's list marks a use this repair leaves alone. */
    private record Handler(List<Type> arguments, boolean isStatic) {}

    /** A mixin's sole target and the uses of each annotation key in it. */
    private record Mixin(String target, Map<String, List<Handler>> keys) {}

    /**
     * {@code json} with each stale handler target moved to the one host method the finished
     * handler fits, or {@code json} itself when none moves. {@code classes} answers a mixin's
     * internal name with its class as this path wrote it, after every class repair.
     */
    public static String apply(String json, Function<String, byte[]> classes, FuzzyMethodResolver host) {
        if (json == null || classes == null || host == null || !host.isIndexed()) return json;
        try {
            JsonSecurity.validate(json, "Mixin refmap JSON");
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            Map<String, Mixin> mixins = new HashMap<>();
            int moved = 0;
            // Mixin reads the root mappings or the data section of its namespace, so each gets the same repair.
            for (JsonObject section : sections(root)) {
                for (String mixinName : section.keySet()) {
                    if (!section.get(mixinName).isJsonObject()) continue;
                    Mixin mixin = mixins.computeIfAbsent(mixinName.replace('.', '/'), name -> read(classes.apply(name)));
                    moved += repairEntries(section.getAsJsonObject(mixinName), mixin, host);
                }
            }
            if (moved == 0) return json;
            LOGGER.debug(RetromodLog.DETAIL, "Pointed {} refmap target(s) at the host methods their handlers fit", moved);
            return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
        } catch (Exception unreadable) {
            // The remap pass before this one already reported a refmap it could not read.
            return json;
        }
    }

    /** The classes of an extracted jar, by internal name; a name that leaves the folder answers nothing. */
    public static Function<String, byte[]> classesIn(Path root) {
        Path base = root.toAbsolutePath().normalize();
        return name -> {
            Path file = base.resolve(name + ".class").normalize();
            if (!file.startsWith(base) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
            try {
                return Files.readAllBytes(file);
            } catch (IOException unreadable) {
                return null;
            }
        };
    }

    private static List<JsonObject> sections(JsonObject root) {
        List<JsonObject> sections = new ArrayList<>();
        if (root.get("mappings") instanceof JsonObject mappings) sections.add(mappings);
        if (root.get("data") instanceof JsonObject data) {
            for (String namespace : data.keySet()) {
                if (data.get(namespace) instanceof JsonObject section) sections.add(section);
            }
        }
        return sections;
    }

    private static int repairEntries(JsonObject entries, Mixin mixin, FuzzyMethodResolver host) {
        if (mixin == null) return 0;
        int moved = 0;
        for (String key : new ArrayList<>(entries.keySet())) {
            JsonElement value = entries.get(key);
            List<Handler> uses = mixin.keys().get(key);
            if (uses == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) continue;
            // Every use of the key must agree, because they all follow the one entry.
            String agreed = null;
            for (Handler handler : uses) {
                String moveTo = handler == null ? null : retarget(value.getAsString(), handler, mixin.target(), host);
                if (moveTo == null || (agreed != null && !agreed.equals(moveTo))) {
                    agreed = null;
                    break;
                }
                agreed = moveTo;
            }
            if (agreed != null) {
                entries.addProperty(key, agreed);
                moved++;
            }
        }
        return moved;
    }

    /**
     * The entry pointed at the one method of the same name the handler fits, or {@code null}. A
     * handler that takes only the callback fits every overload, so for it the name alone must pick
     * the method, the rule {@link MixinHostDriftRepair} proves before it retypes a handler.
     */
    private static String retarget(String value, Handler handler, String target, FuzzyMethodResolver host) {
        int semicolon = value.indexOf(';');
        int paren = value.indexOf('(');
        boolean qualified = value.startsWith("L") && semicolon > 0 && paren > semicolon;
        String owner = qualified ? value.substring(1, semicolon) : target;
        String member = qualified ? value.substring(semicolon + 1) : value;
        paren = member.indexOf('(');
        if (owner == null || !owner.equals(target) || paren <= 0) return null;
        String name = member.substring(0, paren);
        String desc = member.substring(paren);
        if (name.startsWith("<") || name.contains("*")) return null;

        Map<String, FuzzyMethodResolver.MethodInfo> named = new LinkedHashMap<>();
        for (FuzzyMethodResolver.MethodInfo method : host.getDeclaredMethods(owner)) {
            if (method.name().equals(name)) named.putIfAbsent(method.descriptor(), method);
        }
        // A method the host renamed takes the new name when the handler fits it.
        if (named.isEmpty()) {
            FuzzyMethodResolver.MethodInfo renamed = MixinSelectorRenames.renamed(host, owner, name, desc);
            return renamed != null && fits(handler, renamed)
                    ? (qualified ? "L" + owner + ";" : "") + renamed.name() + renamed.descriptor() : null;
        }
        // An entry that still resolves needs nothing.
        if (named.containsKey(desc)) return null;
        List<FuzzyMethodResolver.MethodInfo> fitting = new ArrayList<>();
        for (FuzzyMethodResolver.MethodInfo method : named.values()) {
            if (fits(handler, method)) fitting.add(method);
        }
        boolean callbackOnly = handler.arguments().size() == 1;
        if (fitting.size() != 1 || (callbackOnly && named.size() != 1)) return null;
        return (qualified ? "L" + owner + ";" : "") + name + fitting.get(0).descriptor();
    }

    /** Whether Mixin accepts the handler on this method: every argument then the callback, or the callback alone. */
    private static boolean fits(Handler handler, FuzzyMethodResolver.MethodInfo method) {
        return fits(handler.arguments(), handler.isStatic(), method);
    }

    /** Whether a handler with these arguments fits {@code method}, by Mixin's rule for a handler without locals. */
    static boolean fits(List<Type> arguments, boolean isStatic, FuzzyMethodResolver.MethodInfo method) {
        if (isStatic != ((method.access() & Opcodes.ACC_STATIC) != 0)) return false;
        Type callback = Type.getReturnType(method.descriptor()).getSort() == Type.VOID
                ? CALLBACK_INFO : CALLBACK_INFO_RETURNABLE;
        List<Type> every = new ArrayList<>(Arrays.asList(Type.getArgumentTypes(method.descriptor())));
        every.add(callback);
        return arguments.equals(every) || arguments.equals(List.of(callback));
    }

    /** The uses of each annotation key in a mixin class, or {@code null} for anything else. */
    private static Mixin read(byte[] classBytes) {
        if (classBytes == null) return null;
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException unreadable) {
            return null;
        }
        String target = MixinHostDriftRepair.soleRemappedTarget(node);
        if (target == null) return null;
        Map<String, List<Handler>> keys = new HashMap<>();
        for (MethodNode method : node.methods) {
            AnnotationNode retypable = MixinHostDriftRepair.retypableInject(method);
            String selector = retypable == null ? null : MixinHostDriftRepair.soleSelector(retypable);
            List<AnnotationNode> annotations = annotations(method.visibleAnnotations, method.invisibleAnnotations);
            for (AnnotationNode annotation : annotations) {
                for (String key : keysOf(annotation)) {
                    // Only the retypable handler's own selector can move; any other use pins the entry.
                    Handler use = annotation == retypable && key.equals(selector)
                            ? new Handler(MixinHostDriftRepair.checkedArguments(method),
                                    (method.access & Opcodes.ACC_STATIC) != 0)
                            : null;
                    keys.computeIfAbsent(key, k -> new ArrayList<>()).add(use);
                }
            }
            // A shadowed or overwritten member is looked up by its own name.
            if (annotations.stream().anyMatch(a -> NAMED_BY_MEMBER.contains(a.desc))) {
                keys.computeIfAbsent(method.name, k -> new ArrayList<>()).add(null);
            }
        }
        // Field and class annotations name refmap keys too, and a shadow field by its own name.
        for (FieldNode field : node.fields) {
            List<AnnotationNode> annotations = annotations(field.visibleAnnotations, field.invisibleAnnotations);
            for (AnnotationNode annotation : annotations) {
                for (String key : keysOf(annotation)) keys.computeIfAbsent(key, k -> new ArrayList<>()).add(null);
            }
            if (annotations.stream().anyMatch(a -> NAMED_BY_MEMBER.contains(a.desc))) {
                keys.computeIfAbsent(field.name, k -> new ArrayList<>()).add(null);
            }
        }
        for (AnnotationNode annotation : annotations(node.visibleAnnotations, node.invisibleAnnotations)) {
            for (String key : keysOf(annotation)) keys.computeIfAbsent(key, k -> new ArrayList<>()).add(null);
        }
        return new Mixin(target, keys);
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        List<AnnotationNode> all = new ArrayList<>();
        if (visible != null) all.addAll(visible);
        if (invisible != null) all.addAll(invisible);
        return all;
    }

    /** Every string a Mixin annotation can look up in the refmap: selectors, point targets and accessor names. */
    private static List<String> keysOf(AnnotationNode annotation) {
        List<String> keys = new ArrayList<>();
        if (!annotation.desc.startsWith(MIXIN_PACKAGE) && !annotation.desc.startsWith(MIXINEXTRAS_PACKAGE)) {
            return keys;
        }
        collectStrings(annotation, keys);
        return keys;
    }

    private static void collectStrings(Object value, List<String> keys) {
        if (value instanceof String text) {
            keys.add(text);
        } else if (value instanceof AnnotationNode nested && nested.values != null) {
            for (int i = 1; i < nested.values.size(); i += 2) collectStrings(nested.values.get(i), keys);
        } else if (value instanceof List<?> list) {
            for (Object entry : list) collectStrings(entry, keys);
        }
    }
}
