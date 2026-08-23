/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.retromod.util.JsonSecurity;
import com.retromod.util.ZipSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Scans game logs and crash reports for known error patterns and registers
 * targeted fixes on the transformer so the next launch succeeds. Runs after the
 * initial transformation; it never relaunches the game.
 *
 * <p>A fix key dedupes within a run; a fix applied but whose error recurs on the
 * next run is blacklisted. At most {@value #MAX_FIXES_PER_RUN} fixes per run
 * guard against runaway loops.
 *
 * <p>Must not reference Fabric/NeoForge loader classes: the standalone CLI uses
 * this too.
 */
public class AutoFixEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-AutoFix");

    // Relative to the working dir; symlink-guarded at write time in persistFixes.
    private static final Path FIXES_FILE = Path.of("config/retromod/auto-fixes.json");

    private static final int MAX_FIXES_PER_RUN = 50;
    private static final long MAX_FIXES_FILE_BYTES = 2L * 1024 * 1024;
    private static final long MAX_LOG_FILE_BYTES = 64L * 1024 * 1024;
    private static final int MAX_LOG_LINE_CHARS = 256 * 1024;
    private static final int MAX_LOG_LINES = 1_000_000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final Pattern VERIFY_TYPE_PAT = Pattern.compile("Type '(\\S+)' .*(?:is not assignable to|not assignable to) '(\\S+)'");

    private static final Pattern INJECTION_MIXIN_PAT = Pattern.compile("in (\\S+Mixin\\S*)");

    private static final Pattern NEG_ARRAY_CLASS_PAT = Pattern.compile("(?:processing|transforming|visiting)\\s+(\\S+)");

    private static final Pattern NAMESPACE_MOD_PAT = Pattern.compile("(?:for|from|in) (?:mod )?'?(\\S+?)'?(?:\\s|$|:)");

    private final List<ErrorPattern> patterns = new ArrayList<>();

    private final List<AppliedFix> appliedFixes = new ArrayList<>();

    private final Set<String> appliedFixKeys = new HashSet<>();

    /** Fix keys whose error came back after being applied, so they are not re-applied. */
    private final Set<String> blacklist = new HashSet<>();

    /** Fix keys from the previous run, loaded from disk to detect recurrence. */
    private final Set<String> previousRunFixKeys = new HashSet<>();

    public AutoFixEngine() {
        registerAllPatterns();
    }

    /**
     * Scan a log file and apply the matching fixes to the transformer.
     *
     * @param logFile     path to the game log or crash report
     * @param transformer the transformer to register fixes on
     * @return the fixes that were applied (empty if none)
     */
    public List<AppliedFix> analyzeAndFix(Path logFile, RetromodTransformer transformer) {
        if (logFile == null || !Files.exists(logFile)) {
            LOGGER.warn("[AutoFix] Log file not found: {}", logFile);
            return Collections.emptyList();
        }

        loadPreviousFixes();

        List<String> logLines;
        try {
            logLines = readLogLines(logFile);
        } catch (IOException e) {
            LOGGER.error("[AutoFix] Could not read log file: {}", e.getMessage());
            return Collections.emptyList();
        }

        LOGGER.info("[AutoFix] Analyzing {} lines from {}", logLines.size(), logFile.getFileName());

        List<AppliedFix> fixes = new ArrayList<>();
        int fixCount = 0;

        for (int i = 0; i < logLines.size() && fixCount < MAX_FIXES_PER_RUN; i++) {
            String line = logLines.get(i);

            for (ErrorPattern pattern : patterns) {
                Matcher matcher = pattern.regex.matcher(line);
                if (matcher.find()) {
                    String fixKey = pattern.buildFixKey(matcher, line);

                    if (appliedFixKeys.contains(fixKey)) break;
                    if (blacklist.contains(fixKey)) {
                        LOGGER.debug("[AutoFix] Skipping blacklisted fix: {}", fixKey);
                        break;
                    }

                    // Applied last run, yet the error is back: it didn't work.
                    if (previousRunFixKeys.contains(fixKey)) {
                        LOGGER.warn("[AutoFix] Fix '{}' was applied previously but error recurred - blacklisting", fixKey);
                        blacklist.add(fixKey);
                        break;
                    }

                    // Errors can span lines; look ahead for stack-trace context.
                    String context = buildContext(logLines, i, 5);
                    AppliedFix fix = pattern.apply(matcher, line, context, transformer);
                    if (fix != null) {
                        fixes.add(fix);
                        appliedFixes.add(fix);
                        appliedFixKeys.add(fixKey);
                        fixCount++;
                        LOGGER.info("[AutoFix] Applied: {}", fix);
                    }
                    break; // first matching pattern wins for this line
                }
            }
        }

        if (!fixes.isEmpty()) {
            persistFixes();
            LOGGER.info("[AutoFix] Applied {} fix(es). Retransform on next launch to take effect.", fixes.size());
        } else {
            LOGGER.info("[AutoFix] No actionable errors found in log.");
        }

        return fixes;
    }

    /**
     * Report what fixes would be applied without applying them (CLI {@code autofix} dry-run).
     *
     * @param logFile path to the log file
     * @return the suggested fixes
     */
    public List<AppliedFix> analyzeOnly(Path logFile) {
        if (logFile == null || !Files.exists(logFile)) {
            LOGGER.warn("[AutoFix] Log file not found: {}", logFile);
            return Collections.emptyList();
        }

        List<String> logLines;
        try {
            logLines = readLogLines(logFile);
        } catch (IOException e) {
            LOGGER.error("[AutoFix] Could not read log file: {}", e.getMessage());
            return Collections.emptyList();
        }

        List<AppliedFix> suggestions = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        for (int i = 0; i < logLines.size(); i++) {
            String line = logLines.get(i);

            for (ErrorPattern pattern : patterns) {
                Matcher matcher = pattern.regex.matcher(line);
                if (matcher.find()) {
                    String fixKey = pattern.buildFixKey(matcher, line);
                    if (seenKeys.contains(fixKey)) break;
                    seenKeys.add(fixKey);

                    String context = buildContext(logLines, i, 5);
                    AppliedFix suggestion = pattern.describe(matcher, line, context);
                    if (suggestion != null) {
                        suggestions.add(suggestion);
                    }
                    break;
                }
            }
        }

        return suggestions;
    }

    static List<String> readLogLines(Path logFile) throws IOException {
        return readLogLines(logFile, MAX_LOG_FILE_BYTES,
                MAX_LOG_LINE_CHARS, MAX_LOG_LINES);
    }

    static List<String> readLogLines(Path logFile, long maxBytes,
            int maxLineChars, int maxLines) throws IOException {
        if (maxBytes <= 0 || maxLineChars <= 0 || maxLines <= 0) {
            throw new IllegalArgumentException("Log limits must be positive");
        }
        Path inputPath = ZipSecurity.requireRegularFileNoFollow(
                logFile, "AutoFix log input");
        if (Files.size(inputPath) > maxBytes) {
            throw new IOException("AutoFix log exceeds " + maxBytes + " bytes: " + inputPath);
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(inputPath)) {
            bytes = ZipSecurity.safeReadAllBytes(input, maxBytes);
        }
        return splitLogLines(new String(bytes, StandardCharsets.UTF_8),
                maxLineChars, maxLines);
    }

    private static List<String> splitLogLines(
            String content, int maxLineChars, int maxLines) throws IOException {
        List<String> lines = new ArrayList<>();
        int lineStart = 0;
        for (int index = 0; index < content.length(); index++) {
            char value = content.charAt(index);
            if (value != '\n' && value != '\r') {
                if (index - lineStart + 1 > maxLineChars) {
                    throw new IOException("AutoFix log line exceeds "
                            + maxLineChars + " characters");
                }
                continue;
            }
            addLogLine(lines, content.substring(lineStart, index), maxLines);
            if (value == '\r' && index + 1 < content.length()
                    && content.charAt(index + 1) == '\n') {
                index++;
            }
            lineStart = index + 1;
        }
        if (lineStart < content.length()) {
            addLogLine(lines, content.substring(lineStart), maxLines);
        }
        return lines;
    }

    private static void addLogLine(List<String> lines, String line, int maxLines)
            throws IOException {
        if (lines.size() >= maxLines) {
            throw new IOException("AutoFix log exceeds " + maxLines + " lines");
        }
        lines.add(line);
    }

    /**
     * Re-apply fixes saved from the previous launch. Called at startup before
     * transformation.
     *
     * @param transformer the transformer to apply saved fixes to
     * @return number of fixes loaded and applied
     */
    public int loadAndApplySavedFixes(RetromodTransformer transformer) {
        if (!Files.exists(FIXES_FILE)) {
            return 0;
        }

        try {
            List<PersistedFix> saved = readPersistedFixes();

            if (saved == null || saved.isEmpty()) {
                return 0;
            }

            int applied = 0;
            for (PersistedFix fix : saved) {
                if (fix.blacklisted) {
                    blacklist.add(fix.fixKey);
                    continue;
                }

                boolean success = applyPersistedFix(fix, transformer);
                if (success) {
                    appliedFixKeys.add(fix.fixKey);
                    previousRunFixKeys.add(fix.fixKey);
                    applied++;
                    LOGGER.info("[AutoFix] Re-applied saved fix: {}", fix.description);
                }
            }

            LOGGER.info("[AutoFix] Loaded {} saved fix(es) from previous run", applied);
            return applied;
        } catch (Exception e) {
            LOGGER.warn("[AutoFix] Could not load saved fixes: {}", e.getMessage());
            return 0;
        }
    }

    public List<AppliedFix> getAppliedFixes() {
        return Collections.unmodifiableList(appliedFixes);
    }

    public Set<String> getBlacklist() {
        return Collections.unmodifiableSet(blacklist);
    }

    private void registerAllPatterns() {

        // NoSuchMethodError: resolve a replacement via heuristics/fuzzy and register a redirect.
        //   "NoSuchMethodError: 'void com.example.Foo.oldMethod(int)'"
        patterns.add(new ErrorPattern(
            "NoSuchMethodError",
            Pattern.compile("NoSuchMethodError:.*'(\\S+)\\s+(\\S+)\\.(\\w+)\\(([^)]*)\\)'"),
            (matcher, line, context, transformer) -> {
                String returnType = matcher.group(1);
                String ownerDot = matcher.group(2);
                String methodName = matcher.group(3);
                String paramTypes = matcher.group(4);

                String owner = ownerDot.replace('.', '/');
                String desc = buildDescriptor(paramTypes, returnType);

                // Heuristics first: faster and more reliable than fuzzy.
                PatternHeuristics heuristics = transformer.getPatternHeuristics();
                if (heuristics != null) {
                    PatternHeuristics.PatternResult patternMatch = heuristics.resolveMethod(owner, methodName, desc);
                    if (patternMatch != null && patternMatch.confidence() >= 0.6) {
                        transformer.registerMethodRedirect(
                            owner, methodName, desc,
                            patternMatch.newOwner(), patternMatch.newName(), patternMatch.newDescriptor()
                        );
                        return new AppliedFix(
                            "NoSuchMethodError",
                            ownerDot + "." + methodName + "(" + paramTypes + ")",
                            "Pattern match -> " + patternMatch.newOwner().replace('/', '.') +
                                "." + patternMatch.newName() + " (rule: " + patternMatch.rule() +
                                ", confidence: " + patternMatch.confidence() + ")",
                            "method_redirect:" + owner + "." + methodName + desc
                        );
                    }
                }

                // Known bridge method (signature changed, not just renamed): BridgeAdapterGenerator
                // handles it at retransform time.
                if (BridgeAdapterGenerator.isKnownBridgeMethod(methodName)) {
                    String oldDesc = BridgeAdapterGenerator.getOldDescriptor(methodName);
                    String newDesc = BridgeAdapterGenerator.getNewDescriptor(methodName);
                    if (oldDesc != null && newDesc != null) {
                        return new AppliedFix(
                            "NoSuchMethodError",
                            ownerDot + "." + methodName + "(" + paramTypes + ")",
                            "Signature changed in 26.1: " + oldDesc + " -> " + newDesc +
                                ". BridgeAdapterGenerator will create a bridge at retransform time.",
                            "bridge_adapter:" + owner + "." + methodName + desc
                        );
                    }
                }

                FuzzyMethodResolver fuzzy = transformer.getFuzzyResolver();
                if (fuzzy != null) {
                    FuzzyMethodResolver.MethodInfo resolved = fuzzy.resolveMethod(owner, methodName, desc);
                    if (resolved != null) {
                        transformer.registerMethodRedirect(
                            owner, methodName, desc,
                            resolved.owner(), resolved.name(), resolved.descriptor()
                        );
                        return new AppliedFix(
                            "NoSuchMethodError",
                            ownerDot + "." + methodName + "(" + paramTypes + ")",
                            "Registered redirect -> " + resolved.owner().replace('/', '.') +
                                "." + resolved.name() + resolved.descriptor(),
                            "method_redirect:" + owner + "." + methodName + desc
                        );
                    }
                }
                // No pattern, bridge, or fuzzy match: report it, can't fix.
                return new AppliedFix(
                    "NoSuchMethodError",
                    ownerDot + "." + methodName + "(" + paramTypes + ")",
                    "No replacement found - pattern heuristics and fuzzy resolver could not match. Manual shim needed.",
                    "method_redirect:" + owner + "." + methodName + desc
                );
            }
        ));

        // NoSuchFieldError, two log shapes:
        //   "NoSuchFieldError: Class com.example.Foo does not have member field 'int bar'"
        //   "NoSuchFieldError: 'int com.example.Foo.bar'"
        patterns.add(new ErrorPattern(
            "NoSuchFieldError",
            Pattern.compile("NoSuchFieldError:.*?(?:Class (\\S+) does not have member field '(\\S+)\\s+(\\w+)'|'(\\S+)\\s+(\\S+)\\.(\\w+)')"),
            (matcher, line, context, transformer) -> {
                String ownerDot, fieldName, fieldType;

                if (matcher.group(1) != null) {
                    ownerDot = matcher.group(1);
                    fieldType = matcher.group(2);
                    fieldName = matcher.group(3);
                } else {
                    fieldType = matcher.group(4);
                    ownerDot = matcher.group(5);
                    fieldName = matcher.group(6);
                }

                String owner = ownerDot.replace('.', '/');
                String desc = typeNameToDescriptor(fieldType);

                // Heuristics first: faster and more reliable than fuzzy.
                PatternHeuristics heuristics = transformer.getPatternHeuristics();
                if (heuristics != null) {
                    PatternHeuristics.PatternResult patternMatch = heuristics.resolveField(owner, fieldName, desc);
                    if (patternMatch != null && patternMatch.confidence() >= 0.6) {
                        transformer.registerFieldRedirect(
                            owner, fieldName,
                            patternMatch.newOwner(), patternMatch.newName()
                        );
                        return new AppliedFix(
                            "NoSuchFieldError",
                            ownerDot + "." + fieldName,
                            "Pattern match -> " + patternMatch.newOwner().replace('/', '.') +
                                "." + patternMatch.newName() + " (rule: " + patternMatch.rule() + ")",
                            "field_redirect:" + owner + "." + fieldName
                        );
                    }
                }

                FuzzyMethodResolver fuzzy = transformer.getFuzzyResolver();
                if (fuzzy != null) {
                    FuzzyMethodResolver.FieldInfo resolved = fuzzy.resolveField(owner, fieldName, desc);
                    if (resolved != null) {
                        transformer.registerFieldRedirect(
                            owner, fieldName,
                            resolved.owner(), resolved.name()
                        );
                        return new AppliedFix(
                            "NoSuchFieldError",
                            ownerDot + "." + fieldName,
                            "Registered field redirect -> " + resolved.owner().replace('/', '.') +
                                "." + resolved.name(),
                            "field_redirect:" + owner + "." + fieldName
                        );
                    }
                }
                return new AppliedFix(
                    "NoSuchFieldError",
                    ownerDot + "." + fieldName,
                    "No replacement found - manual field redirect needed.",
                    "field_redirect:" + owner + "." + fieldName
                );
            }
        ));

        // AbstractMethodError / missing implementation. Report only; injecting a no-op stub
        // needs ASM at retransform time.
        //   "Missing implementation of resolved method 'abstract void net.minecraft.X.extractContents(...)' in class com.example.MyBlock"
        patterns.add(new ErrorPattern(
            "AbstractMethodError",
            Pattern.compile("(?:AbstractMethodError|Missing implementation of resolved method).*?'(?:abstract )?(?:(\\S+)\\s+)?(\\S+)\\.(\\w+)\\(([^)]*)\\)'"),
            (matcher, line, context, transformer) -> {
                String returnType = matcher.group(1) != null ? matcher.group(1) : "void";
                String ownerDot = matcher.group(2);
                String methodName = matcher.group(3);
                String paramTypes = matcher.group(4);

                return new AppliedFix(
                    "AbstractMethodError",
                    ownerDot + "." + methodName + "(" + paramTypes + ")",
                    "Missing abstract method implementation. A no-op stub should be injected " +
                        "via ASM during retransformation. Flag mod class for method injection.",
                    "abstract_method:" + ownerDot.replace('.', '/') + "." + methodName
                );
            }
        ));

        // VerifyError (bad type on operand stack).
        //   "Type 'net/minecraft/world/level/Level' is not assignable to 'net/minecraft/server/level/ServerLevel'"
        patterns.add(new ErrorPattern(
            "VerifyError",
            Pattern.compile("VerifyError.*(?:Bad type on operand stack|Bad return type)"),
            (matcher, line, context, transformer) -> {
                Matcher typeMatcher = VERIFY_TYPE_PAT.matcher(context);

                String actualType = "unknown";
                String expectedType = "unknown";
                if (typeMatcher.find()) {
                    actualType = typeMatcher.group(1);
                    expectedType = typeMatcher.group(2);
                }

                return new AppliedFix(
                    "VerifyError",
                    "Type mismatch: " + actualType + " vs " + expectedType,
                    "Bytecode verification failed. If caused by a fuzzy match, the match " +
                        "should be blacklisted. If caused by a field/method redirect, it should " +
                        "be reverted. Check the full stack trace for the offending redirect.",
                    "verify_error:" + actualType + ":" + expectedType
                );
            }
        ));

        // IncompatibleClassChangeError (a class became an interface). The fix is adding the
        // class to KNOWN_INTERFACES so INVOKEVIRTUAL becomes INVOKEINTERFACE; that set is
        // static final, so we only report it here.
        //   "Method 'X' must be InterfaceMethodref constant" / "interface was expected"
        patterns.add(new ErrorPattern(
            "IncompatibleClassChangeError",
            Pattern.compile("IncompatibleClassChangeError.*?(?:must be InterfaceMethodref|interface was expected|Expecting non-static method).*?(?:'(\\S+)'|(\\S+))"),
            (matcher, line, context, transformer) -> {
                String rawRef = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);

                // rawRef may be a full method ref (Registry.register); keep just the class.
                String ownerDot = rawRef;
                int lastDot = rawRef.lastIndexOf('.');
                if (lastDot > 0) {
                    String suffix = rawRef.substring(lastDot + 1);
                    if (suffix.length() > 0 && Character.isLowerCase(suffix.charAt(0))) {
                        ownerDot = rawRef.substring(0, lastDot);
                    }
                }

                String owner = ownerDot.replace('.', '/');

                return new AppliedFix(
                    "IncompatibleClassChangeError",
                    ownerDot + " needs InterfaceMethodref",
                    "Class '" + ownerDot + "' became an interface in newer MC. " +
                        "Add '" + owner + "' to KNOWN_INTERFACES in RetromodTransformer. " +
                        "Alternatively, the transformer's INVOKEVIRTUAL->INVOKEINTERFACE " +
                        "rewrite should handle this at retransform time.",
                    "interface_change:" + owner
                );
            }
        ));

        // Mixin transformation failed. Strip the failing mixin from the mod's mixin config.
        //   "Mixin transformation of net.minecraft.client.gui.screens.TitleScreen failed"
        //   "Mixin apply failed ... in mixins.modid.json"
        patterns.add(new ErrorPattern(
            "MixinTransformFailed",
            Pattern.compile("(?:Mixin (?:transformation|apply) (?:of|failed).*?((?:net\\.minecraft|com\\.mojang)\\S+)|Mixin apply.*?failed.*?(\\S+\\.json))"),
            (matcher, line, context, transformer) -> {
                String targetClass = matcher.group(1);
                String mixinConfig = matcher.group(2);

                String description = targetClass != null
                    ? "Mixin targeting " + targetClass + " failed"
                    : "Mixin config " + mixinConfig + " has failures";

                return new AppliedFix(
                    "MixinTransformFailed",
                    description,
                    "The mixin failed to apply, likely due to changed MC internals. " +
                        "Strip the failing mixin from the mod's mixin config JSON during " +
                        "retransformation. The mod may lose some features but won't crash.",
                    "mixin_strip:" + (targetClass != null ? targetClass : mixinConfig)
                );
            }
        ));

        // InvalidMixinException (super class not found). Remove the mixin class entry from
        // the mixin config.
        //   "Super class 'net.minecraft.X' of com.example.mixin.MyMixin was not found ..."
        patterns.add(new ErrorPattern(
            "InvalidMixinSuperClass",
            Pattern.compile("Super class '(\\S+)' of (\\S+) was not found"),
            (matcher, line, context, transformer) -> {
                String superClass = matcher.group(1);
                String mixinClass = matcher.group(2);

                return new AppliedFix(
                    "InvalidMixinSuperClass",
                    "Mixin " + mixinClass + " has missing super class " + superClass,
                    "Remove mixin class '" + mixinClass + "' from the mod's mixin config JSON. " +
                        "The super class was renamed or removed in this MC version.",
                    "mixin_remove:" + mixinClass
                );
            }
        ));

        // InvalidAccessorException (@Invoker/@Accessor not found).
        //   "No candidates were found matching methodName(desc) in net.minecraft.X for com.example.mixin.MyMixin"
        patterns.add(new ErrorPattern(
            "InvalidAccessorException",
            Pattern.compile("No candidates were found matching (\\w+)\\(([^)]*)\\).*?(?:in (\\S+) for (\\S+)|(\\S+))"),
            (matcher, line, context, transformer) -> {
                String methodName = matcher.group(1);
                String params = matcher.group(2);
                String targetClass = matcher.group(3) != null ? matcher.group(3) : "unknown";
                String mixinClass = matcher.group(4) != null ? matcher.group(4) :
                    (matcher.group(5) != null ? matcher.group(5) : "unknown");

                return new AppliedFix(
                    "InvalidAccessorException",
                    "Accessor " + methodName + "(" + params + ") not found in " + targetClass,
                    "Mixin @Invoker/@Accessor target was renamed or removed. " +
                        "If the accessor is required=true, strip the mixin. " +
                        "If required=false (default for accessors), this is just a warning.",
                    "accessor_missing:" + targetClass + "." + methodName
                );
            }
        ));

        // InvalidInjectionException (descriptor mismatch). Strip the @Inject method, or the
        // whole mixin.
        //   "Invalid descriptor on ... Expected (Lnet/minecraft/X;)V but found (Lnet/minecraft/Y;)V"
        patterns.add(new ErrorPattern(
            "InvalidInjectionException",
            Pattern.compile("Invalid (?:descriptor|injection).*?Expected \\(([^)]*)\\).*?(?:but )?found \\(([^)]*)\\)"),
            (matcher, line, context, transformer) -> {
                String expected = matcher.group(1);
                String actual = matcher.group(2);

                String mixinClass = "unknown";
                Matcher mixinMatcher = INJECTION_MIXIN_PAT.matcher(context);
                if (mixinMatcher.find()) {
                    mixinClass = mixinMatcher.group(1);
                }

                return new AppliedFix(
                    "InvalidInjectionException",
                    "Descriptor mismatch in " + mixinClass + ": expected (" + expected + ") vs found (" + actual + ")",
                    "Strip the @Inject method with the mismatched descriptor from the mixin, " +
                        "or strip the entire mixin if it has no other useful injections.",
                    "injection_mismatch:" + mixinClass + ":" + expected
                );
            }
        ));

        // ClassNotFoundException / NoClassDefFoundError. Report as a missing dependency;
        // can't fix without the library.
        //   "NoClassDefFoundError: com/teamresourceful/resourcefulconfig/api/ConfigEntry"
        patterns.add(new ErrorPattern(
            "ClassNotFound",
            Pattern.compile("(?:NoClassDefFoundError|ClassNotFoundException):\\s*'?(\\S+?)(?:'|$)"),
            (matcher, line, context, transformer) -> {
                String missingClass = matcher.group(1).replace('/', '.');

                boolean isMcClass = missingClass.startsWith("net.minecraft.") ||
                                    missingClass.startsWith("com.mojang.");
                boolean isFabricApi = missingClass.startsWith("net.fabricmc.fabric.api.");

                String action;
                if (isMcClass) {
                    action = "MC class was removed or relocated. Check if a class redirect " +
                        "or polyfill exists for this class. May need a new shim.";
                } else if (isFabricApi) {
                    action = "Fabric API class missing. The mod may need a newer Fabric API, " +
                        "or the API module was removed. Check polyfill providers.";
                } else {
                    action = "Missing mod dependency. User must install the required library " +
                        "mod. Retromod cannot fix missing third-party dependencies.";
                }

                return new AppliedFix(
                    "ClassNotFound",
                    "Missing class: " + missingClass,
                    action,
                    "class_missing:" + missingClass
                );
            }
        ));

        // IllegalAccessError (member became private/protected). Flag for a reflection bridge
        // using setAccessible(true).
        //   "IllegalAccessError: tried to access private field net.minecraft.X.Y from class Z"
        patterns.add(new ErrorPattern(
            "IllegalAccessError",
            Pattern.compile("IllegalAccessError:.*?(?:tried to access (?:private|protected) (?:field|method)).*?((?:net\\.minecraft|com\\.mojang)\\S+)\\.(\\w+)"),
            (matcher, line, context, transformer) -> {
                String ownerDot = matcher.group(1);
                String memberName = matcher.group(2);

                return new AppliedFix(
                    "IllegalAccessError",
                    "Cannot access " + ownerDot + "." + memberName,
                    "Field/method became private in newer MC. Generate a reflection bridge " +
                        "that uses setAccessible(true) to access the member. Alternatively, " +
                        "use an AccessWidener or AccessTransformer.",
                    "access_error:" + ownerDot.replace('.', '/') + "." + memberName
                );
            }
        ));

        // ClassCastException.
        //   "class net.minecraft.world.level.Level cannot be cast to class net.minecraft.server.level.ServerLevel"
        patterns.add(new ErrorPattern(
            "ClassCastException",
            Pattern.compile("ClassCastException:.*?(?:class )?(\\S+) cannot be cast to (?:class )?(\\S+)"),
            (matcher, line, context, transformer) -> {
                String sourceType = matcher.group(1).replace('.', '/');
                String targetType = matcher.group(2).replace('.', '/');

                return new AppliedFix(
                    "ClassCastException",
                    sourceType.replace('/', '.') + " cannot be cast to " + targetType.replace('/', '.'),
                    "Type hierarchy changed in newer MC. If this is from a constructor redirect " +
                        "CHECKCAST, fix the cast type. If from a method redirect, the return type " +
                        "changed and the redirect needs updating.",
                    "cast_error:" + sourceType + ":" + targetType
                );
            }
        ));

        // NegativeArraySizeException during ASM frame computation. Strip the mixin whose
        // frame computation fails.
        //   "NegativeArraySizeException ... computeAllFrames"
        patterns.add(new ErrorPattern(
            "NegativeArraySizeException",
            Pattern.compile("NegativeArraySizeException"),
            (matcher, line, context, transformer) -> {
                String targetClass = "unknown";
                Matcher classMatcher = NEG_ARRAY_CLASS_PAT.matcher(context);
                if (classMatcher.find()) {
                    targetClass = classMatcher.group(1);
                }

                return new AppliedFix(
                    "NegativeArraySizeException",
                    "ASM frame computation failed" + (targetClass.equals("unknown") ? "" : " for " + targetClass),
                    "ASM's computeAllFrames() crashed, likely because a mixin references " +
                        "classes that don't exist in this MC version. Strip the mixin that " +
                        "targets this class, or use COMPUTE_MAXS instead of COMPUTE_FRAMES.",
                    "asm_frames:" + targetClass
                );
            }
        ));

        // Entrypoint failure. Flag the entrypoint for try-catch wrapping.
        //   "Could not execute entrypoint stage 'client' due to errors, provided by 'modid'"
        patterns.add(new ErrorPattern(
            "EntryPointFailure",
            Pattern.compile("Could not execute entrypoint stage '(\\w+)'.*?provided by '(\\S+)'"),
            (matcher, line, context, transformer) -> {
                String stage = matcher.group(1);
                String modId = matcher.group(2);

                return new AppliedFix(
                    "EntryPointFailure",
                    "Entrypoint '" + stage + "' failed for mod '" + modId + "'",
                    "The mod's entrypoint crashed during initialization. The entrypoint " +
                        "class should be wrapped in try-catch during retransformation. " +
                        "Check the log for the root cause (often a follow-on error).",
                    "entrypoint_fail:" + modId + ":" + stage
                );
            }
        ));

        // SoundEvent(Identifier) constructor removed: redirect to createVariableRangeEvent.
        patterns.add(new ErrorPattern(
            "SoundEventConstructor",
            Pattern.compile("NoSuchMethodError.*SoundEvent\\.<init>\\(.*(?:Identifier|ResourceLocation)"),
            (matcher, line, context, transformer) -> {
                transformer.registerConstructorRedirect(
                    "net/minecraft/sounds/SoundEvent",
                    "(Lnet/minecraft/resources/Identifier;)V",
                    "net/minecraft/sounds/SoundEvent",
                    "createVariableRangeEvent",
                    "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/sounds/SoundEvent;"
                );

                return new AppliedFix(
                    "SoundEventConstructor",
                    "SoundEvent constructor removed",
                    "Registered constructor redirect: new SoundEvent(Identifier) -> " +
                        "SoundEvent.createVariableRangeEvent(Identifier)",
                    "ctor_redirect:SoundEvent"
                );
            }
        ));

        // ResourceLocation/Identifier constructor removed: redirect to fromNamespaceAndPath()
        // (two args) or parse() (one arg).
        //   "...ResourceLocation.<init>(java.lang.String, java.lang.String)" / "...Identifier.<init>(String)"
        patterns.add(new ErrorPattern(
            "ResourceLocationConstructor",
            Pattern.compile("NoSuchMethodError.*(?:ResourceLocation|Identifier)\\.<init>\\(.*String"),
            (matcher, line, context, transformer) -> {
                boolean twoArgs = line.contains("String, java.lang.String") ||
                                  line.contains("String,String") ||
                                  line.contains("String, String");

                if (twoArgs) {
                    transformer.registerConstructorRedirect(
                        "net/minecraft/resources/Identifier",
                        "(Ljava/lang/String;Ljava/lang/String;)V",
                        "net/minecraft/resources/Identifier",
                        "fromNamespaceAndPath",
                        "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/Identifier;"
                    );
                    // Old ResourceLocation name too (pre-26.1 mods).
                    transformer.registerConstructorRedirect(
                        "net/minecraft/resources/ResourceLocation",
                        "(Ljava/lang/String;Ljava/lang/String;)V",
                        "net/minecraft/resources/Identifier",
                        "fromNamespaceAndPath",
                        "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/Identifier;"
                    );
                    return new AppliedFix(
                        "ResourceLocationConstructor",
                        "ResourceLocation(String, String) constructor removed",
                        "Registered constructor redirect -> Identifier.fromNamespaceAndPath(String, String)",
                        "ctor_redirect:ResourceLocation:2arg"
                    );
                } else {
                    transformer.registerConstructorRedirect(
                        "net/minecraft/resources/Identifier",
                        "(Ljava/lang/String;)V",
                        "net/minecraft/resources/Identifier",
                        "parse",
                        "(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;"
                    );
                    transformer.registerConstructorRedirect(
                        "net/minecraft/resources/ResourceLocation",
                        "(Ljava/lang/String;)V",
                        "net/minecraft/resources/Identifier",
                        "parse",
                        "(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;"
                    );
                    return new AppliedFix(
                        "ResourceLocationConstructor",
                        "ResourceLocation(String) constructor removed",
                        "Registered constructor redirect -> Identifier.parse(String)",
                        "ctor_redirect:ResourceLocation:1arg"
                    );
                }
            }
        ));

        // Registry.register InterfaceMethodref.
        //   "IncompatibleClassChangeError: ... must be InterfaceMethodref ... Registry.register"
        patterns.add(new ErrorPattern(
            "RegistryInterfaceMethodref",
            Pattern.compile("(?:IncompatibleClassChangeError|InterfaceMethodref).*Registry"),
            (matcher, line, context, transformer) -> {
                return new AppliedFix(
                    "RegistryInterfaceMethodref",
                    "Registry.register must use InterfaceMethodref",
                    "Registry became an interface in newer MC. Ensure " +
                        "'net/minecraft/core/Registry' is in KNOWN_INTERFACES. " +
                        "The INVOKEVIRTUAL -> INVOKEINTERFACE rewrite handles this.",
                    "interface_change:net/minecraft/core/Registry"
                );
            }
        ));

        // Access-widener namespace mismatch. Flag the mod's access widener for re-remapping.
        //   "Namespace (intermediary) does not match current runtime namespace (official)"
        patterns.add(new ErrorPattern(
            "NamespaceMismatch",
            Pattern.compile("Namespace \\((\\w+)\\) does not match (?:current )?runtime namespace \\((\\w+)\\)"),
            (matcher, line, context, transformer) -> {
                String modNamespace = matcher.group(1);
                String runtimeNamespace = matcher.group(2);

                String modName = "unknown";
                Matcher modMatcher = NAMESPACE_MOD_PAT.matcher(context);
                if (modMatcher.find()) {
                    modName = modMatcher.group(1);
                }

                return new AppliedFix(
                    "NamespaceMismatch",
                    "Access widener uses " + modNamespace + " but runtime expects " + runtimeNamespace,
                    "The mod's access widener or class tweaker uses intermediary names but " +
                        "MC 26.1 uses official names. Re-remap the access widener file during " +
                        "retransformation using IntermediaryToMojangMapper.",
                    "namespace_mismatch:" + modName
                );
            }
        ));

        // Missing mod dependency. Report only; the user must install it.
        //   "Mod 'sodium' is a required dependency of 'iris' but it is not installed"
        patterns.add(new ErrorPattern(
            "MissingModDependency",
            Pattern.compile("(?:'(\\S+)' is a (?:required )?dependency of '(\\S+)'.*not installed|Unmet dependency.*?(\\S+) requires (\\S+))"),
            (matcher, line, context, transformer) -> {
                String missingMod, requiringMod;
                if (matcher.group(1) != null) {
                    missingMod = matcher.group(1);
                    requiringMod = matcher.group(2);
                } else {
                    requiringMod = matcher.group(3);
                    missingMod = matcher.group(4);
                }

                return new AppliedFix(
                    "MissingModDependency",
                    "Mod '" + requiringMod + "' requires '" + missingMod + "' (not installed)",
                    "The user must install the '" + missingMod + "' mod. Retromod cannot " +
                        "fix missing third-party mod dependencies. Check Modrinth or CurseForge " +
                        "for a compatible version.",
                    "dep_missing:" + missingMod + ":" + requiringMod
                );
            }
        ));

        // Util.backgroundExecutor() removed: redirect to Util.nonCriticalIoPool(), the
        // closest equivalent in newer MC.
        patterns.add(new ErrorPattern(
            "UtilBackgroundExecutor",
            Pattern.compile("NoSuchMethodError.*Util\\.backgroundExecutor"),
            (matcher, line, context, transformer) -> {
                transformer.registerMethodRedirect(
                    "net/minecraft/Util", "backgroundExecutor",
                    "()Ljava/util/concurrent/ExecutorService;",
                    "net/minecraft/Util", "nonCriticalIoPool",
                    "()Ljava/util/concurrent/ExecutorService;"
                );

                return new AppliedFix(
                    "UtilBackgroundExecutor",
                    "Util.backgroundExecutor() removed",
                    "Registered method redirect: Util.backgroundExecutor() -> Util.nonCriticalIoPool(). " +
                        "The nonCriticalIoPool() is the closest equivalent in newer MC.",
                    "method_redirect:Util.backgroundExecutor"
                );
            }
        ));
    }

    private void persistFixes() {
        try {
            Path configDir = FIXES_FILE.getParent();
            if (configDir != null) {
                Files.createDirectories(configDir);
                // Reject a symlinked config dir so a pre-planted target can't be overwritten.
                com.retromod.util.ZipSecurity.validateNotSymlink(configDir);
            }
            if (Files.exists(FIXES_FILE)) {
                com.retromod.util.ZipSecurity.validateNotSymlink(FIXES_FILE);
            }

            List<PersistedFix> toSave = new ArrayList<>();

            for (AppliedFix fix : appliedFixes) {
                toSave.add(new PersistedFix(
                    fix.fixKey, fix.errorType, fix.description, fix.action, false
                ));
            }

            for (String key : blacklist) {
                toSave.add(new PersistedFix(key, "blacklisted", key, "blacklisted", true));
            }

            String json = GSON.toJson(toSave);
            Files.writeString(FIXES_FILE, json);
            LOGGER.info("[AutoFix] Persisted {} fix(es) to {}", toSave.size(), FIXES_FILE);
        } catch (IOException e) {
            LOGGER.warn("[AutoFix] Could not persist fixes: {}", e.getMessage());
        }
    }

    private void loadPreviousFixes() {
        if (!Files.exists(FIXES_FILE)) return;

        try {
            List<PersistedFix> saved = readPersistedFixes();

            if (saved != null) {
                for (PersistedFix fix : saved) {
                    if (fix.blacklisted) {
                        blacklist.add(fix.fixKey);
                    } else {
                        previousRunFixKeys.add(fix.fixKey);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("[AutoFix] Could not load previous fixes: {}", e.getMessage());
        }
    }

    private static List<PersistedFix> readPersistedFixes() throws IOException {
        String json = JsonSecurity.readUtf8(FIXES_FILE, MAX_FIXES_FILE_BYTES,
                JsonSecurity.DEFAULT_MAX_DEPTH, "Saved automatic fixes");
        Type listType = new TypeToken<List<PersistedFix>>() {}.getType();
        return GSON.fromJson(json, listType);
    }

    /** Re-applies the fix types that register redirects; others are informational. */
    private boolean applyPersistedFix(PersistedFix fix, RetromodTransformer transformer) {
        if (fix.fixKey == null) return false;

        if (fix.fixKey.startsWith("ctor_redirect:SoundEvent")) {
            transformer.registerConstructorRedirect(
                "net/minecraft/sounds/SoundEvent",
                "(Lnet/minecraft/resources/Identifier;)V",
                "net/minecraft/sounds/SoundEvent",
                "createVariableRangeEvent",
                "(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/sounds/SoundEvent;"
            );
            return true;
        }

        if (fix.fixKey.equals("ctor_redirect:ResourceLocation:1arg")) {
            transformer.registerConstructorRedirect(
                "net/minecraft/resources/Identifier",
                "(Ljava/lang/String;)V",
                "net/minecraft/resources/Identifier",
                "parse",
                "(Ljava/lang/String;)Lnet/minecraft/resources/Identifier;"
            );
            return true;
        }

        if (fix.fixKey.equals("ctor_redirect:ResourceLocation:2arg")) {
            transformer.registerConstructorRedirect(
                "net/minecraft/resources/Identifier",
                "(Ljava/lang/String;Ljava/lang/String;)V",
                "net/minecraft/resources/Identifier",
                "fromNamespaceAndPath",
                "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/Identifier;"
            );
            return true;
        }

        if (fix.fixKey.equals("method_redirect:Util.backgroundExecutor")) {
            transformer.registerMethodRedirect(
                "net/minecraft/Util", "backgroundExecutor",
                "()Ljava/util/concurrent/ExecutorService;",
                "net/minecraft/Util", "nonCriticalIoPool",
                "()Ljava/util/concurrent/ExecutorService;"
            );
            return true;
        }

        // Informational fixes (ClassNotFound, MissingModDependency, ...) register no redirect.
        LOGGER.debug("[AutoFix] Persisted fix '{}' is informational only, not re-applied", fix.fixKey);
        return false;
    }

    /** The current line plus up to {@code lookAhead} following lines, for multi-line errors. */
    private String buildContext(List<String> lines, int currentIndex, int lookAhead) {
        StringBuilder sb = new StringBuilder();
        int end = Math.min(currentIndex + lookAhead + 1, lines.size());
        for (int i = currentIndex; i < end; i++) {
            sb.append(lines.get(i)).append('\n');
        }
        return sb.toString();
    }

    /**
     * Build a JVM method descriptor from human-readable type names, e.g.
     * ("int, java.lang.String", "void") -> "(ILjava/lang/String;)V".
     */
    static String buildDescriptor(String paramTypes, String returnType) {
        StringBuilder desc = new StringBuilder("(");

        if (paramTypes != null && !paramTypes.trim().isEmpty()) {
            String[] params = paramTypes.split(",");
            for (String param : params) {
                desc.append(typeNameToDescriptor(param.trim()));
            }
        }

        desc.append(")");
        desc.append(typeNameToDescriptor(returnType.trim()));
        return desc.toString();
    }

    /** Single Java type name to its JVM descriptor (primitives, arrays, reference types). */
    static String typeNameToDescriptor(String typeName) {
        if (typeName == null || typeName.isEmpty()) return "V";

        int arrayDepth = 0;
        String base = typeName;
        while (base.endsWith("[]")) {
            arrayDepth++;
            base = base.substring(0, base.length() - 2);
        }

        String baseDesc = switch (base) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> "L" + base.replace('.', '/') + ";";
        };

        if (arrayDepth > 0) {
            return "[".repeat(arrayDepth) + baseDesc;
        }
        return baseDesc;
    }

    /**
     * A fix applied (or suggested) by the engine.
     *
     * @param errorType   error category, e.g. "NoSuchMethodError"
     * @param description what the error was
     * @param action      what was done (or should be done) about it
     * @param fixKey      dedup/blacklist key
     */
    public record AppliedFix(String errorType, String description, String action, String fixKey) {
        @Override
        public String toString() {
            return "[" + errorType + "] " + description + " => " + action;
        }
    }

    /** Persisted fix in auto-fixes.json. A class, not a record, for Gson. */
    static class PersistedFix {
        String fixKey;
        String errorType;
        String description;
        String action;
        boolean blacklisted;

        PersistedFix() {} // for Gson

        PersistedFix(String fixKey, String errorType, String description,
                     String action, boolean blacklisted) {
            this.fixKey = fixKey;
            this.errorType = errorType;
            this.description = description;
            this.action = action;
            this.blacklisted = blacklisted;
        }
    }

    /** A named regex paired with the fix action to run on a match. */
    static class ErrorPattern {
        final String name;
        final Pattern regex;
        final FixAction fixAction;

        ErrorPattern(String name, Pattern regex, FixAction fixAction) {
            this.name = name;
            this.regex = regex;
            this.fixAction = fixAction;
        }

        /** Dedup/blacklist key: pattern name plus the matched text's hash. */
        String buildFixKey(Matcher matcher, String line) {
            return name + ":" + matcher.group(0).hashCode();
        }

        /** Run the fix action, returning the applied fix or null. */
        AppliedFix apply(Matcher matcher, String line, String context,
                         RetromodTransformer transformer) {
            try {
                AppliedFix fix = fixAction.apply(matcher, line, context, transformer);
                if (fix != null) {
                    return fix;
                }
            } catch (Exception e) {
                LOGGER.warn("[AutoFix] Error applying fix for pattern '{}': {}", name, e.getMessage());
            }
            return null;
        }

        /** Describe the fix without applying it (CLI dry-run). */
        AppliedFix describe(Matcher matcher, String line, String context) {
            try {
                // Null transformer: redirect-registering patterns still build an AppliedFix
                // (registering nothing); transformer-dependent ones throw and fall through.
                return fixAction.apply(matcher, line, context, null);
            } catch (NullPointerException e) {
                return new AppliedFix(name, line.trim(), "Fix requires transformer (would apply at runtime)", name);
            } catch (Exception e) {
                return new AppliedFix(name, line.trim(), "Analysis error: " + e.getMessage(), name);
            }
        }
    }

    @FunctionalInterface
    interface FixAction {
        /**
         * Parse the match and apply a fix.
         *
         * @param transformer the transformer (null in dry-run mode)
         * @return the fix, or null if none could be applied
         */
        AppliedFix apply(Matcher matcher, String line, String context,
                         RetromodTransformer transformer);
    }
}
