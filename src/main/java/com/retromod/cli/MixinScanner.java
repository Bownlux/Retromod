/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Scans mod JARs for Mixin classes and their injector handlers, so we can find (predictively)
 * which mixins target MC members that a version jump changes, instead of waiting for crash
 * reports. Reads annotations properly with ASM (never regex over class bytes).
 *
 * <p>Recognizes the SpongePowered core annotation set plus the MixinExtras injector/sugar set.
 * Each injector handler becomes one record; a Mixin class with no injectors still emits one
 * record (injector null) so its target-class coverage is captured.
 */
public final class MixinScanner {

    private MixinScanner() {
    }

    // SpongePowered core injector/handler annotations -> simple name.
    // Descriptors confirmed via javap on real transformed mods (Wynnventory, replaymod).
    private static final Map<String, String> SPONGE_INJECTORS = Map.ofEntries(
            Map.entry("Lorg/spongepowered/asm/mixin/injection/Inject;", "Inject"),
            Map.entry("Lorg/spongepowered/asm/mixin/injection/Redirect;", "Redirect"),
            Map.entry("Lorg/spongepowered/asm/mixin/injection/ModifyArg;", "ModifyArg"),
            Map.entry("Lorg/spongepowered/asm/mixin/injection/ModifyArgs;", "ModifyArgs"),
            Map.entry("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;", "ModifyVariable"),
            Map.entry("Lorg/spongepowered/asm/mixin/injection/ModifyConstant;", "ModifyConstant"),
            Map.entry("Lorg/spongepowered/asm/mixin/gen/Accessor;", "Accessor"),
            Map.entry("Lorg/spongepowered/asm/mixin/gen/Invoker;", "Invoker"),
            Map.entry("Lorg/spongepowered/asm/mixin/Overwrite;", "Overwrite"));

    // MixinExtras injector annotations. Descriptors vary a little by MixinExtras version
    // (some annotations moved into their own subpackage), so recognition also falls back to a
    // known simple-name set under com/llamalad7/mixinextras/injector/ (see recognizeInjector).
    private static final Map<String, String> MIXINEXTRAS_INJECTORS = Map.ofEntries(
            Map.entry("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", "WrapOperation"),
            Map.entry("Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;", "ModifyExpressionValue"),
            Map.entry("Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;", "ModifyReturnValue"),
            Map.entry("Lcom/llamalad7/mixinextras/injector/ModifyReceiver;", "ModifyReceiver"),
            Map.entry("Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;", "WrapMethod"),
            Map.entry("Lcom/llamalad7/mixinextras/injector/WrapWithCondition;", "WrapWithCondition"),
            Map.entry("Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;", "WrapWithCondition"));

    private static final java.util.Set<String> MIXINEXTRAS_SIMPLE_NAMES = java.util.Set.of(
            "WrapOperation", "ModifyExpressionValue", "ModifyReturnValue", "ModifyReceiver",
            "WrapMethod", "WrapWithCondition");

    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String LOCAL_DESC = "Lcom/llamalad7/mixinextras/sugar/Local;";
    private static final String MIXINEXTRAS_INJECTOR_PREFIX = "Lcom/llamalad7/mixinextras/injector/";
    private static final String MIXINEXTRAS_PREFIX = "Lcom/llamalad7/mixinextras/";
    private static final String DESC_DESC = "Lorg/spongepowered/asm/mixin/injection/Desc;";

    /** One emitted record: either a single injector handler, or a bare Mixin class (injector null). */
    public static final class Record {
        public String jar;
        public String mixinClass;
        public List<String> targetClasses = new ArrayList<>();
        public Boolean applied;
        public String handler;
        public String handlerDesc;
        public String injector;
        public List<String> targetSelectors = new ArrayList<>();
        public List<String> at = new ArrayList<>();
        public boolean capturesLocal;
    }

    /** Aggregate result of a scan across one or more JARs. */
    public static final class ScanResult {
        public int scannedJars;
        public int skippedJars;
        public final List<Record> records = new ArrayList<>();
    }

