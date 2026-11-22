/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.util.McReflect;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.objectweb.asm.Opcodes.*;

/** Rebuilds the old game rule API on hosts with typed rules: value views over the host's rules, and old rules registered as real ones. */
public final class LegacyGameRuleValueBridge {

    static final String VALUE = "com/retromod/generated/LegacyGameRuleValue";
    static final String BOOLEAN = "com/retromod/generated/LegacyGameRuleBoolean";
    static final String INTEGER = "com/retromod/generated/LegacyGameRuleInteger";

    static final String OLD = "net/minecraft/world/level/GameRules";
    static final String OLD_KEY = OLD + "$Key";
    static final String RULES = "net/minecraft/world/level/gamerules/GameRules";
    static final String RULE = "net/minecraft/world/level/gamerules/GameRule";
    static final String SERVER = "net/minecraft/server/MinecraftServer";
    static final String DATA_RESULT = "com/mojang/serialization/DataResult";
    static final String CATEGORY = "net/minecraft/world/level/gamerules/GameRuleCategory";
    static final String RULE_TYPE = "net/minecraft/world/level/gamerules/GameRuleType";
    static final String REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";
    static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    static final String NOTIFICATIONS = "net/minecraft/server/notifications/";

    // The rebuilt registration classes, embedded into each mod that registers old rules.
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/gamerules/";
    static final String TYPE = REBUILT + "LegacyGameRuleType";
    static final String LEGACY_RULES = REBUILT + "LegacyGameRules";
    static final String FABRIC_API = REBUILT + "LegacyFabricGameRuleApi";
    static final String VISITOR = REBUILT + "LegacyGameRuleTypeVisitor";
    static final String VISITS = REBUILT + "LegacyGameRuleVisits";
    static final String FLAGS = "net/minecraft/world/flag/";
    static final String FABRIC_REGISTRY = "net/fabricmc/fabric/api/gamerule/v1/GameRuleRegistry";
    static final String FABRIC_FACTORY = "net/fabricmc/fabric/api/gamerule/v1/GameRuleFactory";
    /** The old rule categories; each kept its name as a 26.x category constant. */
    static final List<String> CATEGORIES = List.of("PLAYER", "MOBS", "SPAWNING", "DROPS", "UPDATES", "CHAT", "MISC");

    /** The integer rule that replaced the old fire tick switch; the old switch reads "on" while the radius is not 0. */
    static final String RADIUS_RULE = "FIRE_SPREAD_RADIUS_AROUND_PLAYER";
    /** The three rules 1.21.11 turned from "disable" to "enable", so an old reader sees them negated. */
    static final List<String> INVERTED_RULES = List.of("ELYTRA_MOVEMENT_CHECK", "PLAYER_MOVEMENT_CHECK", "RAIDS");

    private static final String RULES_DESC = "L" + RULES + ";";
    private static final String RULE_DESC = "L" + RULE + ";";
    private static final String SERVER_DESC = "L" + SERVER + ";";
    private static final String OBJECT = "java/lang/Object";

    /**
     * What the host offers: the typed reads and writes the views stand on, which special rules exist,
     * whether old rules can be registered as real ones, and whether Fabric API's old rule classes need a stand-in.
     */
    record Shape(boolean views, boolean radius, List<String> inverted, boolean onChanged, boolean ruleId,
            boolean registration, boolean fabricApi) {}

    private LegacyGameRuleValueBridge() {}

    /** Reads the shape from the running or offline host; an unreadable class leaves its part off. */
    static Shape hostShape() {
        return shape(ClassResourceInspector::read, fabricTarget());
    }

    /**
     * Whether Retromod translates for Fabric or Quilt. Fabric API's rule classes are replaced only then:
     * NeoForge's Forgified Fabric API ships its own. It needs positive evidence, the CLI's
     * {@code --target-loader} or a running Fabric loader, so an offline run with no loader named stays off.
     */
    static boolean fabricTarget() {
        if (McReflect.isNeoForge() || McReflect.isForge() || McReflect.isForgeTarget()) return false;
        String offline = com.retromod.core.ModVersionDetector.defaultPreferredLoader();
        if (offline != null) return offline.equals("fabric") || offline.equals("quilt");
        return McReflect.isFabric();
    }

    static Shape shape(Function<String, ClassNode> host) {
        return shape(host, false);
    }

