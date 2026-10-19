/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds the block, item and entity type ids a jar registers its static fields under. */
public final class StaticFieldRegistryIds {

    /** No field has a known id. */
    public static final StaticFieldRegistryIds NONE = new StaticFieldRegistryIds(Map.of(), Map.of(), null);

    // 26.x refuses to build a block or item whose Properties carry no registry id. A multiloader mod
    // fills ModBlocks.FOO in one class and registers it from another, loader-specific one, and FOO can
    // be built before that class runs. So the id is read off the registration and stamped where the
    // field is filled.
    private final Map<String, Map<String, String[]>> idsByOwner;
    // Kept apart, because 1.21.2 builds an entity type's translation key and loot table from its id
    // instead of stamping a Properties.
    private final Map<String, Map<String, String[]>> entityTypeIdsByOwner;
    // For an entity whose old build string carries no namespace.
    private final String namespace;
    /** Each owner's name as it appears in a class file that refers to it. */
    private final List<byte[]> ownerNames;

    private StaticFieldRegistryIds(Map<String, Map<String, String[]>> idsByOwner,
                                   Map<String, Map<String, String[]>> entityTypeIdsByOwner, String namespace) {
        this.idsByOwner = idsByOwner;
        this.entityTypeIdsByOwner = entityTypeIdsByOwner;
        this.namespace = namespace;
        this.ownerNames = idsByOwner.keySet().stream().map(StaticFieldIdStamper::utf8).toList();
    }

    /** {@code field -> {namespace, path}} for {@code owner}'s static block and item fields with ids. */
    public Map<String, String[]> fieldsOf(String owner) {
        return owner == null ? Map.of() : idsByOwner.getOrDefault(owner, Map.of());
    }

    /** {@code field -> {namespace, path}} for {@code owner}'s static entity type fields with ids. */
    public Map<String, String[]> entityTypeFieldsOf(String owner) {
        return owner == null ? Map.of() : entityTypeIdsByOwner.getOrDefault(owner, Map.of());
    }

    /** The namespace the jar registers its content under, or null when it names none or several. */
    public String namespace() {
        return namespace;
    }

    /** Whether no field has a known id. The jar may still name a {@link #namespace()}. */
    public boolean isEmpty() {
        return idsByOwner.isEmpty() && entityTypeIdsByOwner.isEmpty();
    }

    /** Whether a class might fill one of these fields, checked without parsing it. */
    public boolean mayStoreInto(byte[] classBytes) {
        if (classBytes == null) return false;
        // A class can only store into a field of a class its constant pool names.
        for (byte[] owner : ownerNames) {
            if (StaticFieldIdStamper.containsBytes(classBytes, owner)) return true;
        }
        return false;
    }

    /** The number of fields with a known id, for logging. */
    public int fieldCount() {
        return idsByOwner.values().stream().mapToInt(Map::size).sum()
                + entityTypeIdsByOwner.values().stream().mapToInt(Map::size).sum();
    }

    /** Starts an index of one jar, before any of its classes is transformed. */
    public static Builder builder(Set<String> jarClasses) {
        // Every class in the jar, so a field of a class from another jar or from Minecraft is never
        // taken for the mod's own.
        return new Builder(jarClasses);
    }

    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String RESOURCE_LOCATION = "net/minecraft/resources/ResourceLocation";
    /** {@code Registry}, its 3-argument {@code register}, and {@code Identifier}, in intermediary. */
    private static final String INTERMEDIARY_REGISTRY = "net/minecraft/class_2378";
    private static final String INTERMEDIARY_REGISTER = "method_10230";
    private static final String INTERMEDIARY_IDENTIFIER = "net/minecraft/class_2960";
    private static final String STRING = "Ljava/lang/String;";
    private static final Set<String> REGISTER_HELPERS = Set.of(
            "net/neoforged/neoforge/registries/RegisterEvent$RegisterHelper",
            "net/minecraftforge/registries/RegisterEvent$RegisterHelper");
    private static final Set<String> REGISTER_EVENTS = Set.of(
            "net/neoforged/neoforge/registries/RegisterEvent",
            "net/minecraftforge/registries/RegisterEvent");
    private static final Set<String> DEFERRED_REGISTERS = Set.of(
            "net/neoforged/neoforge/registries/DeferredRegister",
            "net/neoforged/neoforge/registries/DeferredRegister$Blocks",
            "net/neoforged/neoforge/registries/DeferredRegister$Items",
            "net/minecraftforge/registries/DeferredRegister");
    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";

    /** The registries this index cares about; any other registration is ignored. */
    private enum Kind { BLOCK, ITEM, ENTITY_TYPE }

