/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.util.RetromodLog;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Lets reload listeners written for an older {@code PreparableReloadListener.reload} shape run on hosts from 1.21.2. */
public final class LegacyReloadListenerBridge implements Opcodes {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    /** Post-process flag for hosts from 1.21.2 to 1.21.8. */
    public static final String BARRIER_FLAG = "legacy-reload-listener-barrier";
    /** Post-process flag for hosts from 1.21.9. */
    public static final String STATE_FLAG = "legacy-reload-listener-state";

    static final String HELPER = "com/retromod/generated/LegacyReloadListeners";
    static final String EXECUTOR = "java/util/concurrent/Executor";
    static final String THREAD_LOCAL = "java/lang/ThreadLocal";
    static final String FUTURE = "java/util/concurrent/CompletableFuture";

    /** The shapes of {@code reload}, oldest first. */
    public enum Shape {
        // Until 1.21.1: barrier, resources, preparation profiler, reload profiler, background, game.
        PROFILED("com/retromod/generated/LegacyReloadListener"),
        // 1.21.2 to 1.21.8 dropped the two profilers.
        BARRIER("com/retromod/generated/LegacyReloadListener1212"),
        // 1.21.9 and newer: shared state, background, barrier, game. The resources moved into the state.
        STATE(null);

        /** The generated interface a lambda of this old shape is built as. */
        final String lambdaInterface;

        Shape(String lambdaInterface) {
            this.lambdaInterface = lambdaInterface;
        }

        /** Name of the helper's identity method that returns such a lambda as the host interface. */
        String wrapName() {
            return this == PROFILED ? "listener" : "listener1212";
        }
    }

    /** The reload types in one namespace. */
    public record Names(String listener, String barrier, String state, String resources, String profiler,
            String inactive, String inactiveField, String reload, String stateResources, String prepare) {

        public static final Names MOJANG = new Names(
                "net/minecraft/server/packs/resources/PreparableReloadListener",
                "net/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier",
                "net/minecraft/server/packs/resources/PreparableReloadListener$SharedState",
                "net/minecraft/server/packs/resources/ResourceManager",
                "net/minecraft/util/profiling/ProfilerFiller",
                "net/minecraft/util/profiling/InactiveProfiler", "INSTANCE",
                "reload", "resourceManager", "prepareSharedState");

        // Fabric hosts before 26.1 run these. Checked against the 1.21.1, 1.21.2, 1.21.8, 1.21.9 and
        // 1.21.11 intermediary tables: reload keeps method_25931 through every shape, and
        // prepareSharedState was never obfuscated.
        public static final Names INTERMEDIARY = new Names(
                "net/minecraft/class_3302", "net/minecraft/class_3302$class_4045",
                "net/minecraft/class_3302$class_11558", "net/minecraft/class_3300",
                "net/minecraft/class_3695", "net/minecraft/class_3694", "field_16280",
                "method_25931", "method_72361", "prepareSharedState");

        /** The descriptor of {@code reload} in the given shape. */
        public String desc(Shape shape) {
            String barrierType = "L" + barrier + ";";
            String executor = "L" + EXECUTOR + ";";
            String profilerType = "L" + profiler + ";";
            String args = switch (shape) {
                case PROFILED -> barrierType + "L" + resources + ";" + profilerType + profilerType + executor + executor;
                case BARRIER -> barrierType + "L" + resources + ";" + executor + executor;
                case STATE -> "L" + state + ";" + executor + barrierType + executor;
            };
            return "(" + args + ")L" + FUTURE + ";";
        }
    }

    private LegacyReloadListenerBridge() {}

    /** Registers the bridge in Mojang names, for Forge, NeoForge, and Fabric on 26.x. */
    public static void register(RetromodTransformer transformer) {
        register(transformer, Names.MOJANG);
    }

    /** Registers the bridge in intermediary names, for Fabric hosts from 1.21.2 to 1.21.11. */
    public static void registerIntermediary(RetromodTransformer transformer) {
        register(transformer, Names.INTERMEDIARY);
    }

    static void register(RetromodTransformer transformer, Names names) {
        Shape host = hostShape(names);
        if (host == Shape.PROFILED) {
            LOGGER.debug("Reload listener bridge not needed: the host keeps the old reload shape");
            return;
        }
        registerFor(transformer, names, host);
        // Each loader registers this from two shims, so the notice prints once.
        if (transformer.firstRegistrationNotice(LegacyReloadListenerBridge.class.getName() + names.listener())) {
            LOGGER.debug(RetromodLog.DETAIL, "Bridged old reload listener shapes to the host's {} shape", host);
        }
    }