    static Shape shape(Function<String, ClassNode> host, boolean fabricTarget) {
        ClassNode rules = host.apply(RULES);
        ClassNode rule = host.apply(RULE);
        ClassNode server = host.apply(SERVER);
        // A host that still has the old value classes keeps the old API, so nothing is rebuilt there.
        boolean oldApiGone = host.apply(OLD + "$Value") == null;
        boolean views = oldApiGone && rules != null && rule != null
                && method(rules, "get", "(" + RULE_DESC + ")L" + OBJECT + ";")
                && method(rules, "set", "(" + RULE_DESC + "L" + OBJECT + ";" + SERVER_DESC + ")V")
                && method(rules, "getAsString", "(" + RULE_DESC + ")Ljava/lang/String;")
                && method(rule, "valueClass", "()Ljava/lang/Class;")
                && method(rule, "defaultValue", "()L" + OBJECT + ";")
                && method(rule, "deserialize", "(Ljava/lang/String;)L" + DATA_RESULT + ";")
                && method(rule, "getCommandResult", "(L" + OBJECT + ";)I");
        boolean radius = views && field(rules, RADIUS_RULE);
        List<String> inverted = new ArrayList<>();
        if (views) {
            for (String name : INVERTED_RULES) {
                if (field(rules, name)) inverted.add(name);
            }
        }
        boolean onChanged = views && server != null
                && method(server, "onGameRuleChanged", "(" + RULE_DESC + "L" + OBJECT + ";)V");
        boolean ruleId = rule != null && method(rule, "id", "()Ljava/lang/String;") && !named(rule, "getId");
        boolean registration = onChanged && registrationShape(host, rule);
        boolean fabricApi = registration && fabricTarget && host.apply(FABRIC_REGISTRY) == null;
        return new Shape(views, radius, List.copyOf(inverted), onChanged, ruleId, registration, fabricApi);
    }

    /** Whether the host has everything LegacyGameRules builds and registers a rule with, and the change hook it listens on. */
    private static boolean registrationShape(Function<String, ClassNode> host, ClassNode rule) {
        ClassNode category = host.apply(CATEGORY);
        ClassNode type = host.apply(RULE_TYPE);
        ClassNode registries = host.apply(REGISTRIES);
        ClassNode server = host.apply(SERVER);
        ClassNode level = host.apply(SERVER_LEVEL);
        ClassNode empty = host.apply(NOTIFICATIONS + "EmptyNotificationService");
        ClassNode manager = host.apply(NOTIFICATIONS + "NotificationManager");
        ClassNode rules = host.apply(RULES);
        ClassNode flags = host.apply(FLAGS + "FeatureFlags");
        ClassNode flagRegistry = host.apply(FLAGS + "FeatureFlagRegistry");
        if (category == null || type == null || registries == null || server == null || level == null || empty == null
                || manager == null || rules == null || flags == null || flagRegistry == null) {
            return false;
        }
        String ctor = "(L" + CATEGORY + ";L" + RULE_TYPE + ";Lcom/mojang/brigadier/arguments/ArgumentType;L" + RULES
                + "$VisitorCaller;Lcom/mojang/serialization/Codec;Ljava/util/function/ToIntFunction;L" + OBJECT
                + ";Lnet/minecraft/world/flag/FeatureFlagSet;)V";
        return method(rule, "<init>", ctor) && method(rule, "category", "()L" + CATEGORY + ";")
                && CATEGORIES.stream().allMatch(name -> staticField(category, name, "L" + CATEGORY + ";"))
                && staticField(type, "BOOL", "L" + RULE_TYPE + ";") && staticField(type, "INT", "L" + RULE_TYPE + ";")
                && registries.fields.stream().anyMatch(f -> f.name.equals("GAME_RULE"))
                && method(server, "notificationManager", "()L" + NOTIFICATIONS + "NotificationManager;")
                && method(server, "overworld", "()L" + SERVER_LEVEL + ";")
                && method(level, "getGameRules", "()" + RULES_DESC)
                && method(manager, "registerService", "(L" + NOTIFICATIONS + "NotificationService;)V")
                && method(empty, "<init>", "()V")
                // Listing every rule and the old no-argument rules: rule kinds, and rules built for all flags.
                && method(rule, "gameRuleType", "()L" + RULE_TYPE + ";")
                && method(rules, "<init>", "(L" + FLAGS + "FeatureFlagSet;)V")
                && staticField(flags, "REGISTRY", "L" + FLAGS + "FeatureFlagRegistry;")
                && method(flagRegistry, "allFlags", "()L" + FLAGS + "FeatureFlagSet;");
    }