    /** Fields holding the block, item or entity type registry or its key, under any remapped name. */
    private static final Map<String, Kind> REGISTRY_HOLDERS = Map.ofEntries(
            Map.entry("net/minecraft/core/registries/BuiltInRegistries.BLOCK", Kind.BLOCK),
            Map.entry("net/minecraft/core/registries/BuiltInRegistries.ITEM", Kind.ITEM),
            Map.entry("net/minecraft/core/registries/Registries.BLOCK", Kind.BLOCK),
            Map.entry("net/minecraft/core/registries/Registries.ITEM", Kind.ITEM),
            // The pre-1.19.3 holders on Registry, then Forge's.
            Map.entry("net/minecraft/core/Registry.BLOCK", Kind.BLOCK),
            Map.entry("net/minecraft/core/Registry.ITEM", Kind.ITEM),
            Map.entry("net/minecraftforge/registries/ForgeRegistries.BLOCKS", Kind.BLOCK),
            Map.entry("net/minecraftforge/registries/ForgeRegistries.ITEMS", Kind.ITEM),
            Map.entry("net/minecraftforge/registries/ForgeRegistries$Keys.BLOCKS", Kind.BLOCK),
            Map.entry("net/minecraftforge/registries/ForgeRegistries$Keys.ITEMS", Kind.ITEM),
            // On NeoForge the remap moves Forge's key holder here before the field reads move on.
            Map.entry("net/neoforged/neoforge/registries/NeoForgeRegistries$Keys.BLOCKS", Kind.BLOCK),
            Map.entry("net/neoforged/neoforge/registries/NeoForgeRegistries$Keys.ITEMS", Kind.ITEM),
            Map.entry("net/minecraft/core/registries/BuiltInRegistries.ENTITY_TYPE", Kind.ENTITY_TYPE),
            Map.entry("net/minecraft/core/registries/Registries.ENTITY_TYPE", Kind.ENTITY_TYPE),
            Map.entry("net/minecraft/core/Registry.ENTITY_TYPE", Kind.ENTITY_TYPE),
            Map.entry("net/minecraftforge/registries/ForgeRegistries.ENTITY_TYPES", Kind.ENTITY_TYPE),
            Map.entry("net/minecraftforge/registries/ForgeRegistries$Keys.ENTITY_TYPES", Kind.ENTITY_TYPE),
            Map.entry("net/neoforged/neoforge/registries/NeoForgeRegistries$Keys.ENTITY_TYPES", Kind.ENTITY_TYPE),
            // Entity types in intermediary too (BuiltInRegistries and Registries), since pre-26.1
            // Fabric hosts never remap a mod to Mojang names.
            Map.entry("net/minecraft/class_7923.field_41177", Kind.ENTITY_TYPE),
            Map.entry("net/minecraft/class_7924.field_41266", Kind.ENTITY_TYPE));

    /** A literal namespace, or the one a static {@code id(String path)} method in the jar adds. */
    private record Namespace(String literal, String idHelper) {
        static Namespace of(String literal) {
            return new Namespace(literal, null);
        }

        // The helper can live in a class the index has not read yet, so it is resolved once the
        // whole jar is read.
        String resolve(Map<String, String> idHelpers) {
            return literal != null ? literal : idHelpers.get(idHelper);
        }
    }

    /** A field found registered under {@code namespace:path} in the registry of {@code kind}. */
    private record Found(Kind kind, String field, Namespace namespace, String path) {}

    /** A static helper that registers its {@code objectArg} under {@code namespace:<nameArg>}. */
    private record Helper(Namespace namespace, int nameArg, int objectArg, Kind kind, int registryArg) {}

    /** A call to some static method of the jar, kept until every helper is known. */
    private record HelperCall(String callee, Map<Integer, String> literals, Map<Integer, String> fields,
                              Map<Integer, Kind> registries) {}

    /** A {@code RegisterHelper.register} in a method that may turn out to be a registration consumer. */
    private record ConsumerRegistration(String method, String field, Namespace namespace, String path) {}

    /** {@code register("name", () -> Owner.FIELD)} on the deferred register held in {@code register}. */
    private record DeferredCall(String register, String name, String supplier) {}

    /** A deferred register's namespace and registry, read from where its field is filled. */
    private record DeferredRegister(String namespace, Kind kind) {}

    /** Collects facts one class at a time, so a whole jar never has to be held as trees. */
    public static final class Builder {
        private final Set<String> jarClasses;
        private final Map<String, String[]> ids = new HashMap<>();
        private final Set<String> conflicting = new HashSet<>();
        private final Map<String, String[]> entityTypeIds = new HashMap<>();
        private final Set<String> conflictingEntityTypes = new HashSet<>();
        private final NamespaceEvidence namespaces = new NamespaceEvidence();
        private final Map<String, Helper> helpers = new HashMap<>();
        private final Set<String> ambiguousHelpers = new HashSet<>();
        private final List<HelperCall> helperCalls = new ArrayList<>();
        private final Map<String, Kind> consumerKinds = new HashMap<>();
        private final Set<String> ambiguousConsumers = new HashSet<>();
        private final List<ConsumerRegistration> consumerRegistrations = new ArrayList<>();
        private final Map<String, DeferredRegister> deferredRegisters = new HashMap<>();
        private final Set<String> ambiguousDeferredRegisters = new HashSet<>();
        private final List<DeferredCall> deferredCalls = new ArrayList<>();
        private final Map<String, String> fieldSuppliers = new HashMap<>();
        /** {@code owner.name+desc} of each static {@code id(String path)} to the namespace it adds. */
        private final Map<String, String> idHelpers = new HashMap<>();
        private final List<Found> found = new ArrayList<>();

