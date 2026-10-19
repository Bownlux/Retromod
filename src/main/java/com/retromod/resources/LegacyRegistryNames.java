/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.objectweb.asm.ClassReader;
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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.Value;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Finds the registry names a 1.12 mod's classes set, and the translation key set on each object. */
final class LegacyRegistryNames {

    /** A registry name. The namespace is null when the mod gave only a path. */
    record Name(String namespace, String path) {}

    /** Which 1.12 key family a translation setter fed: {@code item.}, {@code tile.}, or either. */
    enum KeyFamily { ITEM, BLOCK, EITHER }

    // A 1.12 item or block chose its own translation key, and a potion its name. 1.13 derives the
    // key from the registry name and the 1.12 class bridge drops these setters, so a converted
    // language file needs to know which old key belongs to which registry name.
    private static final Set<String> REGISTRY_SETTERS = Set.of("setRegistryName");
    private static final Map<String, KeyFamily> TRANSLATION_SETTERS = Map.of(
            "func_77655_b", KeyFamily.ITEM, // Item.setTranslationKey
            "func_149663_c", KeyFamily.BLOCK, // Block.setTranslationKey
            "setTranslationKey", KeyFamily.EITHER,
            "setUnlocalizedName", KeyFamily.EITHER);
    private static final Set<String> POTION_NAME_SETTERS = Set.of("func_76390_b", "setPotionName");
    /** What the 1.12 builder setters on items, blocks and potions return: the object itself. */
    private static final Set<String> BUILDER_TYPES = Set.of("net/minecraft/item/Item",
            "net/minecraft/block/Block", "net/minecraft/potion/Potion");
    // How many rounds a value is followed up through callers, and the largest method analyzed.
    private static final int MAX_PROPAGATION_ROUNDS = 6;
    private static final int MAX_METHOD_INSTRUCTIONS = 60_000;

    private final Set<Name> registered = new LinkedHashSet<>();
    private final Map<KeyFamily, Map<String, Set<Name>>> translationKeys = new HashMap<>();
    private final Map<String, Set<Name>> potionNames = new LinkedHashMap<>();
    private final Map<String, Set<Name>> entityNames = new LinkedHashMap<>();

    private LegacyRegistryNames() {
        for (KeyFamily family : KeyFamily.values()) translationKeys.put(family, new LinkedHashMap<>());
    }

    /** Reads the given class files, skipping any that cannot be parsed. */
    static LegacyRegistryNames read(Collection<byte[]> classes) {
        LegacyRegistryNames names = new LegacyRegistryNames();
        // On the transform paths the classes have already lost their item, block and potion name
        // setters, so only the registry names and the entity pairs are found there.
        new Reader(names).read(classes);
        return names;
    }

    /** Every registry name the classes set from constants. */
    Set<Name> registered() {
        return registered;
    }

    /** The registry name set with this unlocalized name, or null when there is none or several. */
    Name translationKey(KeyFamily family, String unlocalizedName) {
        // The generic setters could be on an item or a block, so their keys count for both.
        Set<Name> names = new LinkedHashSet<>();
        names.addAll(translationKeys.get(family).getOrDefault(unlocalizedName, Set.of()));
        names.addAll(translationKeys.get(KeyFamily.EITHER).getOrDefault(unlocalizedName, Set.of()));
        return names.size() == 1 ? names.iterator().next() : null;
    }

    /** The registry name of the potion whose name is this key, or null. */
    Name potionName(String key) {
        Set<Name> names = potionNames.get(key);
        return names != null && names.size() == 1 ? names.iterator().next() : null;
    }

    /** The registry name of the entity registered under this 1.12 name, or null. */
    Name entityName(String entityName) {
        Set<Name> names = entityNames.get(entityName);
        return names != null && names.size() == 1 ? names.iterator().next() : null;
    }

    // ------------------------------------------------------------------ strings the analysis follows

    /** One part of a string: a constant, or a method argument, lowercased when the method did so. */
    record Piece(String constant, int argument, boolean lowercase) {
        static Piece of(String constant) {
            return new Piece(constant, -1, false);
        }

        static Piece argument(int index) {
            return new Piece(null, index, false);
        }

        // Registration helpers often lowercase the id they are given.
        Piece lowercased() {
            return constant != null ? of(constant.toLowerCase(java.util.Locale.ROOT))
                    : new Piece(null, argument, true);
        }
    }

    /** A string built from constants and arguments, in order. */
    record Text(List<Piece> pieces) {
        static Text constant(String value) {
            return new Text(List.of(Piece.of(value)));
        }

        /** The value when it is all constant, or null. */
        String constant() {
            StringBuilder value = new StringBuilder();
            for (Piece piece : pieces) {
                if (piece.constant() == null) return null;
                value.append(piece.constant());
            }
            return value.toString();
        }

        /** This text with each argument replaced by what a caller passed, or null when one is unknown. */
        Text substitute(List<Text> arguments) {
            List<Piece> result = new ArrayList<>();
            for (Piece piece : pieces) {
                if (piece.constant() != null) {
                    result.add(piece);
                    continue;
                }
                Text passed = piece.argument() < arguments.size() ? arguments.get(piece.argument()) : null;
                if (passed == null) return null;
                result.addAll(piece.lowercase() ? passed.lowercased().pieces() : passed.pieces());
            }
            return new Text(merged(result));
        }

