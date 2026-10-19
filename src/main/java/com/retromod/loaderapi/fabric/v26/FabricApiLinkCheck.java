/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.retromod.core.RetromodTransformer.NestedArchiveBudget;
import com.retromod.util.ZipSecurity;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Logs the Fabric API references a translated mod still makes that the host lacks. */
public final class FabricApiLinkCheck {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-LoaderApi");
    private static final String FABRIC_API = "net/fabricmc/fabric/";
    private static final int MAX_LINES = 25;
    private static final String NESTED_JARS = "META-INF/jars/";
    private static final int MAX_NESTING = 8;
    private static final String LAMBDA_FACTORY = "java/lang/invoke/LambdaMetafactory";

    private FabricApiLinkCheck() {}

    /**
     * The references in {@code classes} that {@code surface} does not have, as sorted text. Types
     * the mod defines itself are skipped, as are members whose supertypes leave Fabric API.
     */
    public static Set<String> missingReferences(Collection<byte[]> classes, HostSurface surface) {
        return missingReferences(classes, surface, typeNames(classes));
    }

    /**
     * The missing references of a translated jar and of each jar nested in it, keyed by the
     * nested path, such as {@code META-INF/jars/a.jar > META-INF/jars/b.jar}, and {@code ""} for
     * the jar itself. Only jars with a missing reference appear.
     */
    public static Map<String, Set<String>> missingReferencesByJar(byte[] jar, HostSurface surface)
            throws IOException {
        Map<String, List<byte[]>> units = new LinkedHashMap<>();
        readUnits(jar, "", 0, NestedArchiveBudget.defaults(), units);
        // Knot loads a mod and the jars it nests side by side, so each sees the others' types.
        Set<String> ownTypes = new HashSet<>();
        units.values().forEach(classes -> ownTypes.addAll(typeNames(classes)));
        Map<String, Set<String>> missing = new LinkedHashMap<>();
        for (Map.Entry<String, List<byte[]>> unit : units.entrySet()) {
            Set<String> found = missingReferences(unit.getValue(), surface, ownTypes);
            if (!found.isEmpty()) missing.put(unit.getKey(), found);
        }
        return missing;
    }

    /** Logs the missing references of a translated jar and of each jar it nests, and returns how many. */
    public static int reportJar(byte[] jar, String modName, String hostVersion) throws IOException {
        int total = 0;
        for (Map.Entry<String, Set<String>> unit
                : missingReferencesByJar(jar, HostSurface.forHost(hostVersion)).entrySet()) {
            String name = unit.getKey().isEmpty() ? modName : modName + " > " + unit.getKey();
            total += log(unit.getValue(), name, hostVersion);
        }
        return total;
    }

    private static Set<String> missingReferences(Collection<byte[]> classes, HostSurface surface,
            Set<String> ownTypes) {
        Set<String> missing = new TreeSet<>();
        for (byte[] bytes : classes) {
            new ClassReader(bytes).accept(new ReferenceCollector(surface, ownTypes, missing),
                    ClassReader.SKIP_FRAMES);
        }
        return missing;
    }

    /** Logs each missing reference once for {@code modName} and returns how many there were. */
    private static int log(Set<String> missing, String modName, String hostVersion) {
        if (missing.isEmpty()) return 0;
        LOGGER.info("{} still uses {} Fabric API reference(s) that Minecraft {} does not have. "
                + "The loader API runtime has no row for them yet:",
                modName, missing.size(), hostVersion);
        int shown = 0;
        for (String reference : missing) {
            if (shown++ == MAX_LINES) {
                LOGGER.info("  and {} more", missing.size() - MAX_LINES);
                break;
            }
            LOGGER.info("  {}", reference);
        }
        return missing.size();
    }

    private static Set<String> typeNames(Collection<byte[]> classes) {
        Set<String> names = new HashSet<>();
        for (byte[] bytes : classes) names.add(new ClassReader(bytes).getClassName());
        return names;
    }