        private Builder(Set<String> jarClasses) {
            this.jarClasses = Set.copyOf(jarClasses);
        }

        /** Reads one class of the jar, already renamed to the host's names. */
        public Builder add(ClassNode cn) {
            if (cn == null || cn.methods == null) return this;
            // The remap has run, so intermediary, SRG and Mojang jars all look alike here.
            namespaces.add(cn);
            for (MethodNode m : cn.methods) {
                if (m.instructions == null || m.instructions.size() == 0) continue;
                noteFieldSupplier(cn.name, m);
                boolean mayBeIdHelper = mayBeIdHelper(m);
                if (!mayBeIdHelper && !mayMatter(m)) continue;
                Frame<SourceValue>[] frames;
                try {
                    frames = new Analyzer<>(new SourceInterpreter()).analyze(cn.name, m);
                } catch (Throwable unanalyzable) {
                    continue;
                }
                if (mayBeIdHelper) noteIdHelper(cn.name, m, frames);
                readMethod(cn.name, m, frames);
            }
            return this;
        }

        /** Matches what the whole jar showed and returns the index. */
        public StaticFieldRegistryIds build() {
            // A call to a helper counts once the helper's body proved it registers that argument.
            for (HelperCall call : helperCalls) {
                Helper helper = helpers.get(call.callee());
                if (helper == null || ambiguousHelpers.contains(call.callee())) continue;
                String path = call.literals().get(helper.nameArg());
                String field = call.fields().get(helper.objectArg());
                Kind kind = helper.kind() != null ? helper.kind() : call.registries().get(helper.registryArg());
                if (path != null && field != null && kind != null) {
                    found.add(new Found(kind, field, helper.namespace(), path));
                }
            }
            // A RegisterHelper call counts inside a consumer a RegisterEvent handed one known registry.
            for (ConsumerRegistration registration : consumerRegistrations) {
                if (consumerKinds.containsKey(registration.method())
                        && !ambiguousConsumers.contains(registration.method())) {
                    found.add(new Found(consumerKinds.get(registration.method()), registration.field(),
                            registration.namespace(), registration.path()));
                }
            }
            // A deferred call counts when its register's namespace and registry are known.
            for (DeferredCall call : deferredCalls) {
                DeferredRegister register = deferredRegisters.get(call.register());
                String field = fieldSuppliers.get(call.supplier());
                if (register != null && register.kind() != null && field != null
                        && !ambiguousDeferredRegisters.contains(call.register())) {
                    found.add(new Found(register.kind(), field, Namespace.of(register.namespace()), call.name()));
                }
            }
            // Resolve each namespace now that every id helper is known.
            for (Found registration : found) {
                String namespace = registration.namespace().resolve(idHelpers);
                if (namespace == null) continue;
                if (registration.kind() == Kind.ENTITY_TYPE) {
                    record(entityTypeIds, conflictingEntityTypes, registration.field(), namespace, registration.path());
                } else {
                    record(ids, conflicting, registration.field(), namespace, registration.path());
                }
            }
            // A field registered under two different ids is left out.
            conflicting.forEach(ids::remove);
            conflictingEntityTypes.forEach(entityTypeIds::remove);
            Map<String, Map<String, String[]>> byOwner = byOwner(ids);
            Map<String, Map<String, String[]>> entityTypesByOwner = byOwner(entityTypeIds);
            String namespace = namespaces.namespace();
            if (byOwner.isEmpty() && entityTypesByOwner.isEmpty() && namespace == null) return NONE;
            return new StaticFieldRegistryIds(byOwner, entityTypesByOwner, namespace);
        }

        /** Groups {@code owner.field} keys by owner. */
        private static Map<String, Map<String, String[]>> byOwner(Map<String, String[]> ids) {
            Map<String, Map<String, String[]>> byOwner = new HashMap<>();
            ids.forEach((key, id) -> {
                int dot = key.lastIndexOf('.');
                byOwner.computeIfAbsent(key.substring(0, dot), owner -> new HashMap<>())
                        .put(key.substring(dot + 1), id);
            });
            byOwner.replaceAll((owner, fields) -> Map.copyOf(fields));
            return Map.copyOf(byOwner);
        }

        /** Records a field's id; a second, different id marks the field as conflicting. */
        private static void record(Map<String, String[]> ids, Set<String> conflicting,
                                   String field, String namespace, String path) {
            String[] id = {namespace, path};
            String[] previous = ids.putIfAbsent(field, id);
            if (previous != null && !java.util.Arrays.equals(previous, id)) conflicting.add(field);
        }

