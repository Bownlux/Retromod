/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps Fabric intermediary names (class_XXXX, field_XXXX, method_XXXX) to
 * Mojang official names.
 *
 * MC 26.1 dropped code obfuscation, so the runtime uses Mojang names directly.
 * Old Fabric mods reference intermediary names in mixin target annotations,
 * mixin refmaps, and access widener files. The mapping loads from a bundled TSV
 * (Fabric intermediary + Mojang ProGuard for 1.21.4). Intermediary names are
 * stable across MC versions, so one recent mapping covers all old mods.
 */
public class IntermediaryToMojangMapper {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Mapping");
    private static final String MAPPING_RESOURCE = "/intermediary-to-mojang.tsv";
    private static final String CLASS_MOVES_RESOURCE = "/mojang-class-moves-26.1.tsv";

    private static volatile IntermediaryToMojangMapper instance;

    private final Map<String, String> classMap = new ConcurrentHashMap<>(9000);
    private final Map<String, String> fieldMap = new ConcurrentHashMap<>(40000);
    private final Map<String, String> methodMap = new ConcurrentHashMap<>(40000);
    /** Old Mojang name → New Mojang name for classes moved in 26.1 */
    private final Map<String, String> classMoves = new ConcurrentHashMap<>(600);

    private IntermediaryToMojangMapper() {
        loadMappings();
        loadClassMoves();
    }

    public static IntermediaryToMojangMapper getInstance() {
        if (instance == null) {
            synchronized (IntermediaryToMojangMapper.class) {
                if (instance == null) {
                    instance = new IntermediaryToMojangMapper();
                }
            }
        }
        return instance;
    }

