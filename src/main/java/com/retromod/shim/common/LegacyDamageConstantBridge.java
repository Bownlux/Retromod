/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps the damage source constants and factories that 1.19.4 removed working for older mods.
 *
 * <p>Before 1.19.4 a mod wrote {@code DamageSource.IN_FIRE} or {@code DamageSource.mobAttack(mob)}.
 * Damage sources now come from a level's {@code DamageSources}, keyed by a registered damage type,
 * so the static field read fails with {@code NoSuchFieldError} the first time an entity takes
 * damage. The common use is an {@code isInvulnerableTo} override that compares the incoming source
 * with {@code ==}. That comparison becomes a damage type check, which is what it meant. Any other
 * read takes the source from the running server's overworld. A client connected to a remote server
 * has no server, so it takes the source from its own level, whose damage types the server synced.
 * Mobs tick on the client too, and a null source there would crash in {@code isInvulnerableTo}.
 * The read yields {@code null} only before any level exists.
 *
 * <p>The factories are taken from the first entity argument's own damage sources, as vanilla does.
 * The removed flag predicates such as {@code isExplosion()} ask the matching damage type tag, and
 * {@code new DamageSource(id)} returns the vanilla source with that old message id. A mod's own id
 * has no registered damage type to name, so it gets the generic source and its death message.
 */
public final class LegacyDamageConstantBridge {

    static final String SYNTH = "com/retromod/generated/LegacyDamageConstants";
    static final String DAMAGE_SOURCE = "net/minecraft/world/damagesource/DamageSource";
    private static final String L_DAMAGE_SOURCE = "L" + DAMAGE_SOURCE + ";";
    private static final String DAMAGE_SOURCES = "net/minecraft/world/damagesource/DamageSources";
    private static final String DAMAGE_TYPES = "net/minecraft/world/damagesource/DamageTypes";
    private static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String SERVER = "net/minecraft/server/MinecraftServer";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String TAGS = "net/minecraft/tags/DamageTypeTags";
    private static final String TAG_KEY = "net/minecraft/tags/TagKey";
    private static final String[] SERVER_HOOKS = {
        "net.minecraftforge.server.ServerLifecycleHooks",
        "net.neoforged.neoforge.server.ServerLifecycleHooks"
    };

    /**
     * A removed constant, the {@code DamageSources} getter for it, its damage type key and its old
     * message id. The falling-block getters take the falling entity, which a constant never had.
     */
    record Constant(String legacyField, String sourcesMethod, String typeField, String messageId,
            boolean takesEntity) {
        Constant(String legacyField, String sourcesMethod, String typeField) {
            this(legacyField, sourcesMethod, typeField, sourcesMethod, false);
        }

        String checker() {
            return "is" + Character.toUpperCase(sourcesMethod.charAt(0)) + sourcesMethod.substring(1);
        }
    }

    /** A removed factory whose first argument supplies the damage sources. */
    record Factory(String legacyName, String desc, String sourcesMethod) {
    }

