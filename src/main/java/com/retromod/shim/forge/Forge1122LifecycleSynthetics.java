/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 *
 * 1.12.2 FML lifecycle bridge: @Mod(modid=...) + @Mod.EventHandler onto the modern loader.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;

/**
 * The pre-1.13 Forge lifecycle: a 1.12.2 mod is a {@code @Mod(modid="x", name=..., version=...)}
 * class whose setup lives in {@code @Mod.EventHandler} methods taking
 * {@code FMLPreInitializationEvent} / {@code FMLInitializationEvent} /
 * {@code FMLPostInitializationEvent} (all verified against the real
 * forge-1.12.2-14.23.5.2859-universal jar). None of that exists on a modern loader: the modern
 * {@code @Mod} is value-shaped (so FML's scanner finds no mod id in the old attribute form), the
 * event classes are gone, and nothing ever calls the handlers.
 *
 * <p>Three pieces bridge it:
 * <ul>
 *   <li>{@link #upgradeLegacyModClass}: a self-gating bytecode pass that rewrites the old
 *       {@code @Mod} attributes to the modern {@code @Mod("modid")} shape (so the mod is
 *       scanned at all) and, when the class has lifecycle handlers, injects a call to the
 *       lifecycle bridge at the end of each constructor. Registration-wise this timing works
 *       out: handlers run at construct time, before the registry events, and 1.12.2 content
 *       registration goes through {@code RegistryEvent.Register} listeners (GameRegistry has no
 *       register statics in 1.12.2), which ride the existing event-bus bridges.</li>
 *   <li>Event stand-ins (embedded per-mod) with the commonly used 1.12.2 surface:
 *       {@code getModConfigurationDirectory()}, {@code getSuggestedConfigurationFile()},
 *       {@code getSourceFile()}, {@code getModLog()}. Rarely used members that would drag in
 *       other deleted FML types ({@code getModMetadata()}, {@code getAsmData()}) are omitted;
 *       calling them fails exactly as visibly as before this bridge existed.</li>
 *   <li>{@code LegacyFmlLifecycle} (embedded): reflectively finds the handler methods by their
 *       (redirected) event parameter type and invokes them in pre/init/post order, soft-failing
 *       per handler so one broken handler does not kill construction.</li>
 * </ul>
 */
public final class Forge1122LifecycleSynthetics {

    private Forge1122LifecycleSynthetics() {}

    public static final String BRIDGE = "com/retromod/shim/forge/embedded/LegacyFmlLifecycle";
    public static final String PRE_EVENT =
            "com/retromod/shim/forge/embedded/FMLPreInitializationEvent";
    public static final String INIT_EVENT =
            "com/retromod/shim/forge/embedded/FMLInitializationEvent";
    public static final String POST_EVENT =
            "com/retromod/shim/forge/embedded/FMLPostInitializationEvent";

    private static final String OLD_PRE =
            "net/minecraftforge/fml/common/event/FMLPreInitializationEvent";
    private static final String OLD_INIT =
            "net/minecraftforge/fml/common/event/FMLInitializationEvent";
    private static final String OLD_POST =
            "net/minecraftforge/fml/common/event/FMLPostInitializationEvent";

    private static final String MOD_ANNOTATION_DESC = "Lnet/minecraftforge/fml/common/Mod;";

    /** Set when the 1.12.2 chain registered; gates {@link #upgradeLegacyModClass}. */
    private static volatile boolean active;

    /** Test hook: reset the gate between cases. */
    static void resetForTesting() {
        active = false;
    }

    /** Register the synthetics + the event class redirects, and arm the upgrade pass. */
    public static void register(RetromodTransformer t) {
        active = true;
        t.registerSyntheticClass(BRIDGE, generateBridge());
        t.registerSyntheticClass(PRE_EVENT, generateEvent(PRE_EVENT, true));
        t.registerSyntheticClass(INIT_EVENT, generateEvent(INIT_EVENT, false));
        t.registerSyntheticClass(POST_EVENT, generateEvent(POST_EVENT, false));
        t.registerClassRedirect(OLD_PRE, PRE_EVENT);
        t.registerClassRedirect(OLD_INIT, INIT_EVENT);
        t.registerClassRedirect(OLD_POST, POST_EVENT);
    }

