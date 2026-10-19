/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Finds the armor textures an old Forge mod chose in code, for 1.21.2's equipment assets. */
final class ArmorTextureOverrides {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");

    // The two layer types an equipment asset lists textures under.
    static final String HUMANOID = "humanoid";
    static final String LEGGINGS = "humanoid_leggings";

    /** How far a material is followed back through constructors that pass it on. */
    private static final int MAX_FORWARDING = 3;
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    /** 1.12's inner layer names: {@code ruby_2}, {@code rubylayer2}, {@code ruby_2_inner}, {@code ruby_legs}. */
    private static final Pattern INNER_LAYER = Pattern.compile(".*(?:2|_legs|leggings)(?:_[a-z]+)?");

    private ArmorTextureOverrides() {}

    /** The layers to add, by asset id: each layer type to the jar entry of its texture. */
    static Map<String, Map<String, String>> scan(Collection<String> classNames, Function<String, byte[]> read) {
        // Forge let an armor item override getArmorTexture(stack, entity, slot, type) and return any
        // texture instead of its material's textures/models/armor/<name>_layer_1.png. Nothing calls it
        // on 1.21.2 and newer, which draw worn armor from the equipment asset its material names.
        Map<String, String> materials = new HashMap<>();
        List<ClassFlow> overriding = new ArrayList<>();
        Set<String> ownModels = new TreeSet<>();
        List<String> searchable = new ArrayList<>();
        // Parse only the classes whose bytes mention a material or a texture override.
        for (String name : classNames) {
            if (!name.endsWith(".class") || ClassFlow.isRetromodClass(name)) continue;
            searchable.add(name);
            byte[] bytes = read.apply(name);
            if (bytes == null) continue;
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            boolean definesMaterials = text.contains("addArmorMaterial");
            boolean overridesTexture = text.contains("getArmorTexture");
            if (!definesMaterials && !overridesTexture) continue;
            ClassFlow flow = ClassFlow.parse(bytes);
            if (flow == null) continue;
            if (definesMaterials) collectMaterials(flow, materials);
            if (overridesTexture && armorTextureMethod(flow) != null) {
                // A texture painted for the item's own model would be wrapped around the
                // humanoid one, which has a different layout, so that armor is left out.
                if (drawsOwnModel(flow)) {
                    ownModels.add(flow.node.name);
                } else {
                    overriding.add(flow);
                }
            }
        }
        if (!ownModels.isEmpty()) {
            LOGGER.info("Left out {} armor class(es) drawn with their own model, whose textures do not fit "
                    + "the humanoid armor layout: {}", ownModels.size(), ownModels);
        }
        if (overriding.isEmpty()) return Map.of();

        // Each texture goes to the asset of the material the item is built with. A 1.12 material's
        // texture name is the name Retromod's 1.12 armor material reports, so it is the asset id a
        // material bridge on a newer host would derive from it.
        Map<String, Map<String, String>> assets = new TreeMap<>();
        Set<String> conflicts = new TreeSet<>();
        Set<String> unresolved = new TreeSet<>();
        Map<String, Set<String>> materialsByClass = materialsOf(overriding, materials, searchable, read);
        for (ClassFlow flow : overriding) {
            // A texture built at run time, or a material that is not a constant or comes from
            // another mod, leaves the class out and reported.
            Map<String, String> layers = textures(flow);
            Set<String> used = materialsByClass.getOrDefault(flow.node.name, Set.of());
            if (layers.isEmpty() || used.isEmpty()) {
                unresolved.add(flow.node.name);
                continue;
            }
            for (String field : used) {
                String asset = assetId(materials.get(field));
                if (asset == null) continue;
                Map<String, String> existing = assets.computeIfAbsent(asset, key -> new LinkedHashMap<>());
                for (Map.Entry<String, String> layer : layers.entrySet()) {
                    String previous = existing.putIfAbsent(layer.getKey(), layer.getValue());
                    if (previous != null && !previous.equals(layer.getValue())) conflicts.add(asset);
                }
            }
        }
        // An asset that two items give different textures for is left out.
        conflicts.forEach(assets::remove);
        if (!unresolved.isEmpty() || !conflicts.isEmpty()) {
            LOGGER.info("Could not derive equipment assets for {} armor class(es) whose getArmorTexture "
                    + "texture or armor material is not a constant{}: {}", unresolved.size(),
                    conflicts.isEmpty() ? "" : ", and skipped " + conflicts + " whose items disagree on a texture",
                    unresolved);
        }
        // No host draws these assets yet. 1.12 armor is armor only through the 1.21 and 1.21.1
        // material bridge, on hosts without equipment assets. From 1.21.5 the item is not built
        // as armor at all, so the assets wait for a 1.12 armor bridge there.
        return assets;
    }

