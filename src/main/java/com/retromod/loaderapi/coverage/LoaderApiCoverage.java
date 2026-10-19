/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.coverage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.retromod.core.RedirectKey;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.LoaderApiContext;
import com.retromod.loaderapi.LoaderApiRuntime;
import com.retromod.loaderapi.fabric.v26.HostSurface;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.shim.ShimRegistry;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Builds the loader API coverage report by replaying registration per host with the runtime forced on. */
public final class LoaderApiCoverage {

    /** The default hosts reported. The newest indexed line is also checked, but not reported. */
    public static final List<String> DEFAULT_HOSTS = List.of("26.1", "26.2", "26.3");

    /**
     * One row of the hand-kept status table. {@code oldMods} counts mods that name the declaring
     * owner; {@code callSiteMods} counts mods that call the member through a subtype, once per
     * call-site owner.
     */
    public record StatusRow(String module, String owner, String kind, String name,
            String oldDescriptor, List<String> callSiteOwners, int oldMods, int callSiteMods,
            Map<String, String> marks, String reason, String batch, String evidence) {

        /** The count a worklist ranks by: direct calls plus calls through a subtype. */
        public int reachMods() {
            return oldMods + callSiteMods;
        }

        /** This row's key in a corpus ranking TSV, for a call that names {@code callOwner}. */
        String usageKey(String callOwner) {
            return switch (kind) {
                case "C" -> "C " + callOwner;
                case "M" -> "M " + callOwner + "#" + name + oldDescriptor;
                default -> "F " + callOwner + "#" + name + ":" + oldDescriptor;
            };
        }

        /** {@code owner}, {@code owner.name(desc)} or {@code owner.name:desc}, in old names. */
        public String key() {
            return switch (kind) {
                case "C" -> owner;
                case "M" -> owner + "." + name + oldDescriptor;
                default -> owner + "." + name + ":" + oldDescriptor;
            };
        }
    }

    // but one status for one row on one host.
    public record Result(String host, StatusRow row, String status, String target) {}


     // The whole report. {@code unmatchedUsage} lists, per host, the corpus usage keys that resolve
     // on neither the host surface nor a status row, as {@code key<TAB>mods}
    public record Report(List<String> hosts, List<Result> results, Map<String, Integer> declaredKeys,
            Map<String, List<String>> unmatchedUsage) {

        // Results for one host;.
        public List<Result> forHost(String host) {
            return results.stream().filter(result -> result.host().equals(host)).toList();
        }

        /** Row keys with status {@code done} on {@code host}. */
        public Set<String> doneKeys(String host) {
            Set<String> keys = new TreeSet<>();
            for (Result result : forHost(host)) {
                if ("done".equals(result.status())) keys.add(result.row().key());
            }
            return keys;
        }

        /** Counts per status on {@code host}. */
        public Map<String, Integer> totals(String host) {
            Map<String, Integer> totals = new TreeMap<>();
            for (Result result : forHost(host)) {
                totals.merge(result.status(), 1, Integer::sum);
            }
            return totals;
        }
    }

    private LoaderApiCoverage() {}

