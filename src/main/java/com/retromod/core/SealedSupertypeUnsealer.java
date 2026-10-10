/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Lets a Fabric mod keep implementing a Minecraft type the host has since sealed.
 *
 * <p>26.2 sealed {@code net.minecraft.core.Holder} to its own {@code Direct} and {@code Reference}.
 * A class outside that list, such as Porting Lib's {@code DeferredHolder}, then fails to load with
 * {@code IncompatibleClassChangeError: Failed listed permitted subclass check}, so a mod that
 * worked on 26.1.2 lost its entrypoint on 26.2 and 26.3. Fabric's access widener
 * {@code extendable} entry unseals a class, so each sealed supertype the jar's classes need is
 * added to the jar's own access widener, or to a new one.
 */
public final class SealedSupertypeUnsealer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Unseal");
    static final String CREATED_WIDENER = "retromod-unseal.accesswidener";

    private SealedSupertypeUnsealer() {}

    /**
     * Unseals what the classes directly under {@code jarRoot} need; nested jars are their own
     * roots. {@code hostClass} returns the host's class, or null when it has none.
     *
     * @return the sealed types that were unsealed
     */
    public static Set<String> unseal(Path jarRoot, Function<String, ClassNode> hostClass) throws IOException {
        Path modJson = jarRoot.resolve("fabric.mod.json");
        if (!Files.isRegularFile(modJson)) return Set.of();
        Set<String> sealed = sealedSupertypesNeeded(jarRoot, hostClass);
        if (sealed.isEmpty()) return sealed;

        JsonObject mod = JsonParser.parseString(Files.readString(modJson, StandardCharsets.UTF_8)).getAsJsonObject();
        String widener = mod.has("accessWidener") && mod.get("accessWidener").isJsonPrimitive()
                ? mod.get("accessWidener").getAsString() : null;
        Path widenerFile = widener == null ? null : jarRoot.resolve(widener);
        if (widenerFile != null && Files.isRegularFile(widenerFile)) {
            String content = Files.readString(widenerFile, StandardCharsets.UTF_8);
            if (!content.startsWith("accessWidener") && !content.startsWith("classTweaker")) return Set.of();
            if (!content.lines().findFirst().orElse("").trim().endsWith("official")) {
                LOGGER.warn("Not unsealing {} for {}: its access widener is not in the official namespace",
                        sealed, jarRoot.getFileName());
                return Set.of();
            }
            StringBuilder out = new StringBuilder(content.stripTrailing()).append('\n');
            for (String type : sealed) out.append("extendable\tclass\t").append(type).append('\n');
            Files.writeString(widenerFile, out.toString(), StandardCharsets.UTF_8);
        } else {
            StringBuilder out = new StringBuilder("accessWidener\tv2\tofficial\n");
            for (String type : sealed) out.append("extendable\tclass\t").append(type).append('\n');
            Files.writeString(jarRoot.resolve(CREATED_WIDENER), out.toString(), StandardCharsets.UTF_8);
            mod.addProperty("accessWidener", CREATED_WIDENER);
            Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            Files.writeString(modJson, gson.toJson(mod), StandardCharsets.UTF_8);
        }
        LOGGER.info("Unsealed {} for {}, which the host sealed after the mod was built", sealed, jarRoot.getFileName());
        return sealed;
    }

    /** Minecraft supertypes of the jar's classes that the host seals without listing those classes. */
    static Set<String> sealedSupertypesNeeded(Path jarRoot, Function<String, ClassNode> hostClass) throws IOException {
        Map<String, List<String>> permitted = new HashMap<>();
        Set<String> needed = new TreeSet<>();
        List<Path> classes;
        try (var stream = Files.walk(jarRoot)) {
            classes = stream.filter(p -> p.toString().endsWith(".class")).toList();
        }
        for (Path file : classes) {
            ClassReader reader = new ClassReader(Files.readAllBytes(file));
            List<String> supertypes = new ArrayList<>(List.of(reader.getInterfaces()));
            if (reader.getSuperName() != null) supertypes.add(reader.getSuperName());
            for (String type : supertypes) {
                if (!type.startsWith("net/minecraft/") && !type.startsWith("com/mojang/")) continue;
                // An empty list stands for "not sealed or not on the host", so it is cached too.
                List<String> allowed = permitted.computeIfAbsent(type, name -> {
                    ClassNode host = hostClass.apply(name);
                    return host == null || host.permittedSubclasses == null ? List.of() : host.permittedSubclasses;
                });
                if (!allowed.isEmpty() && !allowed.contains(reader.getClassName())) {
                    needed.add(type);
                }
            }
        }
        return needed;
    }
}