    /** {@code EnumHelper.addArmorMaterial(name, textureName, ...)} results stored in static fields. */
    private static void collectMaterials(ClassFlow flow, Map<String, String> materials) {
        for (MethodNode method : flow.node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode call) || !call.name.equals("addArmorMaterial")
                        || Type.getArgumentTypes(call.desc).length < 2) {
                    continue;
                }
                // Only a result stored straight into a static field, maybe after a cast, counts.
                String name = ClassFlow.stringConstant(flow.argument(method, call, 0));
                String texture = ClassFlow.stringConstant(flow.argument(method, call, 1));
                AbstractInsnNode next = nextReal(call);
                if (next instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) next = nextReal(cast);
                if (!(next instanceof FieldInsnNode store) || store.getOpcode() != Opcodes.PUTSTATIC) continue;
                // 1.12 drew the armor from the texture name, or the material name without one.
                String asset = texture != null ? texture : name;
                if (asset != null) materials.put(store.owner + "." + store.name, asset.toLowerCase(Locale.ROOT));
            }
        }
    }

    /** The class's own {@code getArmorTexture} that returns a texture as a string, or null. */
    private static MethodNode armorTextureMethod(ClassFlow flow) {
        for (MethodNode method : flow.node.methods) {
            if (method.name.equals("getArmorTexture")
                    && Type.getReturnType(method.desc).getDescriptor().equals("Ljava/lang/String;")
                    && (method.access & Opcodes.ACC_ABSTRACT) == 0) {
                return method;
            }
        }
        return null;
    }

    /** Whether the class supplies its own armor model, as Forge's {@code getArmorModel} let it. */
    private static boolean drawsOwnModel(ClassFlow flow) {
        for (MethodNode method : flow.node.methods) {
            if (method.name.equals("getArmorModel") && (method.access & Opcodes.ACC_ABSTRACT) == 0
                    && (method.access & Opcodes.ACC_BRIDGE) == 0) {
                return true;
            }
        }
        return false;
    }

    /** Layer type to the jar entry of the texture the method returns for it. */
    private static Map<String, String> textures(ClassFlow flow) {
        MethodNode method = armorTextureMethod(flow);
        Map<String, String> layers = new LinkedHashMap<>();
        Frame<SourceValue>[] frames = flow.frames(method);
        if (frames == null) return layers;
        // Follow the value at each return back to the constants it can be, and sort each one
        // into its layer.
        JumpInsnNode legsTest = legsTest(method);
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() != Opcodes.ARETURN) continue;
            Frame<SourceValue> frame = frames[method.instructions.indexOf(insn)];
            if (frame == null) continue;
            List<Candidate> returned = candidates(flow, method, frame.getStack(frame.getStackSize() - 1), 0);
            if (returned == null) return Map.of(); // built at run time: the item's texture is not knowable here
            for (Candidate candidate : returned) {
                if (candidate.text() == null) continue; // null asked for the default texture
                String entry = textureEntry(candidate.text());
                if (entry == null) return Map.of();
                String previous = layers.putIfAbsent(layerOf(method, legsTest, candidate.marker(), entry), entry);
                // One asset holds one texture per layer, so pieces drawn from different files of
                // the same layer cannot share it.
                if (previous != null && !previous.equals(entry)) return Map.of();
            }
        }
        return layers;
    }

    // A string the method can produce, and the instruction whose place in the method decides
    // which slot it is for: the constant itself, or the one varying part of a concatenation.
    private record Candidate(String text, AbstractInsnNode marker) {}

    /** How far a value is followed through locals and concatenations before it counts as computed. */
    private static final int MAX_VALUE_DEPTH = 4;
    /** More strings than this from one value is not a choice between armor layers. */
    private static final int MAX_CANDIDATES = 4;

    /** Every string a value can hold, or null when any of them is computed at run time. */
    private static List<Candidate> candidates(ClassFlow flow, MethodNode method, SourceValue value, int depth) {
        if (value == null || value.insns.isEmpty() || depth > MAX_VALUE_DEPTH) return null;
        List<Candidate> all = new ArrayList<>();
        for (AbstractInsnNode producer : value.insns) {
            List<Candidate> some = candidatesOf(flow, method, producer, depth);
            if (some == null) return null;
            all.addAll(some);
        }
        return all.size() > MAX_CANDIDATES ? null : all;
    }

    /** The strings one instruction can put on the stack, or null when it computes one. */
    private static List<Candidate> candidatesOf(ClassFlow flow, MethodNode method, AbstractInsnNode producer,
                                                int depth) {
        // A null, a string, or an int constant counts as it is.
        if (producer.getOpcode() == Opcodes.ACONST_NULL) return List.of(new Candidate(null, producer));
        if (producer instanceof LdcInsnNode ldc && (ldc.cst instanceof String || ldc.cst instanceof Integer)) {
            return List.of(new Candidate(String.valueOf(ldc.cst), producer));
        }
        Integer small = ClassFlow.intConstant(new SourceValue(1, producer));
        if (small != null) return List.of(new Candidate(String.valueOf(small), producer));
        // A local assigned only constants, and String.valueOf of one, are followed back.
        if (producer instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD
                && load.getOpcode() <= Opcodes.ALOAD) {
            return candidates(flow, method, flow.storedValue(method, load), depth + 1);
        }
        if (producer instanceof MethodInsnNode call && call.owner.equals("java/lang/String")
                && call.name.equals("valueOf") && Type.getArgumentTypes(call.desc).length == 1) {
            return candidates(flow, method, flow.argument(method, call, 0), depth + 1);
        }
        // A StringBuilder concatenation of constants is how 1.12 mods wrote
        // "mod:textures/armor/ruby_" + (legs ? 2 : 1) + ".png".
        if (producer instanceof MethodInsnNode call && call.owner.equals(STRING_BUILDER)
                && call.name.equals("toString")) {
            return concatenation(flow, method, call, depth + 1);
        }
        return null;
    }

    private static final String STRING_BUILDER = "java/lang/StringBuilder";

    /** The strings a {@code StringBuilder} chain ending in {@code toString} builds, or null. */
    private static List<Candidate> concatenation(ClassFlow flow, MethodNode method, MethodInsnNode toString,
                                                 int depth) {
        // Walk back from toString through each append, collecting the parts in order.
        List<List<Candidate>> parts = new ArrayList<>();
        SourceValue builder = flow.receiver(method, toString);
        for (int length = 0; length < 32; length++) {
            AbstractInsnNode producer = builder == null || builder.insns.size() != 1 ? null
                    : builder.insns.iterator().next();
            if (producer instanceof MethodInsnNode append && append.owner.equals(STRING_BUILDER)
                    && append.name.equals("append") && Type.getArgumentTypes(append.desc).length == 1) {
                List<Candidate> part = candidates(flow, method, flow.argument(method, append, 0), depth);
                if (part == null) return null;
                if (Type.getArgumentTypes(append.desc)[0] == Type.CHAR_TYPE) part = asCharacters(part);
                parts.add(0, part);
                builder = flow.receiver(method, append);
            } else if (createdBuilder(producer) != null) {
                // The chain starts at new StringBuilder. A constructor argument is the first part.
                MethodInsnNode init = constructorOf(flow, method, createdBuilder(producer));
                if (init == null) return null;
                Type[] arguments = Type.getArgumentTypes(init.desc);
                if (arguments.length == 1 && arguments[0].getSort() == Type.OBJECT) {
                    List<Candidate> first = candidates(flow, method, flow.argument(method, init, 0), depth);
                    if (first == null) return null;
                    parts.add(0, first);
                }
                return join(parts, toString);
            } else {
                return null;
            }
        }
        return null;
    }

    /** Every combination of the parts, when at most one part has more than one value. */
    private static List<Candidate> join(List<List<Candidate>> parts, AbstractInsnNode whole) {
        // Only one part may vary, as a slot test picks one of two layer numbers. The strings it
        // gives are marked by the constant that varied.
        List<Candidate> joined = List.of(new Candidate("", whole));
        boolean varied = false;
        for (List<Candidate> part : parts) {
            if (part.size() > 1) {
                if (varied) return null;
                varied = true;
            }
            List<Candidate> next = new ArrayList<>();
            for (Candidate prefix : joined) {
                for (Candidate piece : part) {
                    if (piece.text() == null) return null;
                    next.add(new Candidate(prefix.text() + piece.text(), part.size() > 1 ? piece.marker() : prefix.marker()));
                }
            }
            joined = next;
        }
        return joined;
    }

    /** The characters a {@code char} append adds, whose constants arrive as numbers. */
    private static List<Candidate> asCharacters(List<Candidate> numbers) {
        List<Candidate> characters = new ArrayList<>();
        for (Candidate number : numbers) {
            try {
                characters.add(new Candidate(String.valueOf((char) Integer.parseInt(number.text())), number.marker()));
            } catch (NumberFormatException notACharacter) {
                characters.add(number);
            }
        }
        return characters;
    }

    /** The {@code NEW StringBuilder} a value comes from, or null. */
    private static TypeInsnNode createdBuilder(AbstractInsnNode producer) {
        // The analyzer reports the builder either as that instruction or as the DUP right after
        // it, depending on how ASM tracks the constructor call, so both count.
        AbstractInsnNode created = producer != null && producer.getOpcode() == Opcodes.DUP
                ? previousReal(producer) : producer;
        return created instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                && type.desc.equals(STRING_BUILDER) ? type : null;
    }

    /** The {@code <init>} call on the builder {@code created} makes, which follows it through a {@code DUP}. */
    private static MethodInsnNode constructorOf(ClassFlow flow, MethodNode method, TypeInsnNode created) {
        AbstractInsnNode dup = nextReal(created);
        if (dup == null || dup.getOpcode() != Opcodes.DUP) return null;
        for (AbstractInsnNode insn = dup.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode init && init.getOpcode() == Opcodes.INVOKESPECIAL
                    && init.owner.equals(STRING_BUILDER) && init.name.equals("<init>")) {
                SourceValue receiver = flow.receiver(method, init);
                if (receiver != null && receiver.insns.size() == 1 && receiver.insns.contains(dup)) return init;
            }
        }
        return null;
    }

    /** The first branch on a comparison with a {@code LEGS} constant, or null. */
    private static JumpInsnNode legsTest(MethodNode method) {
        // As in slot == EquipmentSlot.LEGS ? inner : outer.
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && field.name.equals("LEGS") && nextReal(field) instanceof JumpInsnNode jump
                    && (jump.getOpcode() == Opcodes.IF_ACMPEQ || jump.getOpcode() == Opcodes.IF_ACMPNE)) {
                return jump;
            }
        }
        return null;
    }

    /** The layer a returned texture belongs to. */
    private static String layerOf(MethodNode method, JumpInsnNode legsTest, AbstractInsnNode texture, String entry) {
        // Behind a LEGS test the branch decides. Code after the jump target runs when the test
        // jumped.
        if (legsTest != null) {
            int test = method.instructions.indexOf(legsTest);
            int target = method.instructions.indexOf(legsTest.label);
            int at = method.instructions.indexOf(texture);
            if (at > test && target > test) {
                boolean jumped = at >= target;
                boolean legs = legsTest.getOpcode() == Opcodes.IF_ACMPEQ ? jumped : !jumped;
                return legs ? LEGGINGS : HUMANOID;
            }
        }
        // Without a slot test, a name that marks the inner layer with a 2, as 1.12 named it,
        // goes to the leggings layer.
        String file = entry.substring(entry.lastIndexOf('/') + 1, entry.length() - ".png".length());
        return INNER_LAYER.matcher(file).matches() ? LEGGINGS : HUMANOID;
    }

    /** The material fields each overriding class is built with. */
    private static Map<String, Set<String>> materialsOf(List<ClassFlow> overriding, Map<String, String> materials,
                                                        List<String> classNames, Function<String, byte[]> read) {
        Map<String, Set<String>> result = new HashMap<>();
        if (materials.isEmpty()) return result; // nothing a constructor could pass would count
        // A material constant the constructor passes to its superclass counts. A parameter it
        // passes on is followed to the callers of that constructor, so pending maps constructor
        // owner + descriptor to the parameters to follow, and servedClass to the class they serve.
        Map<String, Set<Integer>> pending = new HashMap<>();
        Map<String, String> servedClass = new HashMap<>();
        for (ClassFlow flow : overriding) {
            for (MethodNode constructor : flow.node.methods) {
                if (!constructor.name.equals("<init>")) continue;
                MethodInsnNode superCall = superCall(flow.node.superName, constructor);
                if (superCall == null) continue;
                int arguments = Type.getArgumentTypes(superCall.desc).length;
                for (int index = 0; index < arguments; index++) {
                    SourceValue value = flow.argument(constructor, superCall, index);
                    String field = ClassFlow.staticField(value);
                    if (field != null && materials.containsKey(field)) {
                        result.computeIfAbsent(flow.node.name, key -> new LinkedHashSet<>()).add(field);
                    }
                    int parameter = ClassFlow.parameter(constructor, value);
                    if (parameter >= 0) {
                        String key = flow.node.name + "." + constructor.desc;
                        pending.computeIfAbsent(key, k -> new HashSet<>()).add(parameter);
                        servedClass.put(key, flow.node.name);
                    }
                }
            }
        }
        // Look at what every caller passes, including subclasses calling super, up to
        // MAX_FORWARDING constructors back.
        for (int depth = 0; depth < MAX_FORWARDING && !pending.isEmpty(); depth++) {
            Map<String, Set<Integer>> next = new HashMap<>();
            Set<String> owners = new HashSet<>();
            for (String key : pending.keySet()) owners.add(key.substring(0, key.indexOf('.')));
            for (String name : classNames) {
                byte[] bytes = read.apply(name);
                if (bytes == null || !mentionsAny(bytes, owners)) continue;
                ClassFlow caller = ClassFlow.parse(bytes);
                if (caller == null) continue;
                for (MethodNode method : caller.node.methods) {
                    for (AbstractInsnNode insn : method.instructions) {
                        if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                                || !call.name.equals("<init>")) {
                            continue;
                        }
                        String key = call.owner + "." + call.desc;
                        Set<Integer> parameters = pending.get(key);
                        if (parameters == null) continue;
                        for (int parameter : parameters) {
                            SourceValue value = caller.argument(method, call, parameter);
                            String field = ClassFlow.staticField(value);
                            if (field != null && materials.containsKey(field)) {
                                result.computeIfAbsent(servedClass.get(key), k -> new LinkedHashSet<>()).add(field);
                            }
                            // A constructor passing on its own parameter is followed next round.
                            int forwarded = ClassFlow.parameter(method, value);
                            if (forwarded >= 0 && method.name.equals("<init>")) {
                                String callerKey = caller.node.name + "." + method.desc;
                                next.computeIfAbsent(callerKey, k -> new HashSet<>()).add(forwarded);
                                servedClass.putIfAbsent(callerKey, servedClass.get(key));
                            }
                        }
                    }
                }
            }
            pending = next;
        }
        return result;
    }

    /** The call to the superclass constructor that a constructor makes on itself. */
    private static MethodInsnNode superCall(String superName, MethodNode constructor) {
        for (AbstractInsnNode insn : constructor.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.name.equals("<init>") && call.owner.equals(superName)) {
                return call;
            }
        }
        return null;
    }

    /** {@code namespace:path} for a 1.12 texture name, lowercased as the material reports it. */
    static String assetId(String textureName) {
        if (textureName == null) return null;
        String id = textureName.indexOf(':') >= 0 ? textureName : "minecraft:" + textureName;
        if (!RESOURCE_ID.matcher(id).matches()) return null;
        // A shared namespace gives null. A material named iron with its own texture would
        // otherwise replace vanilla iron armor's asset and textures for every player.
        return MigrationContext.SHARED_NAMESPACES.contains(id.substring(0, id.indexOf(':'))) ? null : id;
    }

    /** The jar entry of a texture location such as {@code mod:textures/armor/x.png}, or null. */
    static String textureEntry(String texture) {
        String id = texture.toLowerCase(Locale.ROOT);
        if (id.indexOf(':') < 0) id = "minecraft:" + id;
        if (!RESOURCE_ID.matcher(id).matches() || !id.endsWith(".png")) return null;
        int colon = id.indexOf(':');
        return "assets/" + id.substring(0, colon) + "/" + id.substring(colon + 1);
    }

    /** A quick text search of a class file for any of the names, before parsing it. */
    private static boolean mentionsAny(byte[] bytes, Set<String> names) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        for (String name : names) {
            if (text.contains(name)) return true;
        }
        return false;
    }

    /** The previous instruction that is not a label, line number, or frame. */
    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }

    /** The next instruction that is not a label, line number, or frame. */
    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }
}