    /** Registers the views and the call redirects that reach them, on a host whose shape allows it. */
    public static void register(RetromodTransformer t) {
        register(t, hostShape());
    }

    static void register(RetromodTransformer t, Shape shape) {
        if (shape.ruleId()) {
            // The old key's id read is the rule's id read under a shorter name.
            for (String owner : List.of(RULE, OLD_KEY)) {
                t.registerMethodRedirect(owner, "getId", "()Ljava/lang/String;", RULE, "id", "()Ljava/lang/String;");
            }
        }
        if (!shape.views()) return;
        generate(shape).forEach(t::registerSyntheticClass);
        if (shape.registration()) registerRegistration(t, shape);
        t.registerClassRedirect(OLD + "$Value", VALUE);
        t.registerClassRedirect(OLD + "$BooleanValue", BOOLEAN);
        t.registerClassRedirect(OLD + "$IntegerValue", INTEGER);
        // getRule is keyed under both owner spellings and both return spellings, since the class
        // move and the value redirect may or may not have run when the call is looked up.
        String[][] owners = {{RULES, RULE}, {OLD, OLD_KEY}};
        for (String[] owner : owners) {
            for (String value : List.of(OLD + "$Value", VALUE)) {
                t.registerMethodRedirect(owner[0], "getRule", "(L" + owner[1] + ";)L" + value + ";", VALUE, "of",
                        "(" + RULES_DESC + RULE_DESC + ")L" + VALUE + ";");
            }
        }
    }

    /** Old rule registration: the type and category classes, {@code GameRules.register}, and Fabric API's old helpers. */
    private static void registerRegistration(RetromodTransformer t, Shape shape) {
        RebuiltApiClasses.registerTree(t, LEGACY_RULES);
        t.registerClassRedirect(OLD + "$Type", TYPE);
        t.registerClassRedirect(OLD + "$Category", CATEGORY);
        // Keyed with the descriptor after every class move; the lookup resolves a call's descriptor the same way.
        String register = "(Ljava/lang/String;L" + CATEGORY + ";L" + TYPE + ";)" + RULE_DESC;
        for (String owner : List.of(RULES, OLD)) {
            t.registerMethodRedirect(owner, "register", register, LEGACY_RULES, "register", register);
        }
        for (String owner : List.of(RULE, OLD_KEY)) {
            t.registerMethodRedirect(owner, "getCategory", "()L" + CATEGORY + ";", RULE, "category", "()L" + CATEGORY + ";");
        }
        // Listing every rule: the old visitor, the static visit call, the type map, and the no-argument rules.
        RebuiltApiClasses.registerTree(t, VISITS);
        t.registerClassRedirect(OLD + "$GameRuleTypeVisitor", VISITOR);
        for (String owner : List.of(RULES, OLD)) {
            t.registerMethodRedirect(owner, "visitGameRuleTypes", "(L" + VISITOR + ";)V",
                    VISITS, "visitGameRuleTypes", "(L" + VISITOR + ";)V");
            t.registerStaticFieldBridge(owner, "GAME_RULE_TYPES", VISITS, "gameRuleTypes", "()Ljava/util/Map;", null, null);
            t.registerConstructorRedirect(owner, "()V", VISITS, "newGameRules", "()" + RULES_DESC);
        }
        if (shape.fabricApi()) {
            RebuiltApiClasses.registerTree(t, FABRIC_API);
            t.registerClassRedirect(FABRIC_REGISTRY, FABRIC_API);
            t.registerClassRedirect(FABRIC_FACTORY, FABRIC_API);
        }
    }