    /**
     * Scan the given inputs (each a JAR or a directory recursed for {@code *.jar}) and return the
     * collected records. A corrupt or unreadable JAR is counted as skipped and never aborts the run.
     */
    public static ScanResult scan(List<Path> inputs) {
        ScanResult result = new ScanResult();
        for (Path jar : collectJars(inputs)) {
            try {
                scanJar(jar, result);
                result.scannedJars++;
            } catch (Exception e) {
                // one bad jar must never abort the whole run; keep going
                result.skippedJars++;
            }
        }
        return result;
    }

    /** Expand inputs into a de-duplicated, sorted list of JAR paths (recursing directories). */
    static List<Path> collectJars(List<Path> inputs) {
        java.util.TreeSet<Path> jars = new java.util.TreeSet<>();
        for (Path in : inputs) {
            if (in == null) continue;
            try {
                if (Files.isDirectory(in)) {
                    try (var walk = Files.walk(in)) {
                        walk.filter(Files::isRegularFile)
                                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                                .forEach(jars::add);
                    }
                } else if (Files.isRegularFile(in)
                        && in.getFileName().toString().toLowerCase().endsWith(".jar")) {
                    jars.add(in);
                }
            } catch (IOException e) {
                // unreadable input path: skip it
            }
        }
        return new ArrayList<>(jars);
    }

