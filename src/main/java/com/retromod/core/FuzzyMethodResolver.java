/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 *
 * Fuzzy method/field resolver: last-resort fallback for unresolved references.
 * Indexes the target MC JAR and scores candidates to find probable matches.
 */
package com.retromod.core;

import org.objectweb.asm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Last-resort fuzzy resolver for method/field references that no shim redirect covers.
 * Scans the target MC JAR and scores candidates by class, name, and parameter similarity.
 * Fires only after every explicit lookup has failed, and never overrides a hardcoded redirect.
 *
 * <p>Must not reference {@code Retromod} (which implements ModInitializer): this also runs
 * in the standalone CLI where Fabric classes aren't on the classpath.</p>
 */
public class FuzzyMethodResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Fuzzy");

    /** Points awarded when the candidate method is on the exact same class. */
    private static final int SCORE_EXACT_CLASS = 40;
    /** Points awarded when the candidate method is on a superclass/interface. */
    private static final int SCORE_RELATED_CLASS = 25;

    /** Points for exact method name match. */
    private static final int SCORE_EXACT_NAME = 30;
    /** Points when Levenshtein distance is <= 3. */
    private static final int SCORE_LEVENSHTEIN_CLOSE = 20;
    /** Points when old name is a substring of the candidate (or vice versa). */
    private static final int SCORE_SUBSTRING_MATCH = 15;
    /** Points for common prefix or suffix (at least 4 chars). */
    private static final int SCORE_PREFIX_SUFFIX = 5;

    /** Points when parameter count matches exactly. */
    private static final int SCORE_PARAM_COUNT_EXACT = 15;
    /** Points when parameter count is off by exactly 1. */
    private static final int SCORE_PARAM_COUNT_OFF_BY_ONE = 5;

    /** Points when ALL parameter types match exactly. */
    private static final int SCORE_ALL_PARAMS_MATCH = 15;
    /** Points when >50% of parameter types match. */
    private static final int SCORE_MOST_PARAMS_MATCH = 8;

    /** Minimum score to auto-apply the redirect. Below 85, mismatched return types (MutableComponent vs GuiMessageTag) slipped through and caused VerifyErrors. */
    private static final int THRESHOLD_AUTO_APPLY = 85;
    /** Minimum score to log a warning (but not apply). */
    private static final int THRESHOLD_LOG_WARNING = 50;

    /** Key: JVM internal class name. Value: methods declared on it. */
    private final Map<String, List<MethodInfo>> methodIndex = new HashMap<>();

    /**
     * Key: JVM internal class name. Value: constructor descriptors declared on it.
     * Constructors are retained for exact verification but never enter fuzzy candidate lists.
     */
    private final Map<String, Set<String>> constructorIndex = new HashMap<>();

    /** Key: JVM internal class name. Value: fields declared on it. */
    private final Map<String, List<FieldInfo>> fieldIndex = new HashMap<>();

    /** Key: JVM internal class name. Value: its superclass plus implemented interfaces. */
    private final Map<String, List<String>> classHierarchy = new HashMap<>();

    /** Platform ancestors loaded lazily for exact inherited-member checks only. */
    private final Map<String, ExternalClassInfo> externalClassIndex = new ConcurrentHashMap<>();

    /** Negative cache for platform classes that could not be inspected. */
    private final Set<String> missingExternalClasses = ConcurrentHashMap.newKeySet();

    /** Cap on resolve-cache entries; once hit, the cache is cleared. A mod has ~50-200 unresolved refs. */
    private static final int MAX_CACHE_SIZE = 10_000;

    /** Key: "owner.name.desc". Value: the resolved match, or EMPTY_METHOD_INFO for "no match". */
    private final Map<String, MethodInfo> methodResolveCache = new ConcurrentHashMap<>();

    /** Key: "owner.name.desc". Value: the resolved match, or EMPTY_FIELD_INFO for "no match". */
    private final Map<String, FieldInfo> fieldResolveCache = new ConcurrentHashMap<>();

    /** Cached "looked and found nothing" so we don't re-scan. */
    private static final MethodInfo EMPTY_METHOD_INFO = new MethodInfo("", "", "", -1);
    private static final FieldInfo EMPTY_FIELD_INFO = new FieldInfo("", "", "", -1);

    /** Clear-on-overflow eviction (ConcurrentHashMap has no LRU). */
    private <V> void boundedCachePut(Map<String, V> cache, String key, V value) {
        if (cache.size() >= MAX_CACHE_SIZE) {
            LOGGER.debug("Resolve cache reached {} entries, clearing to prevent unbounded growth",
                    MAX_CACHE_SIZE);
            cache.clear();
        }
        cache.put(key, value);
    }

    /** Volatile for cross-thread visibility. */
    private volatile boolean indexed = false;

    private volatile int indexedClassCount = 0;
    private volatile int indexedMethodCount = 0;
    private volatile int indexedFieldCount = 0;

    /**
     * A method in the target MC JAR. {@code score} is -1 in the index, set when returned as a fuzzy match.
     *
     * @param descriptor JVM method descriptor, e.g. "(IIF)V"
     */
    public record MethodInfo(String owner, String name, String descriptor, int score, int access) {
        /** Keeps the original four-argument API for callers that only need a scored signature. */
        public MethodInfo(String owner, String name, String descriptor, int score) {
            this(owner, name, descriptor, score, -1);
        }
    }

    /**
     * A field in the target MC JAR. {@code score} is -1 in the index, set when returned as a fuzzy match.
     *
     * @param descriptor JVM field descriptor, e.g. "Ljava/lang/String;"
     */
    public record FieldInfo(String owner, String name, String descriptor, int score) {}

    private record ExternalClassInfo(
            List<MethodInfo> methods, List<FieldInfo> fields, List<String> parents) {}

    /**
     * Index every {@code net/minecraft/} and {@code com/mojang/} class, method, and field in the JAR.
     * Call once at startup before any resolve call.
     *
     * @throws IOException if the JAR cannot be read
     */
    public void indexJar(Path mcJar) throws IOException {
        if (indexed) {
            LOGGER.debug("JAR already indexed, skipping");
            return;
        }

        if (mcJar == null || !Files.exists(mcJar)) {
            LOGGER.warn("MC JAR not found at {}, fuzzy resolver disabled", mcJar);
            return;
        }

        LOGGER.info("Indexing MC JAR for fuzzy resolution: {}", mcJar.getFileName());
        long startTime = System.currentTimeMillis();

        int classes = 0;
        int methods = 0;
        int fields = 0;

        try (JarFile jar = new JarFile(mcJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();

                // net/minecraft plus com/mojang (DFU, brigadier)
                if (!entryName.endsWith(".class")) continue;
                if (!entryName.startsWith("net/minecraft/") &&
                    !entryName.startsWith("com/mojang/")) {
                    continue;
                }

                try (InputStream is = jar.getInputStream(entry)) {
                    byte[] classBytes = is.readAllBytes();
                    ClassReader reader = new ClassReader(classBytes);

                    String className = reader.getClassName();
                    String superName = reader.getSuperName();
                    String[] interfaces = reader.getInterfaces();

                    List<String> parents = new ArrayList<>();
                    if (superName != null) {
                        parents.add(superName);
                    }
                    if (interfaces != null) {
                        Collections.addAll(parents, interfaces);
                    }
                    classHierarchy.put(className, parents);

                    // signatures only: SKIP_CODE + SKIP_DEBUG for speed
                    List<MethodInfo> classMethods = new ArrayList<>();
                    List<FieldInfo> classFields = new ArrayList<>();

                    reader.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name,
                                String descriptor, String signature, String[] exceptions) {
                            // Constructors are exact-linkage data, not fuzzy-match candidates.
                            if ("<init>".equals(name)) {
                                constructorIndex.computeIfAbsent(className, ignored -> new HashSet<>())
                                        .add(descriptor);
                            } else if (!"<clinit>".equals(name)) {
                                classMethods.add(new MethodInfo(className, name, descriptor, -1, access));
                            }
                            return null;
                        }

                        @Override
                        public FieldVisitor visitField(int access, String name,
                                String descriptor, String signature, Object value) {
                            classFields.add(new FieldInfo(className, name, descriptor, -1));
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                    if (!classMethods.isEmpty()) {
                        methodIndex.put(className, classMethods);
                        methods += classMethods.size();
                    }
                    if (!classFields.isEmpty()) {
                        fieldIndex.put(className, classFields);
                        fields += classFields.size();
                    }
                    classes++;

                } catch (Exception e) {
                    LOGGER.debug("Could not index class {}: {}", entryName, e.getMessage());
                }
            }
        }

        indexedClassCount = classes;
        indexedMethodCount = methods;
        indexedFieldCount = fields;
        indexed = true;

        long elapsed = System.currentTimeMillis() - startTime;
        LOGGER.info("Indexed {} classes, {} methods, {} fields from MC JAR in {}ms",
                classes, methods, fields, elapsed);
    }

    public boolean isIndexed() {
        return indexed;
    }

    // Exact "does this class/member exist?" checks, unlike the fuzzy resolve* methods below.
    // Used by ReferenceVerifier (to flag broken references) and ReflectionStringRemapper.

    /**
     * Whether the class exists in the indexed MC JAR. Returns false when not indexed, so callers
     * that need to distinguish the two cases should check {@link #isIndexed()} first.
     */
    public boolean hasClass(String internalName) {
        if (!indexed || internalName == null) return false;
        // classHierarchy has an entry for every indexed class, even members-free ones
        return classHierarchy.containsKey(internalName);
    }

    /**
     * Whether the indexed hierarchy proves that {@code child} is {@code ancestor} or one of its
     * descendants. This is an exact relationship query. It never uses fuzzy member scoring.
     */
    public boolean isSubtypeOf(String child, String ancestor) {
        if (!indexed || child == null || ancestor == null) return false;
        return hasMemberInHierarchy(child, ancestor::equals);
    }

    /**
     * Whether {@code owner} or any ancestor declares this method. Walks the hierarchy so a reference
     * to an inherited method (Level#getBlockState) isn't a false negative.
     */
    public boolean hasMethod(String owner, String name, String descriptor) {
        if (!indexed || owner == null) return false;

        // JVM constructors belong to their exact owner and are never inherited. Keep this
        // separate from methodIndex so fuzzy resolution can never offer a constructor candidate.
        if ("<init>".equals(name)) {
            Set<String> constructors = constructorIndex.get(owner);
            return constructors != null && constructors.contains(descriptor);
        }
        if (isJvmSpecialMethod(name)) return false;

        return hasMemberInHierarchy(owner, parent -> {
            for (MethodInfo m : declaredMethodsForExactLookup(parent)) {
                if (m.name().equals(name) && m.descriptor().equals(descriptor)) {
                    return true;
                }
            }
            return false;
        });
    }

    /** Whether {@code owner} or any ancestor declares this field. */
    public boolean hasField(String owner, String name, String descriptor) {
        if (!indexed || owner == null) return false;
        return hasMemberInHierarchy(owner, parent -> {
            for (FieldInfo f : declaredFieldsForExactLookup(parent)) {
                if (f.name().equals(name) && f.descriptor().equals(descriptor)) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Name-only method existence (hierarchy-aware), ignoring the descriptor. Lets the gap report
     * tell a signature change (BAD_SIGNATURE) from a removal (MISSING_METHOD).
     */
    public boolean hasMethodName(String owner, String name) {
        if (!indexed || owner == null) return false;

        if ("<init>".equals(name)) {
            Set<String> constructors = constructorIndex.get(owner);
            return constructors != null && !constructors.isEmpty();
        }
        if (isJvmSpecialMethod(name)) return false;

        return hasMemberInHierarchy(owner, parent -> {
            for (MethodInfo m : declaredMethodsForExactLookup(parent)) {
                if (m.name().equals(name)) return true;
            }
            return false;
        });
    }

    /** Name-only field existence (hierarchy-aware), ignoring the descriptor. */
    public boolean hasFieldName(String owner, String name) {
        if (!indexed || owner == null) return false;
        return hasMemberInHierarchy(owner, parent -> {
            for (FieldInfo f : declaredFieldsForExactLookup(parent)) {
                if (f.name().equals(name)) return true;
            }
            return false;
        });
    }

    /**
     * Walk up from {@code start} (superclasses and interfaces), returning true as soon as
     * {@code predicate} matches. The visited set keeps it terminating on a cyclic hierarchy.
     */
    private boolean hasMemberInHierarchy(String start, java.util.function.Predicate<String> predicate) {
        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            String current = stack.pop();
            if (!visited.add(current)) continue;
            if (predicate.test(current)) return true;
            List<String> parents = parentsForExactLookup(current);
            if (parents != null) {
                for (String p : parents) {
                    if (p != null && !visited.contains(p)) stack.push(p);
                }
            }
        }
        return false;
    }

    private List<MethodInfo> declaredMethodsForExactLookup(String owner) {
        List<MethodInfo> indexedMethods = methodIndex.get(owner);
        if (indexedMethods != null) return indexedMethods;
        ExternalClassInfo external = externalClassInfo(owner);
        return external == null ? List.of() : external.methods();
    }

    private List<FieldInfo> declaredFieldsForExactLookup(String owner) {
        List<FieldInfo> indexedFields = fieldIndex.get(owner);
        if (indexedFields != null) return indexedFields;
        ExternalClassInfo external = externalClassInfo(owner);
        return external == null ? List.of() : external.fields();
    }

    private List<String> parentsForExactLookup(String owner) {
        List<String> indexedParents = classHierarchy.get(owner);
        if (indexedParents != null) return indexedParents;
        ExternalClassInfo external = externalClassInfo(owner);
        return external == null ? null : external.parents();
    }

    /**
     * Inspect a reachable JDK ancestor without adding its members to fuzzy candidates. Reflection
     * is used because the running JDK may emit a newer class-file version than bundled ASM can
     * parse. The class is never initialized.
     */
    private ExternalClassInfo externalClassInfo(String owner) {
        if (owner == null || !owner.startsWith("java/")) return null;
        ExternalClassInfo cached = externalClassIndex.get(owner);
        if (cached != null) return cached;
        if (missingExternalClasses.contains(owner)) return null;

        try {
            Class<?> platformClass = Class.forName(owner.replace('/', '.'), false,
                    ClassLoader.getPlatformClassLoader());

            List<MethodInfo> methods = new ArrayList<>();
            for (java.lang.reflect.Method method : platformClass.getDeclaredMethods()) {
                methods.add(new MethodInfo(owner, method.getName(),
                        Type.getMethodDescriptor(method), -1, method.getModifiers()));
            }

            List<FieldInfo> fields = new ArrayList<>();
            for (java.lang.reflect.Field field : platformClass.getDeclaredFields()) {
                fields.add(new FieldInfo(owner, field.getName(),
                        Type.getDescriptor(field.getType()), -1));
            }

            List<String> parents = new ArrayList<>();
            Class<?> superClass = platformClass.getSuperclass();
            if (superClass != null) parents.add(Type.getInternalName(superClass));
            for (Class<?> iface : platformClass.getInterfaces()) {
                parents.add(Type.getInternalName(iface));
            }

            ExternalClassInfo loaded = new ExternalClassInfo(
                    List.copyOf(methods), List.copyOf(fields), List.copyOf(parents));
            ExternalClassInfo raced = externalClassIndex.putIfAbsent(owner, loaded);
            return raced != null ? raced : loaded;
        } catch (ClassNotFoundException | LinkageError | SecurityException e) {
            missingExternalClasses.add(owner);
            return null;
        }
    }

    /**
     * Unmodifiable view of every indexed class name, empty if not indexed. Used by
     * {@code FuzzyBackedSymbolIndex.suggestClassAlternatives} to find simple-name matches
     * across packages (a class that moved package).
     */
    public Set<String> getIndexedClassNames() {
        if (!indexed) return Collections.emptySet();
        return Collections.unmodifiableSet(classHierarchy.keySet());
    }

    /**
     * Methods declared directly by {@code owner}. The returned list is immutable and empty when
     * the target JAR is unavailable. This is intentionally separate from fuzzy resolution: Mixin
     * injection targets are declared methods, and an automatic repair must inspect every candidate
     * before it can reject an overloaded or otherwise ambiguous match.
     */
    public List<MethodInfo> getDeclaredMethods(String owner) {
        if (!indexed || owner == null) return List.of();
        List<MethodInfo> methods = methodIndex.get(owner);
        return methods == null ? List.of() : List.copyOf(methods);
    }

    /**
     * Methods visible on {@code owner}, including its superclasses and interfaces. The returned
     * list is immutable. Callers must still deduplicate overridden signatures when declaration
     * identity is not important.
     */
    public List<MethodInfo> getMethodsInHierarchy(String owner) {
        if (!indexed || owner == null) return List.of();
        return List.copyOf(gatherMethodCandidates(owner));
    }

    /**
     * Fuzzy-match an unresolved method reference against the target MC JAR. Returns the best match
     * at or above {@link #THRESHOLD_AUTO_APPLY}; warns (returning null) in the 50-84 band; null below.
     *
     * @param owner      JVM internal name of the class the method was called on
     * @param name       the method name that wasn't found
     * @param descriptor the JVM method descriptor of the call
     */
    public MethodInfo resolveMethod(String owner, String name, String descriptor) {
        if (!indexed || isJvmSpecialMethod(name)) return null;

        // A reference that already resolves (hierarchy-aware) needs no fixing. Without this
        // guard the scorer can rewrite a VALID call: an inherited method has no exact-owner
        // candidate (owner match only scores RELATED, 25), so a same-descriptor sibling declared
        // ON the owner (exact, 40) can tie or beat it and win by candidate order. Live case:
        // Registry.get(ResourceKey) - inherited from HolderGetter - was rewritten to
        // Registry.getOptional, the VALUE getter, and YUNG's worldgen died CCE
        // "StructureTemplatePool cannot be cast to Holder" at first structure piece.
        if (hasMethod(owner, name, descriptor)) return null;

        String cacheKey = owner + "." + name + "." + descriptor;
        MethodInfo cached = methodResolveCache.get(cacheKey);
        if (cached != null) {
            return cached == EMPTY_METHOD_INFO ? null : cached;
        }

        List<MethodInfo> candidates = gatherMethodCandidates(owner);
        if (candidates.isEmpty()) {
            boundedCachePut(methodResolveCache, cacheKey, EMPTY_METHOD_INFO);
            return null;
        }

        MethodInfo bestMatch = null;
        int bestScore = 0;

        List<String> unresolvedParams = parseParameterTypes(descriptor);
        String unresolvedReturn = parseReturnType(descriptor);

        for (MethodInfo candidate : candidates) {
            int score = scoreMethodMatch(
                    owner, name, descriptor, unresolvedParams, unresolvedReturn,
                    candidate
            );

            if (score > bestScore) {
                bestScore = score;
                bestMatch = new MethodInfo(
                        candidate.owner(), candidate.name(),
                        candidate.descriptor(), score, candidate.access()
                );
            }
        }

        if (bestMatch != null && bestScore >= THRESHOLD_AUTO_APPLY) {
            // Name+arity scoring can pick a method whose param type changed incompatibly across
            // versions (AnimationUtils.swingWeaponDown Mob -> HumanoidArm in 1.21.11); redirecting
            // there would push the old type into a slot typed for the new one -> VerifyError at load.
            if (!isRedirectStackSafe(unresolvedParams, unresolvedReturn, bestMatch.descriptor())) {
                LOGGER.debug("[Retromod-Fuzzy] Suppressed type-incompatible redirect "
                        + "{}.{}{} -> {}.{}{} (would VerifyError; left unresolved)",
                        owner, name, descriptor,
                        bestMatch.owner(), bestMatch.name(), bestMatch.descriptor());
                FuzzyMatchReport.record("METHOD", FuzzyMatchReport.BAND_SUPPRESSED, bestScore,
                        owner, name, descriptor,
                        bestMatch.owner(), bestMatch.name(), bestMatch.descriptor());
                boundedCachePut(methodResolveCache, cacheKey, EMPTY_METHOD_INFO);
                return null;
            }
            LOGGER.debug("[Retromod-Fuzzy] Resolved {}.{}{} -> {}.{}{} (confidence: {}%)",
                    owner, name, descriptor,
                    bestMatch.owner(), bestMatch.name(), bestMatch.descriptor(),
                    bestScore);
            FuzzyMatchReport.record("METHOD", FuzzyMatchReport.BAND_AUTO, bestScore,
                    owner, name, descriptor,
                    bestMatch.owner(), bestMatch.name(), bestMatch.descriptor());
            boundedCachePut(methodResolveCache, cacheKey, bestMatch);
            return bestMatch;
        } else if (bestMatch != null && bestScore >= THRESHOLD_LOG_WARNING) {
            LOGGER.warn("[Retromod-Fuzzy] Possible match for {}.{}{} -> {}.{}{} " +
                    "(confidence: {}% - below auto-apply threshold of {}%)",
                    owner, name, descriptor,
                    bestMatch.owner(), bestMatch.name(), bestMatch.descriptor(),
                    bestScore, THRESHOLD_AUTO_APPLY);
            FuzzyMatchReport.record("METHOD", FuzzyMatchReport.BAND_NEAR, bestScore,
                    owner, name, descriptor,
                    bestMatch.owner(), bestMatch.name(), bestMatch.descriptor());
        } else if (bestMatch != null) {
            LOGGER.debug("[Retromod-Fuzzy] Low confidence match for {}.{}{} -> {}.{}{} ({}%)",
                    owner, name, descriptor,
                    bestMatch.owner(), bestMatch.name(), bestMatch.descriptor(),
                    bestScore);
        }

        boundedCachePut(methodResolveCache, cacheKey, EMPTY_METHOD_INFO);
        return null;
    }

    /**
     * Constructors and class initializers have JVM-defined linkage rules. They cannot be
     * replaced with an ordinary same-shaped method, even when that method scores highly.
     */
    private static boolean isJvmSpecialMethod(String name) {
        return name != null && name.startsWith("<");
    }

    /** Methods on {@code owner} plus all inherited from its superclasses and interfaces. */
    private List<MethodInfo> gatherMethodCandidates(String owner) {
        List<MethodInfo> candidates = new ArrayList<>();

        List<MethodInfo> ownerMethods = methodIndex.get(owner);
        if (ownerMethods != null) {
            candidates.addAll(ownerMethods);
        }

        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        visited.add(owner);

        List<String> parents = classHierarchy.get(owner);
        if (parents != null) {
            for (String parent : parents) {
                if (visited.add(parent)) {
                    queue.add(parent);
                }
            }
        }

        while (!queue.isEmpty()) {
            String current = queue.poll();
            List<MethodInfo> parentMethods = methodIndex.get(current);
            if (parentMethods != null) {
                candidates.addAll(parentMethods);
            }
            List<String> grandparents = classHierarchy.get(current);
            if (grandparents != null) {
                for (String gp : grandparents) {
                    if (visited.add(gp)) {
                        queue.add(gp);
                    }
                }
            }
        }

        return candidates;
    }

    /** Score a candidate against the unresolved reference, 0 (no match) to 100 (perfect). */
    private int scoreMethodMatch(
            String unresolvedOwner, String unresolvedName, String unresolvedDesc,
            List<String> unresolvedParams, String unresolvedReturn,
            MethodInfo candidate) {

        int score = 0;
        String candDesc = candidate.descriptor();

        // Axis 1: class match (0-40). An unrelated class scores nothing, capping the
        // total at 60 (name + params) so it can't reach the auto-apply threshold.
        if (candidate.owner().equals(unresolvedOwner)) {
            score += SCORE_EXACT_CLASS;
        } else if (isRelatedClass(unresolvedOwner, candidate.owner())) {
            score += SCORE_RELATED_CLASS;
        }

        // Axis 2: name similarity (0-30).
        String candidateName = candidate.name();
        if (candidateName.equals(unresolvedName)) {
            score += SCORE_EXACT_NAME;
        } else {
            int editDistance = levenshteinDistance(unresolvedName, candidateName);
            if (editDistance <= 3) {
                score += SCORE_LEVENSHTEIN_CLOSE;
            } else {
                String candLower = candidateName.toLowerCase();
                String unresLower = unresolvedName.toLowerCase();
                if (candLower.contains(unresLower) || unresLower.contains(candLower)) {
                    score += SCORE_SUBSTRING_MATCH;
                } else {
                    int commonPrefix = commonPrefixLength(unresolvedName, candidateName);
                    int commonSuffix = commonSuffixLength(unresolvedName, candidateName);
                    if (commonPrefix >= 4 || commonSuffix >= 4) {
                        score += SCORE_PREFIX_SUFFIX;
                    }
                }
            }
        }

        // Axis 3: parameter count (0-15). Off-by-one covers an added/removed param between versions.
        List<String> candidateParams = parseParameterTypes(candDesc);
        int paramCountDiff = Math.abs(unresolvedParams.size() - candidateParams.size());
        if (paramCountDiff == 0) {
            score += SCORE_PARAM_COUNT_EXACT;
        } else if (paramCountDiff == 1) {
            score += SCORE_PARAM_COUNT_OFF_BY_ONE;
        }

        // Axis 4: parameter types (0-15).
        if (!unresolvedParams.isEmpty() && !candidateParams.isEmpty()) {
            int matchCount = countMatchingParams(unresolvedParams, candidateParams);
            int minSize = Math.min(unresolvedParams.size(), candidateParams.size());
            if (matchCount == minSize && paramCountDiff == 0) {
                score += SCORE_ALL_PARAMS_MATCH;
            } else if (minSize > 0 && matchCount > minSize / 2) {
                score += SCORE_MOST_PARAMS_MATCH;
            }
        } else if (unresolvedParams.isEmpty() && candidateParams.isEmpty()) {
            score += SCORE_ALL_PARAMS_MATCH;
        }

        // Return-type check: a mismatched return type VerifyErrors, so reject outright unless
        // both are objects with near-identical names (a type rename, not a different method).
        String candidateReturn = candDesc.substring(candDesc.lastIndexOf(')') + 1);
        if (!unresolvedReturn.equals(candidateReturn)) {
            boolean unresolvedPrimitive = !unresolvedReturn.startsWith("L") && !unresolvedReturn.startsWith("[");
            boolean candidatePrimitive = !candidateReturn.startsWith("L") && !candidateReturn.startsWith("[");

            if (unresolvedPrimitive != candidatePrimitive) {
                return 0; // primitive vs object
            }
            if (unresolvedPrimitive && candidatePrimitive && !unresolvedReturn.equals(candidateReturn)) {
                return 0; // two different primitives
            }
            if (levenshteinDistance(unresolvedReturn, candidateReturn) > 10) {
                return 0; // unrelated object types
            }
            score -= 20; // similar object types: penalize
        }

        return score;
    }

    /**
     * Fuzzy-match an unresolved field reference: a rename (same type, new name) or a move to a
     * superclass. Same thresholds as {@link #resolveMethod}.
     *
     * @param owner      JVM internal name of the class the field was accessed on
     * @param name       the field name that wasn't found
     * @param descriptor the JVM field descriptor (type)
     */
    public FieldInfo resolveField(String owner, String name, String descriptor) {
        if (!indexed) return null;

        // Same guard as resolveMethod: never rewrite a field reference that already resolves.
        if (hasField(owner, name, descriptor)) return null;

        String cacheKey = owner + "." + name + "." + descriptor;
        FieldInfo cached = fieldResolveCache.get(cacheKey);
        if (cached != null) {
            return cached == EMPTY_FIELD_INFO ? null : cached;
        }

        List<FieldInfo> candidates = gatherFieldCandidates(owner);
        if (candidates.isEmpty()) {
            boundedCachePut(fieldResolveCache, cacheKey, EMPTY_FIELD_INFO);
            return null;
        }

        FieldInfo bestMatch = null;
        int bestScore = 0;

        for (FieldInfo candidate : candidates) {
            int score = scoreFieldMatch(owner, name, descriptor, candidate);

            if (score > bestScore) {
                bestScore = score;
                bestMatch = new FieldInfo(
                        candidate.owner(), candidate.name(),
                        candidate.descriptor(), score
                );
            }
        }

        if (bestMatch != null && bestScore >= THRESHOLD_AUTO_APPLY) {
            LOGGER.debug("[Retromod-Fuzzy] Resolved field {}.{} {} -> {}.{} {} (confidence: {}%)",
                    owner, name, descriptor,
                    bestMatch.owner(), bestMatch.name(), bestMatch.descriptor(),
                    bestScore);
            FuzzyMatchReport.record("FIELD", FuzzyMatchReport.BAND_AUTO, bestScore,
                    owner, name, descriptor,
                    bestMatch.owner(), bestMatch.name(), bestMatch.descriptor());
            boundedCachePut(fieldResolveCache, cacheKey, bestMatch);
            return bestMatch;
        } else if (bestMatch != null && bestScore >= THRESHOLD_LOG_WARNING) {
            LOGGER.warn("[Retromod-Fuzzy] Possible field match for {}.{} {} -> {}.{} {} " +
                    "(confidence: {}% - below auto-apply threshold of {}%)",
                    owner, name, descriptor,
                    bestMatch.owner(), bestMatch.name(), bestMatch.descriptor(),
                    bestScore, THRESHOLD_AUTO_APPLY);
            FuzzyMatchReport.record("FIELD", FuzzyMatchReport.BAND_NEAR, bestScore,
                    owner, name, descriptor,
                    bestMatch.owner(), bestMatch.name(), bestMatch.descriptor());
        }

        boundedCachePut(fieldResolveCache, cacheKey, EMPTY_FIELD_INFO);
        return null;
    }

    /** Fields on {@code owner} plus all inherited from its hierarchy. */
    private List<FieldInfo> gatherFieldCandidates(String owner) {
        List<FieldInfo> candidates = new ArrayList<>();

        List<FieldInfo> ownerFields = fieldIndex.get(owner);
        if (ownerFields != null) {
            candidates.addAll(ownerFields);
        }

        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        visited.add(owner);

        List<String> parents = classHierarchy.get(owner);
        if (parents != null) {
            for (String parent : parents) {
                if (visited.add(parent)) {
                    queue.add(parent);
                }
            }
        }

        while (!queue.isEmpty()) {
            String current = queue.poll();
            List<FieldInfo> parentFields = fieldIndex.get(current);
            if (parentFields != null) {
                candidates.addAll(parentFields);
            }
            List<String> grandparents = classHierarchy.get(current);
            if (grandparents != null) {
                for (String gp : grandparents) {
                    if (visited.add(gp)) {
                        queue.add(gp);
                    }
                }
            }
        }

        return candidates;
    }

    /**
     * Score a candidate field, 0 to 100: class match (0-40), name similarity (0-30),
     * type match (0-30, in place of the method axes for param count and types).
     */
    private int scoreFieldMatch(String unresolvedOwner, String unresolvedName,
            String unresolvedDesc, FieldInfo candidate) {

        int score = 0;

        // Class match (0-40).
        if (candidate.owner().equals(unresolvedOwner)) {
            score += SCORE_EXACT_CLASS;
        } else if (isRelatedClass(unresolvedOwner, candidate.owner())) {
            score += SCORE_RELATED_CLASS;
        }

        // Name similarity (0-30).
        if (candidate.name().equals(unresolvedName)) {
            score += SCORE_EXACT_NAME;
        } else {
            int editDistance = levenshteinDistance(unresolvedName, candidate.name());
            if (editDistance <= 3) {
                score += SCORE_LEVENSHTEIN_CLOSE;
            } else {
                String candName = candidate.name();
                String candLower = candName.toLowerCase();
                String unresLower = unresolvedName.toLowerCase();
                if (candLower.contains(unresLower) || unresLower.contains(candLower)) {
                    score += SCORE_SUBSTRING_MATCH;
                } else {
                    int commonPrefix = commonPrefixLength(unresolvedName, candName);
                    int commonSuffix = commonSuffixLength(unresolvedName, candName);
                    if (commonPrefix >= 4 || commonSuffix >= 4) {
                        score += SCORE_PREFIX_SUFFIX;
                    }
                }
            }
        }

        // Type match (0-30). For a field the descriptor is the type; an exact match means
        // a rename with the type unchanged, a related type is weaker evidence.
        if (candidate.descriptor().equals(unresolvedDesc)) {
            score += 30;
        } else {
            String unresolvedType = extractTypeFromDescriptor(unresolvedDesc);
            String candidateType = extractTypeFromDescriptor(candidate.descriptor());
            if (unresolvedType != null && candidateType != null &&
                isRelatedClass(unresolvedType, candidateType)) {
                score += 15;
            }
        }

        return score;
    }

    /** Whether one of the two classes is an ancestor of the other, within 5 levels. */
    private boolean isRelatedClass(String classA, String classB) {
        return isAncestor(classA, classB, 5) || isAncestor(classB, classA, 5);
    }

    /**
     * Whether redirecting a call to {@code candDescriptor} is stack-safe. The rewritten bytecode
     * still pushes args of the original types, so arities must match, each original arg type must be
     * assignable to the candidate's param type, and the candidate's return assignable to the original
     * return. Package-private for testing.
     */
    boolean isRedirectStackSafe(List<String> origParams, String origReturn, String candDescriptor) {
        List<String> candParams = parseParameterTypes(candDescriptor);
        if (origParams.size() != candParams.size()) {
            return false;
        }
        for (int i = 0; i < origParams.size(); i++) {
            if (!isTypeAssignable(origParams.get(i), candParams.get(i))) return false;
        }
        return isTypeAssignable(parseReturnType(candDescriptor), origReturn);
    }

    /**
     * Whether a value of descriptor type {@code from} can stand in where {@code to} is expected
     * (is {@code from} a subtype of {@code to}). Returns false when unprovable, so the caller
     * declines the redirect rather than risk a VerifyError. Package-private for testing.
     */
    boolean isTypeAssignable(String from, String to) {
        if (from.equals(to)) return true;
        if (from.isEmpty() || to.isEmpty()) return false;
        boolean fromRef = from.charAt(0) == 'L' || from.charAt(0) == '[';
        boolean toRef   = to.charAt(0) == 'L'   || to.charAt(0) == '[';
        if (fromRef != toRef) return false;   // primitive vs reference
        if (!fromRef) return false;           // two different primitives
        if (from.charAt(0) == '[' || to.charAt(0) == '[') return false; // arrays need exact match (above)
        String f = from.substring(1, from.length() - 1); // strip 'L' .. ';'
        String t = to.substring(1, to.length() - 1);
        if (f.equals(t)) return true;
        if (t.equals("java/lang/Object")) return true;
        return isAncestor(f, t, 6);
    }

    /** Whether {@code ancestor} appears above {@code child} in the hierarchy, within maxDepth levels. */
    private boolean isAncestor(String child, String ancestor, int maxDepth) {
        if (maxDepth <= 0) return false;

        List<String> parents = classHierarchy.get(child);
        if (parents == null) return false;

        for (String parent : parents) {
            if (parent.equals(ancestor)) return true;
            if (isAncestor(parent, ancestor, maxDepth - 1)) return true;
        }
        return false;
    }

    /** Levenshtein edit distance, two-row DP in O(min(m,n)) space. A distance of 1-3 suggests a rename. */
    static int levenshteinDistance(String a, String b) {
        if (a.equals(b)) return 0;
        if (a.isEmpty()) return b.length();
        if (b.isEmpty()) return a.length();

        if (a.length() > b.length()) { // keep a the shorter one
            String tmp = a;
            a = b;
            b = tmp;
        }

        int[] prev = new int[a.length() + 1];
        int[] curr = new int[a.length() + 1];

        for (int i = 0; i <= a.length(); i++) {
            prev[i] = i;
        }

        for (int j = 1; j <= b.length(); j++) {
            curr[0] = j;
            for (int i = 1; i <= a.length(); i++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                curr[i] = Math.min(
                        Math.min(curr[i - 1] + 1, prev[i] + 1),
                        prev[i - 1] + cost
                );
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }

        return prev[a.length()];
    }

    private int commonPrefixLength(String a, String b) {
        int len = Math.min(a.length(), b.length());
        int count = 0;
        for (int i = 0; i < len; i++) {
            if (a.charAt(i) == b.charAt(i)) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private int commonSuffixLength(String a, String b) {
        int lenA = a.length();
        int lenB = b.length();
        int len = Math.min(lenA, lenB);
        int count = 0;
        for (int i = 1; i <= len; i++) {
            if (a.charAt(lenA - i) == b.charAt(lenB - i)) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /** Parameter types of a method descriptor: "(ILjava/lang/String;F)V" -> ["I", "Ljava/lang/String;", "F"]. */
    static List<String> parseParameterTypes(String descriptor) {
        List<String> params = new ArrayList<>();
        int i = 1; // skip the opening '('
        while (i < descriptor.length() && descriptor.charAt(i) != ')') {
            int start = i;
            char c = descriptor.charAt(i);
            switch (c) {
                case 'L' -> {
                    int end = descriptor.indexOf(';', i);
                    if (end < 0) return params; // malformed; a 'break' would leave i stuck and spin the while
                    params.add(descriptor.substring(start, end + 1));
                    i = end + 1;
                }
                case '[' -> {
                    while (i < descriptor.length() && descriptor.charAt(i) == '[') i++;
                    if (i < descriptor.length() && descriptor.charAt(i) == 'L') {
                        int end = descriptor.indexOf(';', i);
                        if (end < 0) return params; // malformed
                        params.add(descriptor.substring(start, end + 1));
                        i = end + 1;
                    } else if (i < descriptor.length()) {
                        params.add(descriptor.substring(start, i + 1));
                        i++;
                    }
                }
                default -> {
                    params.add(String.valueOf(c));
                    i++;
                }
            }
        }
        return params;
    }

    /** Return type of a method descriptor: "(IF)Ljava/lang/String;" -> "Ljava/lang/String;". */
    private String parseReturnType(String descriptor) {
        int closeParenIdx = descriptor.indexOf(')');
        if (closeParenIdx < 0 || closeParenIdx + 1 >= descriptor.length()) {
            return "V";
        }
        return descriptor.substring(closeParenIdx + 1);
    }

    /** How many parameter types match by position, up to the length of the shorter list. */
    private int countMatchingParams(List<String> paramsA, List<String> paramsB) {
        int count = 0;
        int minLen = Math.min(paramsA.size(), paramsB.size());
        for (int i = 0; i < minLen; i++) {
            if (paramsA.get(i).equals(paramsB.get(i))) {
                count++;
            }
        }
        return count;
    }

    /** Class name of a field descriptor, or null for a primitive: "Ljava/lang/String;" -> "java/lang/String", "I" -> null. */
    private String extractTypeFromDescriptor(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) return null;
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
            return descriptor.substring(1, descriptor.length() - 1);
        }
        return null;
    }

    /** Locate the Minecraft client JAR from the running JVM, or null if not found. */
    public static Path findMcJarFromClasspath() {
        // Code source of an already-loaded MC class is the (patched) MC jar. Most reliable on
        // NeoForge, where MC is a JPMS module off java.class.path. SharedConstants exists on
        // every Mojang-named runtime; a PRE-26.1 Fabric runtime is INTERMEDIARY-named, so probe
        // its spellings too (class_310 is the client Minecraft class; MinecraftServer keeps its
        // readable name in intermediary, covering dedicated servers). Without these the fuzzy
        // engine was silently disabled on exactly the pre-26.1 Fabric hosts SS A targets.
        for (String probe : new String[]{
                "net.minecraft.SharedConstants",
                "net.minecraft.class_310",
                "net.minecraft.server.MinecraftServer"}) {
            try {
                Class<?> mc = Class.forName(probe, false,
                        Thread.currentThread().getContextClassLoader());
                java.security.CodeSource cs = mc.getProtectionDomain().getCodeSource();
                if (cs != null && cs.getLocation() != null) {
                    Path p = Path.of(cs.getLocation().toURI());
                    if (Files.exists(p) && p.toString().toLowerCase().endsWith(".jar")) {
                        return p;
                    }
                }
            } catch (Throwable ignored) {
                // this spelling not present here, or a non-file code source; try the next
            }
        }

        // Classpath scan, matching on the jar file name. Matching the whole path false-matched
        // library jars under .../net/minecraftforge/... (srgutils), which indexed 0 MC classes.
        String classpath = System.getProperty("java.class.path");
        if (classpath == null || classpath.isEmpty()) {
            return null;
        }
        String separator = System.getProperty("path.separator", ":");
        for (String entry : classpath.split(separator)) {
            Path jarPath = Path.of(entry);
            String name = jarPath.getFileName() == null
                    ? "" : jarPath.getFileName().toString().toLowerCase();
            if (!name.endsWith(".jar")) continue;
            // MC client jars look like minecraft-<ver>-client.jar (and variants) or a bare vanilla
            // <ver>.jar under .../versions/<ver>/; exclude forge/neoforge/library jars
            boolean looksLikeMc =
                    (name.startsWith("minecraft") && name.contains("client"))
                    || name.matches("\\d[\\w.\\-]*\\.jar"); // bare version jar (vanilla)
            boolean isLibrary = name.contains("forge") || name.contains("srgutils")
                    || name.contains("fmlloader") || name.contains("loader");
            if (looksLikeMc && !isLibrary && Files.exists(jarPath)) {
                return jarPath;
            }
        }
        return null;
    }

    /**
     * Locate the Minecraft JAR for a version from known launcher locations on disk, or null.
     *
     * @param mcVersion the Minecraft version to find, e.g. "26.1"
     */
    public static Path findMcJarByVersion(String mcVersion) {
        if (mcVersion == null || mcVersion.isEmpty()) return null;

        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase();

        List<Path> candidates = new ArrayList<>();

        if (os.contains("mac")) {
            candidates.add(Path.of(home, "Library/Application Support/PrismLauncher/libraries",
                    "com/mojang/minecraft", mcVersion, "minecraft-" + mcVersion + "-client.jar"));
            candidates.add(Path.of(home, "Library/Application Support/minecraft/versions",
                    mcVersion, mcVersion + ".jar"));
        } else if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            if (appdata == null) appdata = home;
            candidates.add(Path.of(appdata, "PrismLauncher/libraries",
                    "com/mojang/minecraft", mcVersion, "minecraft-" + mcVersion + "-client.jar"));
            candidates.add(Path.of(appdata, ".minecraft/versions",
                    mcVersion, mcVersion + ".jar"));
        } else {
            candidates.add(Path.of(home, ".local/share/PrismLauncher/libraries",
                    "com/mojang/minecraft", mcVersion, "minecraft-" + mcVersion + "-client.jar"));
            candidates.add(Path.of(home, ".minecraft/versions",
                    mcVersion, mcVersion + ".jar"));
        }

        for (Path path : candidates) {
            if (Files.exists(path)) {
                return path;
            }
        }

        return null;
    }

    public int getMethodCacheSize() {
        return methodResolveCache.size();
    }

    public int getFieldCacheSize() {
        return fieldResolveCache.size();
    }

    public int getIndexedClassCount() {
        return indexedClassCount;
    }

    public int getIndexedMethodCount() {
        return indexedMethodCount;
    }

    public int getIndexedFieldCount() {
        return indexedFieldCount;
    }

    /** Direct superclass recorded from the target Minecraft jar, or null when unknown. */
    String directSuperclass(String owner) {
        List<String> parents = classHierarchy.get(owner);
        return parents == null || parents.isEmpty() ? null : parents.get(0);
    }

    /** Clear both resolve caches; call after rebuilding the index or changing redirect maps. */
    public void clearCaches() {
        methodResolveCache.clear();
        fieldResolveCache.clear();
        LOGGER.debug("Cleared fuzzy resolver caches");
    }
}