    /** Unconditional registration for a known host shape. */
    static void registerFor(RetromodTransformer transformer, Names names, Shape host) {
        transformer.registerSyntheticClass(HELPER, generateHelper(names, host));
        // Lambdas and method references of each older shape are built as a generated interface
        // whose host method calls the old one.
        for (Shape old : Shape.values()) {
            if (old.ordinal() >= host.ordinal()) break;
            transformer.registerSyntheticClass(old.lambdaInterface, generateLambdaInterface(names, old, host));
            transformer.registerLambdaAdapter(names.listener(), names.reload(), names.desc(old),
                    new RetromodTransformer.LambdaAdapter(old.lambdaInterface, names.reload(), names.desc(old),
                            HELPER, old.wrapName(), "(L" + old.lambdaInterface + ";)L" + names.listener() + ";"));
        }
        // Classes that declare an old reload, and calls to one, are adapted after the remap.
        transformer.enablePostProcess(host == Shape.BARRIER ? BARRIER_FLAG : STATE_FLAG);
    }

    /** The host's reload shape, read from the host or, offline, from its version. */
    static Shape hostShape(Names names) {
        ClassNode listener = ClassResourceInspector.read(names.listener());
        if (listener == null) return shapeForVersion(RetromodVersion.TARGET_MC_VERSION);
        for (Shape shape : new Shape[] {Shape.STATE, Shape.BARRIER}) {
            for (MethodNode method : listener.methods) {
                if (method.name.equals(names.reload()) && method.desc.equals(names.desc(shape))) return shape;
            }
        }
        return Shape.PROFILED;
    }

    static Shape shapeForVersion(String host) {
        if (RetromodVersion.compareMcVersions(host, "1.21.2") < 0) return Shape.PROFILED;
        return RetromodVersion.compareMcVersions(host, "1.21.9") < 0 ? Shape.BARRIER : Shape.STATE;
    }

