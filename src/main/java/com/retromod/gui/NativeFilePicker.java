/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.gui;

import com.retromod.util.McReflect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;
import java.util.function.LongUnaryOperator;

/** Shows the system file picker through the LWJGL that Minecraft ships, without AWT. */
final class NativeFilePicker {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Screen");
    private static final String TITLE = "Retromod: choose mod jars";
    private static final String THREAD_NAME = "Retromod-FilePicker";
    // SDL ends its list with a NULL pointer. A longer list than this is not a real selection.
    private static final int MAX_LISTED_FILES = 4096;

    /** Receives the outcome of one picker. */
    interface Listener {
        /** The chosen jars, called on a Retromod worker thread. */
        void chosen(List<File> jars);

        /** No picker could open, for the reason given, called on any thread. */
        void unavailable(String reason);
    }

    private NativeFilePicker() {}

    /** The dialog a host can show. */
    enum Dialog { SDL, TINYFD, NONE }

    /** Opens a picker in startFolder; clientThread runs work on Minecraft's main thread. */
    static void open(Executor clientThread, Path startFolder, Listener listener) {
        Class<?> sdlDialog = McReflect.findClass("org.lwjgl.sdl.SDLDialog");
        Class<?> tinyfd = McReflect.findClass("org.lwjgl.util.tinyfd.TinyFileDialogs");
        String start = startFolder.toAbsolutePath().toString();
        switch (choose(sdlDialog != null, sdlDialog != null && sdlVideoRunning(), tinyfd != null)) {
            // SDL's dialog has to start on the main thread.
            case SDL -> clientThread.execute(
                    () -> chooseWithSdl(sdlDialog, clientThread, start, listener));
            case TINYFD -> startWorker(() -> chooseWithTinyfd(tinyfd, start, listener));
            case NONE -> listener.unavailable("this Minecraft version ships neither the SDL3 nor "
                    + "the tinyfd file dialog");
        }
    }

    /** SDL's dialog where the host runs SDL video (26.3 and newer), else tinyfd (up to 26.2). */
    static Dialog choose(boolean sdlPresent, boolean sdlVideoRunning, boolean tinyfdPresent) {
        // A GLFW window leaves SDL video off, and SDL's dialog cannot open without it.
        if (sdlPresent && sdlVideoRunning) return Dialog.SDL;
        return tinyfdPresent ? Dialog.TINYFD : Dialog.NONE;
    }

    /** True when SDL's video subsystem runs, which its file dialog needs. */
    private static boolean sdlVideoRunning() {
        Class<?> sdlInit = McReflect.findClass("org.lwjgl.sdl.SDLInit");
        if (sdlInit == null) return false;
        try {
            int video = sdlInit.getField("SDL_INIT_VIDEO").getInt(null);
            Object running = sdlInit.getMethod("SDL_WasInit", int.class).invoke(null, video);
            return running instanceof Integer flags && (flags & video) != 0;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            // An SDL jar without its native library cannot show a dialog either.
            return false;
        }
    }

    /** Waits on tinyfd's dialog on this worker thread and reports the jars. */
    private static void chooseWithTinyfd(Class<?> tinyfd, String startFolder, Listener listener) {
        String answer;
        try {
            Class<?> pointerBuffer = McReflect.findClass("org.lwjgl.PointerBuffer");
            Method dialog = tinyfd.getMethod("tinyfd_openFileDialog", CharSequence.class,
                    CharSequence.class, pointerBuffer, CharSequence.class, boolean.class);
            // A filter needs native memory, so every file is offered and the jars are kept below.
            answer = (String) dialog.invoke(null, TITLE, startFolder + File.separator,
                    null, null, true);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            listener.unavailable("tinyfd could not open its dialog (" + describe(error) + ")");
            return;
        }
        report(jarsFromTinyfd(answer), listener);
    }

    /** Starts SDL3's dialog on the main thread; SDL answers later through a callback. */
    private static void chooseWithSdl(Class<?> sdlDialog, Executor clientThread, String startFolder,
            Listener listener) {
        try {
            Class<?> callbackType = McReflect.findClass("org.lwjgl.sdl.SDL_DialogFileCallbackI");
            Class<?> callbackClass = McReflect.findClass("org.lwjgl.sdl.SDL_DialogFileCallback");
            Class<?> filters = McReflect.findClass("org.lwjgl.sdl.SDL_DialogFileFilter$Buffer");
            Method show = sdlDialog.getMethod("SDL_ShowOpenFileDialog", callbackType, long.class,
                    long.class, filters, CharSequence.class, boolean.class);
            SdlAnswer answer = new SdlAnswer(clientThread, listener, LwjglMemory.load());

            // create() wraps the proxy in a native upcall, which is freed once SDL has answered.
            Object proxy = Proxy.newProxyInstance(callbackType.getClassLoader(),
                    new Class<?>[]{callbackType}, answer);
            Object callback = callbackClass.getMethod("create", callbackType).invoke(null, proxy);
            answer.attach(callback, callbackClass.getMethod("free"));

            // Without a parent window, macOS runs the panel modally until the player closes it.
            show.invoke(null, callback, 0L, 0L, null, startFolder, true);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            // The upcall stays allocated, because SDL may still answer through it.
            listener.unavailable("SDL could not open its file dialog (" + describe(error) + ")");
        }
    }

    /** Hands the jars to the listener, or logs that nothing usable was chosen. */
    private static void report(List<File> jars, Listener listener) {
        if (jars.isEmpty()) {
            // tinyfd answers a cancel and a Linux system without a dialog tool the same way.
            LOGGER.info("No mod jar was chosen. If no file picker appeared, drop the jars on the "
                    + "Retromod screen or use Open retromod-input/ instead.");
            return;
        }
        listener.chosen(jars);
    }