        boolean usesArguments() {
            return pieces.stream().anyMatch(piece -> piece.constant() == null);
        }

        Text lowercased() {
            return new Text(pieces.stream().map(Piece::lowercased).toList());
        }

        /** Joins neighboring constants into one piece, so equal texts have equal pieces. */
        private static List<Piece> merged(List<Piece> pieces) {
            List<Piece> merged = new ArrayList<>();
            for (Piece piece : pieces) {
                int last = merged.size() - 1;
                if (piece.constant() != null && last >= 0 && merged.get(last).constant() != null) {
                    merged.set(last, Piece.of(merged.get(last).constant() + piece.constant()));
                } else {
                    merged.add(piece);
                }
            }
            return List.copyOf(merged);
        }
    }

    /** A registry name as an expression: an optional namespace and a path. */
    record NameText(Text namespace, Text path) {
        static NameText of(Text full) {
            // "ns:path" as one constant, or a constant "ns:" in front of an argument.
            if (full.pieces().isEmpty() || full.pieces().get(0).constant() == null) {
                return new NameText(null, full);
            }
            String head = full.pieces().get(0).constant();
            int colon = head.indexOf(':');
            if (colon < 0) return new NameText(null, full);
            // Everything after the colon, in the head and past it, is the path.
            List<Piece> rest = new ArrayList<>(full.pieces().subList(1, full.pieces().size()));
            if (colon + 1 < head.length()) rest.add(0, Piece.of(head.substring(colon + 1)));
            return new NameText(Text.constant(head.substring(0, colon)), new Text(Text.merged(rest)));
        }

        /** The name when it is all constant, or null. */
        Name constant() {
            String path = this.path.constant();
            if (path == null || path.isEmpty()) return null;
            if (namespace == null) return new Name(null, path);
            String namespace = this.namespace.constant();
            return namespace == null ? null : new Name(namespace, path);
        }

        /** This name with a caller's arguments filled in, or null when one is unknown. */
        NameText substitute(List<Text> arguments) {
            Text path = this.path.substitute(arguments);
            if (path == null) return null;
            // A filled-in argument may carry "ns:path", so a bare path is split again.
            if (namespace == null) return NameText.of(path);
            Text namespace = this.namespace.substitute(arguments);
            return namespace == null ? null : new NameText(namespace, path);
        }

        boolean usesArguments() {
            return path.usesArguments() || (namespace != null && namespace.usesArguments());
        }
    }

    /** What one method sets, in terms of its own arguments where the values come from a caller. */
    private record Template(List<NameText> registered, List<KeyPair> keys, List<KeyPair> potions,
                            List<KeyPair> entities) {
        boolean isEmpty() {
            return registered.isEmpty() && keys.isEmpty() && potions.isEmpty() && entities.isEmpty();
        }
    }

    /** A translation key, potion name or entity name, paired with a registry name. */
    private record KeyPair(KeyFamily family, Text key, NameText name) {}

    // ------------------------------------------------------------------ reading classes

    /** Reads classes and follows names and keys through the methods that set them. */
    private static final class Reader {
        private final LegacyRegistryNames out;
        private final Map<String, ClassNode> classes = new LinkedHashMap<>();
        private final Map<String, Template> templates = new HashMap<>();
        private final Map<String, Frame<Tracked>[]> frames = new HashMap<>();

        Reader(LegacyRegistryNames out) {
            this.out = out;
        }

        void read(Collection<byte[]> classBytes) {
            // Parse only the classes whose bytes name a known setter.
            List<byte[]> candidates = new ArrayList<>();
            for (byte[] bytes : classBytes) {
                if (bytes != null && mentionsSetter(bytes)) candidates.add(bytes);
            }
            for (byte[] bytes : candidates) parse(bytes);

            // A method that sets a name from its arguments changes what its callers set, so the
            // callers are analyzed again each round. A base class that passes one constructor
            // argument to both is followed this way to the constant each subclass or caller passes.
            Set<String> pending = new LinkedHashSet<>(classes.keySet());
            for (int round = 0; round < MAX_PROPAGATION_ROUNDS && !pending.isEmpty(); round++) {
                Set<String> changedOwners = new LinkedHashSet<>();
                for (String owner : pending) {
                    ClassNode node = classes.get(owner);
                    for (MethodNode method : node.methods) {
                        if (analyze(node, method)) changedOwners.add(owner);
                    }
                }
                pending = callersOf(changedOwners, classBytes);
            }
        }

        /** Classes that call a method of these owners, which may now pass constants to it. */
        private Set<String> callersOf(Set<String> owners, Collection<byte[]> classBytes) {
            if (owners.isEmpty()) return Set.of();
            Set<String> callers = new LinkedHashSet<>();
            for (byte[] bytes : classBytes) {
                if (bytes == null) continue;
                // A class whose bytes never name an owner cannot call it, so it is not parsed.
                String text = new String(bytes, StandardCharsets.ISO_8859_1);
                if (owners.stream().noneMatch(text::contains)) continue;
                ClassNode node = parse(bytes);
                if (node != null) callers.add(node.name);
            }
            return callers;
        }