    /**
     * Reads the classes of {@code jar} under {@code path}, then each jar it nests. A bundled Fabric
     * API module is left out, because the host's own module replaces it, as the runtime path
     * strips it. One budget bounds the whole tree.
     */
    private static void readUnits(byte[] jar, String path, int depth, NestedArchiveBudget budget,
            Map<String, List<byte[]>> units) throws IOException {
        List<byte[]> classes = new ArrayList<>();
        Map<String, byte[]> nested = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                String name = ZipSecurity.safeEntryName(entry.getName());
                if (entry.isDirectory()) continue;
                boolean isClass = name.endsWith(".class");
                boolean isNestedJar = name.startsWith(NESTED_JARS) && name.endsWith(".jar")
                        && name.indexOf('/', NESTED_JARS.length()) < 0;
                if (!isClass && !isNestedJar) continue;
                long allowance = budget.beginRead(ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, name);
                byte[] bytes = ZipSecurity.safeReadAllBytes(in, allowance);
                budget.completeRead(allowance, bytes.length);
                if (isClass) {
                    classes.add(bytes);
                } else if (!name.substring(NESTED_JARS.length()).startsWith("fabric-")) {
                    nested.putIfAbsent(name, bytes);
                }
            }
        }
        units.put(path, classes);
        if (depth >= MAX_NESTING) return;
        for (Map.Entry<String, byte[]> entry : nested.entrySet()) {
            String child = path.isEmpty() ? entry.getKey() : path + " > " + entry.getKey();
            readUnits(entry.getValue(), child, depth + 1, budget, units);
        }
    }

    /**
     * Collects every Fabric API name a class links against: supertypes, declarations, calls,
     * field access, lambdas and method handles, class constants, annotations and array types.
     */
    private static final class ReferenceCollector extends ClassVisitor {
        private final HostSurface surface;
        private final Set<String> ownTypes;
        private final Set<String> missing;

        ReferenceCollector(HostSurface surface, Set<String> ownTypes, Set<String> missing) {
            super(Opcodes.ASM9);
            this.surface = surface;
            this.ownTypes = ownTypes;
            this.missing = missing;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            checkType(superName);
            if (interfaces != null) {
                for (String itf : interfaces) checkType(itf);
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return annotation(descriptor);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                String descriptor, boolean visible) {
            return annotation(descriptor);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                String signature, Object value) {
            checkDescriptor(descriptor);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    return annotation(desc);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            checkDescriptor(descriptor);
            if (exceptions != null) {
                for (String exception : exceptions) checkType(exception);
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String method, String desc,
                        boolean isInterface) {
                    checkMember(owner, method, desc);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                    checkMember(owner, field, desc);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    checkType(type);
                }

                @Override
                public void visitInvokeDynamicInsn(String indyName, String desc, Handle bootstrap,
                        Object... arguments) {
                    // A lambda names its interface in the call site type and its body in a handle.
                    checkDescriptor(desc);
                    checkConstant(bootstrap);
                    for (Object argument : arguments) checkConstant(argument);
                    // The host calls the interface's own method, so a lambda built on another one never runs.
                    if (LAMBDA_FACTORY.equals(bootstrap.getOwner()) && arguments.length > 0
                            && arguments[0] instanceof Type sam && sam.getSort() == Type.METHOD
                            && Type.getReturnType(desc).getSort() == Type.OBJECT) {
                        checkMember(Type.getReturnType(desc).getInternalName(), indyName, sam.getDescriptor());
                    }
                }

                @Override
                public void visitLdcInsn(Object value) {
                    checkConstant(value);
                }

                @Override
                public void visitMultiANewArrayInsn(String desc, int dimensions) {
                    checkDescriptor(desc);
                }

                @Override
                public void visitTryCatchBlock(org.objectweb.asm.Label start,
                        org.objectweb.asm.Label end, org.objectweb.asm.Label handler, String type) {
                    checkType(type);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    return annotation(desc);
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int parameter, String desc,
                        boolean visible) {
                    return annotation(desc);
                }

                @Override
                public AnnotationVisitor visitAnnotationDefault() {
                    return annotationValues();
                }
            };
        }

        /** Checks an annotation's own type, then its class, enum and nested annotation values. */
        private AnnotationVisitor annotation(String descriptor) {
            checkDescriptor(descriptor);
            return annotationValues();
        }

        private AnnotationVisitor annotationValues() {
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    checkConstant(value);
                }

                @Override
                public void visitEnum(String name, String descriptor, String value) {
                    checkDescriptor(descriptor);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                    return annotation(descriptor);
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    return annotationValues();
                }
            };
        }

        /** A constant pool value: a class or method type, or a method handle to a member. */
        private void checkConstant(Object value) {
            if (value instanceof Type type) {
                if (type.getSort() == Type.METHOD) {
                    checkDescriptor(type.getDescriptor());
                } else {
                    checkType(type);
                }
            } else if (value instanceof Handle handle) {
                checkMember(handle.getOwner(), handle.getName(), handle.getDesc());
                checkDescriptor(handle.getDesc());
            }
        }

        private void checkDescriptor(String descriptor) {
            Type type = Type.getType(descriptor);
            if (type.getSort() == Type.METHOD) {
                for (Type argument : type.getArgumentTypes()) checkType(argument);
                checkType(type.getReturnType());
            } else {
                checkType(type);
            }
        }

        private void checkType(Type type) {
            Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
            if (element.getSort() == Type.OBJECT) checkType(element.getInternalName());
        }

        private void checkType(String internalName) {
            if (internalName == null || internalName.startsWith("[")) return;
            if (!internalName.startsWith(FABRIC_API) || ownTypes.contains(internalName)) return;
            if (!surface.hasClass(internalName)) missing.add(internalName);
        }

        private void checkMember(String owner, String name, String desc) {
            if (owner.startsWith("[") || !owner.startsWith(FABRIC_API)
                    || ownTypes.contains(owner)) {
                return;
            }
            if (!surface.hasClass(owner)) {
                missing.add(owner);
                return;
            }
            if (surface.member(owner, name, desc) == HostSurface.Presence.MISSING) {
                missing.add(owner + "." + name + (desc.startsWith("(") ? desc : ":" + desc));
            }
        }
    }
}