    /** The jars in tinyfd's answer, which separates several files with '|'; null is a cancel. */
    static List<File> jarsFromTinyfd(String answer) {
        if (answer == null || answer.isEmpty()) return List.of();
        return keepJars(List.of(answer.split("\\|")));
    }

    /** The paths that name a .jar file, in their order. */
    static List<File> keepJars(List<String> paths) {
        List<File> jars = new ArrayList<>();
        for (String path : paths) {
            if (path.toLowerCase(Locale.ROOT).endsWith(".jar")) jars.add(new File(path));
        }
        return jars;
    }

    /** Reads SDL's NULL-terminated array of UTF-8 paths. */
    static List<String> readPathList(long list, int pointerSize, LongUnaryOperator readAddress,
            LongFunction<String> readUtf8) {
        List<String> paths = new ArrayList<>();
        for (int index = 0; index < MAX_LISTED_FILES; index++) {
            long path = readAddress.applyAsLong(list + (long) index * pointerSize);
            if (path == 0L) return paths;
            paths.add(readUtf8.apply(path));
        }
        throw new IllegalStateException("SDL's file list has no end marker within "
                + MAX_LISTED_FILES + " entries");
    }

    private static void startWorker(Runnable work) {
        Thread worker = new Thread(work, THREAD_NAME);
        worker.setDaemon(true);
        worker.start();
    }

    private static String describe(Throwable error) {
        Throwable cause = error instanceof InvocationTargetException wrapped
                && wrapped.getCause() != null ? wrapped.getCause() : error;
        return cause.getClass().getSimpleName()
                + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
    }

    /** SDL's answer to one dialog; the proxy also forwards LWJGL's own default methods. */
    private static final class SdlAnswer implements InvocationHandler {
        private final Executor clientThread;
        private final Listener listener;
        private final LwjglMemory memory;
        private final AtomicBoolean answered = new AtomicBoolean();
        private volatile Object callback;
        private volatile Method free;

        SdlAnswer(Executor clientThread, Listener listener, LwjglMemory memory) {
            this.clientThread = clientThread;
            this.listener = listener;
            this.memory = memory;
        }

        void attach(Object callback, Method free) {
            this.callback = callback;
            this.free = free;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // SDL_DialogFileCallbackI.invoke(userdata, filelist, filter) carries the answer.
            if (method.getName().equals("invoke") && args != null && args.length == 3) {
                if (answered.compareAndSet(false, true)) answer((Long) args[1]);
                return null;
            }
            boolean noArgs = method.getParameterCount() == 0;
            if (method.getName().equals("equals") && method.getParameterCount() == 1) {
                return proxy == args[0];
            }
            if (method.getName().equals("hashCode") && noArgs) return System.identityHashCode(proxy);
            if (method.getName().equals("toString") && noArgs) return "Retromod file dialog callback";
            // address(), callback() and getDescriptor() are LWJGL's defaults for the upcall.
            return InvocationHandler.invokeDefault(proxy, method, args);
        }

        /** Reads the list while SDL still owns it, then leaves SDL's thread before any real work. */
        private void answer(long fileList) {
            List<File> jars = List.of();
            String failure = null;
            try {
                // A NULL list is SDL's error answer. An empty list is a cancel.
                if (fileList == 0L) {
                    failure = memory.lastSdlError();
                } else {
                    jars = keepJars(readPathList(fileList, memory.pointerSize(),
                            memory::address, memory::utf8));
                }
            } catch (RuntimeException readFailure) {
                failure = describe(readFailure);
            }
            List<File> chosen = jars;
            String error = failure;
            startWorker(() -> {
                // Queued from this worker, so the upcall is freed only after SDL returned from it.
                clientThread.execute(this::free);
                if (error != null) {
                    listener.unavailable("SDL's file dialog failed (" + error + ")");
                } else {
                    report(chosen, listener);
                }
            });
        }

        private void free() {
            try {
                if (callback != null && free != null) free.invoke(callback);
            } catch (ReflectiveOperationException | RuntimeException error) {
                LOGGER.debug("Could not free the file dialog callback: {}", describe(error));
            }
        }
    }

    /** LWJGL's raw memory readers and SDL's error text, found by reflection. */
    private record LwjglMemory(int pointerSize, Method memGetAddress, Method memUtf8,
            Method sdlGetError) {

        static LwjglMemory load() throws ReflectiveOperationException {
            Class<?> memoryUtil = McReflect.findClass("org.lwjgl.system.MemoryUtil");
            Class<?> pointer = McReflect.findClass("org.lwjgl.system.Pointer");
            Class<?> errors = McReflect.findClass("org.lwjgl.sdl.SDLError");
            if (memoryUtil == null || pointer == null || errors == null) {
                throw new ClassNotFoundException("LWJGL's MemoryUtil, Pointer or SDLError");
            }
            return new LwjglMemory(pointer.getField("POINTER_SIZE").getInt(null),
                    memoryUtil.getMethod("memGetAddress", long.class),
                    memoryUtil.getMethod("memUTF8", long.class),
                    errors.getMethod("SDL_GetError"));
        }

        long address(long at) {
            return (Long) call(memGetAddress, at);
        }

        String utf8(long at) {
            return (String) call(memUtf8, at);
        }

        /** SDL keeps its error per thread, so this runs on the thread that got the error answer. */
        String lastSdlError() {
            Object text = call(sdlGetError);
            return text == null || text.toString().isEmpty() ? "no reason given" : text.toString();
        }

        private static Object call(Method method, Object... args) {
            try {
                return method.invoke(null, args);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("LWJGL call " + method.getName() + " failed: "
                        + describe(error), error);
            }
        }
    }
}
