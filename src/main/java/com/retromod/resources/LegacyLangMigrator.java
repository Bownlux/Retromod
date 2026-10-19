/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Writes a JSON language file for each 1.12 {@code .lang} file, with the keys newer games ask for. */
public final class LegacyLangMigrator implements ModResourceConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /** The first host that reads JSON language files with lowercase codes and registry-id keys. */
    static final String FIRST_JSON_LANG_HOST = "1.13";

    private static final Pattern LANG_FILE = Pattern.compile("assets/([^/]+)/lang/([^/]+)\\.lang");
    /** A language code as the game names it, such as {@code en_us}; a backup copy never matches. */
    private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z0-9_]+");
    private static final Pattern ID_PATH = Pattern.compile("[a-z0-9_./-]+");
    private static final Pattern ITEM_OR_BLOCK_KEY = Pattern.compile("(item|tile)\\.(.+)\\.name");
    private static final Pattern ENTITY_KEY = Pattern.compile("entity\\.(.+)\\.name");
    private static final Pattern EFFECT_KEY = Pattern.compile("(?:effect|potion)\\.([^.]+(?:\\.[^.]+)?)");
    private static final String PARSE_ESCAPES = "PARSE_ESCAPES";

    /** 1.12 brewed potion key prefixes and the item key each became. */
    private static final Map<String, String> POTION_ITEM_PREFIXES = Map.of(
            "potion.effect.", "item.minecraft.potion.effect.",
            "splash_potion.effect.", "item.minecraft.splash_potion.effect.",
            "lingering_potion.effect.", "item.minecraft.lingering_potion.effect.",
            "tipped_arrow.effect.", "item.minecraft.tipped_arrow.effect.");

    /** How sure a modern key is, best first. A better match claims a key over a worse one. */
    private enum Match { CLASS_PAIR, POTION_NAME, EXACT, IGNORING_CASE, CAMEL_CASE }

    @Override
    public String name() {
        return "1.12 language files";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return context.sourceBefore(FIRST_JSON_LANG_HOST) && context.targetAtLeast(FIRST_JSON_LANG_HOST);
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        // 1.13 reads only lang/<code>.json, with a lowercase language code, so a 1.12 mod showed
        // raw translation keys everywhere. The .lang originals stay.
        SortedSet<String> names = tree.names();
        Map<String, String> sources = langSources(names, context.ownNamespaces());
        if (sources.isEmpty()) return 0;

        // The keys changed too, so each file also gains the modern keys the index can work out.
        KeyIndex index = new KeyIndex(names, classes(tree, context));
        int written = 0;
        int aliases = 0;
        for (Map.Entry<String, String> file : sources.entrySet()) {
            String target = file.getKey();
            String namespace = target.split("/")[1];
            Map<String, String> entries = parseLang(tree.read(file.getValue()), file.getValue());
            Map<String, String> converted = withModernKeys(entries, namespace, index);
            aliases += converted.size() - entries.size();
            JsonObject json = new JsonObject();
            converted.forEach(json::addProperty);
            tree.write(target, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
            written++;
        }
        LOGGER.info("Wrote {} JSON language file(s) from 1.12 .lang files, with {} modern key(s)",
                written, aliases);
        return written;
    }

    /** The JSON file to write for each language, and the {@code .lang} file it comes from. */
    static Map<String, String> langSources(SortedSet<String> names, Set<String> ownNamespaces) {
        // Files go only in the mod's own namespaces, never in minecraft or a loader namespace. A
        // language that already has a JSON file of any case is left alone, so the mod's own JSON
        // file is never replaced.
        Map<String, List<String>> candidates = new TreeMap<>();
        for (String name : names) {
            Matcher lang = LANG_FILE.matcher(name);
            if (!lang.matches() || !ownNamespaces.contains(lang.group(1))) continue;
            String code = lang.group(2).toLowerCase(Locale.ROOT);
            if (!LANGUAGE_CODE.matcher(code).matches()) continue;
            String target = "assets/" + lang.group(1) + "/lang/" + code + ".json";
            if (hasJsonTwin(names, target)) continue;
            candidates.computeIfAbsent(target, key -> new ArrayList<>()).add(name);
        }
        // A file named with the lowercase code wins over a mixed-case one, as 1.12 asked for the
        // lowercase name first.
        Map<String, String> sources = new TreeMap<>();
        for (Map.Entry<String, List<String>> candidate : candidates.entrySet()) {
            String lowercase = candidate.getKey().substring(0, candidate.getKey().length() - ".json".length()) + ".lang";
            List<String> files = candidate.getValue();
            sources.put(candidate.getKey(), files.contains(lowercase) ? lowercase : files.get(0));
        }
        return sources;
    }

    /** Whether the folder already has the JSON file {@code target} under any letter case. */
    private static boolean hasJsonTwin(SortedSet<String> names, String target) {
        String folder = target.substring(0, target.lastIndexOf('/') + 1);
        for (String name : names.subSet(folder, folder + Character.MAX_VALUE)) {
            if (name.toLowerCase(Locale.ROOT).equals(target)) return true;
        }
        return false;
    }

    /** Reads a {@code .lang} file as 1.12 Forge did. */
    static Map<String, String> parseLang(byte[] bytes, String name) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        // A file with a #PARSE_ESCAPES comment line is read as a Java properties file, which turns
        // escapes such as \n and unicode escapes into characters.
        List<String> lines = text.lines().toList();
        if (lines.stream().anyMatch(LegacyLangMigrator::isParseEscapesMarker)) {
            try {
                return readProperties(text);
            } catch (IllegalArgumentException malformedEscape) {
                // Forge's reader failed on this file too, so 1.12 never showed this language.
                // Reading line by line keeps every other translation as Forge would have read it.
                LOGGER.warn("{} has a broken escape ({}). Its other lines were converted, and the "
                        + "broken one was kept as written.", name, malformedEscape.getMessage());
                return readPropertiesByLine(lines);
            }
        }
        // Any other file is read line by line. Format codes such as %d stay as written, since
        // newer games convert them when they load.
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : lines) putPlainLine(entries, line);
        return entries;
    }

    private static Map<String, String> readProperties(String text) throws IOException {
        OrderedProperties properties = new OrderedProperties();
        properties.load(new StringReader(text));
        return properties.entries;
    }

    /** Reads each line as properties on its own. */
    private static Map<String, String> readPropertiesByLine(List<String> lines) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : lines) {
            try {
                entries.putAll(readProperties(line));
            } catch (IllegalArgumentException malformedEscape) {
                // A broken escape, such as a unicode escape without four hex digits, costs only its
                // own line, which keeps its text as written.
                putPlainLine(entries, line);
            }
        }
        return entries;
    }

    /** Reads one plain line: it splits at its first {@code =}, and nothing is trimmed. */
    private static void putPlainLine(Map<String, String> entries, String line) {
        // A line starting with # is a comment, and a later key replaces an earlier one.
        if (line.isEmpty() || line.charAt(0) == '#') return;
        int equals = line.indexOf('=');
        if (equals > 0) entries.put(line.substring(0, equals), line.substring(equals + 1));
    }

    /** Whether a line is the {@code #PARSE_ESCAPES} comment. */
    private static boolean isParseEscapesMarker(String line) {
        return !line.isEmpty() && line.charAt(0) == '#' && line.substring(1).trim().equals(PARSE_ESCAPES);
    }

    /** {@link Properties} that remembers the order its entries were read in. */
    private static final class OrderedProperties extends Properties {
        private final Map<String, String> entries = new LinkedHashMap<>();

        @Override
        public synchronized Object put(Object key, Object value) {
            // Properties.load calls put for each entry in the order the file lists them.
            entries.put(String.valueOf(key), String.valueOf(value));
            return super.put(key, value);
        }
    }

    /** The original entries, each followed by the modern key it gained, if any. */
    static Map<String, String> withModernKeys(Map<String, String> entries, String namespace, KeyIndex index) {
        // Each modern key goes to its best match, and a tie goes to the earlier line.
        Map<String, Candidate> best = new HashMap<>();
        int order = 0;
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            for (Candidate candidate : index.modernKeys(entry.getKey(), namespace, order++)) {
                if (entries.containsKey(candidate.key())) continue; // the mod already ships it
                Candidate claimed = best.get(candidate.key());
                if (claimed == null || candidate.compareTo(claimed) < 0) {
                    if (claimed != null) {
                        LOGGER.debug("{} names {} as well as {}; keeping {}", candidate.source(),
                                candidate.key(), claimed.source(), candidate.source());
                    }
                    best.put(candidate.key(), candidate);
                }
            }
        }
        // Every original entry is kept, since the mod's code still asks for its old keys by name.
        // Each one is followed by the modern keys it gained.
        Map<String, List<Candidate>> bySource = new HashMap<>();
        for (Candidate candidate : best.values()) {
            bySource.computeIfAbsent(candidate.source(), key -> new ArrayList<>()).add(candidate);
        }
        Map<String, String> converted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            converted.put(entry.getKey(), entry.getValue());
            List<Candidate> added = bySource.get(entry.getKey());
            if (added == null) continue;
            added.sort(Comparator.comparing(Candidate::key));
            for (Candidate candidate : added) converted.put(candidate.key(), entry.getValue());
        }
        return converted;
    }

    /** One modern key an old key could fill. */
    record Candidate(String key, String source, Match match, int order) implements Comparable<Candidate> {
        @Override
        public int compareTo(Candidate other) {
            int byMatch = match.compareTo(other.match);
            return byMatch != 0 ? byMatch : Integer.compare(order, other.order);
        }
    }

    /** Class bytes from the tree on an extracted path, or from the context on a streaming one. */
    private static List<byte[]> classes(ResourceTree tree, MigrationContext context) throws IOException {
        Set<String> names = new LinkedHashSet<>(tree.names());
        names.addAll(context.classNames());
        List<byte[]> classes = new ArrayList<>();
        for (String name : names) {
            if (!name.endsWith(".class")) continue;
            byte[] bytes = null;
            try {
                bytes = tree.exists(name) ? tree.read(name) : context.readClass(name);
            } catch (IOException unreadable) {
                // An unreadable class only costs the names it would have shown.
            }
            if (bytes != null) classes.add(bytes);
        }
        return classes;
    }

    /** The registry ids a mod shows, and the 1.12 names its classes pair with them. */
    static final class KeyIndex {
        private final Collection<byte[]> classBytes;
        private final Map<String, Set<String>> assetIds = new HashMap<>();
        private LegacyRegistryNames registryNames;

        KeyIndex(SortedSet<String> names, Collection<byte[]> classBytes) {
            this.classBytes = classBytes;
            // A blockstate or an item model shows that its id exists.
            for (String name : names) {
                String[] parts = name.split("/");
                if (!parts[0].equals("assets") || !name.endsWith(".json")) continue;
                String id = null;
                if (parts.length == 4 && parts[2].equals("blockstates")) {
                    id = parts[3];
                } else if (parts.length == 5 && parts[2].equals("models") && parts[3].equals("item")) {
                    id = parts[4];
                }
                if (id != null) {
                    assetIds.computeIfAbsent(parts[1], key -> new LinkedHashSet<>())
                            .add(id.substring(0, id.length() - ".json".length()));
                }
            }
        }

        /** Parsed on first use, since a file with no item, block or entity key never needs it. */
        private LegacyRegistryNames registryNames() {
            if (registryNames == null) registryNames = LegacyRegistryNames.read(classBytes);
            return registryNames;
        }

        /** The modern keys one old key could fill. */
        List<Candidate> modernKeys(String key, String namespace, int order) {
            // A brewed potion key needs no id: potion.effect.<name> became
            // item.minecraft.potion.effect.<name>, and both end in the potion's own name.
            for (Map.Entry<String, String> prefix : POTION_ITEM_PREFIXES.entrySet()) {
                if (key.startsWith(prefix.getKey()) && key.length() > prefix.getKey().length()) {
                    String modern = prefix.getValue() + key.substring(prefix.getKey().length());
                    return List.of(new Candidate(modern, key, Match.POTION_NAME, order));
                }
            }
            // 1.12 named an item item.<name>.name and a block tile.<name>.name, with whatever name
            // the mod chose. 1.13 derives item.<ns>.<id> and block.<ns>.<id> from the registry id.
            Matcher itemOrBlock = ITEM_OR_BLOCK_KEY.matcher(key);
            if (itemOrBlock.matches()) {
                boolean item = itemOrBlock.group(1).equals("item");
                LegacyRegistryNames.KeyFamily family = item
                        ? LegacyRegistryNames.KeyFamily.ITEM : LegacyRegistryNames.KeyFamily.BLOCK;
                return modern(item ? "item" : "block", key, itemOrBlock.group(2),
                        name -> registryNames().translationKey(family, name), namespace, order);
            }
            Matcher entity = ENTITY_KEY.matcher(key);
            if (entity.matches()) {
                // entity.<name>.name became entity.<ns>.<id>. Forge put the mod id in front of the
                // name a mod registered its entity under.
                return modern("entity", key, entity.group(1), registryNames()::entityName, namespace, order);
            }
            // An effect becomes effect.<ns>.<id>, matched through the mod's classes first and then
            // by name alone.
            if (registryNames().potionName(key) != null) {
                return modern("effect", key, key, registryNames()::potionName, namespace, order);
            }
            Matcher effect = EFFECT_KEY.matcher(key);
            if (effect.matches()) {
                return modern("effect", key, effect.group(1), name -> null, namespace, order);
            }
            return List.of();
        }

        /** The modern key for an old name, from the first way it matches exactly one id. */
        private List<Candidate> modern(String family, String key, String oldName,
                                       Function<String, LegacyRegistryNames.Name> pairs,
                                       String namespace, int order) {
            // First, a pair the mod's classes set on one object (LegacyRegistryNames).
            List<String> names = withoutModPrefix(oldName, namespace);
            for (String name : names) {
                LegacyRegistryNames.Name paired = pairs.apply(name);
                if (paired == null) continue;
                String id = modernKey(family, paired.namespace() == null ? namespace : paired.namespace(),
                        paired.path());
                return id == null ? List.of() : List.of(new Candidate(id, key, Match.CLASS_PAIR, order));
            }
            // Then an id that a blockstate, item model or registry call shows: the same name, with
            // or without the mod id in front, then compared without case, then a camel-case name
            // against the id's underscores. A name that matches no id, or more than one, gets no key.
            Set<String> ids = knownIds(namespace);
            for (String name : names) {
                for (Match match : List.of(Match.EXACT, Match.IGNORING_CASE, Match.CAMEL_CASE)) {
                    String id = uniqueMatch(name, ids, match);
                    if (id != null) {
                        String modern = modernKey(family, namespace, id);
                        return modern == null ? List.of() : List.of(new Candidate(modern, key, match, order));
                    }
                }
            }
            return List.of();
        }

        /** The ids in this namespace that assets or registry calls show. */
        private Set<String> knownIds(String namespace) {
            Set<String> ids = new LinkedHashSet<>(assetIds.getOrDefault(namespace, Set.of()));
            for (LegacyRegistryNames.Name name : registryNames().registered()) {
                if (name.namespace() == null || name.namespace().equalsIgnoreCase(namespace)) ids.add(name.path());
            }
            return ids;
        }

        /** The name as written, then without a leading {@code <ns>.}, {@code <ns>:} or {@code <ns>_}. */
        private static List<String> withoutModPrefix(String name, String namespace) {
            List<String> names = new ArrayList<>();
            names.add(name);
            for (String separator : List.of(".", ":", "_")) {
                String prefix = namespace + separator;
                if (name.length() > prefix.length() && name.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    names.add(name.substring(prefix.length()));
                }
            }
            return names;
        }

        /** The one id a name matches in this way, or null when none or several do. */
        private static String uniqueMatch(String name, Set<String> ids, Match match) {
            String wanted = switch (match) {
                case EXACT -> name;
                case IGNORING_CASE -> name.toLowerCase(Locale.ROOT);
                case CAMEL_CASE -> camelToSnake(name);
                default -> null;
            };
            if (wanted == null) return null;
            String found = null;
            for (String id : ids) {
                String candidate = match == Match.EXACT ? id : id.toLowerCase(Locale.ROOT);
                if (!candidate.equals(wanted)) continue;
                if (found != null && !found.equals(id.toLowerCase(Locale.ROOT))) return null;
                found = id.toLowerCase(Locale.ROOT);
            }
            return found;
        }

        private static String camelToSnake(String name) {
            if (name.equals(name.toLowerCase(Locale.ROOT))) return null; // nothing to split
            return name.replaceAll("(?<=[a-z0-9])(?=[A-Z])", "_").toLowerCase(Locale.ROOT);
        }

        /** {@code <family>.<ns>.<id>}, or null when the id is not one a newer game accepts. */
        private static String modernKey(String family, String namespace, String id) {
            String path = id.toLowerCase(Locale.ROOT);
            String lowerNamespace = namespace.toLowerCase(Locale.ROOT);
            if (!ID_PATH.matcher(path).matches() || !ID_PATH.matcher(lowerNamespace).matches()
                    || lowerNamespace.contains("/")) {
                return null;
            }
            // Ids use slashes, keys use dots: block.oldmod.ore/ruby is asked for as block.oldmod.ore.ruby.
            return family + "." + lowerNamespace + "." + path.replace('/', '.');
        }
    }
}