    /** Reads the hand-kept status table. */
    public static List<StatusRow> readStatus(Path path) throws IOException {
        List<StatusRow> rows = new ArrayList<>();
        List<String> header = null;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] cells = line.split("\t", -1);
            if (header == null) {
                header = List.of(cells);
                continue;
            }
            Map<String, String> cell = new LinkedHashMap<>();
            for (int i = 0; i < header.size() && i < cells.length; i++) {
                cell.put(header.get(i), "-".equals(cells[i]) ? "" : cells[i]);
            }
            Map<String, String> marks = new TreeMap<>();
            for (String column : header) {
                if (column.startsWith("mark_")) marks.put(column.substring(5), cell.get(column));
            }
            String owners = cell.getOrDefault("call_site_owners", "");
            rows.add(new StatusRow(cell.get("module"), cell.get("owner"), cell.get("kind"),
                    cell.get("name"), cell.get("old_descriptor"),
                    owners.isEmpty() ? List.of() : List.of(owners.split(",")),
                    parseInt(cell.get("old_mods")), parseInt(cell.get("call_site_mods")), marks,
                    cell.getOrDefault("reason", ""), cell.getOrDefault("batch", ""),
                    cell.getOrDefault("evidence", "")));
        }
        return rows;
    }

    /**
     * Reads the corpus counts of a ranking TSV such as {@code ranked-members.tsv}: its {@code key}
     * column mapped to its {@code old_mods} column. Only counts are read.
     */
    public static Map<String, Integer> readUsage(Path usage) throws IOException {
        Map<String, Integer> counts = new TreeMap<>();
        List<String> lines = Files.readAllLines(usage, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return counts;
        List<String> header = List.of(lines.get(0).split("\t", -1));
        int keyColumn = header.indexOf("key");
        int modsColumn = header.indexOf("old_mods");
        if (keyColumn < 0 || modsColumn < 0) {
            throw new IOException(usage + " needs 'key' and 'old_mods' columns");
        }
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split("\t", -1);
            if (cells.length <= Math.max(keyColumn, modsColumn)) continue;
            counts.put(cells[keyColumn], parseInt(cells[modsColumn]));
        }
        return counts;
    }

    /** {@link #applyUsage(List, Map)} with the counts of a ranking TSV. */
    public static List<StatusRow> applyUsage(List<StatusRow> rows, Path usage) throws IOException {
        return applyUsage(rows, readUsage(usage));
    }

    /**
     * Sets each row's {@code old_mods} from its own key and its {@code call_site_mods} from the
     * keys of its call-site owners. A row with no matching key keeps the counts it had.
     */
    public static List<StatusRow> applyUsage(List<StatusRow> rows, Map<String, Integer> counts) {
        List<StatusRow> updated = new ArrayList<>();
        for (StatusRow row : rows) {
            Integer direct = counts.get(row.usageKey(row.owner()));
            Integer throughSubtypes = null;
            for (String owner : row.callSiteOwners()) {
                Integer count = counts.get(row.usageKey(owner));
                if (count != null) throughSubtypes = (throughSubtypes == null ? 0 : throughSubtypes) + count;
            }
            updated.add(new StatusRow(row.module(), row.owner(), row.kind(), row.name(),
                    row.oldDescriptor(), row.callSiteOwners(),
                    direct != null ? direct : row.oldMods(),
                    throughSubtypes != null ? throughSubtypes : row.callSiteMods(),
                    row.marks(), row.reason(), row.batch(), row.evidence()));
        }
        return updated;
    }

    private static int parseInt(String value) {
        try {
            return value == null || value.isEmpty() ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Generates the report for {@code hosts}. The singleton transformer is cleared before and after,
     * so this belongs in a tool or test, never in a running game.
     */
    public static Report generate(List<StatusRow> rows, List<String> hosts) {
        return generate(rows, hosts, Map.of());
    }

    /** {@link #generate(List, List)}, also listing the {@code usage} keys no row or host member covers. */
    public static Report generate(List<StatusRow> rows, List<String> hosts, Map<String, Integer> usage) {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        String previousHost = RetromodVersion.TARGET_MC_VERSION;
        List<Result> results = new ArrayList<>();
        Map<String, Integer> declared = new TreeMap<>();
        Map<String, List<String>> unmatched = new LinkedHashMap<>();
        try {
            for (String host : hosts) {
                List<LoaderApiRuntime.Ledger> ledgers = replay(transformer, host);
                int keys = ledgers.stream().mapToInt(ledger -> ledger.declared().size()).sum();
                declared.put(host, keys);
                HostSurface surface = HostSurface.forHost(host);
                Remapper names = finalNames(transformer);
                for (StatusRow row : rows) {
                    results.add(classify(row, host, ledgers, transformer, surface, names));
                }
                unmatched.put(host, unmatchedUsage(usage, rows, surface, names));
            }
        } finally {
            transformer.resetOfflineRegistrations();
            RetromodVersion.TARGET_MC_VERSION = previousHost;
        }
        return new Report(List.copyOf(hosts), results, declared, unmatched);
    }

    /**
     * The usage keys that neither resolve on {@code surface} nor belong to a status row, directly
     * or through a call-site owner. Mixin selectors and string constants are not API members.
     */
    static List<String> unmatchedUsage(Map<String, Integer> usage, List<StatusRow> rows,
            HostSurface surface, Remapper names) {
        Set<String> covered = new java.util.HashSet<>();
        for (StatusRow row : rows) {
            covered.add(row.usageKey(row.owner()));
            for (String owner : row.callSiteOwners()) covered.add(row.usageKey(owner));
        }
        List<String> unmatched = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : new TreeMap<>(usage).entrySet()) {
            String key = entry.getKey();
            if (covered.contains(key) || !isFabricApiKey(key) || resolvesOnHost(key, surface, names)) {
                continue;
            }
            unmatched.add(key + "\t" + entry.getValue());
        }
        return unmatched;
    }

    private static boolean isFabricApiKey(String key) {
        return (key.startsWith("C ") || key.startsWith("M ") || key.startsWith("F "))
                && key.startsWith("net/fabricmc/fabric/", 2);
    }

    /** Whether a usage key in old names names a class or member the host still has. */
    private static boolean resolvesOnHost(String key, HostSurface surface, Remapper names) {
        String body = key.substring(2);
        if (key.startsWith("C ")) return surface.hasClass(body);
        int hash = body.indexOf('#');
        if (hash < 0) return false;
        String owner = body.substring(0, hash);
        String member = body.substring(hash + 1);
        int split = key.startsWith("M ") ? member.indexOf('(') : member.indexOf(':');
        if (split < 0) return false;
        String name = member.substring(0, split);
        String desc = key.startsWith("M ") ? names.mapMethodDesc(member.substring(split))
                : names.mapDesc(member.substring(split + 1));
        return surface.member(owner, name, desc) != HostSurface.Presence.MISSING;
    }

    /** Replays the Fabric pre-launch registration for {@code host}, then the runtime, forced on. */
    static List<LoaderApiRuntime.Ledger> replay(RetromodTransformer transformer, String host) {
        transformer.resetOfflineRegistrations();
        RetromodVersion.TARGET_MC_VERSION = host;
        // Shims first, gated as the pre-launch gates them.
        Iterator<VersionShim> shims = ServiceLoader.load(VersionShim.class).iterator();
        while (shims.hasNext()) {
            VersionShim shim;
            try {
                shim = shims.next();
            } catch (ServiceConfigurationError e) {
                continue;
            }
            String loader = shim.getModLoaderType();
            if (!"fabric".equals(loader) && !"common".equals(loader)) continue;
            if (!ShimRegistry.isAvailableOnHost(shim, host)) continue;
            try {
                shim.registerRedirects(transformer);
            } catch (RuntimeException e) {
                // A shim that cannot register on this host does not change the runtime's rows.
            }
        }
        // Then the intermediary names composed with the class moves, as on a 26.x host.
        IntermediaryToMojangMapper mapper = IntermediaryToMojangMapper.getInstance();
        if (mapper.isLoaded()) {
            Map<String, String> moves = mapper.getClassMoves();
            for (Map.Entry<String, String> entry : mapper.getClassMap().entrySet()) {
                transformer.registerClassRedirect(entry.getKey(),
                        moves.getOrDefault(entry.getValue(), entry.getValue()));
            }
            moves.forEach(transformer::registerClassRedirect);
        }
        new com.retromod.polyfill.PolyfillRegistry().loadAndRegister(transformer);
        // Last, the runtime, whatever the setting says.
        return LoaderApiRuntime.prepareForced(transformer,
                new LoaderApiContext("fabric", host, LoaderApiContext.TransformPath.TEST, null));
    }

    private static Remapper finalNames(RetromodTransformer transformer) {
        Map<String, String> redirects = transformer.getClassRedirects();
        return new Remapper() {
            @Override
            public String map(String internalName) {
                String current = internalName;
                // Follow chains such as class_4064 to CycleOption to OptionInstance.
                for (int hops = 0; hops < 8; hops++) {
                    String next = redirects.get(current);
                    if (next == null || next.equals(current)) break;
                    current = next;
                }
                return current;
            }
        };
    }

    private static Result classify(StatusRow row, String host,
            List<LoaderApiRuntime.Ledger> ledgers, RetromodTransformer transformer,
            HostSurface surface, Remapper names) {
        String owner = row.owner();
        String desc = row.oldDescriptor() == null || row.oldDescriptor().isEmpty() ? null
                : row.kind().equals("M") ? names.mapMethodDesc(row.oldDescriptor())
                : names.mapDesc(row.oldDescriptor());
        RedirectKey key = switch (row.kind()) {
            case "C" -> RedirectKey.type(owner);
            case "M" -> RedirectKey.method(owner, row.name(), desc);
            default -> RedirectKey.field(owner, row.name());
        };
        boolean owned = false;
        boolean memberDeclared = false;
        boolean suppressed = false;
        for (LoaderApiRuntime.Ledger ledger : ledgers) {
            if (ledger.declared().contains(key)) memberDeclared = true;
            if (memberDeclared || ledger.declared().contains(RedirectKey.type(owner))) owned = true;
            if (ledger.suppressed().contains(key)) suppressed = true;
        }
        if (!owned) return new Result(host, row, todayStatus(row, host), "");
        // Owning a type does not serve a member the status table marks impossible, such as a
        // pre-1.20 PoseStack shape of a callback whose newer shapes the runtime serves.
        String today = todayStatus(row, host);
        if (!memberDeclared && !"C".equals(row.kind()) && today.startsWith("impossible:")) {
            return new Result(host, row, today, "");
        }

        // A runtime row: its target must link on this host.
        String target;
        boolean links;
        if (suppressed) {
            target = "host";
            links = "C".equals(row.kind()) ? surface.hasClass(owner)
                    : surface.member(owner, row.name(), desc) != HostSurface.Presence.MISSING;
        } else {
            target = targetOf(transformer, key);
            String classTarget = transformer.getClassRedirects().get(owner);
            String onNewType = target == null && classTarget != null && "M".equals(row.kind())
                    ? targetOf(transformer, RedirectKey.method(classTarget, row.name(), desc)) : null;
            if (onNewType != null) {
                // The class remap runs first, then a redirect keyed on the new type renames the call,
                // as it does for a shared type's erased method.
                target = onNewType;
                links = targetLinks(transformer, surface, target);
            } else if (target == null && !"C".equals(row.kind()) && classTarget != null) {
                // A member of a redirected type is served by the same member on the new type.
                target = classTarget + "." + row.name() + ("M".equals(row.kind()) ? desc : "");
                links = memberOnRedirectedType(transformer, surface, classTarget, row.name(), desc,
                        "M".equals(row.kind()));
            } else {
                links = target != null && targetLinks(transformer, surface, target);
            }
        }
        if (!links) return new Result(host, row, "unlinked", target == null ? "" : target);
        boolean evidence = row.evidence().contains("unit:")
                && (row.evidence().contains("testmod:") || row.evidence().contains("corpus:"));
        return new Result(host, row, evidence ? "done" : "linked", target);
    }

    /** The status from the hand-kept mark, for a row the runtime does not own. */
    private static String todayStatus(StatusRow row, String host) {
        String mark = row.marks().getOrDefault(HostSurface.lineFor(host), "");
        if (mark.isEmpty()) mark = row.marks().getOrDefault("26.3", "missing");
        return switch (mark) {
            case "working" -> "shim";
            case "replace" -> "stand-in";
            case "impossible" -> "impossible:" + reasonCode(row.reason());
            case "dev-only" -> "dev-only";
            default -> "missing";
        };
    }

    private static String reasonCode(String reason) {
        int colon = reason.indexOf(':');
        return colon > 0 ? reason.substring(0, colon).trim() : "unspecified";
    }

    /** The target the transformer holds for a runtime key, as {@code owner.name(desc)} or a class. */
    private static String targetOf(RetromodTransformer transformer, RedirectKey key) {
        return switch (key.kind()) {
            case TYPE -> transformer.getClassRedirects().get(key.owner());
            case METHOD -> {
                var target = transformer.getMethodRedirects().get(
                        new RetromodTransformer.MethodKey(key.owner(), key.name(), key.desc()));
                yield target == null ? null : target.owner() + "." + target.name() + target.desc();
            }
            case FIELD -> {
                var target = transformer.getFieldRedirects().get(
                        new RetromodTransformer.FieldKey(key.owner(), key.name()));
                yield target == null ? null : target.owner() + "." + target.name()
                        + (target.newDesc() == null ? "" : ":" + target.newDesc());
            }
        };
    }

    /**
     * Whether a target exists on the host: in its Fabric API surface, as a registered synthetic,
     * or as a compiled Retromod class that declares the member.
     */
    static boolean targetLinks(RetromodTransformer transformer, HostSurface surface, String target) {
        int dot = target.indexOf('.');
        String owner = dot < 0 ? target : target.substring(0, dot);
        String member = dot < 0 ? null : target.substring(dot + 1);
        if (transformer.getSyntheticClasses().containsKey(owner)) return true;
        if (owner.startsWith("net/fabricmc/fabric/")) {
            if (member == null) return surface.hasClass(owner);
            String[] parts = splitMember(member);
            return surface.member(owner, parts[0], parts[1]) != HostSurface.Presence.MISSING;
        }
        if (owner.startsWith("com/retromod/")) {
            return compiledClassDeclares(owner, member);
        }
        // Minecraft and JDK targets are outside this report; the Minecraft side owns them.
        return true;
    }

    /**
     * Whether {@code type}, the target of a class redirect, has the member. A compiled Retromod
     * type may erase parameters to {@code Object}, so for it the name and parameter count decide.
     */
    private static boolean memberOnRedirectedType(RetromodTransformer transformer,
            HostSurface surface, String type, String name, String desc, boolean method) {
        if (type.startsWith("net/fabricmc/fabric/")) {
            return surface.member(type, name, desc) != HostSurface.Presence.MISSING;
        }
        if (transformer.getSyntheticClasses().containsKey(type)) return true;
        if (!type.startsWith("com/retromod/")) return true;
        try (InputStream in = LoaderApiCoverage.class.getClassLoader()
                .getResourceAsStream(type + ".class")) {
            if (in == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
            if (!method) return node.fields.stream().anyMatch(field -> field.name.equals(name));
            int arguments = org.objectweb.asm.Type.getArgumentTypes(desc).length;
            return node.methods.stream().anyMatch(candidate -> candidate.name.equals(name)
                    && org.objectweb.asm.Type.getArgumentTypes(candidate.desc).length == arguments);
        } catch (IOException e) {
            return false;
        }
    }

    private static String[] splitMember(String member) {
        int paren = member.indexOf('(');
        int colon = member.indexOf(':');
        // A field read sent to a method is written name:(desc), so the colon comes first.
        if (colon >= 0 && (paren < 0 || colon < paren)) {
            return new String[]{member.substring(0, colon), member.substring(colon + 1)};
        }
        if (paren >= 0) return new String[]{member.substring(0, paren), member.substring(paren)};
        return new String[]{member, ""};
    }

    private static boolean compiledClassDeclares(String owner, String member) {
        try (InputStream in = LoaderApiCoverage.class.getClassLoader()
                .getResourceAsStream(owner + ".class")) {
            if (in == null) return false;
            if (member == null) return true;
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
            String[] parts = splitMember(member);
            if (parts[1].startsWith("(")) {
                return node.methods.stream().anyMatch(method -> method.name.equals(parts[0])
                        && method.desc.equals(parts[1]));
            }
            return node.fields.stream().anyMatch(field -> field.name.equals(parts[0]));
        } catch (IOException e) {
            return false;
        }
    }

    // ---- Output --------------------------------------------------------------------------

    /** Writes {@code fabric-26x.tsv}, {@code fabric-26x.json} and {@code fabric-26x.md}. */
    public static void write(Report report, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        StringBuilder tsv = new StringBuilder(
                "host\tmodule\towner\tkind\tname\told_descriptor\tstatus\treason\tbatch\ttarget\t"
                        + "evidence\told_mods\tcall_site_owners\tcall_site_mods\treach_mods\n");
        for (Result result : report.results()) {
            StatusRow row = result.row();
            tsv.append(String.join("\t", result.host(), row.module(), row.owner(), row.kind(),
                    row.name(), row.oldDescriptor(), result.status(), row.reason(), row.batch(),
                    result.target(), row.evidence(), Integer.toString(row.oldMods()),
                    String.join(",", row.callSiteOwners()), Integer.toString(row.callSiteMods()),
                    Integer.toString(row.reachMods()))).append('\n');
        }
        Files.writeString(outDir.resolve("fabric-26x.tsv"), tsv.toString());

        StringBuilder unmatched = new StringBuilder("host\tkey\told_mods\n");
        report.unmatchedUsage().forEach((host, keys) -> keys.forEach(
                key -> unmatched.append(host).append('\t').append(key).append('\n')));
        Files.writeString(outDir.resolve("fabric-26x-unmatched.tsv"), unmatched.toString());

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("hosts", report.hosts());
        Map<String, Object> totals = new LinkedHashMap<>();
        for (String host : report.hosts()) totals.put(host, report.totals(host));
        json.put("totals", totals);
        json.put("runtime_keys", report.declaredKeys());
        Map<String, Integer> unmatchedCounts = new LinkedHashMap<>();
        report.unmatchedUsage().forEach((host, keys) -> unmatchedCounts.put(host, keys.size()));
        json.put("unmatched_usage_keys", unmatchedCounts);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Result result : report.results()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("host", result.host());
            entry.put("key", result.row().key());
            entry.put("module", result.row().module());
            entry.put("status", result.status());
            entry.put("batch", result.row().batch());
            entry.put("target", result.target());
            entry.put("old_mods", result.row().oldMods());
            entry.put("call_site_mods", result.row().callSiteMods());
            entry.put("reach_mods", result.row().reachMods());
            rows.add(entry);
        }
        json.put("rows", rows);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Files.writeString(outDir.resolve("fabric-26x.json"), gson.toJson(json));
        Files.writeString(outDir.resolve("fabric-26x.md"), markdown(report));
    }

    private static String markdown(Report report) {
        List<String> statuses = List.of("done", "linked", "unlinked", "shim", "stand-in",
                "missing", "dev-only");
        StringBuilder md = new StringBuilder("# Fabric API Runtime Coverage\n\n");
        md.append("Generated by `loader-api-coverage` on ").append(java.time.LocalDate.now())
                .append(" for Retromod ").append(RetromodVersion.RETROMOD_VERSION)
                .append(". `shim` rows link today through an older Retromod shim outside the "
                        + "runtime. `unlinked` rows are runtime rows whose target is missing on "
                        + "that host.\n\n");
        md.append("## Totals by Host\n\n| Host | ");
        for (String status : statuses) md.append(status).append(" | ");
        md.append("impossible |\n|---|");
        md.append("---|".repeat(statuses.size() + 1)).append('\n');
        for (String host : report.hosts()) {
            Map<String, Integer> totals = report.totals(host);
            md.append("| ").append(host).append(" | ");
            for (String status : statuses) md.append(totals.getOrDefault(status, 0)).append(" | ");
            int impossible = totals.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith("impossible:"))
                    .mapToInt(Map.Entry::getValue).sum();
            md.append(impossible).append(" |\n");
        }
        String newest = report.hosts().get(report.hosts().size() - 1);
        md.append("\n## Batches on ").append(newest).append("\n\n| Batch | Rows | Done |\n|---|---|---|\n");
        Map<String, int[]> batches = new TreeMap<>();
        for (Result result : report.forHost(newest)) {
            String batch = result.row().batch().isEmpty() ? "-" : result.row().batch();
            int[] counts = batches.computeIfAbsent(batch, ignored -> new int[2]);
            counts[0]++;
            if ("done".equals(result.status())) counts[1]++;
        }
        batches.forEach((batch, counts) -> md.append("| ").append(batch).append(" | ")
                .append(counts[0]).append(" | ").append(counts[1]).append(" |\n"));
        md.append("\n## Impossible Rows on ").append(newest).append("\n\n| Reason | Rows |\n|---|---|\n");
        Map<String, Integer> impossible = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : report.totals(newest).entrySet()) {
            if (entry.getKey().startsWith("impossible:")) {
                impossible.put(entry.getKey().substring("impossible:".length()), entry.getValue());
            }
        }
        impossible.forEach((reason, count) -> md.append("| ").append(reason).append(" | ")
                .append(count).append(" |\n"));
        List<String> unmatched = report.unmatchedUsage().getOrDefault(newest, List.of());
        if (!unmatched.isEmpty()) {
            md.append("\n## Unmatched Usage on ").append(newest).append("\n\n")
                    .append(unmatched.size()).append(" corpus usage key(s) resolve on neither the host ")
                    .append("surface nor a status row. They are listed in `fabric-26x-unmatched.tsv`.\n");
        }
        return md.toString();
    }

}