    /** The three view classes for this host shape, by internal name. */
    static Map<String, byte[]> generate(Shape shape) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(VALUE, value(shape));
        classes.put(BOOLEAN, booleanView(shape));
        classes.put(INTEGER, integerView(shape));
        return classes;
    }

    /** The base view: holds the host rules and one rule, reads and writes through the host, and picks the typed view. */
    private static byte[] value(Shape shape) {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, VALUE, null, OBJECT, null);
        cw.visitField(ACC_PROTECTED | ACC_FINAL, "rules", RULES_DESC, null, null).visitEnd();
        cw.visitField(ACC_PROTECTED | ACC_FINAL, "rule", RULE_DESC, null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + RULES_DESC + RULE_DESC + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, OBJECT, "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, VALUE, "rules", RULES_DESC);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 2);
        init.visitFieldInsn(PUTFIELD, VALUE, "rule", RULE_DESC);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        emitOf(cw, shape);

        // The old text form is the host's text form of the stored value.
        MethodVisitor serialize = cw.visitMethod(ACC_PUBLIC, "serialize", "()Ljava/lang/String;", null, null);
        serialize.visitCode();
        loadRulesAndRule(serialize);
        serialize.visitMethodInsn(INVOKEVIRTUAL, RULES, "getAsString", "(" + RULE_DESC + ")Ljava/lang/String;", false);
        serialize.visitInsn(ARETURN);
        serialize.visitMaxs(0, 0);
        serialize.visitEnd();

        MethodVisitor text = cw.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null);
        text.visitCode();
        text.visitVarInsn(ALOAD, 0);
        text.visitMethodInsn(INVOKEVIRTUAL, VALUE, "serialize", "()Ljava/lang/String;", false);
        text.visitInsn(ARETURN);
        text.visitMaxs(0, 0);
        text.visitEnd();

        // Text the rule's codec rejects leaves the value unchanged, as the old parse did.
        MethodVisitor parse = cw.visitMethod(ACC_PUBLIC, "deserialize", "(Ljava/lang/String;)V", null, null);
        parse.visitCode();
        parse.visitVarInsn(ALOAD, 0);
        parse.visitFieldInsn(GETFIELD, VALUE, "rule", RULE_DESC);
        parse.visitVarInsn(ALOAD, 1);
        parse.visitMethodInsn(INVOKEVIRTUAL, RULE, "deserialize", "(Ljava/lang/String;)L" + DATA_RESULT + ";", false);
        parse.visitMethodInsn(INVOKEINTERFACE, DATA_RESULT, "result", "()Ljava/util/Optional;", true);
        parse.visitInsn(ACONST_NULL);
        parse.visitMethodInsn(INVOKEVIRTUAL, "java/util/Optional", "orElse", "(L" + OBJECT + ";)L" + OBJECT + ";", false);
        parse.visitVarInsn(ASTORE, 2);
        Label rejected = new Label();
        parse.visitVarInsn(ALOAD, 2);
        parse.visitJumpInsn(IFNULL, rejected);
        loadRulesAndRule(parse);
        parse.visitVarInsn(ALOAD, 2);
        parse.visitInsn(ACONST_NULL);
        parse.visitMethodInsn(INVOKEVIRTUAL, RULES, "set", "(" + RULE_DESC + "L" + OBJECT + ";" + SERVER_DESC + ")V", false);
        parse.visitLabel(rejected);
        parse.visitInsn(RETURN);
        parse.visitMaxs(0, 0);
        parse.visitEnd();

        MethodVisitor result = cw.visitMethod(ACC_PUBLIC, "getCommandResult", "()I", null, null);
        result.visitCode();
        result.visitVarInsn(ALOAD, 0);
        result.visitFieldInsn(GETFIELD, VALUE, "rule", RULE_DESC);
        loadRulesAndRule(result);
        result.visitMethodInsn(INVOKEVIRTUAL, RULES, "get", "(" + RULE_DESC + ")L" + OBJECT + ";", false);
        result.visitMethodInsn(INVOKEVIRTUAL, RULE, "getCommandResult", "(L" + OBJECT + ";)I", false);
        result.visitInsn(IRETURN);
        result.visitMaxs(0, 0);
        result.visitEnd();

        emitIdentity(cw);

        if (shape.onChanged()) {
            // The old change hook tells the server, which is what a host write with a server does.
            MethodVisitor changed = cw.visitMethod(ACC_PUBLIC, "onChanged", "(" + SERVER_DESC + ")V", null, null);
            changed.visitCode();
            Label noServer = new Label();
            changed.visitVarInsn(ALOAD, 1);
            changed.visitJumpInsn(IFNULL, noServer);
            changed.visitVarInsn(ALOAD, 1);
            changed.visitVarInsn(ALOAD, 0);
            changed.visitFieldInsn(GETFIELD, VALUE, "rule", RULE_DESC);
            loadRulesAndRule(changed);
            changed.visitMethodInsn(INVOKEVIRTUAL, RULES, "get", "(" + RULE_DESC + ")L" + OBJECT + ";", false);
            changed.visitMethodInsn(INVOKEVIRTUAL, SERVER, "onGameRuleChanged", "(" + RULE_DESC + "L" + OBJECT + ";)V", false);
            changed.visitLabel(noServer);
            changed.visitInsn(RETURN);
            changed.visitMaxs(0, 0);
            changed.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Two views of the same rule in the same rules are equal, so a view works as a map key or in a set. */
    private static void emitIdentity(ClassWriter cw) {
        MethodVisitor equals = cw.visitMethod(ACC_PUBLIC, "equals", "(L" + OBJECT + ";)Z", null, null);
        equals.visitCode();
        Label differs = new Label();
        equals.visitVarInsn(ALOAD, 1);
        equals.visitTypeInsn(INSTANCEOF, VALUE);
        equals.visitJumpInsn(IFEQ, differs);
        // The host's rules and rule compare by identity, so the views do too.
        for (String field : List.of("rules", "rule")) {
            String desc = field.equals("rules") ? RULES_DESC : RULE_DESC;
            equals.visitVarInsn(ALOAD, 0);
            equals.visitFieldInsn(GETFIELD, VALUE, field, desc);
            equals.visitVarInsn(ALOAD, 1);
            equals.visitTypeInsn(CHECKCAST, VALUE);
            equals.visitFieldInsn(GETFIELD, VALUE, field, desc);
            equals.visitJumpInsn(IF_ACMPNE, differs);
        }
        equals.visitInsn(ICONST_1);
        equals.visitInsn(IRETURN);
        equals.visitLabel(differs);
        equals.visitInsn(ICONST_0);
        equals.visitInsn(IRETURN);
        equals.visitMaxs(0, 0);
        equals.visitEnd();

        MethodVisitor hash = cw.visitMethod(ACC_PUBLIC, "hashCode", "()I", null, null);
        hash.visitCode();
        hash.visitVarInsn(ALOAD, 0);
        hash.visitFieldInsn(GETFIELD, VALUE, "rules", RULES_DESC);
        hash.visitMethodInsn(INVOKESTATIC, "java/lang/System", "identityHashCode", "(L" + OBJECT + ";)I", false);
        hash.visitIntInsn(BIPUSH, 31);
        hash.visitInsn(IMUL);
        hash.visitVarInsn(ALOAD, 0);
        hash.visitFieldInsn(GETFIELD, VALUE, "rule", RULE_DESC);
        hash.visitMethodInsn(INVOKESTATIC, "java/lang/System", "identityHashCode", "(L" + OBJECT + ";)I", false);
        hash.visitInsn(IADD);
        hash.visitInsn(IRETURN);
        hash.visitMaxs(0, 0);
        hash.visitEnd();
    }

    /** {@code of(rules, rule)}: the boolean view for boolean rules and the fire radius, the integer view for integers, else the base. */
    private static void emitOf(ClassWriter cw, Shape shape) {
        MethodVisitor of = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "of", "(" + RULES_DESC + RULE_DESC + ")L" + VALUE + ";",
                null, null);
        of.visitCode();
        if (shape.radius()) {
            Label notRadius = new Label();
            of.visitVarInsn(ALOAD, 1);
            of.visitFieldInsn(GETSTATIC, RULES, RADIUS_RULE, RULE_DESC);
            of.visitJumpInsn(IF_ACMPNE, notRadius);
            newBoolean(of, () -> of.visitInsn(ICONST_0), ICONST_1);
            of.visitLabel(notRadius);
        }
        Label notBoolean = new Label();
        of.visitVarInsn(ALOAD, 1);
        of.visitMethodInsn(INVOKEVIRTUAL, RULE, "valueClass", "()Ljava/lang/Class;", false);
        of.visitLdcInsn(Type.getObjectType("java/lang/Boolean"));
        of.visitJumpInsn(IF_ACMPNE, notBoolean);
        newBoolean(of, () -> emitInverted(of, shape), ICONST_0);
        of.visitLabel(notBoolean);

        Label notInteger = new Label();
        of.visitVarInsn(ALOAD, 1);
        of.visitMethodInsn(INVOKEVIRTUAL, RULE, "valueClass", "()Ljava/lang/Class;", false);
        of.visitLdcInsn(Type.getObjectType("java/lang/Integer"));
        of.visitJumpInsn(IF_ACMPNE, notInteger);
        of.visitTypeInsn(NEW, INTEGER);
        of.visitInsn(DUP);
        of.visitVarInsn(ALOAD, 0);
        of.visitVarInsn(ALOAD, 1);
        of.visitMethodInsn(INVOKESPECIAL, INTEGER, "<init>", "(" + RULES_DESC + RULE_DESC + ")V", false);
        of.visitInsn(ARETURN);
        of.visitLabel(notInteger);

        of.visitTypeInsn(NEW, VALUE);
        of.visitInsn(DUP);
        of.visitVarInsn(ALOAD, 0);
        of.visitVarInsn(ALOAD, 1);
        of.visitMethodInsn(INVOKESPECIAL, VALUE, "<init>", "(" + RULES_DESC + RULE_DESC + ")V", false);
        of.visitInsn(ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();
    }

    /** Returns {@code new LegacyGameRuleBoolean(rules, rule, <inverted>, <radius>)}. */
    private static void newBoolean(MethodVisitor mv, Runnable pushInverted, int radius) {
        mv.visitTypeInsn(NEW, BOOLEAN);
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        pushInverted.run();
        mv.visitInsn(radius);
        mv.visitMethodInsn(INVOKESPECIAL, BOOLEAN, "<init>", "(" + RULES_DESC + RULE_DESC + "ZZ)V", false);
        mv.visitInsn(ARETURN);
    }

    /** Pushes whether the rule in local 1 is one of the host's inverted rules. */
    private static void emitInverted(MethodVisitor mv, Shape shape) {
        if (shape.inverted().isEmpty()) {
            mv.visitInsn(ICONST_0);
            return;
        }
        Label yes = new Label();
        Label done = new Label();
        for (String name : shape.inverted()) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, RULES, name, RULE_DESC);
            mv.visitJumpInsn(IF_ACMPEQ, yes);
        }
        mv.visitInsn(ICONST_0);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(yes);
        mv.visitInsn(ICONST_1);
        mv.visitLabel(done);
    }

    /** The boolean view. An inverted rule reads and writes negated; the fire radius reads "on" while it is not 0. */
    private static byte[] booleanView(Shape shape) {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, BOOLEAN, null, VALUE, null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "inverted", "Z", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "radius", "Z", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + RULES_DESC + RULE_DESC + "ZZ)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitMethodInsn(INVOKESPECIAL, VALUE, "<init>", "(" + RULES_DESC + RULE_DESC + ")V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ILOAD, 3);
        init.visitFieldInsn(PUTFIELD, BOOLEAN, "inverted", "Z");
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ILOAD, 4);
        init.visitFieldInsn(PUTFIELD, BOOLEAN, "radius", "Z");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor get = cw.visitMethod(ACC_PUBLIC, "get", "()Z", null, null);
        get.visitCode();
        Label plain = new Label();
        get.visitVarInsn(ALOAD, 0);
        get.visitFieldInsn(GETFIELD, BOOLEAN, "radius", "Z");
        get.visitJumpInsn(IFEQ, plain);
        // Fire spreads while the radius is not 0, which is what the old switch being on meant.
        Label stopped = new Label();
        hostValue(get, "java/lang/Integer", "intValue", "I");
        get.visitJumpInsn(IFEQ, stopped);
        get.visitInsn(ICONST_1);
        get.visitInsn(IRETURN);
        get.visitLabel(stopped);
        get.visitInsn(ICONST_0);
        get.visitInsn(IRETURN);
        get.visitLabel(plain);
        hostValue(get, "java/lang/Boolean", "booleanValue", "Z");
        get.visitVarInsn(ALOAD, 0);
        get.visitFieldInsn(GETFIELD, BOOLEAN, "inverted", "Z");
        get.visitInsn(IXOR);
        get.visitInsn(IRETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();

        emitBooleanSet(cw);

        MethodVisitor from = cw.visitMethod(ACC_PUBLIC, "setFrom", "(L" + BOOLEAN + ";" + SERVER_DESC + ")V", null, null);
        from.visitCode();
        from.visitVarInsn(ALOAD, 0);
        from.visitVarInsn(ALOAD, 1);
        from.visitMethodInsn(INVOKEVIRTUAL, BOOLEAN, "get", "()Z", false);
        from.visitVarInsn(ALOAD, 2);
        from.visitMethodInsn(INVOKEVIRTUAL, BOOLEAN, "set", "(Z" + SERVER_DESC + ")V", false);
        from.visitInsn(RETURN);
        from.visitMaxs(0, 0);
        from.visitEnd();

        // The text form, the parse, and the command result follow the old boolean, not the stored value.
        MethodVisitor serialize = cw.visitMethod(ACC_PUBLIC, "serialize", "()Ljava/lang/String;", null, null);
        serialize.visitCode();
        serialize.visitVarInsn(ALOAD, 0);
        serialize.visitMethodInsn(INVOKEVIRTUAL, BOOLEAN, "get", "()Z", false);
        serialize.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(Z)Ljava/lang/String;", false);
        serialize.visitInsn(ARETURN);
        serialize.visitMaxs(0, 0);
        serialize.visitEnd();

        MethodVisitor parse = cw.visitMethod(ACC_PUBLIC, "deserialize", "(Ljava/lang/String;)V", null, null);
        parse.visitCode();
        parse.visitVarInsn(ALOAD, 0);
        parse.visitVarInsn(ALOAD, 1);
        parse.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "parseBoolean", "(Ljava/lang/String;)Z", false);
        parse.visitInsn(ACONST_NULL);
        parse.visitMethodInsn(INVOKEVIRTUAL, BOOLEAN, "set", "(Z" + SERVER_DESC + ")V", false);
        parse.visitInsn(RETURN);
        parse.visitMaxs(0, 0);
        parse.visitEnd();

        MethodVisitor result = cw.visitMethod(ACC_PUBLIC, "getCommandResult", "()I", null, null);
        result.visitCode();
        result.visitVarInsn(ALOAD, 0);
        result.visitMethodInsn(INVOKEVIRTUAL, BOOLEAN, "get", "()Z", false);
        result.visitInsn(IRETURN);
        result.visitMaxs(0, 0);
        result.visitEnd();

        if (shape.registration()) {
            emitCreate(cw, "Z", "bool");
            emitCreate(cw, "ZLjava/util/function/BiConsumer;", "bool");
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The old static {@code create(...)} that builds a rule type, forwarded to the rebuilt type's factory. */
    private static void emitCreate(ClassWriter cw, String params, String factory) {
        String desc = "(" + params + ")L" + TYPE + ";";
        MethodVisitor create = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create", desc, null, null);
        create.visitCode();
        int slot = 0;
        for (Type param : Type.getArgumentTypes(desc)) {
            create.visitVarInsn(param.getOpcode(ILOAD), slot);
            slot += param.getSize();
        }
        create.visitMethodInsn(INVOKESTATIC, TYPE, factory, desc, false);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();
    }

    /** {@code set(on, server)}: a plain or inverted rule stores the boolean; the fire radius stores 0, or its default when turned back on. */
    private static void emitBooleanSet(ClassWriter cw) {
        MethodVisitor set = cw.visitMethod(ACC_PUBLIC, "set", "(Z" + SERVER_DESC + ")V", null, null);
        set.visitCode();
        Label plain = new Label();
        set.visitVarInsn(ALOAD, 0);
        set.visitFieldInsn(GETFIELD, BOOLEAN, "radius", "Z");
        set.visitJumpInsn(IFEQ, plain);

        Label turnOn = new Label();
        set.visitVarInsn(ILOAD, 1);
        set.visitJumpInsn(IFNE, turnOn);
        loadRulesAndRule(set);
        set.visitInsn(ICONST_0);
        set.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        set.visitVarInsn(ALOAD, 2);
        storeOnHost(set);
        set.visitInsn(RETURN);

        // Turning it on keeps a radius already set and otherwise restores the host's default radius.
        set.visitLabel(turnOn);
        Label off = new Label();
        set.visitVarInsn(ALOAD, 0);
        set.visitMethodInsn(INVOKEVIRTUAL, BOOLEAN, "get", "()Z", false);
        set.visitJumpInsn(IFEQ, off);
        set.visitInsn(RETURN);
        set.visitLabel(off);
        loadRulesAndRule(set);
        set.visitVarInsn(ALOAD, 0);
        set.visitFieldInsn(GETFIELD, VALUE, "rule", RULE_DESC);
        set.visitMethodInsn(INVOKEVIRTUAL, RULE, "defaultValue", "()L" + OBJECT + ";", false);
        set.visitVarInsn(ALOAD, 2);
        storeOnHost(set);
        set.visitInsn(RETURN);

        set.visitLabel(plain);
        loadRulesAndRule(set);
        set.visitVarInsn(ILOAD, 1);
        set.visitVarInsn(ALOAD, 0);
        set.visitFieldInsn(GETFIELD, BOOLEAN, "inverted", "Z");
        set.visitInsn(IXOR);
        set.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
        set.visitVarInsn(ALOAD, 2);
        storeOnHost(set);
        set.visitInsn(RETURN);
        set.visitMaxs(0, 0);
        set.visitEnd();
    }

    /** The integer view: {@code get()} and {@code set(value, server)} on the host's stored integer. */
    private static byte[] integerView(Shape shape) {
        ClassWriter cw = writer();
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, INTEGER, null, VALUE, null);

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + RULES_DESC + RULE_DESC + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitMethodInsn(INVOKESPECIAL, VALUE, "<init>", "(" + RULES_DESC + RULE_DESC + ")V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor get = cw.visitMethod(ACC_PUBLIC, "get", "()I", null, null);
        get.visitCode();
        hostValue(get, "java/lang/Integer", "intValue", "I");
        get.visitInsn(IRETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();

        MethodVisitor set = cw.visitMethod(ACC_PUBLIC, "set", "(I" + SERVER_DESC + ")V", null, null);
        set.visitCode();
        loadRulesAndRule(set);
        set.visitVarInsn(ILOAD, 1);
        set.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        set.visitVarInsn(ALOAD, 2);
        storeOnHost(set);
        set.visitInsn(RETURN);
        set.visitMaxs(0, 0);
        set.visitEnd();

        MethodVisitor from = cw.visitMethod(ACC_PUBLIC, "setFrom", "(L" + INTEGER + ";" + SERVER_DESC + ")V", null, null);
        from.visitCode();
        from.visitVarInsn(ALOAD, 0);
        from.visitVarInsn(ALOAD, 1);
        from.visitMethodInsn(INVOKEVIRTUAL, INTEGER, "get", "()I", false);
        from.visitVarInsn(ALOAD, 2);
        from.visitMethodInsn(INVOKEVIRTUAL, INTEGER, "set", "(I" + SERVER_DESC + ")V", false);
        from.visitInsn(RETURN);
        from.visitMaxs(0, 0);
        from.visitEnd();

        if (shape.registration()) {
            emitCreate(cw, "I", "integer");
            emitCreate(cw, "ILjava/util/function/BiConsumer;", "integer");
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Pushes {@code ((box) rules.get(rule)).unbox()}. */
    private static void hostValue(MethodVisitor mv, String box, String unbox, String primitive) {
        loadRulesAndRule(mv);
        mv.visitMethodInsn(INVOKEVIRTUAL, RULES, "get", "(" + RULE_DESC + ")L" + OBJECT + ";", false);
        mv.visitTypeInsn(CHECKCAST, box);
        mv.visitMethodInsn(INVOKEVIRTUAL, box, unbox, "()" + primitive, false);
    }

    private static void loadRulesAndRule(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, VALUE, "rules", RULES_DESC);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, VALUE, "rule", RULE_DESC);
    }

    /** Calls {@code rules.set(rule, value, server)} with the three on the stack after the rules. */
    private static void storeOnHost(MethodVisitor mv) {
        mv.visitMethodInsn(INVOKEVIRTUAL, RULES, "set", "(" + RULE_DESC + "L" + OBJECT + ";" + SERVER_DESC + ")V", false);
    }

    /** Frames are computed without loading host classes; no branch merges two different reference types. */
    private static ClassWriter writer() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return OBJECT;
            }
        };
    }

    private static boolean method(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    private static boolean named(ClassNode node, String name) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name));
    }

    private static boolean staticField(ClassNode node, String name, String desc) {
        return node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(desc) && (f.access & ACC_STATIC) != 0);
    }

    private static boolean field(ClassNode node, String name) {
        return node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(RULE_DESC));
    }
}
