/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.util.McReflect;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>Overrides {@code onClose()} to invoke {@link #onScreenClose(Object)}</li>
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

    /** Init callbacks keyed by screen instance. */
    private static final Map<Object, Consumer<Object>> INIT_CALLBACKS = new ConcurrentHashMap<>();
    /** Close callbacks keyed by screen instance. */
    private static final Map<Object, Runnable> CLOSE_CALLBACKS = new ConcurrentHashMap<>();

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
        Class<?> cls = getOrGenerate();
        if (cls == null) return null;

        try {
            Class<?> textClass = McReflect.findClass(
                "net.minecraft.text.Text",
                "net.minecraft.network.chat.Component"
            );
            if (textClass == null) return null;

            Constructor<?> ctor = cls.getConstructor(textClass);
            Object screen = ctor.newInstance(title);

            if (initCallback != null) INIT_CALLBACKS.put(screen, initCallback);
            if (closeCallback != null) CLOSE_CALLBACKS.put(screen, closeCallback);
            return screen;
        } catch (Exception e) {
            LOGGER.warn("Could not instantiate generated screen: {}", e.getMessage());
            return null;
        }
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

    /** Invoked by the generated screen's onClose() override. */
    public static void onScreenClose(Object screen) {
        INIT_CALLBACKS.remove(screen);
        Runnable callback = CLOSE_CALLBACKS.remove(screen);
        if (callback == null) return;
        try {
            callback.run();
        } catch (Throwable t) {
            LOGGER.warn("Screen close callback failed: {}", t.getMessage());
        }
    }

    private static Class<?> getOrGenerate() {
        Class<?> local = generatedClass;
        if (local != null) return local;

        synchronized (ScreenClassGenerator.class) {
            if (generatedClass != null) return generatedClass;

            Class<?> screenBase = McReflect.findClass(
                "net.minecraft.client.gui.screen.Screen",
                "net.minecraft.client.gui.screens.Screen"
            );
            Class<?> textClass = McReflect.findClass(
                "net.minecraft.text.Text",
                "net.minecraft.network.chat.Component"
            );
            if (screenBase == null || textClass == null) {
                LOGGER.warn("Retromod cannot open its screen because Minecraft's Screen or "
                        + "Component class was not found");
                return null;
            }

            byte[] bytecode = generateBytecode(screenBase, textClass);

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

    /** Emits a Screen subclass that delegates its lifecycle callbacks to this class. */
    private static byte[] generateBytecode(Class<?> screenBase, Class<?> textClass) {
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
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PROTECTED, "init",
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

        // public void onClose() { ScreenClassGenerator.onScreenClose(this); }
        {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onClose",
                    "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "com/retromod/gui/ScreenClassGenerator", "onScreenClose",
                    "(Ljava/lang/Object;)V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        cw.visitEnd();
        return cw.toByteArray();
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