        /** Only methods with a call worth reading are analyzed, which keeps a large jar cheap. */
        private boolean mayMatter(MethodNode m) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && (isRegistration(call) || isRegisterHelper(call)
                        || isRegisterEvent(call) || isDeferredRegistration(call) || isDeferredCreate(call)
                        || (call.getOpcode() == Opcodes.INVOKESTATIC && jarClasses.contains(call.owner)))) {
                    return true;
                }
            }
            return false;
        }

        /** Reads each call of one method that may register a field. */
        private void readMethod(String owner, MethodNode m, Frame<SourceValue>[] frames) {
            for (AbstractInsnNode insn : m.instructions) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                Frame<SourceValue> f = frames[m.instructions.indexOf(call)];
                if (f == null) continue; // unreachable
                if (isRegistration(call)) {
                    readRegistration(owner, m, frames, f);
                } else if (isRegisterHelper(call)) {
                    readHelperRegistration(owner, m, frames, f);
                } else if (isRegisterEvent(call)) {
                    readRegisterEvent(owner, m, frames, f);
                } else if (isDeferredRegistration(call)) {
                    readDeferredRegistration(m, frames, f);
                } else if (isDeferredCreate(call)) {
                    readDeferredCreate(m, frames, call);
                } else if (call.getOpcode() == Opcodes.INVOKESTATIC && jarClasses.contains(call.owner)) {
                    readHelperCall(call, f);
                }
            }
        }

        /** {@code Registry.register(registry, id, object)}, directly or as a helper's body. */
        private void readRegistration(String owner, MethodNode m, Frame<SourceValue>[] frames, Frame<SourceValue> f) {
            int top = f.getStackSize();
            AbstractInsnNode registry = single(f.getStack(top - 3));
            AbstractInsnNode idProducer = throughLocal(m, frames, single(f.getStack(top - 2)));
            AbstractInsnNode object = throughCast(m, frames, single(f.getStack(top - 1)));
            IdShape id = idShape(m, frames, idProducer);
            if (id == null) return;
            // A literal id, a known registry and a field of the jar: a direct registration.
            Kind kind = registryKind(registry);
            String field = jarStaticField(object);
            if (id.path() != null && field != null && kind != null) {
                found.add(new Found(kind, field, id.namespace(), id.path()));
                return;
            }
            // Otherwise a static method that registers a parameter under "ns":<its String parameter>
            // is a helper, called as helper("name", Owner.FIELD). The registry may be a parameter too.
            if ((m.access & Opcodes.ACC_STATIC) == 0 || id.pathLoad() == null) return;
            int nameArg = parameterRead(m, frames, id.pathLoad(), STRING);
            int objectArg = parameterRead(m, frames, object, null);
            int registryArg = parameterRead(m, frames, registry, null);
            if (nameArg < 0 || objectArg < 0 || (kind == null && registryArg < 0)) return;
            Helper helper = new Helper(id.namespace(), nameArg, objectArg, kind, kind == null ? registryArg : -1);
            // The same method read as two different helpers is not trusted.
            String key = owner + "." + m.name + m.desc;
            Helper previous = helpers.putIfAbsent(key, helper);
            if (previous != null && !previous.equals(helper)) ambiguousHelpers.add(key);
        }

        /** {@code helper.register(id, Owner.FIELD)} inside a registration consumer. */
        private void readHelperRegistration(String owner, MethodNode m, Frame<SourceValue>[] frames,
                                            Frame<SourceValue> f) {
            int top = f.getStackSize();
            IdShape id = idShape(m, frames, throughLocal(m, frames, single(f.getStack(top - 2))));
            String field = jarStaticField(throughCast(m, frames, single(f.getStack(top - 1))));
            // Kept until build() knows which registry a RegisterEvent handed this method.
            if (id != null && id.path() != null && field != null) {
                consumerRegistrations.add(new ConsumerRegistration(owner + "." + m.name + m.desc,
                        field, id.namespace(), id.path()));
            }
        }

        /** Whether a method has the static {@code id(String path)} shape. */
        private static boolean mayBeIdHelper(MethodNode m) {
            return (m.access & Opcodes.ACC_STATIC) != 0 && isIdFromPath(m.desc);
        }

        /** Notes {@code id(String path)} when each return builds {@code literal:path} from that path. */
        private void noteIdHelper(String owner, MethodNode m, Frame<SourceValue>[] frames) {
            // As in return new ResourceLocation(MOD_ID, path). A second namespace or a changed path
            // rules it out.
            String namespace = null;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn.getOpcode() != Opcodes.ARETURN) continue;
                Frame<SourceValue> f = frames[m.instructions.indexOf(insn)];
                if (f == null) continue; // unreachable
                IdShape id = idShape(m, frames, throughLocal(m, frames, single(f.getStack(f.getStackSize() - 1))));
                if (id == null || id.namespace().literal() == null || id.pathLoad() == null
                        || parameterRead(m, frames, id.pathLoad(), STRING) != 0
                        || (namespace != null && !namespace.equals(id.namespace().literal()))) {
                    return;
                }
                namespace = id.namespace().literal();
            }
            if (namespace != null) idHelpers.put(owner + "." + m.name + m.desc, namespace);
        }

        /** {@code event.register(Registries.BLOCK, consumer)}: the consumer then registers blocks. */
        private void readRegisterEvent(String owner, MethodNode m, Frame<SourceValue>[] frames, Frame<SourceValue> f) {
            int top = f.getStackSize();
            Kind kind = registryKind(single(f.getStack(top - 2)));
            String consumer = lambdaImplementation(single(f.getStack(top - 1)));
            if (consumer == null) return;
            // A consumer handed an unknown registry, or two different ones, counts for none.
            Kind previous = consumerKinds.putIfAbsent(consumer, kind);
            if (kind == null || (previous != null && previous != kind)) ambiguousConsumers.add(consumer);
        }

        /** {@code DEFERRED.register("name", () -> Owner.FIELD)}. */
        private void readDeferredRegistration(MethodNode m, Frame<SourceValue>[] frames, Frame<SourceValue> f) {
            int top = f.getStackSize();
            String register = staticField(single(f.getStack(top - 3)));
            String supplier = lambdaImplementation(single(f.getStack(top - 1)));
            if (register != null && supplier != null
                    && single(f.getStack(top - 2)) instanceof LdcInsnNode ldc && ldc.cst instanceof String name) {
                deferredCalls.add(new DeferredCall(register, name, supplier));
            }
        }

        /** {@code FIELD = DeferredRegister.create(Registries.BLOCK, "ns")}, or {@code createBlocks("ns")}. */
        private void readDeferredCreate(MethodNode m, Frame<SourceValue>[] frames, MethodInsnNode create) {
            // Only a register stored straight into a static field can be matched to its calls.
            AbstractInsnNode next = create.getNext();
            while (next != null && next.getOpcode() < 0) next = next.getNext();
            if (!(next instanceof FieldInsnNode put) || put.getOpcode() != Opcodes.PUTSTATIC) return;
            Frame<SourceValue> f = frames[m.instructions.indexOf(create)];
            int args = Type.getArgumentTypes(create.desc).length;
            int top = f.getStackSize();
            // The namespace is the last argument and has to be a literal.
            if (!(single(f.getStack(top - 1)) instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String namespace)) {
                return;
            }
            // createBlocks and createItems name their registry; create takes it before the namespace.
            Kind kind = switch (create.name) {
                case "createBlocks" -> Kind.BLOCK;
                case "createItems" -> Kind.ITEM;
                default -> args == 2 ? registryKind(single(f.getStack(top - 2))) : null;
            };
            String key = put.owner + "." + put.name;
            DeferredRegister register = new DeferredRegister(namespace, kind);
            DeferredRegister previous = deferredRegisters.putIfAbsent(key, register);
            if (previous != null && !previous.equals(register)) ambiguousDeferredRegisters.add(key);
        }

        /** Remembers the literal and field arguments of a call that may reach a helper. */
        private void readHelperCall(MethodInsnNode call, Frame<SourceValue> f) {
            int argCount = Type.getArgumentTypes(call.desc).length;
            if (argCount < 2) return;
            int base = f.getStackSize() - argCount;
            Map<Integer, String> literals = new HashMap<>();
            Map<Integer, String> fields = new HashMap<>();
            Map<Integer, Kind> registries = new HashMap<>();
            for (int arg = 0; arg < argCount; arg++) {
                AbstractInsnNode producer = single(f.getStack(base + arg));
                if (producer instanceof LdcInsnNode ldc && ldc.cst instanceof String text) literals.put(arg, text);
                String field = jarStaticField(producer);
                if (field != null) fields.put(arg, field);
                Kind kind = registryKind(producer);
                if (kind != null) registries.put(arg, kind);
            }
            // Only a literal name and a jar field can make a registration.
            if (!literals.isEmpty() && !fields.isEmpty()) {
                helperCalls.add(new HelperCall(call.owner + "." + call.name + call.desc, literals, fields, registries));
            }
        }

        /** A static, argument-free method whose whole body returns one of the jar's static fields. */
        private void noteFieldSupplier(String owner, MethodNode m) {
            if ((m.access & Opcodes.ACC_STATIC) == 0 || !m.desc.startsWith("()")) return;
            // The real instructions, without labels and line numbers: getstatic, an optional
            // checkcast, then areturn.
            List<AbstractInsnNode> code = new ArrayList<>();
            for (AbstractInsnNode insn : m.instructions) {
                if (insn.getOpcode() >= 0) code.add(insn);
            }
            if (code.size() == 3 && code.get(1).getOpcode() == Opcodes.CHECKCAST) code.remove(1);
            if (code.size() == 2 && code.get(1).getOpcode() == Opcodes.ARETURN) {
                String field = jarStaticField(code.get(0));
                if (field != null) fieldSuppliers.put(owner + "." + m.name + m.desc, field);
            }
        }

        /** {@code owner.field} for a read of a static field of a class in this jar. */
        private String jarStaticField(AbstractInsnNode insn) {
            return insn instanceof FieldInsnNode get && get.getOpcode() == Opcodes.GETSTATIC
                    && jarClasses.contains(get.owner) ? get.owner + "." + get.name : null;
        }
    }

    /** A literal id, or for a helper body a namespace and the load of the path. */
    private record IdShape(Namespace namespace, String path, VarInsnNode pathLoad) {}

    /** The id {@code producer} builds, when its namespace is a literal or comes from an id helper. */
    private static IdShape idShape(MethodNode m, Frame<SourceValue>[] frames, AbstractInsnNode producer) {
        AbstractInsnNode namespace;
        AbstractInsnNode path;
        // A call to a static id(path) that may be the mod's own helper; its namespace resolves later.
        if (producer instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                && isIdFromPath(call.desc)) {
            Frame<SourceValue> f = frames[m.instructions.indexOf(call)];
            if (f == null) return null;
            Namespace fromHelper = new Namespace(null, call.owner + "." + call.name + call.desc);
            AbstractInsnNode pathArg = single(f.getStack(f.getStackSize() - 1));
            if (pathArg instanceof LdcInsnNode ldc && ldc.cst instanceof String pathText) {
                return new IdShape(fromHelper, pathText, null);
            }
            return pathArg instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                    ? new IdShape(fromHelper, null, load) : null;
        }
        // Identifier.fromNamespaceAndPath(ns, path) or any other static two-string factory,
        // otherwise new ResourceLocation(ns, path).
        if (producer instanceof MethodInsnNode factory && factory.getOpcode() == Opcodes.INVOKESTATIC
                && isIdClass(factory.owner)
                && factory.desc.equals("(" + STRING + STRING + ")L" + factory.owner + ";")) {
            Frame<SourceValue> f = frames[m.instructions.indexOf(factory)];
            if (f == null) return null;
            namespace = single(f.getStack(f.getStackSize() - 2));
            path = single(f.getStack(f.getStackSize() - 1));
        } else {
            MethodInsnNode init = constructorOf(m, frames, producer);
            if (init == null) return null;
            Frame<SourceValue> f = frames[m.instructions.indexOf(init)];
            namespace = single(f.getStack(f.getStackSize() - 2));
            path = single(f.getStack(f.getStackSize() - 1));
        }
        // Only a literal namespace counts. The path is a literal, or a local for a helper's body.
        if (!(namespace instanceof LdcInsnNode ns) || !(ns.cst instanceof String nsText)) return null;
        if (path instanceof LdcInsnNode ldc && ldc.cst instanceof String pathText) {
            return new IdShape(Namespace.of(nsText), pathText, null);
        }
        if (path instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
            return new IdShape(Namespace.of(nsText), null, load);
        }
        return null;
    }

    /** The {@code <init>(String, String)} call that builds the id created by {@code new}. */
    private static MethodInsnNode constructorOf(MethodNode m, Frame<SourceValue>[] frames, AbstractInsnNode value) {
        if (value == null || value.getOpcode() != Opcodes.DUP) return null;
        Frame<SourceValue> atDup = frames[m.instructions.indexOf(value)];
        if (atDup == null || !(single(atDup.getStack(atDup.getStackSize() - 1)) instanceof TypeInsnNode created)
                || created.getOpcode() != Opcodes.NEW || !isIdClass(created.desc)) {
            return null;
        }
        // The analyzer attributes the built value to the dup after the new, so the constructor is
        // the one whose receiver came from that same dup.
        for (AbstractInsnNode insn = value.getNext(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode init) || init.getOpcode() != Opcodes.INVOKESPECIAL
                    || !init.owner.equals(created.desc) || !"<init>".equals(init.name)) {
                continue;
            }
            Frame<SourceValue> f = frames[m.instructions.indexOf(init)];
            int receiver = f == null ? -1 : f.getStackSize() - Type.getArgumentTypes(init.desc).length - 1;
            if (receiver >= 0 && single(f.getStack(receiver)) == value) {
                return init.desc.equals("(" + STRING + STRING + ")V") ? init : null;
            }
        }
        return null;
    }

    /** Whether {@code desc} takes one {@code String} and returns an id: the shape of {@code id(path)}. */
    private static boolean isIdFromPath(String desc) {
        Type[] args = Type.getArgumentTypes(desc);
        Type returned = Type.getReturnType(desc);
        return args.length == 1 && args[0].getDescriptor().equals(STRING)
                && returned.getSort() == Type.OBJECT && isIdClass(returned.getInternalName());
    }

    /** Whether {@code owner} is the id class under any name the remap can leave. */
    private static boolean isIdClass(String owner) {
        return IDENTIFIER.equals(owner) || RESOURCE_LOCATION.equals(owner) || INTERMEDIARY_IDENTIFIER.equals(owner);
    }

    /** Whether this is the 3-argument {@code Registry.register}, in Mojang or intermediary names. */
    public static boolean isRegistration(MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKESTATIC) return false;
        String registry;
        if (REGISTRY.equals(call.owner) && "register".equals(call.name)) {
            registry = REGISTRY;
        } else if (INTERMEDIARY_REGISTRY.equals(call.owner) && INTERMEDIARY_REGISTER.equals(call.name)) {
            registry = INTERMEDIARY_REGISTRY;
        } else if (call.owner.endsWith(WorldgenTypeBridgeSynthetic.INTERNAL) && "register".equals(call.name)) {
            // On 26.x the call may already point at the worldgen type bridge, which counts the same.
            registry = REGISTRY;
        } else {
            return false;
        }
        Type[] args = Type.getArgumentTypes(call.desc);
        return args.length == 3 && args[0].getSort() == Type.OBJECT && args[0].getInternalName().equals(registry)
                && args[1].getSort() == Type.OBJECT && isIdClass(args[1].getInternalName());
    }

    /** Whether {@code insn} reads the entity type registry, or its key, in any naming the index knows. */
    public static boolean readsEntityTypeRegistry(AbstractInsnNode insn) {
        return registryKind(insn) == Kind.ENTITY_TYPE;
    }

    /** {@code {namespace, path}} of the id {@code producer} builds, a part null when not literal, or null. */
    public static String[] idParts(MethodNode m, Frame<SourceValue>[] frames, AbstractInsnNode producer) {
        IdShape id = idShape(m, frames, throughLocal(m, frames, producer));
        return id == null ? null : new String[]{id.namespace().literal(), id.path()};
    }

    private static boolean isRegisterHelper(MethodInsnNode call) {
        if (!REGISTER_HELPERS.contains(call.owner) || !"register".equals(call.name)) return false;
        Type[] args = Type.getArgumentTypes(call.desc);
        return args.length == 2 && args[0].getSort() == Type.OBJECT && isIdClass(args[0].getInternalName());
    }

    private static boolean isRegisterEvent(MethodInsnNode call) {
        return REGISTER_EVENTS.contains(call.owner) && "register".equals(call.name)
                && call.desc.equals("(Lnet/minecraft/resources/ResourceKey;Ljava/util/function/Consumer;)V");
    }

    private static boolean isDeferredRegistration(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL && DEFERRED_REGISTERS.contains(call.owner)
                && "register".equals(call.name)
                && call.desc.startsWith("(" + STRING + "Ljava/util/function/Supplier;)");
    }

    private static boolean isDeferredCreate(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESTATIC && DEFERRED_REGISTERS.contains(call.owner)
                && (call.name.equals("create") || call.name.equals("createBlocks")
                    || call.name.equals("createItems"))
                && call.desc.contains(STRING + ")");
    }

    /** The registry a static field read holds, or null for any other read. */
    private static Kind registryKind(AbstractInsnNode insn) {
        return insn instanceof FieldInsnNode get && get.getOpcode() == Opcodes.GETSTATIC
                ? REGISTRY_HOLDERS.get(get.owner + "." + get.name) : null;
    }

    private static String staticField(AbstractInsnNode insn) {
        return insn instanceof FieldInsnNode get && get.getOpcode() == Opcodes.GETSTATIC
                ? get.owner + "." + get.name : null;
    }

    /** {@code owner.name+desc} of the static method a capture-free lambda runs, else null. */
    private static String lambdaImplementation(AbstractInsnNode insn) {
        if (!(insn instanceof InvokeDynamicInsnNode indy) || !indy.desc.startsWith("()")
                || !LAMBDA_METAFACTORY.equals(indy.bsm.getOwner()) || indy.bsmArgs.length < 2
                || !(indy.bsmArgs[1] instanceof Handle impl) || impl.getTag() != Opcodes.H_INVOKESTATIC) {
            return null;
        }
        return impl.getOwner() + "." + impl.getName() + impl.getDesc();
    }

    /** The index of the parameter {@code insn} loads, or -1. A null descriptor allows any type. */
    public static int parameterRead(MethodNode m, Frame<SourceValue>[] frames, AbstractInsnNode insn,
                                    String descriptor) {
        if (!(insn instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) return -1;
        Frame<SourceValue> atLoad = frames[m.instructions.indexOf(load)];
        // The load must read the value the method was called with on every path: a helper that
        // rewrites its name first, as in name = "wood_" + name, registers under a different id than
        // its caller passes. A parameter's entry value has no producing instruction; any store on a
        // path to here adds one.
        if (atLoad == null || !atLoad.getLocal(load.var).insns.isEmpty()) return -1;
        return argumentAt(m, load.var, descriptor);
    }

    /** The argument a local slot holds on entry, or -1. Static methods only, so slot 0 is the first. */
    private static int argumentAt(MethodNode m, int local, String descriptor) {
        int slot = 0;
        Type[] args = Type.getArgumentTypes(m.desc);
        for (int arg = 0; arg < args.length; arg++) {
            if (slot == local) {
                return descriptor == null || args[arg].getDescriptor().equals(descriptor) ? arg : -1;
            }
            // A long or a double takes two slots.
            slot += args[arg].getSize();
        }
        return -1;
    }

    /** Follows {@code aload n} back to the value its only {@code astore n} saved. */
    private static AbstractInsnNode throughLocal(MethodNode m, Frame<SourceValue>[] frames, AbstractInsnNode insn) {
        if (!(insn instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) return insn;
        Frame<SourceValue> atLoad = frames[m.instructions.indexOf(load)];
        // A parameter has no store, so it stays the load, which is what a helper's body is matched on.
        if (atLoad == null || !(single(atLoad.getLocal(load.var)) instanceof VarInsnNode store)
                || store.getOpcode() != Opcodes.ASTORE) {
            return insn;
        }
        Frame<SourceValue> atStore = frames[m.instructions.indexOf(store)];
        return atStore == null ? insn : single(atStore.getStack(atStore.getStackSize() - 1));
    }

    /** The value under a {@code checkcast}, which a generic helper's return or argument often adds. */
    private static AbstractInsnNode throughCast(MethodNode m, Frame<SourceValue>[] frames, AbstractInsnNode insn) {
        if (insn == null || insn.getOpcode() != Opcodes.CHECKCAST) return insn;
        Frame<SourceValue> atCast = frames[m.instructions.indexOf(insn)];
        return atCast == null ? null : single(atCast.getStack(atCast.getStackSize() - 1));
    }

    /** The one instruction that produced a value, or null when several could have. */
    private static AbstractInsnNode single(SourceValue value) {
        return value != null && value.insns.size() == 1 ? value.insns.iterator().next() : null;
    }

    /** The namespace a class or a jar registers its content under, read from its literals. */
    public static final class NamespaceEvidence {
        // Minecraft's namespace and the shared tag namespaces never count: a mod that writes
        // new ResourceLocation("minecraft", "stone") or "c:ingots" is reading, not registering.
        private static final Set<String> SHARED = Set.of("minecraft", "c", "forge", "neoforge", "fabric", "common");
        private static final java.util.regex.Pattern NAMESPACE = java.util.regex.Pattern.compile("[a-z0-9_.-]+");

        private final Map<String, Integer> deferredRegisters = new HashMap<>();
        private final Map<String, Integer> ids = new HashMap<>();

        /** Counts the literal namespaces {@code cn} creates a deferred register with or builds an id in. */
        public NamespaceEvidence add(ClassNode cn) {
            if (cn == null || cn.methods == null) return this;
            for (MethodNode m : cn.methods) {
                if (m.instructions == null) continue;
                for (AbstractInsnNode insn : m.instructions) {
                    if (!(insn instanceof MethodInsnNode call)) continue;
                    // Every literal argument of a deferred register factory counts as its namespace.
                    if (isDeferredRegisterFactory(call)) {
                        AbstractInsnNode argument = call;
                        for (int arg = Type.getArgumentTypes(call.desc).length; arg > 0; arg--) {
                            argument = previousReal(argument);
                            String namespace = literal(argument);
                            if (namespace != null) count(deferredRegisters, namespace);
                        }
                    } else {
                        String namespace = literalIdNamespace(call);
                        if (namespace != null) count(ids, namespace);
                    }
                }
            }
            return this;
        }

        /** The namespace, or null when the code names none or two equally. */
        public String namespace() {
            // The one a deferred register is created with wins over the ones literal ids use.
            return mostUsed(deferredRegisters.isEmpty() ? ids : deferredRegisters);
        }

        /** {@code DeferredRegister.create(...)} and its typed variants, from any loader or library. */
        private static boolean isDeferredRegisterFactory(MethodInsnNode call) {
            String simpleName = call.owner.substring(call.owner.lastIndexOf('/') + 1);
            return call.getOpcode() == Opcodes.INVOKESTATIC && call.name.startsWith("create")
                    && (simpleName.equals("DeferredRegister") || simpleName.startsWith("DeferredRegister$"));
        }

        /** The literal namespace of an id built from {@code ("ns", path)} or {@code "ns:path"}, or null. */
        private static String literalIdNamespace(MethodInsnNode call) {
            if (!isIdClass(call.owner)) return null;
            // new Identifier(...) or a static factory such as Identifier.fromNamespaceAndPath(...).
            boolean constructor = call.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(call.name);
            boolean factory = call.getOpcode() == Opcodes.INVOKESTATIC
                    && Type.getReturnType(call.desc).getDescriptor().equals("L" + call.owner + ";");
            if (!constructor && !factory) return null;
            String args = call.desc.substring(0, call.desc.indexOf(')') + 1);
            AbstractInsnNode last = previousReal(call);
            // One string: the namespace is before the colon of "ns:path".
            if (args.equals("(" + STRING + ")")) {
                String text = literalText(last);
                int colon = text == null ? -1 : text.indexOf(':');
                return colon > 0 ? accepted(text.substring(0, colon)) : null;
            }
            // Two strings: the path must be one instruction (a literal, a local, or a static field),
            // so the namespace literal sits right before it.
            if (!args.equals("(" + STRING + STRING + ")") || last == null) return null;
            int op = last.getOpcode();
            if (op != Opcodes.LDC && op != Opcodes.ALOAD && op != Opcodes.GETSTATIC) return null;
            return literal(previousReal(last));
        }

        private static String literal(AbstractInsnNode insn) {
            String text = literalText(insn);
            return text == null ? null : accepted(text);
        }

        private static String literalText(AbstractInsnNode insn) {
            return insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text ? text : null;
        }

        /** The namespace when it is valid and not a shared one, else null. */
        private static String accepted(String namespace) {
            return !SHARED.contains(namespace) && NAMESPACE.matcher(namespace).matches() ? namespace : null;
        }

        /** The instruction before {@code insn}, skipping labels and line numbers. */
        private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
            AbstractInsnNode previous = insn == null ? null : insn.getPrevious();
            while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
            return previous;
        }

        private static void count(Map<String, Integer> counts, String namespace) {
            counts.merge(namespace, 1, Integer::sum);
        }

        /** The most used namespace, or null on a tie: a wrong guess would misfile an entity. */
        private static String mostUsed(Map<String, Integer> counts) {
            String best = null;
            int bestCount = 0;
            boolean tied = false;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > bestCount) {
                    best = entry.getKey();
                    bestCount = entry.getValue();
                    tied = false;
                } else if (entry.getValue() == bestCount) {
                    tied = true;
                }
            }
            return tied ? null : best;
        }
    }
}
