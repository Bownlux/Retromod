/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodVersion;
import com.retromod.shim.common.Legacy1205GameplayBridge.Shape;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static com.retromod.shim.common.Legacy1205GameplayBridge.*;

/** Gives an old mod's arrows, effects and mobs the overrides the host now calls in place of the ones they declare. */
public final class Legacy1205OverrideAdapter {

    private static final String L_STACK = "L" + ITEM_STACK + ";";
    private static final String L_LIVING = "L" + LIVING + ";";
    private static final String L_MAP = "L" + ATTRIBUTE_MAP + ";";
    private static final String OLD_ATTRIBUTES = "(" + L_LIVING + L_MAP + "I)V";
    private static final String DO_KNOCKBACK = "(" + L_LIVING + "L" + DAMAGE_SOURCE + ";)V";
    private static final String EFFECT_HOOK = "(" + L_LIVING + "I)V";
    private static final String OLD_INSTANT = "(L" + ENTITY + ";L" + ENTITY + ";" + L_LIVING + "ID)V";

    private static volatile Shape probed;
    private static volatile String probedFor;

    /** One old method the host stopped calling, named by its 1.20.1 name, the base declaring it, and its host form. */
    private record Rule(String kind, String base, Set<String> oldNames, String oldDesc, String newName, String newDesc) {
        boolean matches(String name, String desc) {
            return oldNames.contains(name) && oldDesc.equals(desc);
        }
    }

    private Legacy1205OverrideAdapter() {}

    /** The post-remap pass over one class of a mod built for {@code sourceVersion}, on the running host's shape. */
    public static byte[] apply(byte[] bytes, BiPredicate<String, String> inheritsFrom,
            Function<String, byte[]> modClasses, String sourceVersion) {
        // A mod built for 1.21.2 or newer already declares every form this pass knows.
        if (builtFrom(sourceVersion, "1.21.2") || !mayNeedPass(bytes)) return bytes;
        return apply(bytes, inheritsFrom, modClasses, hostShape(), sourceVersion);
    }

    // Names that appear in a class's constant pool when it declares or calls a method this pass bridges.
    private static final String[] MARKERS = {"isDurationEffectTick", "applyEffectTick", "applyInstant", "AttributeModifiers",
            "dropCustomDeathLoot", "createArrow", "projectile/"};

    /** A cheap look at the constant pool, so most classes skip the full read. */
    private static boolean mayNeedPass(byte[] bytes) {
        if (bytes == null) return false;
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        for (String marker : MARKERS) {
            if (text.contains(marker)) return true;
        }
        return false;
    }

    /** The same pass for a given host shape and a mod of unknown version, which transform-shape tests call directly. */
    static byte[] apply(byte[] bytes, BiPredicate<String, String> inheritsFrom, Function<String, byte[]> modClasses,
            Shape shape) {
        return apply(bytes, inheritsFrom, modClasses, shape, null);
    }