        /** Parses a class, or returns the copy parsed earlier; null when the class is malformed. */
        private ClassNode parse(byte[] bytes) {
            try {
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                ClassNode known = classes.putIfAbsent(node.name, node);
                return known == null ? node : known;
            } catch (RuntimeException malformed) {
                return null;
            }
        }

        /** Analyzes one method, records what it sets, and says whether its template changed. */
        private boolean analyze(ClassNode owner, MethodNode method) {
            // Huge methods, and ones that call nothing known, are not worth the frame analysis.
            if (method.instructions.size() == 0 || method.instructions.size() > MAX_METHOD_INSTRUCTIONS
                    || !callsAnythingKnown(method)) {
                return false;
            }
            // The frames depend only on the bytecode, so each method is analyzed once and reused.
            String key = owner.name + "." + method.name + method.desc;
            Frame<Tracked>[] methodFrames = frames.get(key);
            if (methodFrames == null) {
                try {
                    methodFrames = new Analyzer<>(new TrackingInterpreter(method)).analyze(owner.name, method);
                } catch (AnalyzerException | RuntimeException unanalyzable) {
                    return false;
                }
                frames.put(key, methodFrames);
            }
            Template template = record(method, methodFrames);
            if (template.isEmpty()) return false;
            // A changed template means the method's callers need another look.
            Template previous = templates.put(key, template);
            return !template.equals(previous);
        }