    private void loadMappings() {
        long start = System.currentTimeMillis();

        try (InputStream is = getClass().getResourceAsStream(MAPPING_RESOURCE)) {
            if (is == null) {
                LOGGER.warn("Could not find the intermediary to Mojang mapping file at {}. "
                        + "Fabric name remapping is unavailable.", MAPPING_RESOURCE);
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#") || line.isEmpty()) continue;

                    String[] parts = line.split("\t");
                    if (parts.length < 3) continue;

                    switch (parts[0]) {
                        case "CLASS" -> classMap.put(parts[1], parts[2]);
                        case "FIELD" -> fieldMap.put(parts[1], parts[2]);
                        case "METHOD" -> methodMap.put(parts[1], parts[2]);
                    }
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            LOGGER.info("Loaded intermediary to Mojang mappings for {} classes, {} fields, "
                            + "and {} methods in {} ms",
                    classMap.size(), fieldMap.size(), methodMap.size(), elapsed);

        } catch (IOException e) {
            LOGGER.error("Could not load intermediary to Mojang mappings: {}", e.getMessage());
        }
    }

    private void loadClassMoves() {
        try (InputStream is = getClass().getResourceAsStream(CLASS_MOVES_RESOURCE)) {
            if (is == null) {
                LOGGER.debug("No 26.1 class moves file found: {}", CLASS_MOVES_RESOURCE);
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("#") || line.isEmpty()) continue;
                    String[] parts = line.split("\t");
                    if (parts.length >= 2) {
                        classMoves.put(parts[0], parts[1]);
                    }
                }
            }

            LOGGER.info("Loaded {} class move redirects for 26.1 package reorganization", classMoves.size());
        } catch (IOException e) {
            LOGGER.warn("Failed to load 26.1 class moves: {}", e.getMessage());
        }
    }

    /** Old Mojang to new Mojang class redirects for 26.1 package moves. */
    public Map<String, String> getClassMoves() {
        return Collections.unmodifiableMap(classMoves);
    }

    /**
     * Map an intermediary class name to its Mojang name, or return the original
     * if no mapping exists.
     *
     * @param intermediaryClass for example "net/minecraft/class_310"
     * @return Mojang name, for example "net/minecraft/client/Minecraft"
     */
    public String mapClass(String intermediaryClass) {
        return classMap.getOrDefault(intermediaryClass, intermediaryClass);
    }

    /**
     * Map an intermediary field name to its Mojang name.
     *
     * @param intermediaryField for example "field_25318"
     * @return Mojang name, for example "resources"
     */
    public String mapField(String intermediaryField) {
        return fieldMap.getOrDefault(intermediaryField, intermediaryField);
    }

    /**
     * Map an intermediary method name to its Mojang name.
     *
     * @param intermediaryMethod for example "method_18858"
     * @return Mojang name, for example "createTitle"
     */
    public String mapMethod(String intermediaryMethod) {
        return methodMap.getOrDefault(intermediaryMethod, intermediaryMethod);
    }

    // Find class_XXXX/field_XXXX/method_XXXX tokens and look them up directly
    // instead of iterating 86K entries.
    private static final Pattern CLASS_PATTERN = Pattern.compile("class_\\d+");
    private static final Pattern FIELD_PATTERN = Pattern.compile("field_\\d+");
    private static final Pattern METHOD_PATTERN = Pattern.compile("method_\\d+");
    // Record-component accessors (1.20.5+) are keyed like methods in the intermediary
    // mapping. The bytecode remapper already handles comp_ via methodMap; string-level
    // refmaps/reflection strings need it too. (adapted from Sinytra Connector (MIT))
    private static final Pattern COMP_PATTERN = Pattern.compile("comp_\\d+");
    // Fully-qualified "net/minecraft/class_XXXX" style paths
    private static final Pattern FQ_CLASS_PATTERN = Pattern.compile("[a-z]+(?:/[a-z]+)*/class_\\d+");
    // NESTED intermediary class: "net/minecraft/class_X$class_Y" (one or more "$class_N" named-inner
    // segments). The tsv carries the COMBINED entry (e.g. class_327$class_6415 -> Font$DisplayMode),
    // but FQ_CLASS_PATTERN stops at '$' and maps only the outer, leaving a "Font$class_6415" hybrid
    // that doesn't resolve on 26.x (the inner id has no top-level mapping). Match + look up the whole
    // nested name FIRST. Anonymous "$1" inners keep their index and are handled correctly by the
    // outer-only FQ map, so this pattern targets only "$class_N" named inners.
    private static final Pattern NESTED_FQ_CLASS_PATTERN =
            Pattern.compile("[a-z]+(?:/[a-z]+)*/class_\\d+(?:\\$class_\\d+)+");

    /** Remap all intermediary references in a string. */
    public String remapString(String input) {
        if (input == null) return null;

        // Nested FQ (combined tsv entry) first, then plain FQ, so we don't partial-match a
        // "class_XXXX" within a path or leave a nested inner id unmapped.
        String result = input;
        if (result.contains("class_")) {
            result = replaceByPattern(result, NESTED_FQ_CLASS_PATTERN, classMap);
            if (result.contains("class_")) {
                result = replaceByPattern(result, FQ_CLASS_PATTERN, classMap);
                if (result.contains("class_")) {
                    result = replaceByPattern(result, CLASS_PATTERN, classMap);
                }
            }
        }

        if (result.contains("field_")) {
            result = replaceByPattern(result, FIELD_PATTERN, fieldMap);
        }

        if (result.contains("method_")) {
            result = replaceByPattern(result, METHOD_PATTERN, methodMap);
        }

        if (result.contains("comp_")) {
            result = replaceByPattern(result, COMP_PATTERN, methodMap);
        }

        return result;
    }

    /** Remap all intermediary class references in a descriptor. */
    public String remapDescriptor(String descriptor) {
        if (descriptor == null) return null;

        if (!descriptor.contains("class_")) return descriptor;

        // Nested FQ (combined tsv entry) first (Lnet/minecraft/class_X$class_Y;), then plain FQ.
        String result = replaceByPattern(descriptor, NESTED_FQ_CLASS_PATTERN, classMap);

        if (result.contains("class_")) {
            result = replaceByPattern(result, FQ_CLASS_PATTERN, classMap);
            if (result.contains("class_")) {
                result = replaceByPattern(result, CLASS_PATTERN, classMap);
            }
        }

        return result;
    }

    /** Replace all pattern matches using the given lookup map. */
    private String replaceByPattern(String input, Pattern pattern, Map<String, String> lookup) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder sb = new StringBuilder(input.length());
        while (matcher.find()) {
            String match = matcher.group();
            String replacement = lookup.get(match);
            if (replacement != null) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** The full class mapping table (intermediary to Mojang). */
    public Map<String, String> getClassMap() {
        return Collections.unmodifiableMap(classMap);
    }

    /**
     * Register every loaded intermediary to Mojang mapping (classes, methods,
     * fields) plus the 26.1 class moves into the transformer's redirect tables.
     * Idempotent: the single entry point any startup path uses to prepare a
     * {@code RetromodTransformer} for Fabric intermediary remapping.
     *
     * <p>When an intermediary {@code class_X} maps to a Mojang name that was
     * later moved in 26.1 ({@code OldMojangName} to {@code NewMojangName}), both
     * hops are composed so the transformer registers {@code class_X} to
     * {@code NewMojangName} directly. ASM's {@code ClassRemapper} is single-pass,
     * so registering the intermediate stop would never fire the second hop.</p>
     *
     * @param transformer the transformer to populate; must not be null
     * @return number of class redirects registered (for diagnostic logging)
     */
    public static int applyTo(com.retromod.core.RetromodTransformer transformer) {
        if (transformer == null) {
            throw new IllegalArgumentException("transformer must not be null");
        }
        IntermediaryToMojangMapper mapper = getInstance();
        if (!mapper.isLoaded()) {
            LOGGER.debug("IntermediaryToMojangMapper.applyTo called but mappings not loaded");
            return 0;
        }

        Map<String, String> classMoveMap = mapper.getClassMoves();

        int classRedirects = 0;
        int composed = 0;
        for (Map.Entry<String, String> entry : mapper.getClassMap().entrySet()) {
            String intermediary = entry.getKey();
            String mojang = entry.getValue();
            // Compose with class-moves so the single-pass ClassRemapper lands
            // on the final 26.1 name, not an intermediate stop.
            String finalName = classMoveMap.getOrDefault(mojang, mojang);
            if (!finalName.equals(mojang)) composed++;
            transformer.registerClassRedirect(intermediary, finalName);
            classRedirects++;
        }
        transformer.registerIntermediaryNameMappings(
                mapper.getMethodMap(), mapper.getFieldMap());
        registerAmbiguousMemberOverrides(transformer);

        // Also register class-moves on their own, for mods already using Mojang
        // names (1.20+ with GuiGraphics): the intermediary step is skipped but
        // the 26.1 move still applies.
        int classMoves = 0;
        for (Map.Entry<String, String> entry : classMoveMap.entrySet()) {
            transformer.registerClassRedirect(entry.getKey(), entry.getValue());
            classMoves++;
        }

        LOGGER.info("Registered {} intermediary to Mojang class redirects ({} composed), "
                        + "{} method names, {} field names, and {} class moves",
                classRedirects, composed, mapper.getMethodMap().size(),
                mapper.getFieldMap().size(), classMoves);
        return classRedirects;
    }

    /**
     * Register descriptor-qualified overrides for intermediary short-names that the flat
     * (name-only) {@code intermediary-to-mojang.tsv} collapses to the WRONG Mojang name.
     *
     * <p>A Fabric intermediary {@code method_XXXX} can name more than one method (distinguished
     * only by descriptor). The 1.21.4 harvest that built the tsv is name-only, so it keeps just
     * one Mojang name per short-name (last writer wins); the other meaning is silently mis-renamed.
     *
     * <p><b>{@code method_14452}</b> is such a case: verified against Fabric Yarn 1.18.2-1.21.4 it is
     * BOTH {@code PlacementModifier.getPositions(context, random, BlockPos) -> Stream} (Mojang
     * {@code getPositions}) AND a separate {@code getCount(random, BlockPos) -> int} (Mojang
     * {@code count}). The tsv kept only {@code count}, so a mod overriding {@code getPositions} had its
     * override renamed to {@code count}: it no longer overrode the abstract method and threw
     * {@code AbstractMethodError} during chunk generation on 26.x (YUNG's Extras and any mod with a
     * custom placement). Registering every descriptor variant makes {@code method_14452} resolve by
     * descriptor (via the transformer's ambiguous-name fallback), independent of the flat entry.
     * Two descriptor eras: 1.18.x uses {@code java/util/Random}, 1.19+ uses {@code class_5819}
     * (RandomSource). Descriptors are intermediary (the remapper sees them pre-class-remap).
     *
     * <p>Future ambiguous short-names go here as they are found; a full descriptor-qualified harvest
     * (so the tsv itself carries the ambiguity) is the systemic follow-up.
     */
    private static void registerAmbiguousMemberOverrides(com.retromod.core.RetromodTransformer transformer) {
        // PlacementModifier.getPositions(FeaturePlacementContext, random, BlockPos) -> Stream
        transformer.registerIntermediaryMemberMapping("method_14452",
                "(Lnet/minecraft/class_5444;Ljava/util/Random;Lnet/minecraft/class_2338;)Ljava/util/stream/Stream;",
                "getPositions", false); // 1.18.x
        transformer.registerIntermediaryMemberMapping("method_14452",
                "(Lnet/minecraft/class_5444;Lnet/minecraft/class_5819;Lnet/minecraft/class_2338;)Ljava/util/stream/Stream;",
                "getPositions", false); // 1.19+
        // getCount(random, BlockPos) -> int (Mojang: count) is already kept by the TSV,
        // registered explicitly so method_14452 is fully descriptor-resolved even if the flat tsv changes.
        transformer.registerIntermediaryMemberMapping("method_14452",
                "(Ljava/util/Random;Lnet/minecraft/class_2338;)I", "count", false); // 1.18.x
        transformer.registerIntermediaryMemberMapping("method_14452",
                "(Lnet/minecraft/class_5819;Lnet/minecraft/class_2338;)I", "count", false); // 1.19+
    }

    /**
     * The 26.1 ResourceLocation/Identifier constructor redirects: the (String) and
     * (String, String) constructors were removed for the static factories
     * Identifier.parse / Identifier.fromNamespaceAndPath. Single source of truth so
     * every transform path (Fabric/NeoForge/Forge runtime, CLI, AOT) emits the same
     * bytecode; the offline tools must match an in-game boot. The caller gates on the
     * host or target actually having Identifier (26.1+).
     */
    public static void registerIdentifierCtorRedirects(com.retromod.core.RetromodTransformer transformer) {
        transformer.registerConstructorRedirect(
                "net/minecraft/resources/Identifier", "(Ljava/lang/String;)V",
                "net/minecraft/resources/Identifier", "parse",
                "(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;");
        transformer.registerConstructorRedirect(
                "net/minecraft/resources/Identifier", "(Ljava/lang/String;Ljava/lang/String;)V",
                "net/minecraft/resources/Identifier", "fromNamespaceAndPath",
                "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/Identifier;");
    }

    /**
     * Register only the 26.1 vanilla class moves plus the
     * {@code ResourceLocation} to {@code Identifier} constructor redirects: the
     * subset of {@link #applyTo} for mods that already use Mojang names (NeoForge
     * and Forge). Those mods skip the Fabric intermediary remap (they have no
     * {@code class_XXXX} names), but still need the vanilla {@code net/minecraft/*}
     * package reorganization, or a mod referencing {@code net/minecraft/Util} or
     * a repackaged entity crashes with {@code NoClassDefFoundError} on a 26.1 host.
     *
     * <p>Gating is the caller's responsibility: only invoke on a 26.1+ host
     * ({@link com.retromod.core.RetromodVersion#isUnobfuscatedTarget}). On a
     * pre-26.1 host these moves would rewrite a mod's working references into
     * 26.1 names that don't exist yet (#21/#29).
     *
     * <p>The {@code Identifier} constructor redirects are keyed on the post-move
     * name: the class move (or the loader's own shim) renames
     * {@code ResourceLocation} to {@code Identifier} first, then
     * {@code new Identifier(String)} becomes {@code Identifier.parse}.
     *
     * @param transformer the transformer to populate; must not be null
     * @return number of class-move redirects registered (for diagnostic logging)
     */
    public static int applyClassMovesOnly(com.retromod.core.RetromodTransformer transformer) {
        if (transformer == null) {
            throw new IllegalArgumentException("transformer must not be null");
        }
        IntermediaryToMojangMapper mapper = getInstance();
        Map<String, String> classMoveMap = mapper.getClassMoves();
        if (classMoveMap.isEmpty()) {
            LOGGER.debug("applyClassMovesOnly: no class moves loaded; nothing to apply");
            return 0;
        }

        // Host-version-aware filtering. The class-move table maps old vanilla
        // names to their final (26.1) names, but many renames already happened
        // by an intermediate version (ResourceLocation->Identifier and
        // LootContextParamSet->ContextKeySet landed by 1.21.11). Apply a rename
        // on a host only when the host has the new class but not the old one.
        // Gating the whole table on "26.1+" (isUnobfuscatedTarget) was too coarse:
        // it left mods on a 1.21.11 host crashing with NoClassDefFoundError on
        // names already renamed there (#50/#51/#52). The new-on-host check also
        // stops a 26.1-only rename from rewriting a working reference on an older
        // host (the #9 hazard).
        //
        // Ask the classloader that loaded MC whether a class exists, rather than
        // locating the MC JAR on disk: on NeoForge the MC JAR isn't on
        // java.class.path (it's a JPMS module), and the disk search mis-matched
        // a library (srgutils) by substring, yielding an empty index.
        java.util.function.Predicate<String> hostHasClass = buildHostClassCheck();
        boolean haveHost = hostHasClass != null;
        boolean unobfFallback =
                com.retromod.core.RetromodVersion.isUnobfuscatedTarget(
                        com.retromod.core.RetromodVersion.TARGET_MC_VERSION);
        if (!haveHost) {
            LOGGER.warn("Could not inspect host classes while applying class moves. "
                    + "Using the broader 26.1 version check instead (apply all: {}).",
                    unobfFallback);
        }

        int applied = 0, skippedNewMissing = 0, skippedOldPresent = 0;
        for (Map.Entry<String, String> entry : classMoveMap.entrySet()) {
            String oldName = entry.getKey();
            String newName = entry.getValue();
            boolean apply;
            if (haveHost) {
                boolean oldOnHost = hostHasClass.test(oldName);
                if (oldOnHost) {
                    // Host still exposes the old name, so the mod's reference is
                    // already valid; renaming would be wrong.
                    apply = false;
                    skippedOldPresent++;
                } else if (newName.startsWith("net/minecraft/")) {
                    // Vanilla rename: safe only if the host has the new name,
                    // otherwise it's a 26.1-only rename on an older host.
                    apply = hostHasClass.test(newName);
                    if (!apply) skippedNewMissing++;
                } else {
                    // Redirect to a non-vanilla replacement (Retromod polyfill,
                    // JOML, blaze3d). The MC JAR won't contain it, so the old
                    // class being gone is the signal that it's needed.
                    apply = true;
                }
            } else {
                apply = unobfFallback;
            }
            if (apply) {
                transformer.registerClassRedirect(oldName, newName);
                applied++;
            }
        }

        // Identifier constructor redirects, only when the host has Identifier
        // (so a `new ResourceLocation(...)` rewritten to `new Identifier(...)`
        // by the class move lands on the static factory).
        boolean identifierOnHost = haveHost
                ? hostHasClass.test("net/minecraft/resources/Identifier")
                : unobfFallback;
        if (identifierOnHost) {
            registerIdentifierCtorRedirects(transformer);
        }

        LOGGER.info("Registered {} vanilla class moves for host MC {} (host-filtered: "
                + "{} skipped new-missing, {} skipped old-present){}",
                applied, com.retromod.core.RetromodVersion.TARGET_MC_VERSION,
                skippedNewMissing, skippedOldPresent,
                identifierOnHost ? " + 2 Identifier ctor redirects" : "");
        return applied;
    }

    /**
     * Build a predicate answering whether an internal class name exists on the
     * running Minecraft host, without locating the MC JAR on disk (unreliable on
     * NeoForge, where MC is a JPMS module). Returns {@code null} when MC can't be
     * reached (a unit test), so callers fall back to a coarse gate.
     *
     * <p>Prefers {@code getResource("a/b/C.class")} (checks the class file exists
     * without loading or linking it); if the classloader hides class-file
     * resources, falls back to {@code Class.forName(name, false, loader)} with
     * initialize=false, the probe {@code EnvironmentDetector} uses on NeoForge
     * (#46), which never runs {@code <clinit>}.
     */
    private static java.util.function.Predicate<String> buildHostClassCheck() {
        try {
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            // SharedConstants exists across every MC version Retromod targets.
            Class<?> sentinel = Class.forName("net.minecraft.SharedConstants", false, ctx);
            ClassLoader mc = sentinel.getClassLoader();
            final ClassLoader loader = (mc != null) ? mc : ctx;
            if (loader.getResource("net/minecraft/SharedConstants.class") != null) {
                // getResource sees MC class files: cheap, no class loading.
                return name -> loader.getResource(name + ".class") != null;
            }
            // Loader hides class-file resources; probe by name (no init).
            return name -> {
                try {
                    Class.forName(name.replace('/', '.'), false, loader);
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            };
        } catch (Throwable t) {
            return null; // MC not loadable here, caller uses the coarse gate
        }
    }

    /** The full field mapping table (intermediary to Mojang). */
    public Map<String, String> getFieldMap() {
        return Collections.unmodifiableMap(fieldMap);
    }

    /** The full method mapping table (intermediary to Mojang). */
    public Map<String, String> getMethodMap() {
        return Collections.unmodifiableMap(methodMap);
    }

    /** Whether mappings are loaded. */
    public boolean isLoaded() {
        return !classMap.isEmpty();
    }
}