    /** An interface for one old shape whose default host method calls the old method. */
    static byte[] generateLambdaInterface(Names names, Shape old, Shape host) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, old.lambdaInterface, null, "java/lang/Object",
                new String[] {names.listener()});
        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, names.reload(), names.desc(old), null, null).visitEnd();
        emitHostMethod(cw, names, old, host, old.lambdaInterface, true);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Writes the host-shaped {@code reload} that calls the old one on {@code this}. */
    static void emitHostMethod(ClassVisitor cv, Names names, Shape old, Shape host, String owner, boolean isInterface) {
        MethodVisitor mv = cv.visitMethod(ACC_PUBLIC, names.reload(), names.desc(host), null, null);
        mv.visitCode();
        if (host != Shape.STATE) {
            emitOldCallOnThis(mv, names, old, host, owner, isInterface);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            return;
        }
        // The host's state stays active while the old method runs, so a listener it wraps reads the
        // same state. The previous one comes back afterwards, also when the old method throws.
        String stateDesc = "L" + names.state() + ";";
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, HELPER, "enter", "(" + stateDesc + ")Ljava/lang/Object;", false);
        mv.visitVarInsn(ASTORE, 5);
        Label start = new Label();
        Label end = new Label();
        Label failed = new Label();
        mv.visitTryCatchBlock(start, end, failed, null);
        mv.visitLabel(start);
        emitOldCallOnThis(mv, names, old, host, owner, isInterface);
        mv.visitLabel(end);
        mv.visitVarInsn(ASTORE, 6);
        emitExit(mv);
        mv.visitVarInsn(ALOAD, 6);
        mv.visitInsn(ARETURN);
        // An old listener that throws fails the host's reload, which reports it as it reports any listener.
        mv.visitLabel(failed);
        mv.visitFrame(F_FULL, 6, new Object[] {owner, names.state(), EXECUTOR, names.barrier(), EXECUTOR,
                "java/lang/Object"}, 1, new Object[] {"java/lang/Throwable"});
        mv.visitVarInsn(ASTORE, 6);
        emitExit(mv);
        mv.visitVarInsn(ALOAD, 6);
        mv.visitInsn(ATHROW);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code this.reload(old arguments)}, leaving its future on the stack. */
    private static void emitOldCallOnThis(MethodVisitor mv, Names names, Shape old, Shape host, String owner,
            boolean isInterface) {
        mv.visitVarInsn(ALOAD, 0);
        pushOldArguments(mv, names, old, host);
        mv.visitMethodInsn(isInterface ? INVOKEINTERFACE : INVOKEVIRTUAL, owner, names.reload(), names.desc(old),
                isInterface);
    }

    /** {@code exit(previous)}, with the previous state in local 5. */
    private static void emitExit(MethodVisitor mv) {
        mv.visitVarInsn(ALOAD, 5);
        mv.visitMethodInsn(INVOKESTATIC, HELPER, "exit", "(Ljava/lang/Object;)V", false);
    }

    /**
     * Pushes the old method's arguments from the host method's, which sit in slots 1 to 4. The
     * profilers become the inactive profiler, and the host keeps its own profiling.
     */
    static void pushOldArguments(MethodVisitor mv, Names names, Shape old, Shape host) {
        // Host BARRIER is (barrier, resources, background, game); host STATE is (state, background, barrier, game).
        int barrier = host == Shape.STATE ? 3 : 1;
        int background = host == Shape.STATE ? 2 : 3;
        int game = 4;
        mv.visitVarInsn(ALOAD, barrier);
        if (host == Shape.STATE) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, names.state(), names.stateResources(), "()L" + names.resources() + ";",
                    false);
        } else {
            mv.visitVarInsn(ALOAD, 2);
        }
        if (old == Shape.PROFILED) {
            for (int i = 0; i < 2; i++) {
                mv.visitFieldInsn(GETSTATIC, names.inactive(), names.inactiveField(), "L" + names.inactive() + ";");
            }
        }
        mv.visitVarInsn(ALOAD, background);
        mv.visitVarInsn(ALOAD, game);
    }

    /** The helper: identity wraps for built lambdas and, on hosts from 1.21.9, the active state and the old call. */
    static byte[] generateHelper(Names names, Shape host) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        if (host == Shape.STATE) emitActiveState(cw, names);
        for (Shape old : Shape.values()) {
            if (old.ordinal() >= host.ordinal()) break;
            emitWrap(cw, names, old);
            if (host == Shape.STATE) emitOldCall(cw, names, old);
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * The state of the host reload an old listener is running in, per thread: {@code enter} and
     * {@code exit} around the old method, {@code active} for the state over given resources, and
     * {@code state}, which builds one when none is active.
     */
    private static void emitActiveState(ClassWriter cw, Names names) {
        String stateType = "L" + names.state() + ";";
        String resourcesType = "L" + names.resources() + ";";
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "ACTIVE", "L" + THREAD_LOCAL + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitTypeInsn(NEW, THREAD_LOCAL);
        init.visitInsn(DUP);
        init.visitMethodInsn(INVOKESPECIAL, THREAD_LOCAL, "<init>", "()V", false);
        init.visitFieldInsn(PUTSTATIC, HELPER, "ACTIVE", "L" + THREAD_LOCAL + ";");
        init.visitInsn(RETURN);
        end(init);

        // enter(state): returns the state it replaces.
        MethodVisitor enter = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "enter", "(" + stateType + ")Ljava/lang/Object;",
                null, null);
        enter.visitCode();
        enter.visitFieldInsn(GETSTATIC, HELPER, "ACTIVE", "L" + THREAD_LOCAL + ";");
        enter.visitMethodInsn(INVOKEVIRTUAL, THREAD_LOCAL, "get", "()Ljava/lang/Object;", false);
        enter.visitFieldInsn(GETSTATIC, HELPER, "ACTIVE", "L" + THREAD_LOCAL + ";");
        enter.visitVarInsn(ALOAD, 0);
        enter.visitMethodInsn(INVOKEVIRTUAL, THREAD_LOCAL, "set", "(Ljava/lang/Object;)V", false);
        enter.visitInsn(ARETURN);
        end(enter);

        // exit(previous): puts the replaced state back, or clears the thread's entry when there was none.
        MethodVisitor exit = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "exit", "(Ljava/lang/Object;)V", null, null);
        exit.visitCode();
        Label clear = new Label();
        exit.visitFieldInsn(GETSTATIC, HELPER, "ACTIVE", "L" + THREAD_LOCAL + ";");
        exit.visitVarInsn(ALOAD, 0);
        exit.visitJumpInsn(IFNULL, clear);
        exit.visitVarInsn(ALOAD, 0);
        exit.visitMethodInsn(INVOKEVIRTUAL, THREAD_LOCAL, "set", "(Ljava/lang/Object;)V", false);
        exit.visitInsn(RETURN);
        exit.visitLabel(clear);
        exit.visitMethodInsn(INVOKEVIRTUAL, THREAD_LOCAL, "remove", "()V", false);
        exit.visitInsn(RETURN);
        end(exit);

        // active(resources): the active state when it holds these resources, else null. A listener
        // handed other resources keeps its own state over them.
        MethodVisitor active = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "active", "(" + resourcesType + ")" + stateType,
                null, null);
        active.visitCode();
        Label none = new Label();
        active.visitFieldInsn(GETSTATIC, HELPER, "ACTIVE", "L" + THREAD_LOCAL + ";");
        active.visitMethodInsn(INVOKEVIRTUAL, THREAD_LOCAL, "get", "()Ljava/lang/Object;", false);
        active.visitTypeInsn(CHECKCAST, names.state());
        active.visitVarInsn(ASTORE, 1);
        active.visitVarInsn(ALOAD, 1);
        active.visitJumpInsn(IFNULL, none);
        active.visitVarInsn(ALOAD, 1);
        active.visitMethodInsn(INVOKEVIRTUAL, names.state(), names.stateResources(), "()" + resourcesType, false);
        active.visitVarInsn(ALOAD, 0);
        active.visitJumpInsn(IF_ACMPNE, none);
        active.visitVarInsn(ALOAD, 1);
        active.visitInsn(ARETURN);
        active.visitLabel(none);
        active.visitInsn(ACONST_NULL);
        active.visitInsn(ARETURN);
        end(active);

        // state(resources): the active state, or a new one over the resources when none is active.
        MethodVisitor state = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "state", "(" + resourcesType + ")" + stateType,
                null, null);
        state.visitCode();
        Label build = new Label();
        state.visitVarInsn(ALOAD, 0);
        state.visitMethodInsn(INVOKESTATIC, HELPER, "active", "(" + resourcesType + ")" + stateType, false);
        state.visitInsn(DUP);
        state.visitJumpInsn(IFNULL, build);
        state.visitInsn(ARETURN);
        state.visitLabel(build);
        state.visitInsn(POP);
        state.visitTypeInsn(NEW, names.state());
        state.visitInsn(DUP);
        state.visitVarInsn(ALOAD, 0);
        state.visitMethodInsn(INVOKESPECIAL, names.state(), "<init>", "(" + resourcesType + ")V", false);
        state.visitInsn(ARETURN);
        end(state);
    }

    private static void end(MethodVisitor mv) {
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Returns a lambda built as the old-shape interface, typed as the host interface. */
    private static void emitWrap(ClassWriter cw, Names names, Shape old) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, old.wrapName(),
                "(L" + old.lambdaInterface + ";)L" + names.listener() + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The descriptor of the helper's static stand-in for an old {@code reload} call. */
    static String oldCallDesc(Names names, Shape old) {
        return "(L" + names.listener() + ";" + names.desc(old).substring(1);
    }

    /**
     * {@code reload(listener, old arguments...)}: a call another listener makes on one it wraps. The
     * wrapped listener runs in the host reload's state while one is active over the same resources.
     * Otherwise it gets a state of its own, prepared as the host prepares a listener it registered.
     */
    private static void emitOldCall(ClassWriter cw, Names names, Shape old) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, names.reload(), oldCallDesc(names, old), null, null);
        mv.visitCode();
        int last = old == Shape.PROFILED ? 6 : 4;
        int stateSlot = last + 1;
        String resourcesType = "L" + names.resources() + ";";
        Label run = new Label();
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESTATIC, HELPER, "active", "(" + resourcesType + ")L" + names.state() + ";", false);
        mv.visitVarInsn(ASTORE, stateSlot);
        mv.visitVarInsn(ALOAD, stateSlot);
        mv.visitJumpInsn(IFNONNULL, run);
        // new SharedState(resources), prepared by the listener itself.
        mv.visitTypeInsn(NEW, names.state());
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKESPECIAL, names.state(), "<init>", "(" + resourcesType + ")V", false);
        mv.visitVarInsn(ASTORE, stateSlot);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, stateSlot);
        mv.visitMethodInsn(INVOKEINTERFACE, names.listener(), names.prepare(), "(L" + names.state() + ";)V", true);
        // listener.reload(state, background, barrier, game)
        mv.visitLabel(run);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, stateSlot);
        mv.visitVarInsn(ALOAD, last - 1);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, last);
        mv.visitMethodInsn(INVOKEINTERFACE, names.listener(), names.reload(), names.desc(Shape.STATE), true);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