    static byte[] apply(byte[] bytes, BiPredicate<String, String> inheritsFrom, Function<String, byte[]> modClasses,
            Shape shape, String sourceVersion) {
        if (bytes == null) return null;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        if ((node.access & Opcodes.ACC_INTERFACE) != 0) return bytes;
        List<Rule> rules = rules(shape);
        boolean changed = rewriteCalls(node, rules, inheritsFrom, modClasses);
        changed |= addOverrides(node, rules, inheritsFrom, shape);
        // A mod from 1.21 on sets no knockback on its arrows, so only older ones get the hit that applies it.
        changed |= addArrowMembers(node, inheritsFrom, shape, !builtFrom(sourceVersion, "1.21"));
        if (!changed) return bytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Whether a mod built for {@code sourceVersion} is from {@code version} or newer; an unknown version is old. */
    private static boolean builtFrom(String sourceVersion, String version) {
        return sourceVersion != null && RetromodVersion.compareMcVersions(sourceVersion, version) >= 0;
    }

    /** The running host's shape, probed once per target version. */
    private static Shape hostShape() {
        String target = RetromodVersion.TARGET_MC_VERSION;
        Shape shape = probed;
        if (shape == null || !String.valueOf(target).equals(probedFor)) {
            shape = Shape.probe();
            probed = shape;
            probedFor = String.valueOf(target);
        }
        return shape;
    }

    /** The old methods this host no longer calls, with the form it calls instead. */
    private static List<Rule> rules(Shape shape) {
        List<Rule> rules = new ArrayList<>();
        if (shape.effectTickBoolean()) {
            // 1.20.5 renamed the tick check and made the tick answer whether the effect stays.
            rules.add(new Rule("isDurationEffectTick", MOB_EFFECT, Set.of("isDurationEffectTick"), "(II)Z", "shouldApplyEffectTickThisTick", "(II)Z"));
            rules.add(new Rule("applyEffectTick", MOB_EFFECT, Set.of("applyEffectTick"), EFFECT_HOOK, "applyEffectTick",
                    shape.effectTickLevel() ? "(L" + SERVER_LEVEL + ";" + L_LIVING + "I)Z" : "(" + L_LIVING + "I)Z"));
        }
        if (shape.effectTickLevel()) {
            // 1.21.2 passed the server level to the 1.20.5 tick as well.
            rules.add(new Rule("applyEffectTick1205", MOB_EFFECT, Set.of("applyEffectTick"), "(" + L_LIVING + "I)Z",
                    "applyEffectTick", "(L" + SERVER_LEVEL + ";" + L_LIVING + "I)Z"));
            // 1.21.2 passed the server level to instant effects; 26.2 also corrected the spelling.
            rules.add(new Rule("applyInstantenousEffect", MOB_EFFECT,
                    Set.of("applyInstantenousEffect", "applyInstantaneousEffect"), OLD_INSTANT,
                    shape.instantName(), "(L" + SERVER_LEVEL + ";" + OLD_INSTANT.substring(1)));
        }
        if (shape.effectMapOnly()) {
            rules.add(new Rule("addAttributeModifiers", MOB_EFFECT, Set.of("addAttributeModifiers"), OLD_ATTRIBUTES, "addAttributeModifiers",
                    "(" + L_MAP + "I)V"));
            rules.add(new Rule("removeAttributeModifiers", MOB_EFFECT, Set.of("removeAttributeModifiers"), OLD_ATTRIBUTES, "removeAttributeModifiers",
                    "(" + L_MAP + ")V"));
        }
        if (shape.deathLootLevel()) {
            // 1.21 passed the server level where the looting level was, which became an enchantment effect.
            rules.add(new Rule("dropCustomDeathLoot", LIVING, Set.of("dropCustomDeathLoot"), "(L" + DAMAGE_SOURCE + ";IZ)V",
                    "dropCustomDeathLoot", "(L" + SERVER_LEVEL + ";L" + DAMAGE_SOURCE + ";Z)V"));
        }
        if (shape.arrowItemWeapon()) {
            // 1.21 passed the weapon that fires the arrow.
            String created = ")L" + shape.arrow() + ";";
            String old = "(L" + LEVEL + ";" + L_STACK + L_LIVING + created;
            rules.add(new Rule("createArrow", ARROW_ITEM, Set.of("createArrow"), old, "createArrow",
                    "(L" + LEVEL + ";" + L_STACK + L_LIVING + L_STACK + created));
        }
        return rules;
    }

    // ---- call sites ----------------------------------------------------------------------------

    /** Points calls of an old form that only Minecraft would answer at the host's form. */
    private static boolean rewriteCalls(ClassNode node, List<Rule> rules, BiPredicate<String, String> inheritsFrom,
            Function<String, byte[]> modClasses) {
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || call.itf) continue;
                Rule rule = ruleFor(rules, call.name, call.desc);
                if (rule == null) continue;
                // A mod class on the way up that declares the old form still answers the call itself.
                if (!LegacyRegistryNbtBridge.reachesMinecraft(call.owner, call.name, call.desc, modClasses)) continue;
                // The old descriptor on a Minecraft class is proof enough; a mod class has to be shown to inherit the base.
                if (!call.owner.startsWith("net/minecraft/") && !inheritsFrom.test(call.owner, rule.base())) continue;
                if (call.getOpcode() == Opcodes.INVOKESPECIAL) {
                    rewriteSuperCall(method, call, rule);
                    changed = true;
                } else if (call.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                    changed |= rewritePlainCall(method, call, rule);
                }
            }
        }
        return changed;
    }

    private static Rule ruleFor(List<Rule> rules, String name, String desc) {
        for (Rule rule : rules) {
            if (rule.matches(name, desc)) return rule;
        }
        return null;
    }

    /** A super call reaches the host's form directly, with the old arguments turned into the new ones. */
    private static void rewriteSuperCall(MethodNode method, MethodInsnNode call, Rule rule) {
        InsnList before = new InsnList();
        boolean popResult = false;
        int free = method.maxLocals;
        switch (rule.kind()) {
            case "applyEffectTick", "applyEffectTick1205" -> {
                popResult = rule.kind().equals("applyEffectTick");
                if (rule.newDesc().startsWith("(L" + SERVER_LEVEL)) {
                    // [entity, amplifier] -> [level, entity, amplifier]; effects only tick on the server.
                    before.add(new VarInsnNode(Opcodes.ISTORE, free));
                    before.add(new VarInsnNode(Opcodes.ASTORE, free + 1));
                    pushServerLevelOf(before, free + 1);
                    before.add(new VarInsnNode(Opcodes.ALOAD, free + 1));
                    before.add(new VarInsnNode(Opcodes.ILOAD, free));
                    method.maxLocals = free + 2;
                }
            }
            case "applyInstantenousEffect" -> {
                // [source, indirect, target, amplifier, health] -> [level, source, indirect, target, amplifier, health]
                before.add(new VarInsnNode(Opcodes.DSTORE, free));
                before.add(new VarInsnNode(Opcodes.ISTORE, free + 2));
                before.add(new VarInsnNode(Opcodes.ASTORE, free + 3));
                before.add(new VarInsnNode(Opcodes.ASTORE, free + 4));
                before.add(new VarInsnNode(Opcodes.ASTORE, free + 5));
                pushServerLevelOf(before, free + 3);
                before.add(new VarInsnNode(Opcodes.ALOAD, free + 5));
                before.add(new VarInsnNode(Opcodes.ALOAD, free + 4));
                before.add(new VarInsnNode(Opcodes.ALOAD, free + 3));
                before.add(new VarInsnNode(Opcodes.ILOAD, free + 2));
                before.add(new VarInsnNode(Opcodes.DLOAD, free));
                method.maxLocals = free + 6;
            }
            case "addAttributeModifiers" -> {
                // [entity, map, amplifier] -> [map, amplifier]
                before.add(new VarInsnNode(Opcodes.ISTORE, free));
                before.add(new VarInsnNode(Opcodes.ASTORE, free + 1));
                before.add(new InsnNode(Opcodes.POP));
                before.add(new VarInsnNode(Opcodes.ALOAD, free + 1));
                before.add(new VarInsnNode(Opcodes.ILOAD, free));
                method.maxLocals = free + 2;
            }
            case "removeAttributeModifiers" -> {
                // [entity, map, amplifier] -> [map]
                before.add(new InsnNode(Opcodes.POP));
                before.add(new VarInsnNode(Opcodes.ASTORE, free));
                before.add(new InsnNode(Opcodes.POP));
                before.add(new VarInsnNode(Opcodes.ALOAD, free));
                method.maxLocals = free + 1;
            }
            case "dropCustomDeathLoot" -> {
                // [source, looting, recentlyHit] -> [level, source, recentlyHit]; loot only drops on the server.
                before.add(new VarInsnNode(Opcodes.ISTORE, free));
                before.add(new InsnNode(Opcodes.POP));
                before.add(new VarInsnNode(Opcodes.ASTORE, free + 1));
                pushServerLevelOf(before, 0);
                before.add(new VarInsnNode(Opcodes.ALOAD, free + 1));
                before.add(new VarInsnNode(Opcodes.ILOAD, free));
                method.maxLocals = free + 2;
            }
            // The parent builds the arrow with the bow this item is firing from, when one is.
            case "createArrow" -> before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ARROW_MEMBERS, "weapon",
                    "()" + L_STACK, false));
            default -> { }
        }
        method.instructions.insertBefore(call, before);
        call.name = rule.newName();
        call.desc = rule.newDesc();
        if (popResult) method.instructions.insert(call, new InsnNode(Opcodes.POP));
    }

    /** A plain call takes the host's form, through a helper where the new form needs more than the old arguments. */
    private static boolean rewritePlainCall(MethodNode method, MethodInsnNode call, Rule rule) {
        String helperDesc = "(L" + MOB_EFFECT + ";" + call.desc.substring(1);
        switch (rule.kind()) {
            case "isDurationEffectTick" -> call.name = rule.newName();
            case "applyEffectTick" -> retarget(call, "effectTick", helperDesc);
            case "applyEffectTick1205" -> retarget(call, "effectTickKeeps", helperDesc);
            case "applyInstantenousEffect" -> retarget(call, "effectInstant", helperDesc);
            case "addAttributeModifiers" -> retarget(call, "effectAdd", helperDesc);
            case "removeAttributeModifiers" -> retarget(call, "effectRemove", helperDesc);
            case "createArrow" -> {
                method.instructions.insertBefore(call, new InsnNode(Opcodes.ACONST_NULL));
                call.desc = rule.newDesc();
            }
            // Death loot is protected, so a call from outside the mob's own class does not occur.
            default -> {
                return false;
            }
        }
        return true;
    }

    private static void retarget(MethodInsnNode call, String helperName, String helperDesc) {
        call.setOpcode(Opcodes.INVOKESTATIC);
        call.owner = HELPER;
        call.name = helperName;
        call.desc = helperDesc;
    }

    /** Pushes {@code ((ServerLevel) entity.level())} for the entity in {@code local}. */
    private static void pushServerLevelOf(InsnList list, int local) {
        list.add(new VarInsnNode(Opcodes.ALOAD, local));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, "level", "()L" + LEVEL + ";", false));
        list.add(new TypeInsnNode(Opcodes.CHECKCAST, SERVER_LEVEL));
    }

    // ---- overrides ------------------------------------------------------------------------------

    /** Adds the host's form of each old method the class declares, running the old one. */
    private static boolean addOverrides(ClassNode node, List<Rule> rules, BiPredicate<String, String> inheritsFrom,
            Shape shape) {
        boolean changed = false;
        boolean attributes = false;
        for (Rule rule : rules) {
            MethodNode old = declaredOverride(node, rule);
            if (old == null || declares(node, rule.newName(), rule.newDesc())) continue;
            if (!rule.base().equals(node.superName) && !inheritsFrom.test(node.name, rule.base())) continue;
            MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC, rule.newName(), rule.newDesc(), null, null);
            switch (rule.kind()) {
                case "addAttributeModifiers" -> {
                    emitAttributeAdd(bridge, node, old);
                    attributes = true;
                }
                case "removeAttributeModifiers" -> {
                    emitAttributeRemove(bridge, node, old);
                    attributes = true;
                }
                case "dropCustomDeathLoot" -> {
                    bridge.access = Opcodes.ACC_PROTECTED;
                    emitDeathLoot(bridge, node, old);
                }
                case "createArrow" -> emitCreateArrow(bridge, node, old);
                case "applyEffectTick", "applyEffectTick1205" -> {
                    // The tick names the entity and amplifier, so an effect loaded with a saved world learns both
                    // before its removal runs.
                    rememberOwner(bridge, rule.newDesc().startsWith("(L" + SERVER_LEVEL) ? 2 : 1);
                    emitPassThrough(bridge, node, old, rule);
                }
                default -> emitPassThrough(bridge, node, old, rule);
            }
            node.methods.add(bridge);
            changed = true;
        }
        if (attributes) changed |= addEffectHooks(node);
        return changed;
    }

    /** The class's own instance method in an old form of {@code rule}, or null. */
    private static MethodNode declaredOverride(ClassNode node, Rule rule) {
        for (MethodNode method : node.methods) {
            if (rule.matches(method.name, method.desc)
                    && (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) {
                return method;
            }
        }
        return null;
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(desc));
    }

    /** Remembers the entity in {@code local} as the owner of its map, and the amplifier after it as this effect's. */
    private static void rememberOwner(MethodNode bridge, int local) {
        InsnList code = bridge.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, local));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LIVING, "getAttributes", "()" + L_MAP, false));
        code.add(new VarInsnNode(Opcodes.ALOAD, local));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EFFECT_HOLDERS, "remember",
                "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, local));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LIVING, "getAttributes", "()" + L_MAP, false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, local + 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EFFECT_HOLDERS, "applied",
                "(Ljava/lang/Object;Ljava/lang/Object;I)V", false));
    }

    /** {@code newForm(args) { return old(the old arguments); }}, dropping what the old form never took. */
    private static void emitPassThrough(MethodNode bridge, ClassNode node, MethodNode old, Rule rule) {
        InsnList code = bridge.instructions;
        Type[] newArgs = Type.getArgumentTypes(rule.newDesc());
        Type[] oldArgs = Type.getArgumentTypes(old.desc);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        // The new form puts a server level first and a weapon last; the old arguments are the rest, in order.
        int skipFirst = newArgs.length > oldArgs.length && newArgs[0].getInternalName().equals(SERVER_LEVEL) ? 1 : 0;
        int slot = 1;
        for (int i = 0; i < newArgs.length; i++) {
            if (i >= skipFirst && i - skipFirst < oldArgs.length) code.add(new VarInsnNode(newArgs[i].getOpcode(Opcodes.ILOAD), slot));
            slot += newArgs[i].getSize();
        }
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, old.name, old.desc, false));
        Type newReturn = Type.getReturnType(rule.newDesc());
        // An old void tick keeps the effect running, as it always did.
        if (Type.getReturnType(old.desc).getSort() == Type.VOID && newReturn.getSort() == Type.BOOLEAN) {
            code.add(new InsnNode(Opcodes.ICONST_1));
        }
        code.add(new InsnNode(newReturn.getOpcode(Opcodes.IRETURN)));
        bridge.maxLocals = slot;
    }

    /**
     * {@code createArrow(level, ammo, shooter, weapon)} runs the old form with the weapon made current, so the arrow it
     * builds takes the bow's enchantments as vanilla arrows do; the previous weapon comes back, also when it throws.
     */
    private static void emitCreateArrow(MethodNode bridge, ClassNode node, MethodNode old) {
        InsnList code = bridge.instructions;
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode failed = new LabelNode();
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ARROW_MEMBERS, "enterWeapon", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));
        code.add(start);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (int slot = 1; slot <= 3; slot++) code.add(new VarInsnNode(Opcodes.ALOAD, slot));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, old.name, old.desc, false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 6));
        code.add(end);
        code.add(exitWeapon());
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(failed);
        if (needsFrames(node)) {
            code.add(new FrameNode(Opcodes.F_FULL, 6, new Object[]{node.name, LEVEL, ITEM_STACK, LIVING, ITEM_STACK,
                    "java/lang/Object"}, 1, new Object[]{"java/lang/Throwable"}));
        }
        code.add(new VarInsnNode(Opcodes.ASTORE, 6));
        code.add(exitWeapon());
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new InsnNode(Opcodes.ATHROW));
        bridge.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failed, null));
        bridge.maxLocals = 7;
    }

    private static InsnList exitWeapon() {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ARROW_MEMBERS, "exitWeapon", "(Ljava/lang/Object;)V", false));
        return code;
    }

    /** {@code dropCustomDeathLoot(level, source, recentlyHit)} runs the old form with the killer's looting level. */
    private static void emitDeathLoot(MethodNode bridge, ClassNode node, MethodNode old) {
        InsnList code = bridge.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ENCHANTMENT_SUPPORT, "lootingLevel", "(Ljava/lang/Object;)I", false));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, old.name, old.desc, false));
        code.add(new InsnNode(Opcodes.RETURN));
        bridge.maxLocals = 4;
    }

    /**
     * {@code addAttributeModifiers(map, amplifier)} runs the old form with the entity that owns the map. Before a hook
     * has named that entity, the host's attributes apply now and the old form waits for the next hook.
     */
    private static void emitAttributeAdd(MethodNode bridge, ClassNode node, MethodNode old) {
        InsnList code = bridge.instructions;
        LabelNode known = new LabelNode();
        code.add(holderInto(3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, known));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "addAttributeModifiers", "(" + L_MAP + "I)V", false));
        code.add(holders("pending", 1, 0, 2));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(known);
        appendFrame(code, node, "java/lang/Object");
        code.add(holders("applied", 1, 0, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, LIVING));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, old.name, old.desc, false));
        code.add(new InsnNode(Opcodes.RETURN));
        bridge.maxLocals = 4;
    }

    /** {@code removeAttributeModifiers(map)} runs the old form with the owner and the amplifier it applied with. */
    private static void emitAttributeRemove(MethodNode bridge, ClassNode node, MethodNode old) {
        InsnList code = bridge.instructions;
        LabelNode known = new LabelNode();
        code.add(holderInto(2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, known));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "removeAttributeModifiers", "(" + L_MAP + ")V", false));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(known);
        appendFrame(code, node, "java/lang/Object");
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, LIVING));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EFFECT_HOLDERS, "amplifier", "(Ljava/lang/Object;Ljava/lang/Object;)I", false));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, old.name, old.desc, false));
        code.add(new InsnNode(Opcodes.RETURN));
        bridge.maxLocals = 3;
    }

    /** Stores the owner of the map in local 1 into {@code local}. */
    private static InsnList holderInto(int local) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EFFECT_HOLDERS, "holder", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, local));
        return code;
    }

    /** {@code LegacyEffectHolders.<name>(map, effect, amplifier)} from three locals. */
    private static InsnList holders(String name, int map, int effect, int amplifier) {
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, map));
        code.add(new VarInsnNode(Opcodes.ALOAD, effect));
        code.add(new VarInsnNode(Opcodes.ILOAD, amplifier));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EFFECT_HOLDERS, name, "(Ljava/lang/Object;Ljava/lang/Object;I)V", false));
        return code;
    }

    /**
     * The effect hooks that still name the entity learn which entity owns its map, and finish an old addition that ran
     * before the owner was known: {@code onEffectAdded} right after the first addition, {@code onEffectStarted} after
     * every addition or upgrade.
     */
    private static boolean addEffectHooks(ClassNode node) {
        boolean changed = false;
        MethodNode oldAdd = null;
        for (MethodNode method : node.methods) {
            if (method.name.equals("addAttributeModifiers") && method.desc.equals(OLD_ATTRIBUTES)) oldAdd = method;
        }
        for (String hook : new String[]{"onEffectAdded", "onEffectStarted"}) {
            if (declares(node, hook, EFFECT_HOOK)) continue;
            MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, hook, EFFECT_HOOK, null, null);
            InsnList code = method.instructions;
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new VarInsnNode(Opcodes.ILOAD, 2));
            code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, hook, EFFECT_HOOK, false));
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LIVING, "getAttributes", "()" + L_MAP, false));
            code.add(new VarInsnNode(Opcodes.ASTORE, 3));
            code.add(new VarInsnNode(Opcodes.ALOAD, 3));
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EFFECT_HOLDERS, "remember", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
            method.maxLocals = 4;
            if (oldAdd != null) {
                // A waiting old addition runs now, with the owner it was missing.
                LabelNode done = new LabelNode();
                code.add(new VarInsnNode(Opcodes.ALOAD, 3));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, EFFECT_HOLDERS, "takePending", "(Ljava/lang/Object;Ljava/lang/Object;)I", false));
                code.add(new VarInsnNode(Opcodes.ISTORE, 4));
                code.add(new VarInsnNode(Opcodes.ILOAD, 4));
                code.add(new JumpInsnNode(Opcodes.IFLT, done));
                code.add(holders("applied", 3, 0, 4));
                code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new VarInsnNode(Opcodes.ALOAD, 3));
                code.add(new VarInsnNode(Opcodes.ILOAD, 4));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, oldAdd.name, oldAdd.desc, false));
                code.add(done);
                appendFrame(code, node, L_MAP.substring(1, L_MAP.length() - 1), Opcodes.INTEGER);
                method.maxLocals = 5;
            }
            code.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(method);
            changed = true;
        }
        return changed;
    }

    // ---- arrows ---------------------------------------------------------------------------------

    /** An old arrow gets the default pickup stack its parent now asks for, and the knockback its mod may set. */
    private static boolean addArrowMembers(ClassNode node, BiPredicate<String, String> inheritsFrom, Shape shape,
            boolean setsKnockback) {
        if (node.superName == null) return false;
        boolean directArrow = node.superName.equals(ARROW_1205) || node.superName.equals(ARROW_261);
        boolean changed = false;
        String getDefault = "()" + L_STACK;
        if (directArrow && shape.pickupDefault() && !declares(node, "getDefaultPickupItem", getDefault)) {
            node.methods.add(defaultPickup(node));
            changed = true;
        }
        // Every mod arrow that builds on one of Minecraft's applies the knockback set on it, as the old hit did.
        boolean modArrow = node.superName.startsWith("net/minecraft/") && (directArrow
                || inheritsFrom.test(node.name, ARROW_1205) || inheritsFrom.test(node.name, ARROW_261));
        if (modArrow && setsKnockback && shape.knockbackGone() && !declares(node, "doKnockback", DO_KNOCKBACK)) {
            MethodNode method = new MethodNode(Opcodes.ACC_PROTECTED, "doKnockback", DO_KNOCKBACK, null, null);
            InsnList code = method.instructions;
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new VarInsnNode(Opcodes.ALOAD, 2));
            code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "doKnockback", DO_KNOCKBACK, false));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "legacyKnockback", "(Ljava/lang/Object;" + L_LIVING + ")V", false));
            code.add(new InsnNode(Opcodes.RETURN));
            method.maxLocals = 3;
            node.methods.add(method);
            changed = true;
        }
        return changed;
    }

    /**
     * {@code getDefaultPickupItem()} answers with the old {@code getPickupItem()}, or a plain arrow when that is empty.
     * The parent asks while it is still being built, before the mod's own fields are set, so a pickup method that reads
     * one of them fails then; the arrow starts with a plain arrow and the mod's method still decides what is picked up.
     */
    private static MethodNode defaultPickup(ClassNode node) {
        MethodNode method = new MethodNode(Opcodes.ACC_PROTECTED, "getDefaultPickupItem", "()" + L_STACK, null, null);
        InsnList code = method.instructions;
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode failed = new LabelNode();
        code.add(start);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "getPickupItem", "()" + L_STACK, false));
        code.add(end);
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "pickupOrArrow", "(Ljava/lang/Object;)" + L_STACK, false));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(failed);
        if (needsFrames(node)) {
            code.add(new FrameNode(Opcodes.F_FULL, 1, new Object[]{node.name}, 1,
                    new Object[]{"java/lang/Throwable"}));
        }
        code.add(new InsnNode(Opcodes.POP));
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "pickupOrArrow", "(Ljava/lang/Object;)" + L_STACK, false));
        code.add(new InsnNode(Opcodes.ARETURN));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failed, "java/lang/RuntimeException"));
        // A pickup method that names a class or member the host lacks fails the same way, and gets the same arrow.
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, end, failed, "java/lang/LinkageError"));
        method.maxLocals = 1;
        return method;
    }

    // ---- frames ---------------------------------------------------------------------------------

    private static boolean needsFrames(ClassNode node) {
        return (node.version & 0xFFFF) >= Opcodes.V1_6;
    }

    /** A frame that adds {@code locals} to the method's arguments, with an empty stack. */
    private static void appendFrame(InsnList code, ClassNode node, Object... locals) {
        if (needsFrames(node)) code.add(new FrameNode(Opcodes.F_APPEND, locals.length, locals, 0, null));
    }
}