    static List<Constant> constants() {
        List<Constant> constants = new ArrayList<>(List.of(
            new Constant("IN_FIRE", "inFire", "IN_FIRE"),
            new Constant("LIGHTNING_BOLT", "lightningBolt", "LIGHTNING_BOLT"),
            new Constant("ON_FIRE", "onFire", "ON_FIRE"),
            new Constant("LAVA", "lava", "LAVA"),
            new Constant("HOT_FLOOR", "hotFloor", "HOT_FLOOR"),
            new Constant("IN_WALL", "inWall", "IN_WALL"),
            new Constant("CRAMMING", "cramming", "CRAMMING"),
            new Constant("DROWN", "drown", "DROWN"),
            new Constant("STARVE", "starve", "STARVE"),
            new Constant("CACTUS", "cactus", "CACTUS"),
            new Constant("FALL", "fall", "FALL"),
            new Constant("FLY_INTO_WALL", "flyIntoWall", "FLY_INTO_WALL"),
            new Constant("GENERIC", "generic", "GENERIC"),
            new Constant("MAGIC", "magic", "MAGIC"),
            new Constant("WITHER", "wither", "WITHER"),
            new Constant("DRAGON_BREATH", "dragonBreath", "DRAGON_BREATH"),
            new Constant("DRY_OUT", "dryOut", "DRY_OUT", "dryout", false),
            new Constant("SWEET_BERRY_BUSH", "sweetBerryBush", "SWEET_BERRY_BUSH"),
            new Constant("FREEZE", "freeze", "FREEZE"),
            new Constant("STALAGMITE", "stalagmite", "STALAGMITE"),
            new Constant("ANVIL", "anvil", "FALLING_ANVIL", "anvil", true),
            new Constant("FALLING_BLOCK", "fallingBlock", "FALLING_BLOCK", "fallingBlock", true),
            new Constant("FALLING_STALACTITE", "fallingStalactite", "FALLING_STALACTITE",
                    "fallingStalactite", true)));
        // 1.20 renamed the void damage source; 1.19.4 still uses the older name.
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20") >= 0) {
            constants.add(new Constant("OUT_OF_WORLD", "fellOutOfWorld", "FELL_OUT_OF_WORLD",
                    "outOfWorld", false));
        } else {
            constants.add(new Constant("OUT_OF_WORLD", "outOfWorld", "OUT_OF_WORLD"));
        }
        return constants;
    }

    static List<Factory> factories() {
        String entity = "L" + ENTITY + ";";
        String living = "L" + LIVING + ";";
        return List.of(
            new Factory("mobAttack", "(" + living + ")" + L_DAMAGE_SOURCE, "mobAttack"),
            new Factory("sting", "(" + living + ")" + L_DAMAGE_SOURCE, "sting"),
            new Factory("indirectMobAttack", "(" + entity + living + ")" + L_DAMAGE_SOURCE,
                    "mobProjectile"),
            new Factory("playerAttack", "(Lnet/minecraft/world/entity/player/Player;)"
                    + L_DAMAGE_SOURCE, "playerAttack"),
            new Factory("arrow", "(Lnet/minecraft/world/entity/projectile/AbstractArrow;" + entity
                    + ")" + L_DAMAGE_SOURCE, "arrow"),
            new Factory("trident", "(" + entity + entity + ")" + L_DAMAGE_SOURCE, "trident"),
            new Factory("fireworks", "(Lnet/minecraft/world/entity/projectile/FireworkRocketEntity;"
                    + entity + ")" + L_DAMAGE_SOURCE, "fireworks"),
            new Factory("fireball", "(Lnet/minecraft/world/entity/projectile/Fireball;" + entity
                    + ")" + L_DAMAGE_SOURCE, "fireball"),
            new Factory("witherSkull", "(Lnet/minecraft/world/entity/projectile/WitherSkull;"
                    + entity + ")" + L_DAMAGE_SOURCE, "witherSkull"),
            new Factory("thrown", "(" + entity + entity + ")" + L_DAMAGE_SOURCE, "thrown"),
            new Factory("indirectMagic", "(" + entity + entity + ")" + L_DAMAGE_SOURCE,
                    "indirectMagic"),
            new Factory("thorns", "(" + entity + ")" + L_DAMAGE_SOURCE, "thorns"));
    }

    /**
     * 1.19.2 flag predicate, then the damage type tag that holds the same flag. The bridge method is
     * named {@code legacy} plus the predicate, because {@code isFall} and {@code isMagic} are
     * already the damage type checks for the FALL and MAGIC constants.
     */
    private static final String[][] PREDICATES = {
        {"isFire", "IS_FIRE"}, {"isProjectile", "IS_PROJECTILE"}, {"isExplosion", "IS_EXPLOSION"},
        {"isMagic", "WITCH_RESISTANT_TO"}, {"isBypassArmor", "BYPASSES_ARMOR"},
        {"isBypassInvul", "BYPASSES_INVULNERABILITY"}, {"isBypassMagic", "BYPASSES_EFFECTS"},
        {"isBypassEnchantments", "BYPASSES_ENCHANTMENTS"}, {"isFall", "IS_FALL"},
        {"isDamageHelmet", "DAMAGES_HELMET"}, {"isNoAggro", "NO_ANGER"},
    };

    private LegacyDamageConstantBridge() {
    }

    static String predicateBridge(String predicate) {
        return "legacy" + Character.toUpperCase(predicate.charAt(0)) + predicate.substring(1);
    }

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(SYNTH, generate());
        for (Factory factory : factories()) {
            transformer.registerMethodRedirect(DAMAGE_SOURCE, factory.legacyName(), factory.desc(),
                    SYNTH, factory.legacyName(), factory.desc());
        }
        String explosionDesc = "(L" + LIVING + ";)" + L_DAMAGE_SOURCE;
        transformer.registerMethodRedirect(DAMAGE_SOURCE, "explosion", explosionDesc,
                SYNTH, "explosion", explosionDesc);
        for (String[] predicate : PREDICATES) {
            transformer.registerMethodRedirect(DAMAGE_SOURCE, predicate[0], "()Z",
                    SYNTH, predicateBridge(predicate[0]), "(" + L_DAMAGE_SOURCE + ")Z", true);
        }
        transformer.registerConstructorRedirect(DAMAGE_SOURCE, "(Ljava/lang/String;)V",
                SYNTH, "named", "(Ljava/lang/String;)" + L_DAMAGE_SOURCE);
    }

    /** True once the bridge is registered, so the call-site pass runs only where it can link. */
    public static boolean isRegistered(RetromodTransformer transformer) {
        return transformer.getSyntheticClasses().containsKey(SYNTH);
    }

    /**
     * Rewrites reads of the removed constants. {@code source == DamageSource.X}, the reversed
     * {@code DamageSource.X == source} with a local, and {@code source.equals(DamageSource.X)} become
     * a damage type check; any other read becomes a call that returns the server's source for that
     * type.
     *
     * @return the repaired class, or the same array when nothing matched
     */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null) return null;
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (Throwable unreadable) {
            return classBytes;
        }
        if (node.name.startsWith("com/retromod/") || node.name.startsWith("net/minecraft/")) {
            return classBytes;
        }
        List<Constant> constants = constants();
        int changes = 0;
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() != GETSTATIC) continue;
                FieldInsnNode field = (FieldInsnNode) insn;
                if (!DAMAGE_SOURCE.equals(field.owner) || !L_DAMAGE_SOURCE.equals(field.desc)) continue;
                Constant constant = find(constants, field.name);
                if (constant == null) continue;
                AbstractInsnNode next = nextReal(field);
                AbstractInsnNode after = next == null ? null : nextReal(next);
                if (next != null && next.getOpcode() == ALOAD && after != null
                        && (after.getOpcode() == IF_ACMPEQ || after.getOpcode() == IF_ACMPNE)
                        && isDamageSourceLocal(method, ((VarInsnNode) next).var)) {
                    // DamageSource.X == source: the source is loaded after the constant.
                    JumpInsnNode compare = (JumpInsnNode) after;
                    method.instructions.remove(field);
                    method.instructions.insert(next, new MethodInsnNode(INVOKESTATIC, SYNTH,
                            constant.checker(), "(" + L_DAMAGE_SOURCE + ")Z", false));
                    method.instructions.set(compare, new JumpInsnNode(
                            compare.getOpcode() == IF_ACMPEQ ? IFNE : IFEQ, compare.label));
                } else if (next instanceof MethodInsnNode equals && equals.getOpcode() == INVOKEVIRTUAL
                        && DAMAGE_SOURCE.equals(equals.owner) && "equals".equals(equals.name)
                        && "(Ljava/lang/Object;)Z".equals(equals.desc)) {
                    // source.equals(DamageSource.X): the per-level source is a different object.
                    method.instructions.remove(field);
                    method.instructions.set(equals, new MethodInsnNode(INVOKESTATIC, SYNTH,
                            constant.checker(), "(" + L_DAMAGE_SOURCE + ")Z", false));
                } else if (next != null && (next.getOpcode() == IF_ACMPEQ || next.getOpcode() == IF_ACMPNE)) {
                    // The other operand is already on the stack, so the check consumes it alone.
                    JumpInsnNode compare = (JumpInsnNode) next;
                    method.instructions.set(field, new MethodInsnNode(INVOKESTATIC, SYNTH,
                            constant.checker(), "(" + L_DAMAGE_SOURCE + ")Z", false));
                    method.instructions.set(compare, new JumpInsnNode(
                            compare.getOpcode() == IF_ACMPEQ ? IFNE : IFEQ, compare.label));
                } else {
                    method.instructions.set(field, new MethodInsnNode(INVOKESTATIC, SYNTH,
                            constant.sourcesMethod(), "()" + L_DAMAGE_SOURCE, false));
                }
                changes++;
            }
        }
        if (changes == 0) return classBytes;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static Constant find(List<Constant> constants, String name) {
        for (Constant constant : constants) {
            if (constant.legacyField().equals(name)) return constant;
        }
        return null;
    }

    /** Whether local {@code index} is declared as a DamageSource, so the check can take it. */
    private static boolean isDamageSourceLocal(MethodNode method, int index) {
        int slot = (method.access & ACC_STATIC) != 0 ? 0 : 1;
        for (org.objectweb.asm.Type parameter : org.objectweb.asm.Type.getArgumentTypes(method.desc)) {
            if (slot == index) return L_DAMAGE_SOURCE.equals(parameter.getDescriptor());
            slot += parameter.getSize();
        }
        if (method.localVariables == null) return false;
        for (var local : method.localVariables) {
            if (local.index == index && !L_DAMAGE_SOURCE.equals(local.desc)) return false;
        }
        return method.localVariables.stream().anyMatch(local -> local.index == index);
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SYNTH, null, "java/lang/Object", null);
        emitServerLookup(cw);
        emitServerSources(cw);
        for (Constant constant : constants()) {
            emitConstant(cw, constant);
            emitChecker(cw, constant);
        }
        for (Factory factory : factories()) {
            emitFactory(cw, factory);
        }
        emitExplosion(cw);
        for (String[] predicate : PREDICATES) {
            emitPredicate(cw, predicate[0], predicate[1]);
        }
        emitNamed(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * {@code currentServer()}: the loader's current server, or null. Mods read these constants in
     * hot damage handlers, so the reflective {@code getCurrentServer} lookup runs once and is kept
     * in {@code serverGetter}; only the invoke happens per read. The getter is published before the
     * volatile {@code serverGetterResolved} flag, so a reader that sees the flag sees the getter.
     */
    private static void emitServerLookup(ClassWriter cw) {
        String method = "Ljava/lang/reflect/Method;";
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_VOLATILE, "serverGetter", method, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_VOLATILE, "serverGetterResolved", "Z", null, null).visitEnd();

        // getter(hooksClass): hooksClass.getCurrentServer as a Method, or null.
        MethodVisitor m = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "getter",
                "(Ljava/lang/String;)" + method, null, null);
        m.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        m.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        m.visitLabel(start);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;)Ljava/lang/Class;", false);
        m.visitLdcInsn("getCurrentServer");
        m.visitInsn(ICONST_0);
        m.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        m.visitLabel(end);
        m.visitInsn(ARETURN);
        m.visitLabel(handler);
        m.visitInsn(POP);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        // serverGetter(): resolved once across the loader's hook classes.
        m = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "serverGetter", "()" + method, null, null);
        m.visitCode();
        Label resolved = new Label();
        Label store = new Label();
        m.visitFieldInsn(GETSTATIC, SYNTH, "serverGetterResolved", "Z");
        m.visitJumpInsn(IFNE, resolved);
        for (String hooks : SERVER_HOOKS) {
            m.visitLdcInsn(hooks);
            m.visitMethodInsn(INVOKESTATIC, SYNTH, "getter", "(Ljava/lang/String;)" + method, false);
            m.visitInsn(DUP);
            m.visitJumpInsn(IFNONNULL, store);
            m.visitInsn(POP);
        }
        m.visitInsn(ACONST_NULL);
        m.visitLabel(store);
        m.visitFieldInsn(PUTSTATIC, SYNTH, "serverGetter", method);
        m.visitInsn(ICONST_1);
        m.visitFieldInsn(PUTSTATIC, SYNTH, "serverGetterResolved", "Z");
        m.visitLabel(resolved);
        m.visitFieldInsn(GETSTATIC, SYNTH, "serverGetter", method);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();

        // currentServer(): invokes the cached getter, or null.
        m = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "currentServer", "()Ljava/lang/Object;", null, null);
        m.visitCode();
        Label invokeStart = new Label();
        Label invokeEnd = new Label();
        Label invokeFailed = new Label();
        Label none = new Label();
        m.visitTryCatchBlock(invokeStart, invokeEnd, invokeFailed, "java/lang/Throwable");
        m.visitMethodInsn(INVOKESTATIC, SYNTH, "serverGetter", "()" + method, false);
        m.visitInsn(DUP);
        m.visitJumpInsn(IFNULL, none);
        m.visitLabel(invokeStart);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ICONST_0);
        m.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        m.visitLabel(invokeEnd);
        m.visitInsn(ARETURN);
        m.visitLabel(invokeFailed);
        m.visitInsn(POP);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ARETURN);
        m.visitLabel(none);
        m.visitInsn(POP);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code sources()}: the overworld's damage sources, else the client level's, else null. */
    private static void emitServerSources(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "sources",
                "()L" + DAMAGE_SOURCES + ";", null, null);
        m.visitCode();
        Label found = new Label();
        Label noLevel = new Label();
        Label client = new Label();
        m.visitMethodInsn(INVOKESTATIC, SYNTH, "currentServer", "()Ljava/lang/Object;", false);
        m.visitInsn(DUP);
        m.visitJumpInsn(IFNONNULL, found);
        m.visitInsn(POP);
        m.visitJumpInsn(GOTO, client);
        m.visitLabel(found);
        m.visitTypeInsn(CHECKCAST, SERVER);
        m.visitMethodInsn(INVOKEVIRTUAL, SERVER, "overworld", "()L" + SERVER_LEVEL + ";", false);
        // A server that is still starting has no overworld yet.
        m.visitInsn(DUP);
        m.visitJumpInsn(IFNULL, noLevel);
        m.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "damageSources", "()L" + DAMAGE_SOURCES + ";", false);
        m.visitInsn(ARETURN);
        m.visitLabel(noLevel);
        m.visitInsn(POP);
        m.visitLabel(client);
        m.visitMethodInsn(INVOKESTATIC, SYNTH, "clientSources", "()L" + DAMAGE_SOURCES + ";", false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        emitClientSources(cw);
    }

    /**
     * {@code clientSources()}: the client level's damage sources, or null. Kept in its own method
     * so the client classes are resolved only when it runs. On a dedicated server that resolution
     * throws {@code NoClassDefFoundError}, which means there is no client level to use. The level
     * is cast before the call, so verifying the class never loads {@code ClientLevel}.
     */
    private static void emitClientSources(ClassWriter cw) {
        String minecraft = "net/minecraft/client/Minecraft";
        MethodVisitor m = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "clientSources",
                "()L" + DAMAGE_SOURCES + ";", null, null);
        m.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        Label noLevel = new Label();
        m.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        m.visitLabel(start);
        m.visitMethodInsn(INVOKESTATIC, minecraft, "getInstance", "()L" + minecraft + ";", false);
        m.visitFieldInsn(GETFIELD, minecraft, "level", "Lnet/minecraft/client/multiplayer/ClientLevel;");
        m.visitInsn(DUP);
        m.visitJumpInsn(IFNULL, noLevel);
        m.visitTypeInsn(CHECKCAST, LEVEL);
        m.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "damageSources", "()L" + DAMAGE_SOURCES + ";", false);
        m.visitLabel(end);
        m.visitInsn(ARETURN);
        m.visitLabel(noLevel);
        m.visitInsn(POP);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ARETURN);
        m.visitLabel(handler);
        m.visitInsn(POP);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code inFire()} and the rest: the server's source for one type, or null. */
    private static void emitConstant(ClassWriter cw, Constant constant) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, constant.sourcesMethod(),
                "()" + L_DAMAGE_SOURCE, null, null);
        m.visitCode();
        Label missing = new Label();
        m.visitMethodInsn(INVOKESTATIC, SYNTH, "sources", "()L" + DAMAGE_SOURCES + ";", false);
        m.visitInsn(DUP);
        m.visitJumpInsn(IFNULL, missing);
        if (constant.takesEntity()) {
            m.visitInsn(ACONST_NULL);
            m.visitMethodInsn(INVOKEVIRTUAL, DAMAGE_SOURCES, constant.sourcesMethod(),
                    "(L" + ENTITY + ";)" + L_DAMAGE_SOURCE, false);
        } else {
            m.visitMethodInsn(INVOKEVIRTUAL, DAMAGE_SOURCES, constant.sourcesMethod(),
                    "()" + L_DAMAGE_SOURCE, false);
        }
        m.visitInsn(ARETURN);
        m.visitLabel(missing);
        m.visitInsn(POP);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code isInFire(source)}: whether a source has that damage type. */
    private static void emitChecker(ClassWriter cw, Constant constant) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, constant.checker(),
                "(" + L_DAMAGE_SOURCE + ")Z", null, null);
        m.visitCode();
        Label missing = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitJumpInsn(IFNULL, missing);
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETSTATIC, DAMAGE_TYPES, constant.typeField(), "L" + RESOURCE_KEY + ";");
        m.visitMethodInsn(INVOKEVIRTUAL, DAMAGE_SOURCE, "is", "(L" + RESOURCE_KEY + ";)Z", false);
        m.visitInsn(IRETURN);
        m.visitLabel(missing);
        m.visitInsn(ICONST_0);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code mobAttack(mob)} and the rest: the same factory on the first argument's sources. */
    private static void emitFactory(ClassWriter cw, Factory factory) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, factory.legacyName(),
                factory.desc(), null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "damageSources", "()L" + DAMAGE_SOURCES + ";", false);
        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(factory.desc());
        for (int i = 0; i < args.length; i++) {
            m.visitVarInsn(ALOAD, i);
        }
        m.visitMethodInsn(INVOKEVIRTUAL, DAMAGE_SOURCES, factory.sourcesMethod(), factory.desc(), false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code explosion(mob)}: an explosion caused by the mob, or by nothing when it is null. */
    private static void emitExplosion(ClassWriter cw) {
        String entity = "L" + ENTITY + ";";
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "explosion",
                "(L" + LIVING + ";)" + L_DAMAGE_SOURCE, null, null);
        m.visitCode();
        Label noCause = new Label();
        Label noServer = new Label();
        m.visitVarInsn(ALOAD, 0);
        m.visitJumpInsn(IFNULL, noCause);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, ENTITY, "damageSources", "()L" + DAMAGE_SOURCES + ";", false);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKEVIRTUAL, DAMAGE_SOURCES, "explosion",
                "(" + entity + entity + ")" + L_DAMAGE_SOURCE, false);
        m.visitInsn(ARETURN);
        m.visitLabel(noCause);
        m.visitMethodInsn(INVOKESTATIC, SYNTH, "sources", "()L" + DAMAGE_SOURCES + ";", false);
        m.visitInsn(DUP);
        m.visitJumpInsn(IFNULL, noServer);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(INVOKEVIRTUAL, DAMAGE_SOURCES, "explosion",
                "(" + entity + entity + ")" + L_DAMAGE_SOURCE, false);
        m.visitInsn(ARETURN);
        m.visitLabel(noServer);
        m.visitInsn(POP);
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /** {@code legacyIsFire(source)} and the rest: {@code source.is(DamageTypeTags.TAG)}. */
    private static void emitPredicate(ClassWriter cw, String predicate, String tag) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, predicateBridge(predicate),
                "(" + L_DAMAGE_SOURCE + ")Z", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETSTATIC, TAGS, tag, "L" + TAG_KEY + ";");
        m.visitMethodInsn(INVOKEVIRTUAL, DAMAGE_SOURCE, "is", "(L" + TAG_KEY + ";)Z", false);
        m.visitInsn(IRETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }

    /**
     * {@code named(id)}: the vanilla source whose old message id is {@code id}, else the generic
     * source. MCreator wrote {@code new DamageSource("onFire")} for vanilla kinds of damage.
     */
    private static void emitNamed(ClassWriter cw) {
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "named",
                "(Ljava/lang/String;)" + L_DAMAGE_SOURCE, null, null);
        m.visitCode();
        for (Constant constant : constants()) {
            if (constant.takesEntity()) continue;
            Label next = new Label();
            m.visitLdcInsn(constant.messageId());
            m.visitVarInsn(ALOAD, 0);
            m.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
            m.visitJumpInsn(IFEQ, next);
            m.visitMethodInsn(INVOKESTATIC, SYNTH, constant.sourcesMethod(), "()" + L_DAMAGE_SOURCE, false);
            m.visitInsn(ARETURN);
            m.visitLabel(next);
        }
        m.visitMethodInsn(INVOKESTATIC, SYNTH, "generic", "()" + L_DAMAGE_SOURCE, false);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
    }
}