        /** Whether the method calls a setter, or a method already known to set something. */
        private boolean callsAnythingKnown(MethodNode method) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && (isEvent(call)
                        || templates.containsKey(call.owner + "." + call.name + call.desc))) {
                    return true;
                }
            }
            return false;
        }

        /** Records what the method sets from constants, and returns what depends on its arguments. */
        private Template record(MethodNode method, Frame<Tracked>[] methodFrames) {
            // Collect each call the analysis understands, plus what a known callee sets with these arguments.
            List<Event> events = new ArrayList<>();
            int index = 0;
            for (AbstractInsnNode insn : method.instructions) {
                Frame<Tracked> frame = methodFrames[index++];
                if (frame == null || !(insn instanceof MethodInsnNode call)) continue;
                List<Tracked> arguments = arguments(call, frame);
                events.addAll(eventsOf(call, arguments));
                Template callee = templates.get(call.owner + "." + call.name + call.desc);
                if (callee != null) events.addAll(instantiate(callee, call, arguments));
            }

            // Sort the events into registry names, then pair each key, potion name and entity name.
            List<NameText> registered = new ArrayList<>();
            List<KeyPair> keys = new ArrayList<>();
            List<KeyPair> potions = new ArrayList<>();
            List<KeyPair> entities = new ArrayList<>();
            for (Event event : events) {
                if (event.kind() == EventKind.REGISTRY_NAME) registered.add(event.name());
                if (event.kind() == EventKind.ENTITY) {
                    entities.add(new KeyPair(KeyFamily.EITHER, event.text(), event.name()));
                }
            }
            pair(events, EventKind.TRANSLATION_KEY, keys);
            pair(events, EventKind.POTION_NAME, potions);
            pairEntityBuilders(events, entities);

            // Constant results are recorded now; the rest wait in the template for a caller's arguments.
            Template template = new Template(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    new ArrayList<>());
            for (NameText name : registered) {
                if (name.usesArguments()) template.registered().add(name);
                else addName(out.registered, name.constant());
            }
            for (KeyPair pair : keys) keep(pair, template.keys(), out.translationKeys.get(pair.family()));
            for (KeyPair pair : potions) keep(pair, template.potions(), out.potionNames);
            for (KeyPair pair : entities) keep(pair, template.entities(), out.entityNames);
            return template;
        }

        /** Records a constant pair, or keeps one that needs a caller's arguments in the template. */
        private static void keep(KeyPair pair, List<KeyPair> templated, Map<String, Set<Name>> constants) {
            if (pair.key().usesArguments() || pair.name().usesArguments()) {
                templated.add(pair);
                return;
            }
            String key = pair.key().constant();
            Name name = pair.name().constant();
            if (key != null && name != null) {
                constants.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(name);
            }
        }

        /** Pairs each key with the registry name set on the same object. */
        private static void pair(List<Event> events, EventKind keyKind, List<KeyPair> pairs) {
            // Group the names and keys by the object they were set on, when it can be followed.
            Map<Object, List<Event>> byObject = new LinkedHashMap<>();
            List<Event> unknownObject = new ArrayList<>();
            for (Event event : events) {
                if (event.kind() != keyKind && event.kind() != EventKind.REGISTRY_NAME) continue;
                if (event.object() == null) unknownObject.add(event);
                else byObject.computeIfAbsent(event.object(), k -> new ArrayList<>()).add(event);
            }
            // One name and one key on an object pair up, as in
            // new Item().setRegistryName("ruby").setTranslationKey("rubyGem").
            for (List<Event> onObject : byObject.values()) {
                KeyPair pair = single(onObject, keyKind);
                if (pair != null) pairs.add(pair);
            }
            // When no object could be followed, a method that sets exactly one of each pairs them.
            if (byObject.isEmpty()) {
                KeyPair pair = single(unknownObject, keyKind);
                if (pair != null) pairs.add(pair);
            }
        }

        /** The pair when the events hold exactly one name and one key, or null. */
        private static KeyPair single(List<Event> events, EventKind keyKind) {
            Set<NameText> names = new LinkedHashSet<>();
            Set<Text> keys = new LinkedHashSet<>();
            KeyFamily family = KeyFamily.EITHER;
            for (Event event : events) {
                if (event.kind() == EventKind.REGISTRY_NAME) {
                    names.add(event.name());
                } else if (event.kind() == keyKind && event.text() != null) {
                    keys.add(event.text());
                    if (event.family() != null) family = event.family();
                }
            }
            if (names.size() != 1 || keys.size() != 1) return null;
            return new KeyPair(family, keys.iterator().next(), names.iterator().next());
        }

        /** Pairs the id and the name given to one {@code EntityEntryBuilder} chain. */
        private static void pairEntityBuilders(List<Event> events, List<KeyPair> entities) {
            Map<Object, List<Event>> byBuilder = new LinkedHashMap<>();
            for (Event event : events) {
                // A call on a builder that cannot be followed stands alone, so it never joins another chain.
                if (event.kind() == EventKind.ENTITY_BUILDER_ID || event.kind() == EventKind.ENTITY_BUILDER_NAME) {
                    byBuilder.computeIfAbsent(event.object() == null ? event : event.object(),
                            k -> new ArrayList<>()).add(event);
                }
            }
            for (List<Event> chain : byBuilder.values()) {
                Set<NameText> ids = new LinkedHashSet<>();
                Set<Text> names = new LinkedHashSet<>();
                for (Event event : chain) {
                    if (event.kind() == EventKind.ENTITY_BUILDER_ID) ids.add(event.name());
                    else if (event.text() != null) names.add(event.text());
                }
                if (ids.size() == 1 && names.size() == 1) {
                    entities.add(new KeyPair(KeyFamily.EITHER, names.iterator().next(), ids.iterator().next()));
                }
            }
        }

        /** The events a templated callee produces for the arguments this call passes. */
        private static List<Event> instantiate(Template callee, MethodInsnNode call, List<Tracked> arguments) {
            // The callee's parameters follow the receiver, which is the object it sets things on.
            int skip = call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
            List<Text> passed = new ArrayList<>();
            for (int i = skip; i < arguments.size(); i++) passed.add(arguments.get(i).text);
            Object object = skip == 1 ? arguments.get(0).object : null;
            // Each templated name or pair becomes the events the callee makes with these arguments.
            List<Event> events = new ArrayList<>();
            for (NameText name : callee.registered()) {
                NameText filled = name.substitute(passed);
                if (filled != null) events.add(new Event(EventKind.REGISTRY_NAME, object, filled, null, null));
            }
            for (KeyPair pair : callee.keys()) {
                NameText name = pair.name().substitute(passed);
                Text key = pair.key().substitute(passed);
                if (name == null || key == null) continue;
                events.add(new Event(EventKind.REGISTRY_NAME, object, name, null, null));
                events.add(new Event(EventKind.TRANSLATION_KEY, object, null, key, pair.family()));
            }
            for (KeyPair pair : callee.potions()) {
                NameText name = pair.name().substitute(passed);
                Text key = pair.key().substitute(passed);
                if (name == null || key == null) continue;
                events.add(new Event(EventKind.REGISTRY_NAME, object, name, null, null));
                events.add(new Event(EventKind.POTION_NAME, object, null, key, null));
            }
            for (KeyPair pair : callee.entities()) {
                NameText name = pair.name().substitute(passed);
                Text key = pair.key().substitute(passed);
                if (name != null && key != null) events.add(new Event(EventKind.ENTITY, null, name, key, null));
            }
            return events;
        }

        /** The values a call takes off the stack, receiver first, or none when the stack is short. */
        private static List<Tracked> arguments(MethodInsnNode call, Frame<Tracked> frame) {
            int count = Type.getArgumentTypes(call.desc).length
                    + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
            List<Tracked> arguments = new ArrayList<>(count);
            int base = frame.getStackSize() - count;
            if (base < 0) return List.of();
            for (int i = 0; i < count; i++) arguments.add(frame.getStack(base + i));
            return arguments;
        }

        /** The events one call makes, if it is a setter or an entity registration the analysis knows. */
        private static List<Event> eventsOf(MethodInsnNode call, List<Tracked> arguments) {
            if (arguments.isEmpty()) return List.of();
            Type[] types = Type.getArgumentTypes(call.desc);
            boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
            Tracked last = arguments.get(arguments.size() - 1);
            Object receiver = isStatic ? null : arguments.get(0).object;
            if (REGISTRY_SETTERS.contains(call.name)) {
                // The 1.12 bridge turns the call into a static one that takes the entry first.
                if (isStatic && types.length >= 2) receiver = arguments.get(0).object;
                NameText name = registryName(arguments, types, isStatic);
                return name == null ? List.of()
                        : List.of(new Event(EventKind.REGISTRY_NAME, receiver, name, null, null));
            }
            // Translation key and potion name setters take the key last.
            KeyFamily family = TRANSLATION_SETTERS.get(call.name);
            if (family != null && last.text != null) {
                return List.of(new Event(EventKind.TRANSLATION_KEY, receiver, null, last.text, family));
            }
            if (POTION_NAME_SETTERS.contains(call.name) && last.text != null) {
                return List.of(new Event(EventKind.POTION_NAME, receiver, null, last.text, null));
            }
            // Entities pair the id and name given to registerModEntity, or to one EntityEntryBuilder.
            if (call.owner.endsWith("/EntityRegistry") && call.name.equals("registerModEntity")) {
                return entityRegistration(arguments, isStatic);
            }
            if (call.owner.endsWith("/EntityEntryBuilder") && !isStatic) {
                if (call.name.equals("id") && arguments.size() >= 2 && arguments.get(1).location != null) {
                    NameText id = arguments.get(1).location.name();
                    if (id != null) return List.of(new Event(EventKind.ENTITY_BUILDER_ID, receiver, id, null, null));
                }
                if (call.name.equals("name") && last.text != null) {
                    return List.of(new Event(EventKind.ENTITY_BUILDER_NAME, receiver, null, last.text, null));
                }
            }
            return List.of();
        }

        /** The name a setRegistryName call sets: a namespace and path, an identifier, or a string. */
        private static NameText registryName(List<Tracked> arguments, Type[] types, boolean isStatic) {
            int size = arguments.size();
            // (namespace, path) on the entry, or after the entry on the static 1.12 bridge.
            int pairStart = isStatic ? 3 : 2;
            if (types.length >= pairStart && isString(types[types.length - 1])
                    && isString(types[types.length - 2])) {
                Text namespace = arguments.get(size - 2).text;
                Text path = arguments.get(size - 1).text;
                return namespace == null || path == null ? null : new NameText(namespace, path);
            }
            // Otherwise the last argument is the whole name, as an identifier or a string.
            Tracked last = arguments.get(size - 1);
            if (last.location != null) return last.location.name();
            return last.text == null ? null : NameText.of(last.text);
        }

        /** Pairs the id and name of a {@code registerModEntity(id, Class, name, ...)} call. */
        private static List<Event> entityRegistration(List<Tracked> arguments, boolean isStatic) {
            // The first identifier is the id, and the first string after it is the 1.12 name.
            NameText id = null;
            for (int i = isStatic ? 0 : 1; i < arguments.size(); i++) {
                Tracked argument = arguments.get(i);
                if (id == null && argument.location != null) {
                    id = argument.location.name();
                    if (id == null) return List.of();
                } else if (id != null && argument.text != null) {
                    return List.of(new Event(EventKind.ENTITY, null, id, argument.text, null));
                }
            }
            return List.of();
        }

        /** Whether the call is one the analysis reads a name or key from. */
        private static boolean isEvent(MethodInsnNode call) {
            return REGISTRY_SETTERS.contains(call.name) || TRANSLATION_SETTERS.containsKey(call.name)
                    || POTION_NAME_SETTERS.contains(call.name)
                    || (call.owner.endsWith("/EntityRegistry") && call.name.equals("registerModEntity"))
                    || call.owner.endsWith("/EntityEntryBuilder");
        }

        /** Whether the class bytes name any setter, a cheap test before parsing. */
        private static boolean mentionsSetter(byte[] bytes) {
            // Latin-1 maps every byte to one char, so a name in the constant pool shows up as text.
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            if (text.contains("setRegistryName") || text.contains("registerModEntity")
                    || text.contains("EntityEntryBuilder")) {
                return true;
            }
            for (String setter : TRANSLATION_SETTERS.keySet()) if (text.contains(setter)) return true;
            for (String setter : POTION_NAME_SETTERS) if (text.contains(setter)) return true;
            return false;
        }
    }

    private static void addName(Set<Name> names, Name name) {
        if (name != null) names.add(name);
    }

    private static boolean isString(Type type) {
        return type.getSort() == Type.OBJECT && type.getInternalName().equals("java/lang/String");
    }

    /** What a call set: a registry name, a key, an entity pair, or one half of a builder's pair. */
    private enum EventKind {
        REGISTRY_NAME, TRANSLATION_KEY, POTION_NAME, ENTITY, ENTITY_BUILDER_ID, ENTITY_BUILDER_NAME
    }

    /** One call the analysis understood, on the object it was made on when that is known. */
    private record Event(EventKind kind, Object object, NameText name, Text text, KeyFamily family) {}

    // ------------------------------------------------------------------ the interpreter

    /** A string builder being filled, shared by every copy of the reference to it. */
    private static final class BuilderState {
        final List<Piece> pieces = new ArrayList<>();
        // Once anything unknown goes in, the whole string is unknown.
        boolean unknown;

        void append(Text text) {
            if (text == null) unknown = true;
            else pieces.addAll(text.pieces());
        }

        Text text() {
            return unknown ? null : new Text(Text.merged(pieces));
        }
    }

    /** An identifier being built, shared by every copy of the reference to it. */
    private static final class LocationState {
        Text namespace;
        Text path;
        Text full;

        /** The identifier as a registry name, or null when part of it is unknown. */
        NameText name() {
            if (full != null) {
                NameText name = NameText.of(full);
                // A bare path meant minecraft here, so it names nothing of the mod's.
                return name.namespace() == null ? null : name;
            }
            return namespace == null || path == null ? null : new NameText(namespace, path);
        }
    }

    /** A stack or local value: its type, plus the string, identifier, builder or object it is, when known. */
    private static final class Tracked implements Value {
        final BasicValue basic;
        final Text text;
        final Integer intConstant;
        final BuilderState builder;
        final LocationState location;
        final Object object;

        Tracked(BasicValue basic, Text text, Integer intConstant, BuilderState builder,
                LocationState location, Object object) {
            this.basic = basic;
            this.text = text;
            this.intConstant = intConstant;
            this.builder = builder;
            this.location = location;
            this.object = object;
        }

        static Tracked of(BasicValue basic) {
            return basic == null ? null : new Tracked(basic, null, null, null, null, null);
        }

        Tracked withText(Text text) {
            return new Tracked(basic, text, null, null, null, null);
        }

        Tracked retyped(BasicValue basic) {
            return new Tracked(basic, text, intConstant, builder, location, object);
        }

        @Override
        public int getSize() {
            return basic.getSize();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Tracked that && basic.equals(that.basic) && Objects.equals(text, that.text)
                    && Objects.equals(intConstant, that.intConstant) && builder == that.builder
                    && location == that.location && object == that.object;
        }

        @Override
        public int hashCode() {
            return Objects.hash(basic, text, intConstant);
        }
    }

    /** Follows constant strings and parameters through a method; anything else stays unknown. */
    private static final class TrackingInterpreter extends Interpreter<Tracked> {
        private final BasicInterpreter basic = new BasicInterpreter();
        private final Map<Integer, Integer> argumentBySlot = new HashMap<>();

        TrackingInterpreter(MethodNode method) {
            super(Opcodes.ASM9);
            // Map each parameter's local slot to its argument index; long and double take two slots.
            int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
            Type[] arguments = Type.getArgumentTypes(method.desc);
            for (int i = 0; i < arguments.length; i++) {
                argumentBySlot.put(slot, i);
                slot += arguments[i].getSize();
            }
        }

        @Override
        public Tracked newValue(Type type) {
            return Tracked.of(basic.newValue(type));
        }

        @Override
        public Tracked newParameterValue(boolean isInstanceMethod, int local, Type type) {
            Tracked value = newValue(type);
            if (value == null || type.getSort() != Type.OBJECT) return value;
            // A string parameter is whatever a caller passes; any other object is one object of its own.
            Integer argument = argumentBySlot.get(local);
            if (argument != null && isString(type)) return value.withText(new Text(List.of(Piece.argument(argument))));
            return new Tracked(value.basic, null, null, null, null, new Object());
        }

        @Override
        public Tracked newOperation(AbstractInsnNode insn) throws AnalyzerException {
            BasicValue type = basic.newOperation(insn);
            switch (insn.getOpcode()) {
                // String and int constants are kept; ints can end up in a concatenated name.
                case Opcodes.LDC -> {
                    Object constant = ((LdcInsnNode) insn).cst;
                    if (constant instanceof String string) return Tracked.of(type).withText(Text.constant(string));
                    if (constant instanceof Integer number) {
                        return new Tracked(type, null, number, null, null, null);
                    }
                }
                case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                        Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 -> {
                    return new Tracked(type, null, insn.getOpcode() - Opcodes.ICONST_0, null, null, null);
                }
                case Opcodes.BIPUSH, Opcodes.SIPUSH -> {
                    return new Tracked(type, null, ((org.objectweb.asm.tree.IntInsnNode) insn).operand,
                            null, null, null);
                }
                case Opcodes.GETSTATIC -> {
                    return fieldValue(type, (FieldInsnNode) insn);
                }
                case Opcodes.NEW -> {
                    // The instruction is the object's identity, so a loop that runs it again
                    // reaches the same value and the analysis settles.
                    String created = ((TypeInsnNode) insn).desc;
                    if (isStringBuilder(created)) {
                        return new Tracked(type, null, null, new BuilderState(), null, insn);
                    }
                    if (isLocation(created)) {
                        return new Tracked(type, null, null, null, new LocationState(), insn);
                    }
                    return new Tracked(type, null, null, null, null, insn);
                }
                default -> {
                }
            }
            return Tracked.of(type);
        }

        @Override
        public Tracked copyOperation(AbstractInsnNode insn, Tracked value) {
            return value;
        }

        @Override
        public Tracked unaryOperation(AbstractInsnNode insn, Tracked value) throws AnalyzerException {
            BasicValue type = basic.unaryOperation(insn, value.basic);
            // A cast keeps what the value carries, and a field read stands for the object in the field.
            if (insn.getOpcode() == Opcodes.CHECKCAST && type != null) return value.retyped(type);
            if (insn.getOpcode() == Opcodes.GETFIELD) return fieldValue(type, (FieldInsnNode) insn);
            return Tracked.of(type);
        }

        /** A field holding an object stands for it, so two reads of {@code Items.RUBY} are one item. */
        private static Tracked fieldValue(BasicValue type, FieldInsnNode field) {
            // A string field is not followed, so a name read from one stays unknown and its key unpaired.
            if (type == null || Type.getType(field.desc).getSort() != Type.OBJECT || isString(Type.getType(field.desc))) {
                return Tracked.of(type);
            }
            // The interned key keeps the identity the same across reads.
            Object object = (field.owner + "." + field.name).intern();
            return new Tracked(type, null, null, null, null, object);
        }

        @Override
        public Tracked binaryOperation(AbstractInsnNode insn, Tracked first, Tracked second)
                throws AnalyzerException {
            return Tracked.of(basic.binaryOperation(insn, first.basic, second.basic));
        }

        @Override
        public Tracked ternaryOperation(AbstractInsnNode insn, Tracked first, Tracked second, Tracked third)
                throws AnalyzerException {
            return Tracked.of(basic.ternaryOperation(insn, first.basic, second.basic, third.basic));
        }

        @Override
        public Tracked naryOperation(AbstractInsnNode insn, List<? extends Tracked> values)
                throws AnalyzerException {
            List<BasicValue> types = new ArrayList<>(values.size());
            for (Tracked value : values) types.add(value.basic);
            Tracked result = Tracked.of(basic.naryOperation(insn, types));
            // String concatenation compiled to invokedynamic.
            if (insn instanceof InvokeDynamicInsnNode dynamic) return concatenation(dynamic, values, result);
            if (!(insn instanceof MethodInsnNode call)) return result;

            boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
            Tracked receiver = isStatic || values.isEmpty() ? null : values.get(0);
            Type[] arguments = Type.getArgumentTypes(call.desc);
            if (isStringBuilder(call.owner) && receiver != null) {
                return builderCall(call, receiver, values, arguments, result);
            }
            // String.valueOf, toLowerCase and concat keep the text they are given.
            if (call.owner.equals("java/lang/String") && result != null) {
                if (call.name.equals("valueOf") && arguments.length == 1 && values.get(0).text != null) {
                    return result.withText(values.get(0).text);
                }
                if (call.name.equals("toLowerCase") && receiver != null && receiver.text != null) {
                    return result.withText(receiver.text.lowercased());
                }
                if (call.name.equals("concat") && receiver != null && receiver.text != null
                        && values.get(1).text != null) {
                    List<Piece> joined = new ArrayList<>(receiver.text.pieces());
                    joined.addAll(values.get(1).text.pieces());
                    return result.withText(new Text(Text.merged(joined)));
                }
            }
            // An identifier made by its constructor or a static factory records its namespace and path.
            if (isLocation(call.owner)) {
                if (call.name.equals("<init>") && receiver != null && receiver.location != null) {
                    fill(receiver.location, values.subList(1, values.size()), arguments);
                    return result;
                }
                // A static factory taking strings counts too, except withDefaultNamespace, which
                // always means minecraft and so names nothing of the mod's.
                Type returned = Type.getReturnType(call.desc);
                if (isStatic && result != null && returned.getSort() == Type.OBJECT
                        && isLocation(returned.getInternalName()) && allStrings(arguments)
                        && !call.name.equals("withDefaultNamespace")) {
                    LocationState location = new LocationState();
                    fill(location, values, arguments);
                    return new Tracked(result.basic, null, null, null, location, insn);
                }
            }
            // A setter chain stays on the object it started on.
            if (result != null && returnsReceiver(call, receiver, values)) {
                Object object = receiver != null ? receiver.object : values.get(0).object;
                return new Tracked(result.basic, null, null, null, null, object);
            }
            Type returned = Type.getReturnType(call.desc);
            if (result != null && returned.getSort() == Type.OBJECT && !isString(returned)) {
                // A factory such as EntityEntryBuilder.create() hands back an object of its own.
                return new Tracked(result.basic, null, null, null, null, insn);
            }
            return result;
        }

        /** Follows a StringBuilder call: the constructor and append add text, toString reads it. */
        private static Tracked builderCall(MethodInsnNode call, Tracked receiver, List<? extends Tracked> values,
                                           Type[] arguments, Tracked result) {
            BuilderState builder = receiver.builder;
            if (builder == null) return result;
            switch (call.name) {
                case "<init>" -> {
                    if (arguments.length == 1) builder.append(textOf(values.get(1), arguments[0]));
                    return result;
                }
                case "append" -> {
                    builder.append(arguments.length == 1 ? textOf(values.get(1), arguments[0]) : null);
                    return receiver;
                }
                case "toString" -> {
                    return result == null ? null : result.withText(builder.text());
                }
                default -> {
                    // Any other call, such as insert or reverse, makes the text unknown.
                    builder.unknown = true;
                    return result;
                }
            }
        }

        /** Follows {@code makeConcatWithConstants}, whose recipe marks arguments and constants in order. */
        private static Tracked concatenation(InvokeDynamicInsnNode dynamic, List<? extends Tracked> values,
                                             Tracked result) {
            if (result == null || !dynamic.name.equals("makeConcatWithConstants")
                    || dynamic.bsmArgs.length == 0 || !(dynamic.bsmArgs[0] instanceof String recipe)) {
                return result;
            }
            Type[] arguments = Type.getArgumentTypes(dynamic.desc);
            List<Piece> pieces = new ArrayList<>();
            // The recipe is the first bootstrap argument, so the constants it marks start at index 1.
            int argument = 0;
            int constant = 1;
            StringBuilder literal = new StringBuilder();
            // Char 1 marks the next argument and char 2 the next constant; anything else is literal
            // text. One unknown part makes the whole string unknown.
            for (char c : recipe.toCharArray()) {
                if (c == '\u0001' || c == '\u0002') {
                    if (literal.length() > 0) {
                        pieces.add(Piece.of(literal.toString()));
                        literal.setLength(0);
                    }
                    Text text = null;
                    if (c == '\u0001' && argument < values.size()) {
                        text = textOf(values.get(argument), arguments[argument]);
                        argument++;
                    } else if (c == '\u0002' && constant < dynamic.bsmArgs.length) {
                        text = Text.constant(String.valueOf(dynamic.bsmArgs[constant++]));
                    }
                    if (text == null) return result;
                    pieces.addAll(text.pieces());
                } else {
                    literal.append(c);
                }
            }
            if (literal.length() > 0) pieces.add(Piece.of(literal.toString()));
            return result.withText(new Text(Text.merged(pieces)));
        }

        /** The text a value contributes to a string, or null when it is not known. */
        private static Text textOf(Tracked value, Type type) {
            if (value.text != null) return value.text;
            if (value.intConstant == null) return null;
            // A known int prints as its digits, or as its character when the type is char.
            return switch (type.getSort()) {
                case Type.CHAR -> Text.constant(String.valueOf((char) value.intConstant.intValue()));
                case Type.INT, Type.SHORT, Type.BYTE -> Text.constant(String.valueOf(value.intConstant));
                default -> null;
            };
        }

        /** Records an identifier's text: one "ns:path" string, or a namespace and a path. */
        private static void fill(LocationState location, List<? extends Tracked> values, Type[] arguments) {
            if (arguments.length == 1 && isString(arguments[0])) {
                location.full = values.get(0).text;
            } else if (arguments.length == 2 && isString(arguments[0]) && isString(arguments[1])) {
                location.namespace = values.get(0).text;
                location.path = values.get(1).text;
            }
        }

        /** Whether the call hands back the object it was made on, as the 1.12 builder setters do. */
        private static boolean returnsReceiver(MethodInsnNode call, Tracked receiver,
                                               List<? extends Tracked> values) {
            // Strings and identifiers are values, never the object a chain is built on.
            Type returned = Type.getReturnType(call.desc);
            if (returned.getSort() != Type.OBJECT || isString(returned) || isLocation(returned.getInternalName())) {
                return false;
            }
            boolean setter = REGISTRY_SETTERS.contains(call.name) || TRANSLATION_SETTERS.containsKey(call.name)
                    || POTION_NAME_SETTERS.contains(call.name);
            // The 1.12 bridge calls are static and take the entry first.
            if (receiver == null) return setter && !values.isEmpty() && values.get(0).object != null;
            if (receiver.object == null) return false;
            // A setter, a builder method, a method returning its own class, or an SRG-named one
            // returning Item, Block or Potion keeps the chain on one object.
            return setter || call.owner.endsWith("/EntityEntryBuilder")
                    || returned.getInternalName().equals(call.owner)
                    || (call.name.startsWith("func_") && BUILDER_TYPES.contains(returned.getInternalName()));
        }

        @Override
        public void returnOperation(AbstractInsnNode insn, Tracked value, Tracked expected) {
        }

        @Override
        public Tracked merge(Tracked first, Tracked second) {
            // Where two paths meet, only what both agree on is kept. A name that differs between
            // them, as in a loop over an array of names, becomes unknown and its key stays unpaired.
            if (first.equals(second)) return first;
            BasicValue type = basic.merge(first.basic, second.basic);
            return new Tracked(type,
                    Objects.equals(first.text, second.text) ? first.text : null,
                    Objects.equals(first.intConstant, second.intConstant) ? first.intConstant : null,
                    first.builder == second.builder ? first.builder : null,
                    first.location == second.location ? first.location : null,
                    first.object == second.object ? first.object : null);
        }

        /** Whether a factory takes one or two strings, as the identifier factories do. */
        private static boolean allStrings(Type[] arguments) {
            if (arguments.length == 0 || arguments.length > 2) return false;
            for (Type argument : arguments) if (!isString(argument)) return false;
            return true;
        }
    }

    private static boolean isStringBuilder(String internalName) {
        return internalName.equals("java/lang/StringBuilder") || internalName.equals("java/lang/StringBuffer");
    }

    /** The identifier class under each name it had: 1.12 and later Mojang names, and 26.x's. */
    private static boolean isLocation(String internalName) {
        return internalName.endsWith("/ResourceLocation") || internalName.equals("net/minecraft/resources/Identifier");
    }

}
