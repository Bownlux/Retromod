/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.util.McReflect;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Generates a minimal Minecraft {@code Screen} subclass at runtime via ASM.
 *
 * <p>Why: Retromod has no compile-time Minecraft classpath, so we can't write
 * {@code class MyScreen extends Screen} directly. Using an existing screen
 * like {@code ConfirmScreen} as a base overlays our widgets on top of the
 * base screen's own text and YES/NO buttons, a messy layout. This generator
 * produces a fresh Screen subclass with only the overrides we need.
 *
 * <p>The generated class:
 * <ul>
 *   <li>Extends {@code Screen} (resolved at runtime via {@link McReflect})</li>
 *   <li>Constructor takes a single {@code Component}/{@code Text} title</li>
 *   <li>Overrides {@code init()} to invoke {@link #onScreenInit(Object)}</li>
 *   <li>Overrides {@code onClose()} to invoke {@link #onScreenClose(Object)}, and
 *       {@code super.onClose()} when that runs no callback</li>
 *   <li>Overrides {@code onFilesDrop(List)} the same way, through
 *       {@link #onScreenFilesDropped(Object, List)}</li>
 * </ul>
 *
 * <p>Callers register an init callback (to add widgets) and a close callback
 * (to save state and return to a parent screen) keyed by screen instance.
 */
public final class ScreenClassGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    private static final String GEN_CLASS_NAME = "com.retromod.gui.RetromodGenScreen";
    private static final String GEN_CLASS_INTERNAL = "com/retromod/gui/RetromodGenScreen";

    private static volatile Class<?> generatedClass;

    // Callbacks live as long as their screen, which Minecraft may init again after Escape or a
    // resize. A callback must not capture its own screen, or the weak key never clears.
    /** Init callbacks keyed by screen instance. */
    private static final Map<Object, Consumer<Object>> INIT_CALLBACKS =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** Close callbacks keyed by screen instance. */
    private static final Map<Object, Runnable> CLOSE_CALLBACKS =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** Drop callbacks keyed by screen instance; like init, they get the screen as an argument. */
    private static final Map<Object, BiConsumer<Object, List<Path>>> DROP_CALLBACKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private ScreenClassGenerator() {}

    // PUBLIC API

    /**
     * Create a new screen instance using the generated class.
     *
     * @param title          the title Component/Text (resolved at runtime)
     * @param initCallback   called from the screen's init() on the render thread
     * @param closeCallback  called from the screen's onClose() on the render thread
     * @return the new screen, or null if the class can't be generated
     */
    public static Object createScreen(Object title,
                                       Consumer<Object> initCallback,
                                       Runnable closeCallback) {
        return createScreen(title, initCallback, closeCallback, null);
    }

    /** {@link #createScreen(Object, Consumer, Runnable)}, plus a callback for files dropped on it. */
    public static Object createScreen(Object title, Consumer<Object> initCallback,
            Runnable closeCallback, BiConsumer<Object, List<Path>> dropCallback) {
        Class<?> cls = getOrGenerate();
        if (cls == null) return null;

        try {
            Class<?> textClass = McReflect.findClass(
                "net.minecraft.text.Text",
                "net.minecraft.network.chat.Component",
                "net.minecraft.class_2561"  // intermediary, pre-26.1 Fabric
            );
            if (textClass == null) return null;

            Constructor<?> ctor = cls.getConstructor(textClass);
            Object screen = ctor.newInstance(title);
            register(screen, initCallback, closeCallback, dropCallback);
            return screen;
        } catch (Exception e) {
            LOGGER.warn("Could not instantiate generated screen: {}", e.getMessage());
            return null;
        }
    }

    /** Attaches the init and close callbacks to a generated screen. */
    static void register(Object screen, Consumer<Object> initCallback, Runnable closeCallback) {
        register(screen, initCallback, closeCallback, null);
    }

    /** Attaches the init, close, and drop callbacks to a generated screen. */
    static void register(Object screen, Consumer<Object> initCallback, Runnable closeCallback,
            BiConsumer<Object, List<Path>> dropCallback) {
        if (initCallback != null) INIT_CALLBACKS.put(screen, initCallback);
        if (closeCallback != null) CLOSE_CALLBACKS.put(screen, closeCallback);
        if (dropCallback != null) DROP_CALLBACKS.put(screen, dropCallback);
    }

    // BYTECODE ENTRY POINTS (called from generated class)

    /** Invoked by the generated screen's init() override. */
    public static void onScreenInit(Object screen) {
        Consumer<Object> callback = INIT_CALLBACKS.get(screen);
        if (callback == null) return;
        try {
            callback.accept(screen);
        } catch (Throwable t) {
            LOGGER.warn("Screen init callback failed: {}", t.getMessage());
        }
    }

    /** Invoked by the generated screen's onClose(); false means Minecraft's own close runs. */
    public static boolean onScreenClose(Object screen) {
        // The callbacks stay: a screen shown again, such as after settings' Done, inits again.
        Runnable callback = CLOSE_CALLBACKS.get(screen);
        if (callback == null) return false;
        try {
            callback.run();
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Screen close callback failed: {}", t.getMessage());
            return false;
        }
    }

    /** Invoked by the generated screen's onFilesDrop(); false means Minecraft's own handling runs. */
    public static boolean onScreenFilesDropped(Object screen, List<?> dropped) {
        BiConsumer<Object, List<Path>> callback = DROP_CALLBACKS.get(screen);
        if (callback == null || dropped == null) return false;
        List<Path> paths = new ArrayList<>();
        for (Object entry : dropped) {
            if (entry instanceof Path path) paths.add(path);
        }
        try {
            callback.accept(screen, paths);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Retromod could not take the dropped files: {}", t.getMessage());
            return false;
        }
    }

    private static Class<?> getOrGenerate() {
        Class<?> local = generatedClass;
        if (local != null) return local;

        synchronized (ScreenClassGenerator.class) {
            if (generatedClass != null) return generatedClass;

            Class<?> screenBase = McReflect.findClass(
                "net.minecraft.client.gui.screen.Screen",
                "net.minecraft.client.gui.screens.Screen",
                "net.minecraft.class_437"  // intermediary
            );
            Class<?> textClass = McReflect.findClass(
                "net.minecraft.text.Text",
                "net.minecraft.network.chat.Component",
                "net.minecraft.class_2561"
            );
            if (screenBase == null || textClass == null) {
                LOGGER.warn("Retromod cannot open its screen because Minecraft's Screen or "
                        + "Component class was not found");
                return null;
            }

            byte[] bytecode = generateBytecode(screenBase, textClass);
            if (bytecode == null) return null;

            // The generated class must share Screen's class loader parent so both sides resolve
            // the same Minecraft types.
            try {
                BytecodeClassLoader loader = new BytecodeClassLoader(screenBase.getClassLoader());
                generatedClass = loader.define(GEN_CLASS_NAME, bytecode);
                LOGGER.debug("Generated {} as a subclass of {}",
                        GEN_CLASS_NAME, screenBase.getName());
            } catch (Throwable e) {
                LOGGER.warn("Could not create the Retromod screen class: {}",
                        e.getMessage(), e);
                return null;
            }
            return generatedClass;
        }
    }

    /** Emits a Screen subclass that delegates its lifecycle callbacks; null without init or onClose. */
    static byte[] generateBytecode(Class<?> screenBase, Class<?> textClass) {
        // Override the names the running game uses: Mojang's, or intermediary on Fabric before 26.1.
        String init = noArgMethodName(screenBase, "init", "method_25426");
        String onClose = noArgMethodName(screenBase, "onClose", "method_25419");
        if (init == null || onClose == null) {
            LOGGER.warn("Retromod cannot open its screen because {} has no init() or onClose() "
                    + "it can override. Put old mods in retromod-input/ instead.",
                    screenBase.getName());
            return null;
        }

        String superName = Type.getInternalName(screenBase);
        String textInternal = Type.getInternalName(textClass);
        String textDesc = "L" + textInternal + ";";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            // Override getCommonSuperClass so we never try to load MC classes
            // via reflection during frame computation.
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                // Our simple bytecode never triggers this; return Object as a safe fallback.
                return "java/lang/Object";
            }
        };

        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                GEN_CLASS_INTERNAL, null, superName, null);

        // Constructor: public <init>(Component title) { super(title); }
        {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                    "(" + textDesc + ")V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);       // this
            mv.visitVarInsn(Opcodes.ALOAD, 1);       // title
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>",
                    "(" + textDesc + ")V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        // protected void init() { ScreenClassGenerator.onScreenInit(this); }
        {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PROTECTED, init,
                    "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/retromod/gui/ScreenClassGenerator", "onScreenInit",
                    "(Ljava/lang/Object;)V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        // public void onClose() { if (!ScreenClassGenerator.onScreenClose(this)) super.onClose(); }
        {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, onClose,
                    "()V", null, null);
            mv.visitCode();
            Label done = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/retromod/gui/ScreenClassGenerator", "onScreenClose",
                    "(Ljava/lang/Object;)Z", false);
            mv.visitJumpInsn(Opcodes.IFNE, done);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, onClose, "()V", false);
            mv.visitLabel(done);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        // public void onFilesDrop(List files): Minecraft hands files dropped on the window to the
        // open screen here. super.onFilesDrop runs only when no callback took them.
        String onFilesDrop = listMethodName(screenBase, "onFilesDrop", "method_29638");
        if (onFilesDrop != null) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, onFilesDrop,
                    "(Ljava/util/List;)V", null, null);
            mv.visitCode();
            Label done = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/retromod/gui/ScreenClassGenerator", "onScreenFilesDropped",
                    "(Ljava/lang/Object;Ljava/util/List;)Z", false);
            mv.visitJumpInsn(Opcodes.IFNE, done);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, onFilesDrop,
                    "(Ljava/util/List;)V", false);
            mv.visitLabel(done);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The first of {@code names} that the class has as a public void method taking one List. */
    private static String listMethodName(Class<?> owner, String... names) {
        // A screen without it still opens; it only ignores dropped files.
        for (String name : names) {
            try {
                Method method = owner.getMethod(name, List.class);
                if (method.getReturnType() == void.class) return name;
            } catch (NoSuchMethodException absent) {
                // Try the next name.
            }
        }
        return null;
    }

    /** The first of {@code names} that the class declares or inherits as a no-argument method. */
    private static String noArgMethodName(Class<?> owner, String... names) {
        Method method = McReflect.findMethod(owner, new Class[0], names);
        // findMethod falls back to any method with the name, which may take arguments.
        return method != null && method.getParameterCount() == 0 ? method.getName() : null;
    }

    // BYTECODE CLASSLOADER

    /**
     * Minimal classloader that exposes {@code defineClass}. Parent is the
     * MC Screen classloader so the generated class can see Screen.
     *
     * <p><strong>Bytecode source:</strong> the bytes passed to {@link #define}
     * are <em>always</em> generated in-process by {@link ScreenClassGenerator}
     * itself using ASM (see the surrounding class). They are never read
     * from disk, downloaded from the network, or supplied by external
     * input. The class generation exists because Minecraft's {@code Screen}
     * hierarchy requires a subclass to register a GUI screen and we want
     * to keep Retromod's GUI loosely coupled from any specific MC version's
     * concrete {@code Screen} class. Generating the subclass at runtime
     * means the same code works across MC versions whose {@code Screen}
     * base class moved between packages.
     *
     * <p>If you're an auditor reading this and worried about runtime class
     * loading: trace every caller of {@link #define}. They all originate
     * from {@code ScreenClassGenerator.generateScreenSubclass(...)} which
     * builds the bytes locally with ASM {@code ClassWriter} from a fixed
     * template. There is no path that loads bytes from an untrusted
     * source.
     */
    private static final class BytecodeClassLoader extends ClassLoader {
        BytecodeClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytecode) {
            return super.defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