    private static void scanJar(Path jarPath, ScanResult result) throws IOException {
        String jarName = jarPath.getFileName().toString();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            // First: which mixin classes are actually declared in a discovered config?
            // null => the jar had no parseable config at all (applied is unresolvable).
            java.util.Set<String> declared = collectDeclaredMixinClasses(jar);

            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class")) continue;
                try (InputStream is = jar.getInputStream(entry)) {
                    ClassReader cr = new ClassReader(is);
                    MixinClassVisitor v = new MixinClassVisitor();
                    // skip code/frames: we only need annotations and member signatures
                    cr.accept(v, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    if (!v.isMixin) continue;

                    Boolean applied;
                    if (declared == null) {
                        applied = null;
                    } else {
                        applied = declared.contains(v.className);
                    }
                    emitRecords(result, jarName, v, applied);
                } catch (Exception e) {
                    // a single unreadable class shouldn't drop the rest of the jar
                }
            }
        }
    }

    private static void emitRecords(ScanResult result, String jarName, MixinClassVisitor v,
            Boolean applied) {
        if (v.injectors.isEmpty()) {
            Record r = new Record();
            r.jar = jarName;
            r.mixinClass = v.className;
            r.targetClasses = v.targetClasses;
            r.applied = applied;
            result.records.add(r);
            return;
        }
        for (InjectorInfo inj : v.injectors) {
            Record r = new Record();
            r.jar = jarName;
            r.mixinClass = v.className;
            r.targetClasses = v.targetClasses;
            r.applied = applied;
            r.handler = inj.handler;
            r.handlerDesc = inj.handlerDesc;
            r.injector = inj.injector;
            r.targetSelectors = inj.targetSelectors;
            r.at = inj.at;
            r.capturesLocal = inj.capturesLocal;
            result.records.add(r);
        }
    }

    /**
     * Build the set of Mixin classes (internal names) declared across every mixin config in the jar,
     * so a scanned Mixin class can be marked applied/not-applied. Returns null when no config was
     * parseable at all (applied then reported as unresolvable).
     */
    private static java.util.Set<String> collectDeclaredMixinClasses(JarFile jar) {
        java.util.LinkedHashSet<String> configNames = new java.util.LinkedHashSet<>();

        // fabric.mod.json "mixins" array (config file names)
        addFabricMixinConfigs(jar, configNames);
        // Forge/NeoForge toml [[mixins]] config = "..."
        addTomlMixinConfigs(jar, "META-INF/mods.toml", configNames);
        addTomlMixinConfigs(jar, "META-INF/neoforge.mods.toml", configNames);
        // MANIFEST MixinConfigs attribute
        addManifestMixinConfigs(jar, configNames);
        // any mixin config JSON anywhere in the jar (also catches root and oddly-named configs
        // like ReplayMod's mixins.<module>.json). parseMixinConfigInto requires a "package" key,
        // so a matched refmap/non-config JSON is skipped harmlessly.
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            String n = entries.nextElement().getName();
            if (n.endsWith(".json")
                    && (n.endsWith(".mixins.json") || n.contains("mixin"))
                    && !n.endsWith(".refmap.json")) {
                configNames.add(n);
            }
        }

        boolean any = false;
        java.util.LinkedHashSet<String> declared = new java.util.LinkedHashSet<>();
        for (String cfg : configNames) {
            JarEntry e = jar.getJarEntry(cfg);
            if (e == null) continue;
            try (InputStream is = jar.getInputStream(e)) {
                String json = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (parseMixinConfigInto(json, declared)) {
                    any = true;
                }
            } catch (Exception ex) {
                // ignore a single unreadable/invalid config
            }
        }
        return any ? declared : null;
    }

    /** Parse one mixin config JSON: package + mixins/client/server arrays -> internal names. */
    private static boolean parseMixinConfigInto(String json, java.util.Set<String> out) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return false;
            JsonObject obj = root.getAsJsonObject();
            if (!obj.has("package")) return false;
            String pkg = obj.get("package").getAsString();
            for (String section : new String[]{"mixins", "client", "server"}) {
                if (obj.has(section) && obj.get(section).isJsonArray()) {
                    for (JsonElement el : obj.getAsJsonArray(section)) {
                        String cls = el.getAsString();
                        String full = (pkg == null || pkg.isEmpty()) ? cls : pkg + "." + cls;
                        out.add(full.replace('.', '/'));
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void addFabricMixinConfigs(JarFile jar, java.util.Set<String> out) {
        JarEntry e = jar.getJarEntry("fabric.mod.json");
        if (e == null) return;
        try (InputStream is = jar.getInputStream(e)) {
            String json = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return;
            JsonElement mixins = root.getAsJsonObject().get("mixins");
            if (mixins == null) return;
            if (mixins.isJsonArray()) {
                for (JsonElement el : mixins.getAsJsonArray()) {
                    if (el.isJsonPrimitive()) {
                        out.add(el.getAsString());
                    } else if (el.isJsonObject() && el.getAsJsonObject().has("config")) {
                        out.add(el.getAsJsonObject().get("config").getAsString());
                    }
                }
            } else if (mixins.isJsonPrimitive()) {
                out.add(mixins.getAsString());
            }
        } catch (Exception ex) {
            // ignore
        }
    }

    private static void addTomlMixinConfigs(JarFile jar, String path, java.util.Set<String> out) {
        JarEntry e = jar.getJarEntry(path);
        if (e == null) return;
        try (InputStream is = jar.getInputStream(e)) {
            String toml = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int idx = 0;
            while ((idx = toml.indexOf("[[mixins]]", idx)) != -1) {
                idx += "[[mixins]]".length();
                int nextSection = toml.indexOf("[[", idx);
                String section = nextSection >= 0 ? toml.substring(idx, nextSection) : toml.substring(idx);
                int configIdx = section.indexOf("config");
                if (configIdx >= 0) {
                    int eqIdx = section.indexOf('=', configIdx);
                    if (eqIdx >= 0) {
                        int qStart = section.indexOf('"', eqIdx);
                        if (qStart >= 0) {
                            int qEnd = section.indexOf('"', qStart + 1);
                            if (qEnd >= 0) {
                                out.add(section.substring(qStart + 1, qEnd));
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            // ignore
        }
    }

    private static void addManifestMixinConfigs(JarFile jar, java.util.Set<String> out) {
        try {
            Manifest mf = jar.getManifest();
            if (mf == null) return;
            String attr = mf.getMainAttributes().getValue("MixinConfigs");
            if (attr == null) return;
            for (String c : attr.split(",")) {
                String t = c.trim();
                if (!t.isEmpty()) out.add(t);
            }
        } catch (Exception ex) {
            // ignore
        }
    }

    /** Recognize an injector annotation descriptor; returns its simple name, or null if not one. */
    static String recognizeInjector(String desc) {
        String name = SPONGE_INJECTORS.get(desc);
        if (name != null) return name;
        name = MIXINEXTRAS_INJECTORS.get(desc);
        if (name != null) return name;
        // Version-tolerant fallback for MixinExtras injector annotations that moved subpackage.
        if (desc.startsWith(MIXINEXTRAS_INJECTOR_PREFIX) && desc.endsWith(";")) {
            String simple = desc.substring(desc.lastIndexOf('/') + 1, desc.length() - 1);
            if (MIXINEXTRAS_SIMPLE_NAMES.contains(simple)) return simple;
        }
        return null;
    }

    /** Recognize the MixinExtras {@code @Local} parameter annotation, tolerant of a moved sugar subpackage. */
    static boolean isLocalAnnotation(String desc) {
        return LOCAL_DESC.equals(desc)
                || (desc != null && desc.startsWith(MIXINEXTRAS_PREFIX) && desc.endsWith("/Local;"));
    }

    // ASM visitors

    private static final class InjectorInfo {
        String injector;
        String handler;
        String handlerDesc;
        List<String> targetSelectors = new ArrayList<>();
        List<String> at = new ArrayList<>();
        boolean capturesLocal;
    }

    private static final class MixinClassVisitor extends ClassVisitor {
        boolean isMixin;
        String className;
        List<String> targetClasses = new ArrayList<>();
        final List<InjectorInfo> injectors = new ArrayList<>();

        MixinClassVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            this.className = name;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (MIXIN_DESC.equals(descriptor)) {
                isMixin = true;
                return new MixinTargetVisitor(targetClasses);
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            return new MixinMethodVisitor(name, descriptor, injectors);
        }
    }

    /** Reads @Mixin value=[Class...] (Type entries) and targets=["dotted.Name"...] (String entries). */
    private static final class MixinTargetVisitor extends AnnotationVisitor {
        private final List<String> targetClasses;

        MixinTargetVisitor(List<String> targetClasses) {
            super(Opcodes.ASM9);
            this.targetClasses = targetClasses;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if ("value".equals(name)) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String n, Object value) {
                        if (value instanceof Type t) {
                            targetClasses.add(t.getInternalName());
                        }
                    }
                };
            }
            if ("targets".equals(name)) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String n, Object value) {
                        if (value instanceof String s) {
                            // string targets are dotted (and may carry a "L...;" @Desc form)
                            targetClasses.add(normalizeStringTarget(s));
                        }
                    }
                };
            }
            return null;
        }

        @Override
        public void visit(String name, Object value) {
            // single-valued forms: value = Foo.class ; targets = "net.example.Foo"
            if ("value".equals(name) && value instanceof Type t) {
                targetClasses.add(t.getInternalName());
            } else if ("targets".equals(name) && value instanceof String s) {
                targetClasses.add(normalizeStringTarget(s));
            }
        }
    }

    private static String normalizeStringTarget(String s) {
        String t = s;
        // tolerate a "Lnet/example/Foo;" @Desc-style descriptor
        if (t.startsWith("L") && t.endsWith(";")) {
            t = t.substring(1, t.length() - 1);
        }
        return t.replace('.', '/');
    }

    private static final class MixinMethodVisitor extends MethodVisitor {
        private final String handler;
        private final String handlerDesc;
        private final List<InjectorInfo> sink;
        private final List<InjectorInfo> mine = new ArrayList<>();
        private boolean capturesLocal;

        MixinMethodVisitor(String handler, String handlerDesc, List<InjectorInfo> sink) {
            super(Opcodes.ASM9);
            this.handler = handler;
            this.handlerDesc = handlerDesc;
            this.sink = sink;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            String injectorName = recognizeInjector(descriptor);
            if (injectorName == null) return null;
            InjectorInfo info = new InjectorInfo();
            info.injector = injectorName;
            info.handler = handler;
            info.handlerDesc = handlerDesc;
            mine.add(info);
            return new InjectorAnnotationVisitor(info);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor,
                boolean visible) {
            if (isLocalAnnotation(descriptor)) {
                capturesLocal = true;
            }
            return null;
        }

        @Override
        public void visitEnd() {
            for (InjectorInfo info : mine) {
                info.capturesLocal = capturesLocal;
                sink.add(info);
            }
        }
    }

    /** Reads a {@code @Desc} selector's member name (its {@code value} element) as a target selector. */
    private static final class DescSelectorVisitor extends AnnotationVisitor {
        private final InjectorInfo info;

        DescSelectorVisitor(InjectorInfo info) {
            super(Opcodes.ASM9);
            this.info = info;
        }

        @Override
        public void visit(String name, Object value) {
            if ("value".equals(name) && value instanceof String s && !s.isEmpty()) {
                info.targetSelectors.add(s);
            }
        }
    }

    /** Reads method/target selectors, nested @At (value + target), and Accessor/Invoker value. */
    private static final class InjectorAnnotationVisitor extends AnnotationVisitor {
        private final InjectorInfo info;

        InjectorAnnotationVisitor(InjectorInfo info) {
            super(Opcodes.ASM9);
            this.info = info;
        }

        @Override
        public void visit(String name, Object value) {
            if (value instanceof String s) {
                if ("method".equals(name) || "target".equals(name)) {
                    info.targetSelectors.add(s);
                } else if ("value".equals(name)
                        && ("Accessor".equals(info.injector) || "Invoker".equals(info.injector))) {
                    // @Accessor/@Invoker value = the field/method name it exposes
                    info.targetSelectors.add(s);
                }
            }
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            if ("method".equals(name) || "target".equals(name)) {
                // "method" holds String selectors; injector-level "target" holds @Desc[] annotations.
                // Handle both: strings go straight in, @Desc entries yield their member name.
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String n, Object value) {
                        if (value instanceof String s) info.targetSelectors.add(s);
                    }

                    @Override
                    public AnnotationVisitor visitAnnotation(String n, String descriptor) {
                        return DESC_DESC.equals(descriptor) ? new DescSelectorVisitor(info) : null;
                    }
                };
            }
            if ("at".equals(name)) {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String n, String descriptor) {
                        return new AtAnnotationVisitor(info);
                    }
                };
            }
            return null;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            // single @At (not wrapped in an array), e.g. @Redirect(at = @At(...))
            if ("at".equals(name)) {
                return new AtAnnotationVisitor(info);
            }
            return null;
        }
    }

    /** Reads a nested @At: value (HEAD/INVOKE/...) and its target selector when present. */
    private static final class AtAnnotationVisitor extends AnnotationVisitor {
        private final InjectorInfo info;
        private String value;
        private String target;

        AtAnnotationVisitor(InjectorInfo info) {
            super(Opcodes.ASM9);
            this.info = info;
        }

        @Override
        public void visit(String name, Object v) {
            if (v instanceof String s) {
                if ("value".equals(name)) {
                    value = s;
                } else if ("target".equals(name)) {
                    target = s;
                }
            }
        }

        @Override
        public void visitEnd() {
            String rendered = value != null ? value : "";
            if (target != null && !target.isEmpty()) {
                rendered = rendered + ":" + target;
            }
            if (!rendered.isEmpty()) {
                info.at.add(rendered);
            }
        }
    }

    // JSON and summary output

    /** Serialize a scan result to the frozen JSON schema the Python tools consume verbatim. */
    public static String toJson(ScanResult result) {
        JsonObject root = new JsonObject();
        root.addProperty("scannedJars", result.scannedJars);
        JsonArray recs = new JsonArray();
        for (Record r : result.records) {
            JsonObject o = new JsonObject();
            o.addProperty("jar", r.jar);
            o.addProperty("mixinClass", r.mixinClass);
            o.add("targetClasses", toJsonArray(r.targetClasses));
            if (r.applied == null) {
                o.add("applied", com.google.gson.JsonNull.INSTANCE);
            } else {
                o.addProperty("applied", r.applied);
            }
            addStringOrNull(o, "handler", r.handler);
            addStringOrNull(o, "handlerDesc", r.handlerDesc);
            addStringOrNull(o, "injector", r.injector);
            o.add("targetSelectors", toJsonArray(r.targetSelectors));
            o.add("at", toJsonArray(r.at));
            o.addProperty("capturesLocal", r.capturesLocal);
            recs.add(o);
        }
        root.add("records", recs);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping()
                .serializeNulls().create();
        return gson.toJson(root);
    }

    private static void addStringOrNull(JsonObject o, String key, String value) {
        if (value == null) {
            o.add(key, com.google.gson.JsonNull.INSTANCE);
        } else {
            o.addProperty(key, value);
        }
    }

    private static JsonArray toJsonArray(List<String> values) {
        JsonArray arr = new JsonArray();
        if (values != null) {
            for (String v : values) arr.add(v);
        }
        return arr;
    }

    /** Print a summary and the most common targets and injectors. */
    public static void printSummary(ScanResult result, int topN, PrintStream out) {
        java.util.Set<String> mixinClasses = new java.util.HashSet<>();
        int handlers = 0;
        Map<String, Integer> byTargetMember = new LinkedHashMap<>();
        Map<String, Integer> byInjector = new TreeMap<>();

        for (Record r : result.records) {
            mixinClasses.add(r.jar + "!" + r.mixinClass);
            if (r.injector != null) {
                handlers++;
                byInjector.merge(r.injector, 1, Integer::sum);
                String member = firstSelectorMember(r);
                for (String tc : r.targetClasses.isEmpty()
                        ? List.of("(unknown)") : r.targetClasses) {
                    String key = tc + "::" + member;
                    byTargetMember.merge(key, 1, Integer::sum);
                }
            }
        }

        out.println();
        out.println("Mixin scan summary");
        out.println("Jars scanned:   " + result.scannedJars
                + (result.skippedJars > 0 ? " (" + result.skippedJars + " skipped)" : ""));
        out.println("Mixin classes:  " + mixinClasses.size());
        out.println("Injector records: " + handlers);

        if (!byInjector.isEmpty()) {
            out.println();
            out.println("By injector type:");
            byInjector.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> out.printf("  %-24s %d%n", e.getKey(), e.getValue()));
        }

        if (topN > 0 && !byTargetMember.isEmpty()) {
            out.println();
            out.println("Top " + topN + " referenced targets (targetClass::targetMember):");
            byTargetMember.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue())
                            .reversed()
                            .thenComparing(Map.Entry::getKey))
                    .limit(topN)
                    .forEach(e -> out.printf("  %4d  %s%n", e.getValue(), e.getKey()));
        }
        out.println();
    }

    /** The target member (method name) from the first selector, for ranking. */
    private static String firstSelectorMember(Record r) {
        if (r.targetSelectors == null || r.targetSelectors.isEmpty()) return "(none)";
        String sel = r.targetSelectors.get(0);
        // selector may be "name(desc)ret" or a fully-qualified "Lowner;name(desc)ret"
        int paren = sel.indexOf('(');
        String head = paren >= 0 ? sel.substring(0, paren) : sel;
        int semi = head.lastIndexOf(';');
        if (semi >= 0) head = head.substring(semi + 1);
        int dot = head.lastIndexOf('.');
        if (dot >= 0) head = head.substring(dot + 1);
        return head.isEmpty() ? sel : head;
    }
}
