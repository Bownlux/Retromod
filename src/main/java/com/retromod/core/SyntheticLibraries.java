/*
 * Retromod - lets a translated jar reuse the stand-in classes of the libraries it builds on.
 * Copyright (c) 2026 Bownlux. MIT License.
 */
package com.retromod.core;

import com.retromod.util.ZipSecurity;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Translated jars that a jar being translated may link against.
 *
 * <p>{@link SyntheticEmbedder} gives each translated jar its own copy of every stand-in class it
 * uses, in a package named after that jar. A library's members then carry the library's copy in
 * their descriptors, while an addon built on the library calls them with its own copy. The two
 * never match, so the addon fails with {@code NoSuchMethodError} although both jars translated
 * cleanly: a 1.12 addon passing its {@code Material} to a core mod's block constructor did.
 *
 * <p>An addon therefore reuses the library's copy of each stand-in that crosses into the library:
 * one named by a library member the addon uses or overrides, or one a library class it extends is
 * built on. Every class that copy links to comes along. Everything else stays the addon's own,
 * including each core mod's 1.12 lifecycle classes, which every {@code @Mod} class names but no
 * addon calls. A copy is not reused when an addon class left behind in the same package uses one
 * of its package-private or protected members, because the two would no longer share a package.
 *
 * <p>The copies a jar reuses are listed in {@link #LINKS_ENTRY}, so a cache can tell when they
 * are gone or when a library arrived after the jar was translated. Libraries must be translated
 * before the jars that use them; {@link #librariesFirst} orders a batch by class references,
 * because 1.12 metadata usually declares no dependencies at all.
 */
public final class SyntheticLibraries {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    /** Lists, one per line, the stand-in copies a translated jar reuses from other jars. */
    public static final String LINKS_ENTRY = "META-INF/retromod-synthetic-links.txt";

    /** No libraries: every jar keeps its own copies, as before libraries were indexed. */
    public static final SyntheticLibraries NONE = new SyntheticLibraries(true);

    private static final int MAX_ARCHIVE_ENTRIES = 100_000;
    private static final long MAX_LINKS_BYTES = 1024 * 1024;
    private static final int MAX_GROUP = 4096;
    private static final int MAX_HIERARCHY_DEPTH = 64;

    private final boolean frozen;
    private final List<Library> libraries = new ArrayList<>();
    /** The library that defines each class. A jar added later wins, as an updated library should. */
    private final Map<String, Library> classOwners = new HashMap<>();
    /** The library that physically holds each relocated stand-in. */
    private final Map<String, Library> copyOwners = new HashMap<>();

    private SyntheticLibraries(boolean frozen) {
        this.frozen = frozen;
    }

    /** An empty index to fill with {@link #add} while a batch is translated. */
    public static SyntheticLibraries create() {
        return new SyntheticLibraries(false);
    }

    /**
     * Every jar in a folder such as {@code mods/}, as libraries for the jars translated into it.
     * Jars that were never translated hold no stand-ins and only make their classes known.
     */
    public static SyntheticLibraries ofFolder(Path folder) {
        SyntheticLibraries libraries = create();
        if (folder == null || !Files.isDirectory(folder)) return libraries;
        try (var jars = Files.list(folder)) {
            jars.filter(jar -> jar.getFileName().toString().endsWith(".jar"))
                    .filter(jar -> Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .forEach(libraries::add);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Could not list {} for libraries: {}", folder, e.toString());
        }
        return libraries;
    }

    /**
     * Indexes a translated jar. Only its entry names and link list are read now; its classes are
     * read when a later jar links to it. A jar that cannot be read is left out.
     */
    public synchronized void add(Path translatedJar) {
        if (frozen || translatedJar == null) return;
        try {
            Library library = Library.read(translatedJar);
            libraries.add(library);
            for (String className : library.classes) classOwners.put(className, library);
            for (String relocated : library.copies) copyOwners.put(relocated, library);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Could not index {} as a library: {}", translatedJar.getFileName(), e.toString());
        }
    }

    /** True when no jar has been indexed, so no stand-in can be reused. */
    public synchronized boolean isEmpty() {
        return libraries.isEmpty();
    }

    /**
     * The stand-ins a jar should take from its libraries instead of embedding its own copies.
     *
     * @param jarClasses   the jar's own classes, naming stand-ins by their registered names
     * @param wanted       registered stand-in names the jar references
     * @param localStandIn bytes of a stand-in the jar would embed itself, by registered name
     * @return stand-in name to the relocated name in a library, closed over what those copies use
     */
    synchronized Map<String, String> borrowable(Map<String, byte[]> jarClasses, Set<String> wanted,
            Function<String, byte[]> localStandIn) {
        if (libraries.isEmpty() || wanted.isEmpty() || jarClasses.isEmpty()) return Map.of();
        List<Library> linked = linkedLibraries(jarClasses);
        if (linked.isEmpty()) return Map.of();
        Map<String, String> borrowed = new TreeMap<>();
        Map<String, ClassNode> copies = new HashMap<>();
        for (Library library : linked) {
            for (String standIn : crossingStandIns(jarClasses, library, wanted)) {
                if (borrowed.containsKey(standIn)) continue;
                Map<String, String> group = borrowedGroup(standIn, library, wanted, borrowed,
                        localStandIn, copies);
                if (group == null) {
                    LOGGER.debug("Keeping a local copy of {}: the copy in {} cannot be linked whole",
                            standIn, library.jar.getFileName());
                    continue;
                }
                borrowed.putAll(group);
            }
        }
        return borrowed;
    }

    /**
     * Whether a translated jar should be translated again against the libraries indexed now: a
     * copy it reuses is gone, it holds its own copy of a stand-in it would now take from a
     * library, or that library's copy moved.
     */
    public synchronized boolean isStale(Path translatedJar) {
        if (frozen || translatedJar == null) return false;
        try {
            Library self = Library.read(translatedJar);
            for (String relocated : self.borrowed.values()) {
                if (!copyOwners.containsKey(relocated)) return true;
            }
            if (self.provided.isEmpty() || libraries.isEmpty()) return false;
            Map<String, byte[]> classes = self.ownClassesWithoutRelocation();
            if (linkedLibraries(classes).isEmpty()) return false;

            Map<String, String> ownCopies = new HashMap<>();
            for (String relocated : self.copies) ownCopies.put(standInOf(relocated), relocated);
            Function<String, byte[]> localStandIn = standIn -> {
                String relocated = ownCopies.get(standIn);
                if (relocated == null) return null;
                try {
                    byte[] bytes = self.read(relocated + ".class");
                    return bytes == null ? null : withoutRelocation(bytes);
                } catch (IOException | RuntimeException e) {
                    return null;
                }
            };
            Map<String, String> now = borrowable(classes, self.provided.keySet(), localStandIn);
            for (Map.Entry<String, String> link : now.entrySet()) {
                if (ownCopies.containsKey(link.getKey())) return true;
                String had = self.borrowed.get(link.getKey());
                if (had != null && !had.equals(link.getValue())) return true;
            }
            return false;
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Could not check the links of {}: {}", translatedJar.getFileName(), e.toString());
            return false;
        }
    }

    /** A class defined by an indexed library, or null when no library defines it. */
    public synchronized byte[] classBytes(String internalName) {
        Library library = classOwners.get(internalName);
        if (library == null) return null;
        try {
            return library.read(internalName + ".class");
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Orders jars so each comes after the jars whose classes it references, keeping the given
     * order otherwise. A cycle is broken where the given order meets it. A jar that cannot be read
     * keeps its place.
     */
    public static <T> List<T> librariesFirst(List<T> jars, Function<T, Path> pathOf) {
        if (jars == null || jars.size() < 2) return jars;
        List<Set<String>> defined = new ArrayList<>();
        List<Set<String>> referenced = new ArrayList<>();
        for (T jar : jars) {
            Set<String> classes = new HashSet<>();
            Set<String> references = new HashSet<>();
            try {
                Library library = Library.read(pathOf.apply(jar));
                classes.addAll(library.classes);
                library.forEachOwnClass((name, bytes) -> references.addAll(constantPoolClasses(bytes)));
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("Could not read {} to order it: {}", jar, e.toString());
            }
            defined.add(classes);
            referenced.add(references);
        }
        List<T> ordered = new ArrayList<>(jars.size());
        boolean[] placed = new boolean[jars.size()];
        boolean[] visiting = new boolean[jars.size()];
        for (int i = 0; i < jars.size(); i++) {
            place(i, jars, defined, referenced, placed, visiting, ordered);
        }
        return ordered;
    }

    private static <T> void place(int index, List<T> jars, List<Set<String>> defined,
            List<Set<String>> referenced, boolean[] placed, boolean[] visiting, List<T> ordered) {
        if (placed[index] || visiting[index]) return;
        visiting[index] = true;
        for (int other = 0; other < jars.size(); other++) {
            if (other != index && !Collections.disjoint(referenced.get(index), defined.get(other))) {
                place(other, jars, defined, referenced, placed, visiting, ordered);
            }
        }
        visiting[index] = false;
        placed[index] = true;
        ordered.add(jars.get(index));
    }

    /** The libraries defining a class the jar references, in the order they were indexed. */
    private List<Library> linkedLibraries(Map<String, byte[]> jarClasses) {
        Set<Library> linked = new HashSet<>();
        for (byte[] bytes : jarClasses.values()) {
            for (String reference : constantPoolClasses(bytes)) {
                if (jarClasses.containsKey(reference)) continue;
                Library owner = classOwners.get(reference);
                if (owner != null) linked.add(owner);
            }
        }
        List<Library> ordered = new ArrayList<>();
        for (Library library : libraries) {
            if (linked.contains(library)) ordered.add(library);
        }
        return ordered;
    }

    /**
     * Stand-ins that cross between the jar and one library: named by a member the jar uses on a
     * library class, or on its own class built on one; named by a method such a class declares,
     * which may override a library method; or a supertype of a library class the jar extends.
     */
    private Set<String> crossingStandIns(Map<String, byte[]> jarClasses, Library library,
            Set<String> wanted) {
        Map<String, List<String>> supertypes = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : jarClasses.entrySet()) {
            ClassReader reader = new ClassReader(entry.getValue());
            List<String> types = new ArrayList<>();
            if (reader.getSuperName() != null) types.add(reader.getSuperName());
            types.addAll(List.of(reader.getInterfaces()));
            supertypes.put(entry.getKey(), types);
        }
        Set<String> builtOnLibrary = new HashSet<>();
        Set<String> libraryAncestors = new TreeSet<>();
        for (String className : jarClasses.keySet()) {
            Set<String> reached = libraryTypesAbove(className, supertypes, library);
            if (!reached.isEmpty()) {
                builtOnLibrary.add(className);
                libraryAncestors.addAll(reached);
            }
        }

        Set<String> crossing = new TreeSet<>();
        Function<String, Boolean> crosses = owner ->
                library.classes.contains(owner) || builtOnLibrary.contains(owner);
        for (Map.Entry<String, byte[]> entry : jarClasses.entrySet()) {
            boolean onLibrary = builtOnLibrary.contains(entry.getKey());
            new ClassReader(entry.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    boolean mayOverride = onLibrary && !name.startsWith("<")
                            && (access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0;
                    if (mayOverride) addTypes(descriptor, wanted, crossing);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String fieldName,
                                String fieldDescriptor) {
                            if (crosses.apply(owner)) addTypes(fieldDescriptor, wanted, crossing);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                String methodDescriptor, boolean isInterface) {
                            if (crosses.apply(owner)) addTypes(methodDescriptor, wanted, crossing);
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String indyName, String indyDescriptor,
                                Handle bootstrap, Object... arguments) {
                            for (Object argument : arguments) {
                                if (argument instanceof Handle handle && crosses.apply(handle.getOwner())) {
                                    addTypes(handle.getDesc(), wanted, crossing);
                                }
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        // A class built on a library class is also an instance of whatever that class extends.
        ArrayDeque<String> pending = new ArrayDeque<>(libraryAncestors);
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty() && seen.size() < MAX_HIERARCHY_DEPTH * 4) {
            String type = pending.poll();
            if (!seen.add(type)) continue;
            byte[] bytes = classBytesOf(library, type);
            if (bytes == null) continue;
            ClassReader reader = new ClassReader(withoutRelocation(bytes));
            List<String> parents = new ArrayList<>(List.of(reader.getInterfaces()));
            if (reader.getSuperName() != null) parents.add(reader.getSuperName());
            for (String parent : parents) {
                if (wanted.contains(parent)) crossing.add(parent);
                if (library.classes.contains(parent)) pending.add(parent);
            }
        }
        return crossing;
    }

    /** The library classes directly above a jar class, through the jar's own classes. */
    private static Set<String> libraryTypesAbove(String className, Map<String, List<String>> supertypes,
            Library library) {
        Set<String> reached = new TreeSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>(supertypes.getOrDefault(className, List.of()));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty() && seen.size() < MAX_HIERARCHY_DEPTH) {
            String type = pending.poll();
            if (!seen.add(type)) continue;
            if (library.classes.contains(type)) {
                reached.add(type);
            } else if (supertypes.containsKey(type)) {
                pending.addAll(supertypes.get(type));
            }
        }
        return reached;
    }

    private static byte[] classBytesOf(Library library, String className) {
        try {
            return library.read(className + ".class");
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * A library's copy of a stand-in with everything it links to, plus any stand-in the jar would
     * otherwise keep in the same package that relies on package access to them. Null when the
     * library cannot supply all of it, so the jar keeps its own copy.
     */
    private Map<String, String> borrowedGroup(String standIn, Library library, Set<String> wanted,
            Map<String, String> borrowed, Function<String, byte[]> localStandIn,
            Map<String, ClassNode> copies) {
        String relocated = library.provided.get(standIn);
        Map<String, String> group = relocated == null ? null : copyClosure(relocated, copies);
        for (int round = 0; group != null && round < MAX_GROUP; round++) {
            String stranded = strandedLocal(group, wanted, borrowed, localStandIn, copies);
            if (stranded == null) break;
            String strandedCopy = library.provided.get(stranded);
            Map<String, String> more = strandedCopy == null ? null : copyClosure(strandedCopy, copies);
            if (more == null || conflicts(more, group)) return null;
            group.putAll(more);
        }
        if (group == null || conflicts(group, borrowed)) return null;
        return group;
    }

    /**
     * A stand-in the jar would keep that shares a package with a borrowed one and uses its
     * package-private or protected members, or overrides its package-private methods. Split
     * across two jars those accesses fail with {@code IllegalAccessError}.
     */
    private String strandedLocal(Map<String, String> group, Set<String> wanted,
            Map<String, String> borrowed, Function<String, byte[]> localStandIn,
            Map<String, ClassNode> copies) {
        for (String local : new TreeSet<>(wanted)) {
            if (group.containsKey(local) || borrowed.containsKey(local)) continue;
            String localPackage = packageOf(local);
            boolean sharesPackage = group.keySet().stream().anyMatch(s -> packageOf(s).equals(localPackage));
            if (!sharesPackage) continue;
            byte[] bytes = localStandIn.apply(local);
            if (bytes != null && reachesHiddenMembers(bytes, localPackage, group, copies)) return local;
        }
        return null;
    }

    private boolean reachesHiddenMembers(byte[] localBytes, String localPackage,
            Map<String, String> group, Map<String, ClassNode> copies) {
        Function<String, ClassNode> sameVisibilityScope = owner ->
                group.containsKey(owner) && packageOf(owner).equals(localPackage)
                        ? copyNode(group.get(owner), copies) : null;
        boolean[] hidden = {false};
        new ClassReader(localBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces) {
                List<String> parents = new ArrayList<>();
                if (superName != null) parents.add(superName);
                if (interfaces != null) parents.addAll(List.of(interfaces));
                for (String parent : parents) {
                    ClassNode node = sameVisibilityScope.apply(parent);
                    if (node != null && (!isPublic(node.access) || hasPackagePrivateMethod(node))) {
                        hidden[0] = true;
                    }
                }
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        checkClass(type);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fieldName,
                            String fieldDescriptor) {
                        checkMember(owner, fieldName, fieldDescriptor, false);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                            String methodDescriptor, boolean isInterface) {
                        checkMember(owner, methodName, methodDescriptor, true);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type type && type.getSort() == Type.OBJECT) {
                            checkClass(type.getInternalName());
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String indyDescriptor,
                            Handle bootstrap, Object... arguments) {
                        for (Object argument : arguments) {
                            if (argument instanceof Handle handle) {
                                checkMember(handle.getOwner(), handle.getName(), handle.getDesc(),
                                        handle.getTag() >= Opcodes.H_INVOKEVIRTUAL);
                            }
                        }
                    }
                };
            }

            private void checkClass(String type) {
                ClassNode node = sameVisibilityScope.apply(type);
                if (node != null && !isPublic(node.access)) hidden[0] = true;
            }

            private void checkMember(String owner, String name, String descriptor, boolean method) {
                ClassNode node = sameVisibilityScope.apply(owner);
                if (node == null) return;
                if (!isPublic(node.access)) {
                    hidden[0] = true;
                    return;
                }
                Integer access = method ? methodAccess(node, name, descriptor)
                        : fieldAccess(node, name, descriptor);
                if (access != null && !isPublic(access)) hidden[0] = true;
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return hidden[0];
    }

    /**
     * A relocated copy and every relocated class it reaches, by stand-in name, read from the jars
     * that hold them. Null when one of them is not in any indexed jar, because a partial set would
     * mix two jars' copies of related classes.
     */
    private Map<String, String> copyClosure(String relocated, Map<String, ClassNode> copies) {
        Map<String, String> group = new LinkedHashMap<>();
        ArrayDeque<String> pending = new ArrayDeque<>(List.of(relocated));
        while (!pending.isEmpty()) {
            String next = pending.poll();
            String standIn = standInOf(next);
            if (standIn == null) return null;
            String previous = group.putIfAbsent(standIn, next);
            if (previous != null) {
                if (!previous.equals(next)) return null;
                continue;
            }
            if (group.size() > MAX_GROUP) return null;
            byte[] bytes = copyBytes(next);
            if (bytes == null) return null;
            for (String reference : SyntheticEmbedder.referencedClasses(bytes)) {
                if (reference.startsWith(SyntheticEmbedder.PREFIX) && !group.containsValue(reference)) {
                    pending.add(reference);
                }
            }
        }
        return group;
    }

    private ClassNode copyNode(String relocated, Map<String, ClassNode> copies) {
        return copies.computeIfAbsent(relocated, name -> {
            byte[] bytes = copyBytes(name);
            if (bytes == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(withoutRelocation(bytes)).accept(node, ClassReader.SKIP_CODE);
            return node;
        });
    }

    private byte[] copyBytes(String relocated) {
        Library owner = copyOwners.get(relocated);
        if (owner == null) return null;
        try {
            return owner.read(relocated + ".class");
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean conflicts(Map<String, String> group, Map<String, String> borrowed) {
        for (Map.Entry<String, String> entry : group.entrySet()) {
            String existing = borrowed.get(entry.getKey());
            if (existing != null && !existing.equals(entry.getValue())) return true;
        }
        return false;
    }

    private static boolean isPublic(int access) {
        return (access & Opcodes.ACC_PUBLIC) != 0;
    }

    private static boolean hasPackagePrivateMethod(ClassNode node) {
        int visibility = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;
        return node.methods.stream().anyMatch(m -> (m.access & visibility) == 0
                && (m.access & Opcodes.ACC_STATIC) == 0 && !m.name.startsWith("<"));
    }

    private static Integer methodAccess(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return method.access;
        }
        return null;
    }

    private static Integer fieldAccess(ClassNode node, String name, String descriptor) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(descriptor)) return field.access;
        }
        return null;
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    /** Adds every wanted stand-in a field or method descriptor names. */
    private static void addTypes(String descriptor, Set<String> wanted, Set<String> into) {
        List<Type> types = new ArrayList<>();
        if (descriptor.startsWith("(")) {
            types.addAll(List.of(Type.getArgumentTypes(descriptor)));
            types.add(Type.getReturnType(descriptor));
        } else {
            types.add(Type.getType(descriptor));
        }
        for (Type type : types) {
            Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
            if (element.getSort() == Type.OBJECT && wanted.contains(element.getInternalName())) {
                into.add(element.getInternalName());
            }
        }
    }

    /** The registered stand-in name inside a relocated name, or null when it is not one. */
    static String standInOf(String relocated) {
        if (relocated == null || !relocated.startsWith(SyntheticEmbedder.PREFIX)) return null;
        String rest = relocated.substring(SyntheticEmbedder.PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) return null;
        return rest.substring(slash + 1);
    }

    /** A translated class with every relocated stand-in named by its registered name again. */
    static byte[] withoutRelocation(byte[] classBytes) {
        ClassWriter writer = new ClassWriter(0);
        new ClassReader(classBytes).accept(new ClassRemapper(writer, new Remapper() {
            @Override
            public String map(String internalName) {
                String standIn = standInOf(internalName);
                return standIn == null ? internalName : standIn;
            }
        }), 0);
        return writer.toByteArray();
    }

    /** The link list a jar that reuses {@code borrowed} carries. */
    static byte[] linksFile(Map<String, String> borrowed) {
        StringBuilder text = new StringBuilder();
        for (String relocated : new TreeSet<>(borrowed.values())) {
            text.append(relocated).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Class names in a class file's constant pool, element types for arrays. */
    private static Set<String> constantPoolClasses(byte[] classBytes) {
        Set<String> names = new HashSet<>();
        ClassReader reader;
        try {
            reader = new ClassReader(classBytes);
        } catch (RuntimeException e) {
            return names;
        }
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int item = 1; item < reader.getItemCount(); item++) {
            int offset = reader.getItem(item);
            if (offset == 0 || reader.readByte(offset - 1) != 7) continue;
            String name = reader.readUTF8(offset, buffer);
            if (name == null) continue;
            int arrayEnd = name.lastIndexOf('[');
            if (arrayEnd >= 0) {
                if (name.length() < arrayEnd + 3 || name.charAt(arrayEnd + 1) != 'L') continue;
                name = name.substring(arrayEnd + 2, name.length() - 1);
            }
            names.add(name);
        }
        return names;
    }

    /** One translated jar, read lazily beyond its entry names. */
    private static final class Library {
        final Path jar;
        final Set<String> classes;
        /** Relocated stand-ins this jar holds. */
        final Set<String> copies;
        /** Stand-ins this jar reuses from other jars, to their relocated names. */
        final Map<String, String> borrowed;
        /** Every stand-in this jar links to, to the relocated name it uses. */
        final Map<String, String> provided;

        private Library(Path jar, Set<String> classes, Set<String> copies,
                Map<String, String> borrowed) {
            this.jar = jar;
            this.classes = classes;
            this.copies = copies;
            this.borrowed = borrowed;
            Map<String, String> all = new HashMap<>(borrowed);
            for (String relocated : copies) all.put(standInOf(relocated), relocated);
            this.provided = all;
        }

        static Library read(Path jar) throws IOException {
            Objects.requireNonNull(jar, "jar");
            Set<String> classes = new HashSet<>();
            Set<String> copies = new HashSet<>();
            Map<String, String> borrowed = new HashMap<>();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                int count = 0;
                for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                    if (++count > MAX_ARCHIVE_ENTRIES) {
                        throw new IOException("too many entries in " + jar.getFileName());
                    }
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.endsWith(".class") || name.startsWith("META-INF/")) {
                        continue;
                    }
                    String internalName = name.substring(0, name.length() - ".class".length());
                    if (internalName.startsWith(SyntheticEmbedder.PREFIX)) {
                        if (standInOf(internalName) != null) copies.add(internalName);
                    } else {
                        classes.add(internalName);
                    }
                }
                ZipEntry links = zip.getEntry(LINKS_ENTRY);
                if (links != null && !links.isDirectory()) {
                    try (InputStream in = zip.getInputStream(links)) {
                        String text = new String(ZipSecurity.safeReadAllBytes(in, MAX_LINKS_BYTES),
                                StandardCharsets.UTF_8);
                        for (String line : text.split("\n")) {
                            String relocated = line.trim();
                            String standIn = standInOf(relocated);
                            if (standIn == null) continue;
                            ZipSecurity.safeEntryName(relocated + ".class");
                            borrowed.put(standIn, relocated);
                        }
                    }
                }
            }
            return new Library(jar, classes, copies, borrowed);
        }

        byte[] read(String entryName) throws IOException {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null || entry.isDirectory()) return null;
                try (InputStream in = zip.getInputStream(entry)) {
                    return ZipSecurity.safeReadAllBytes(in);
                }
            }
        }

        /** This jar's own classes, naming every stand-in by its registered name. */
        Map<String, byte[]> ownClassesWithoutRelocation() throws IOException {
            Map<String, byte[]> classes = new HashMap<>();
            forEachOwnClass((name, bytes) -> classes.put(name, withoutRelocation(bytes)));
            return classes;
        }

        void forEachOwnClass(java.util.function.BiConsumer<String, byte[]> action) throws IOException {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                int count = 0;
                for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                    if (++count > MAX_ARCHIVE_ENTRIES) return;
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.endsWith(".class") || name.startsWith("META-INF/")
                            || name.startsWith(SyntheticEmbedder.PREFIX)) {
                        continue;
                    }
                    try (InputStream in = zip.getInputStream(entry)) {
                        action.accept(name.substring(0, name.length() - ".class".length()),
                                ZipSecurity.safeReadAllBytes(in));
                    } catch (RuntimeException ignored) {
                        // One unreadable class must not hide the rest of the jar.
                    }
                }
            }
        }
    }
}