    private static ClassWriter newWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
    }

    /**
     * One FML lifecycle event stand-in. All three carry the modid (for the config-file name
     * and the logger); only the pre-init event exposes the config/file surface, matching where
     * 1.12.2 mods actually use it.
     */
    public static byte[] generateEvent(String internal, boolean preInitSurface) {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC, internal, null, "java/lang/Object", null);
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "modid", "Ljava/lang/String;", null, null).visitEnd();

        MethodVisitor c = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/String;)V", null, null);
        c.visitCode();
        c.visitVarInsn(ALOAD, 0);
        c.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitVarInsn(ALOAD, 0);
        c.visitVarInsn(ALOAD, 1);
        c.visitFieldInsn(PUTFIELD, internal, "modid", "Ljava/lang/String;");
        c.visitInsn(RETURN);
        c.visitMaxs(0, 0);
        c.visitEnd();

        if (preInitSurface) {
            // File getModConfigurationDirectory() { return new File("config"); }
            MethodVisitor d = cw.visitMethod(ACC_PUBLIC, "getModConfigurationDirectory",
                    "()Ljava/io/File;", null, null);
            d.visitCode();
            d.visitTypeInsn(NEW, "java/io/File");
            d.visitInsn(DUP);
            d.visitLdcInsn("config");
            d.visitMethodInsn(INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false);
            d.visitInsn(ARETURN);
            d.visitMaxs(0, 0);
            d.visitEnd();

            // File getSuggestedConfigurationFile() { return new File("config", modid + ".cfg"); }
            MethodVisitor s = cw.visitMethod(ACC_PUBLIC, "getSuggestedConfigurationFile",
                    "()Ljava/io/File;", null, null);
            s.visitCode();
            s.visitTypeInsn(NEW, "java/io/File");
            s.visitInsn(DUP);
            s.visitLdcInsn("config");
            s.visitVarInsn(ALOAD, 0);
            s.visitFieldInsn(GETFIELD, internal, "modid", "Ljava/lang/String;");
            s.visitLdcInsn(".cfg");
            s.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "concat",
                    "(Ljava/lang/String;)Ljava/lang/String;", false);
            s.visitMethodInsn(INVOKESPECIAL, "java/io/File", "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false);
            s.visitInsn(ARETURN);
            s.visitMaxs(0, 0);
            s.visitEnd();

            // File getSourceFile() { return new File("."); }
            MethodVisitor f = cw.visitMethod(ACC_PUBLIC, "getSourceFile", "()Ljava/io/File;", null, null);
            f.visitCode();
            f.visitTypeInsn(NEW, "java/io/File");
            f.visitInsn(DUP);
            f.visitLdcInsn(".");
            f.visitMethodInsn(INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false);
            f.visitInsn(ARETURN);
            f.visitMaxs(0, 0);
            f.visitEnd();

            // Logger getModLog() { return LogManager.getLogger(modid); } (log4j ships on
            // every loader's runtime classpath)
            MethodVisitor l = cw.visitMethod(ACC_PUBLIC, "getModLog",
                    "()Lorg/apache/logging/log4j/Logger;", null, null);
            l.visitCode();
            l.visitVarInsn(ALOAD, 0);
            l.visitFieldInsn(GETFIELD, internal, "modid", "Ljava/lang/String;");
            l.visitMethodInsn(INVOKESTATIC, "org/apache/logging/log4j/LogManager", "getLogger",
                    "(Ljava/lang/String;)Lorg/apache/logging/log4j/Logger;", false);
            l.visitInsn(ARETURN);
            l.visitMaxs(0, 0);
            l.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * The lifecycle bridge:
     * {@code fire(mod, modid)} runs the mod's handlers in pre/init/post order;
     * {@code fireOne} finds handlers reflectively by their (redirected) event parameter type,
     * constructs the event with the modid, and soft-fails per handler.
     */
    public static byte[] generateBridge() {
        ClassWriter cw = newWriter();
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL, BRIDGE, null, "java/lang/Object", null);

        // public static void fire(Object mod, String modid)
        MethodVisitor f = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "fire",
                "(Ljava/lang/Object;Ljava/lang/String;)V", null, null);
        f.visitCode();
        for (String ev : new String[]{PRE_EVENT, INIT_EVENT, POST_EVENT}) {
            f.visitVarInsn(ALOAD, 0);
            f.visitVarInsn(ALOAD, 1);
            f.visitLdcInsn(Type.getObjectType(ev));
            f.visitMethodInsn(INVOKESTATIC, BRIDGE, "fireOne",
                    "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Class;)V", false);
        }
        f.visitInsn(RETURN);
        f.visitMaxs(0, 0);
        f.visitEnd();

        // static void fireOne(Object mod, String modid, Class ev):
        //   for (Method m : mod.getClass().getDeclaredMethods())
        //     if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == ev)
        //       try { m.setAccessible(true);
        //             m.invoke(mod, ev.getDeclaredConstructor(String.class).newInstance(modid)); }
        //       catch (Throwable t) { t.printStackTrace(); }
        MethodVisitor o = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "fireOne",
                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Class;)V", null, null);
        o.visitCode();
        Label loop = new Label(), next = new Label(), end = new Label();
        o.visitVarInsn(ALOAD, 0);
        o.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        o.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethods",
                "()[Ljava/lang/reflect/Method;", false);
        o.visitVarInsn(ASTORE, 3);
        o.visitInsn(ICONST_0);
        o.visitVarInsn(ISTORE, 4);
        o.visitLabel(loop);
        o.visitVarInsn(ILOAD, 4);
        o.visitVarInsn(ALOAD, 3);
        o.visitInsn(ARRAYLENGTH);
        o.visitJumpInsn(IF_ICMPGE, end);
        o.visitVarInsn(ALOAD, 3);
        o.visitVarInsn(ILOAD, 4);
        o.visitInsn(AALOAD);
        o.visitVarInsn(ASTORE, 5);
        o.visitVarInsn(ALOAD, 5);
        o.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "getParameterCount", "()I", false);
        o.visitInsn(ICONST_1);
        o.visitJumpInsn(IF_ICMPNE, next);
        o.visitVarInsn(ALOAD, 5);
        o.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "getParameterTypes",
                "()[Ljava/lang/Class;", false);
        o.visitInsn(ICONST_0);
        o.visitInsn(AALOAD);
        o.visitVarInsn(ALOAD, 2);
        o.visitJumpInsn(IF_ACMPNE, next);
        Label tryStart = new Label(), tryEnd = new Label(), handler = new Label();
        o.visitTryCatchBlock(tryStart, tryEnd, handler, "java/lang/Throwable");
        o.visitLabel(tryStart);
        o.visitVarInsn(ALOAD, 5);
        o.visitInsn(ICONST_1);
        o.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "setAccessible", "(Z)V", false);
        o.visitVarInsn(ALOAD, 5);
        o.visitVarInsn(ALOAD, 0);
        o.visitInsn(ICONST_1);
        o.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        o.visitInsn(DUP);
        o.visitInsn(ICONST_0);
        // ev.getDeclaredConstructor(String.class).newInstance(modid)
        o.visitVarInsn(ALOAD, 2);
        o.visitInsn(ICONST_1);
        o.visitTypeInsn(ANEWARRAY, "java/lang/Class");
        o.visitInsn(DUP);
        o.visitInsn(ICONST_0);
        o.visitLdcInsn(Type.getObjectType("java/lang/String"));
        o.visitInsn(AASTORE);
        o.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredConstructor",
                "([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;", false);
        o.visitInsn(ICONST_1);
        o.visitTypeInsn(ANEWARRAY, "java/lang/Object");
        o.visitInsn(DUP);
        o.visitInsn(ICONST_0);
        o.visitVarInsn(ALOAD, 1);
        o.visitInsn(AASTORE);
        o.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Constructor", "newInstance",
                "([Ljava/lang/Object;)Ljava/lang/Object;", false);
        o.visitInsn(AASTORE);
        o.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        o.visitInsn(POP);
        o.visitLabel(tryEnd);
        o.visitJumpInsn(GOTO, next);
        o.visitLabel(handler);
        o.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Throwable", "printStackTrace", "()V", false);
        o.visitLabel(next);
        o.visitIincInsn(4, 1);
        o.visitJumpInsn(GOTO, loop);
        o.visitLabel(end);
        o.visitInsn(RETURN);
        o.visitMaxs(0, 0);
        o.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Self-gating upgrade pass for 1.12.2 {@code @Mod} classes. Rewrites the attribute-shaped
     * annotation to the modern value shape (modern FML reads only {@code value}, so without
     * this the mod is never registered even when scanned), and injects the lifecycle-bridge
     * call at the tail of every non-delegating constructor when the class has lifecycle
     * handlers. Modern mods are untouched: their {@code @Mod} has no {@code modid} element.
     * Returns the original array when nothing changed.
     */
    public static byte[] upgradeLegacyModClass(byte[] classBytes) {
        if (!active || classBytes == null) return classBytes;
        // cheap pre-filter before a full parse. BOTH spellings: depending on pass ordering the
        // class may already be remapped to the NeoForge annotation before this runs, and matching
        // only the Forge spelling silently skipped the value-shape upgrade, so the mod was scanned
        // but never registered (#140, Cooking for Blockheads 1.12.2 on a NeoForge host). The
        // modid-element gate below still protects modern value-shaped @Mod classes.
        String pool = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (pool.indexOf("net/minecraftforge/fml/common/Mod") < 0
                && pool.indexOf("net/neoforged/fml/common/Mod") < 0) return classBytes;
        try {
            ClassNode cn = new ClassNode();
            new org.objectweb.asm.ClassReader(classBytes).accept(cn, 0);

            String modid = null;
            AnnotationNode modAnn = null;
            if (cn.visibleAnnotations != null) {
                for (AnnotationNode ann : cn.visibleAnnotations) {
                    boolean modDesc = MOD_ANNOTATION_DESC.equals(ann.desc)
                            || "Lnet/neoforged/fml/common/Mod;".equals(ann.desc);
                    if (!modDesc || ann.values == null) continue;
                    for (int i = 0; i + 1 < ann.values.size(); i += 2) {
                        if ("modid".equals(ann.values.get(i))
                                && ann.values.get(i + 1) instanceof String s) {
                            modid = s;
                            modAnn = ann;
                        }
                    }
                }
            }
            if (modid == null) return classBytes; // modern @Mod (or none): not a 1.12.2 mod class

            modAnn.values = new ArrayList<>(List.of("value", modid));

            boolean hasHandlers = false;
            for (MethodNode m : cn.methods) {
                Type[] args = Type.getArgumentTypes(m.desc);
                if (args.length != 1) continue;
                String a = args[0].getInternalName();
                if (a.equals(PRE_EVENT) || a.equals(INIT_EVENT) || a.equals(POST_EVENT)
                        || a.equals(OLD_PRE) || a.equals(OLD_INIT) || a.equals(OLD_POST)) {
                    hasHandlers = true;
                    break;
                }
            }

            if (hasHandlers) {
                for (MethodNode m : cn.methods) {
                    if (!m.name.equals("<init>")) continue;
                    if (delegatesToThis(cn, m)) continue;
                    for (AbstractInsnNode in : m.instructions.toArray()) {
                        if (in.getOpcode() == RETURN) {
                            m.instructions.insertBefore(in, new VarInsnNode(ALOAD, 0));
                            m.instructions.insertBefore(in, new LdcInsnNode(modid));
                            m.instructions.insertBefore(in, new MethodInsnNode(INVOKESTATIC,
                                    BRIDGE, "fire", "(Ljava/lang/Object;Ljava/lang/String;)V", false));
                        }
                    }
                }
            }

            // the injected tail is stack-neutral at a point where the stack is empty, so the
            // existing frames stay valid; only maxStack needs recomputing
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable t) {
            return classBytes;
        }
    }

    /** Whether this ctor's first {@code <init>} call targets the same class (this(...) chain). */
    private static boolean delegatesToThis(ClassNode cn, MethodNode ctor) {
        for (AbstractInsnNode in : ctor.instructions.toArray()) {
            if (in instanceof MethodInsnNode mi && mi.getOpcode() == INVOKESPECIAL
                    && mi.name.equals("<init>")) {
                return mi.owner.equals(cn.name);
            }
        }
        return false;
    }
}
