/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.LoaderApiContext;
import com.retromod.loaderapi.LoaderApiRuntime;
import com.retromod.loaderapi.LoaderApiTestSupport;
import com.retromod.loaderapi.coverage.LoaderApiCoverage;
import com.retromod.loaderapi.coverage.LoaderApiCoverage.StatusRow;
import com.retromod.loaderapi.fabric.v26.HostSurface;
import com.retromod.loaderapi.fabric.v26.net.FakeHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every S1 row and callback shape, written as an old mod compiled it, links on each 26.x host and runs on the fake one. */
class ScreenRowsTest {

    private static final String API = "net/fabricmc/fabric/api/";
    private static final String HUD = API + "client/rendering/v1/HudRenderCallback";
    private static final String TOOLTIP_V1 = API + "client/item/v1/ItemTooltipCallback";
    private static final String GRAPHICS = "Lnet/minecraft/class_332;";
    private static final String SCREEN_DESC = "(Lnet/minecraft/class_437;" + GRAPHICS + "IIF)V";
    private static final String OLD_TOOLTIP = "(Lnet/minecraft/class_1799;Lnet/minecraft/class_1836;Ljava/util/List;)V";
    private static final String LAYER_DESC = "(" + GRAPHICS + "Lnet/minecraft/class_9779;)V";
    private static final String RECORDER = "fixture/Recorder";
    private static final Pattern INTERMEDIARY = Pattern.compile("(class|method|field)_\\d+");
    /** Minecraft and Fabric API types 26.x removed, which no translated class may still name. */
    private static final Set<String> REMOVED = Set.of("net/minecraft/client/gui/LayeredDraw$Layer",
            "net/minecraft/client/gui/GuiGraphics", HUD, API + "client/rendering/v1/IdentifiedLayer");
    /** Static members of the old API, called with INVOKESTATIC. */
    private static final Set<String> STATIC_MEMBERS = Set.of(API + "client/screen/v1/ScreenEvents.beforeRender",
            API + "client/screen/v1/ScreenEvents.afterRender", API + "client/rendering/v1/IdentifiedLayer.of");

    /** How the host reaches a callback once it is registered. */
    enum Entry { HUD, AFTER_SCREEN, BEFORE_SCREEN, TOOLTIP, SHARED }

    /** One old callback shape: its type, method and how the host calls it. Unserved shapes must not crash. */
    record Callback(String iface, String sam, String desc, Entry entry, boolean served) {}

    static final List<Callback> CALLBACKS = List.of(
            new Callback(HUD, "onHudRender", "(" + GRAPHICS + "F)V", Entry.HUD, true),
            new Callback(HUD, "onHudRender", LAYER_DESC, Entry.HUD, true),
            new Callback(HUD, "onHudRender", "(Lnet/minecraft/class_4587;F)V", Entry.HUD, false),
            new Callback(API + "client/screen/v1/ScreenEvents$AfterRender", "afterRender", SCREEN_DESC,
                    Entry.AFTER_SCREEN, true),
            new Callback(API + "client/screen/v1/ScreenEvents$BeforeRender", "beforeRender", SCREEN_DESC,
                    Entry.BEFORE_SCREEN, true),
            new Callback(TOOLTIP_V1, "getTooltip", OLD_TOOLTIP, Entry.TOOLTIP, true),
            new Callback(API + "event/client/ItemTooltipCallback", "getTooltip", OLD_TOOLTIP, Entry.TOOLTIP, true),
            // The 1.20.5 to 1.21.11 shape, which keeps the host's own event.
            new Callback(TOOLTIP_V1, "getTooltip", "(Lnet/minecraft/class_1799;Lnet/minecraft/class_1792$class_9635;"
                    + "Lnet/minecraft/class_1836;Ljava/util/List;)V", Entry.TOOLTIP, true),
            new Callback("net/minecraft/class_9080$class_9081", "render", LAYER_DESC, Entry.SHARED, true),
            new Callback(API + "client/rendering/v1/HudLayerRegistrationCallback", "register",
                    "(L" + API + "client/rendering/v1/LayeredDrawerWrapper;)V", Entry.SHARED, true),
            new Callback(API + "client/rendering/v1/GatherDebugTextEvents$Left", "onGatherLeftDebugText",
                    "(Ljava/util/List;)V", Entry.SHARED, true),
            new Callback(API + "client/rendering/v1/GatherDebugTextEvents$Right", "onGatherRightDebugText",
                    "(Ljava/util/List;)V", Entry.SHARED, true));

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private AutoCloseable runtime;
    private String savedHost;

    @AfterEach
    void tearDown() throws Exception {
        if (runtime != null) runtime.close();
        transformer.clearRedirectsForTesting();
        if (savedHost != null) RetromodVersion.TARGET_MC_VERSION = savedHost;
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"26.1", "26.3", "26.4"})
    @DisplayName("Every S1 shape translates to verifiable code that links on the host, and every served callback runs")
    void everyShapeLinksAndRuns(String host) throws Exception {
        prepare(host);
        HostSurface surface = HostSurface.forHost(host);
        List<String> problems = new ArrayList<>();
        Fixtures fixtures = fixtures(rows());
        Map<String, byte[]> translated = new LinkedHashMap<>();
        // Every transform path reads the jar's own classes, so a subtype's hierarchy is known.
        try (var jar = transformer.pushJarClassBytesProvider(fixtures.classes()::get)) {
            for (Map.Entry<String, byte[]> fixture : fixtures.classes().entrySet()) {
                byte[] out = transformer.transformClass(fixture.getValue(), fixture.getKey());
                problems.addAll(check(fixture.getKey(), out, surface));
                translated.put(fixture.getKey(), out);
            }
        }
        problems.addAll(runOnFakeHost(translated, transformer.getSyntheticClasses(), fixtures));
        assertEquals(List.of(), problems, "on " + host);
    }

    @ParameterizedTest(name = "on {0}")
    @ValueSource(strings = {"26.1", "26.3"})
    @DisplayName("The runtime declares every S1 row, impossible rows only through their owner type")
    void everyRowIsDeclared(String host) throws Exception {
        prepare(host);
        Set<RedirectKey> declared = new HashSet<>();
        LoaderApiRuntime.ledgers(transformer).forEach(ledger -> declared.addAll(ledger.declared()));
        List<String> undeclared = new ArrayList<>();
        for (StatusRow row : rows()) {
            boolean owned = declared.contains(RedirectKey.type(row.owner()))
                    || declared.contains(RedirectKey.field(row.owner(), row.name()))
                    || declared.stream().anyMatch(key -> key.kind() == RedirectKey.Kind.METHOD
                            && key.owner().equals(row.owner()) && key.name().equals(row.name()));
            if (!owned) undeclared.add(row.key());
        }
        assertEquals(List.of(), undeclared, "rows the runtime does not own on " + host);
    }

    // ---- Setup ------------------------------------------------------------------------------

    private void prepare(String host) {
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        runtime = LoaderApiTestSupport.runtimeOn(LoaderApiTestSupport.fabricProvider());
        RetromodPreLaunch.registerShimsForTransform(host);
        LoaderApiRuntime.prepare(transformer, new LoaderApiContext("fabric", host,
                LoaderApiContext.TransformPath.TEST, null));
    }

    static List<StatusRow> rows() throws IOException {
        List<StatusRow> rows = new ArrayList<>();
        for (StatusRow row : LoaderApiCoverage.readStatus(Path.of("docs-dev/loader-api/fabric-26x-status.tsv"))) {
            if ("S1".equals(row.batch())) rows.add(row);
        }
        return rows;
    }

    // ---- Fixtures ---------------------------------------------------------------------------

    /**
     * The fixture classes, the callback implementations that must run with how the host reaches
     * them, and the unserved ones, which must load and stay silent.
     */
    record Fixtures(Map<String, byte[]> classes, Map<String, Callback> implementations) {}

    static Fixtures fixtures(List<StatusRow> rows) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        Map<String, Callback> implementations = new LinkedHashMap<>();
        int n = 0;
        for (StatusRow row : rows) {
            // A typed call to a shape the runtime cannot serve has nothing to link to.
            if (row.marks().values().stream().allMatch("impossible"::equals) && "M".equals(row.kind())
                    && !STATIC_MEMBERS.contains(row.owner() + "." + row.name())) {
                continue;
            }
            String name = "fixture/s1/Row" + (n++);
            switch (row.kind()) {
                case "C" -> classes.put(name, typeUse(name, row.owner()));
                case "F" -> classes.put(name, fieldRead(name, row.owner(), row.name(), row.oldDescriptor()));
                default -> classes.put(name, call(name, row.owner(), row.name(), row.oldDescriptor()));
            }
            // javac names the subtype as the owner of an interface constant read without its type.
            if ("F".equals(row.kind()) && isInterface(row.owner())) {
                classes.put(name + "ViaImplementor", constantReader(name + "ViaImplementor", row.owner(),
                        name + "ViaImplementor", row.name(), row.oldDescriptor()));
                classes.put(name + "SubInterface", subInterface(name + "SubInterface", row.owner()));
                classes.put(name + "ViaSubInterface", constantReader(name + "ViaSubInterface", null,
                        name + "SubInterface", row.name(), row.oldDescriptor()));
            }
        }
        // Each callback: an override, a lambda, a typed call, a mod interface that extends it with
        // its own lambda and override, and calls through both subtypes. S1 has no injected interfaces.
        for (Callback callback : CALLBACKS) {
            String name = "fixture/s1/Callback" + (n++);
            classes.put(name + "Override", override(name + "Override", callback.iface(),
                    List.of(callback.sam() + callback.desc())));
            classes.put(name + "Lambda", lambda(name + "Lambda", callback.iface(), callback.sam(), callback.desc()));
            classes.put(name + "Sub", subInterface(name + "Sub", callback.iface()));
            classes.put(name + "SubLambda", lambda(name + "SubLambda", name + "Sub", callback.sam(), callback.desc()));
            classes.put(name + "SubOverride", override(name + "SubOverride", name + "Sub",
                    List.of(callback.sam() + callback.desc())));
            classes.put(name + "ViaClass", invoke(name + "ViaClass", name + "Override", callback.sam(),
                    callback.desc(), Opcodes.INVOKEVIRTUAL, false));
            if (callback.served()) {
                classes.put(name + "Call", invoke(name + "Call", callback.iface(), callback.sam(), callback.desc(),
                        Opcodes.INVOKEINTERFACE, true));
                classes.put(name + "ViaSub", invoke(name + "ViaSub", name + "Sub", callback.sam(), callback.desc(),
                        Opcodes.INVOKEINTERFACE, true));
            }
            for (String implementation : List.of("Override", "Lambda", "SubLambda", "SubOverride")) {
                implementations.put(name + implementation, callback);
            }
        }
        // A class that implements IdentifiedLayer itself, with its id and the layer's render.
        String identified = "fixture/s1/OwnIdentifiedLayer";
        classes.put(identified, override(identified, API + "client/rendering/v1/IdentifiedLayer",
                List.of("id()Lnet/minecraft/class_2960;", "render" + LAYER_DESC)));
        implementations.put(identified, new Callback(API + "client/rendering/v1/IdentifiedLayer", "render",
                LAYER_DESC, Entry.SHARED, true));
        return new Fixtures(classes, implementations);
    }

    private static byte[] call(String className, String owner, String name, String desc) {
        boolean isStatic = STATIC_MEMBERS.contains(owner + "." + name);
        return invoke(className, owner, name, desc, isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEINTERFACE,
                isInterface(owner));
    }

    /** Whether an old S1 type is an interface; the event holders and the id list are classes. */
    private static boolean isInterface(String owner) {
        return !owner.endsWith("/ScreenEvents") && !owner.endsWith("/GatherDebugTextEvents")
                && !owner.endsWith("/VanillaHudElements");
    }

    /**
     * An abstract class, implementing {@code iface} when it is not null, whose static
     * {@code read()} reads {@code owner.name}, as javac writes a constant read through a subtype.
     */
    private static byte[] constantReader(String className, String iface, String owner, String name, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER | Opcodes.ACC_ABSTRACT, className, null,
                "java/lang/Object", iface == null ? null : new String[]{iface});
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()" + desc, null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, name, desc);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A static method whose parameters feed one call to {@code owner.name(desc)}. */
    private static byte[] invoke(String className, String owner, String name, String desc, int opcode,
            boolean isInterface) {
        List<Type> own = new ArrayList<>();
        if (opcode != Opcodes.INVOKESTATIC) own.add(Type.getObjectType(owner));
        own.addAll(List.of(Type.getArgumentTypes(desc)));
        Type result = Type.getReturnType(desc);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call",
                Type.getMethodDescriptor(result, own.toArray(Type[]::new)), null, null);
        mv.visitCode();
        int slot = 0;
        for (Type type : own) {
            mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), slot);
            slot += type.getSize();
        }
        mv.visitMethodInsn(opcode, owner, name, desc, isInterface);
        mv.visitInsn(result.getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A static {@code read()} that reads one static field. */
    private static byte[] fieldRead(String className, String owner, String name, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "read", "()" + desc, null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, name, desc);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A field, a parameter and a checkcast of {@code type}. */
    private static byte[] typeUse(String className, String type) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "L" + type + ";", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "cast",
                "(Ljava/lang/Object;)L" + type + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, type);
        mv.visitInsn(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, className, "value", "L" + type + ";");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class that implements {@code iface} with each typed method; each notes its class name when it runs. */
    private static byte[] override(String className, String iface, List<String> methods) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object",
                new String[]{iface});
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        for (String method : methods) {
            String desc = method.substring(method.indexOf('('));
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, method.substring(0, method.indexOf('(')),
                    desc, null, null);
            mv.visitCode();
            note(mv, className);
            returnZero(mv, Type.getReturnType(desc));
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A static {@code make()} returning a lambda of {@code iface} that notes its class name when it runs. */
    private static byte[] lambda(String className, String iface, String sam, String samDesc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()L" + iface + ";",
                null, null);
        mv.visitCode();
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
                + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn(sam, "()L" + iface + ";", metafactory, Type.getType(samDesc),
                new Handle(Opcodes.H_INVOKESTATIC, className, "body", samDesc, false), Type.getType(samDesc));
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        MethodVisitor body = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "body", samDesc, null, null);
        body.visitCode();
        note(body, className);
        returnZero(body, Type.getReturnType(samDesc));
        body.visitMaxs(0, 0);
        body.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] subInterface(String className, String iface) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, className, null,
                "java/lang/Object", new String[]{iface});
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void note(MethodVisitor mv, String className) {
        mv.visitLdcInsn(className);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, RECORDER, "note", "(Ljava/lang/String;)V", false);
    }

    private static void returnZero(MethodVisitor mv, Type type) {
        switch (type.getSort()) {
            case Type.VOID -> mv.visitInsn(Opcodes.RETURN);
            case Type.OBJECT, Type.ARRAY -> {
                mv.visitInsn(Opcodes.ACONST_NULL);
                mv.visitInsn(Opcodes.ARETURN);
            }
            default -> {
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.IRETURN);
            }
        }
    }

    // ---- Running on the fake host ------------------------------------------------------------

    /**
     * Loads every translated class on the fake host, reads every redirected field, and calls each
     * callback implementation the way the host or the shared type does. A served one must note
     * that it ran; an unserved one must not throw.
     */
    private static List<String> runOnFakeHost(Map<String, byte[]> classes, Map<String, byte[]> synthetics,
            Fixtures fixtures) throws Exception {
        Map<String, byte[]> all = new HashMap<>(synthetics);
        all.putAll(classes);
        List<String> problems = new ArrayList<>();
        try (FakeHost fake = FakeHost.start()) {
            ClassLoader mod = fake.defineMod(all);
            List<?> events = (List<?>) fake.type("fixture.Recorder").getField("EVENTS").get(null);
            for (String name : classes.keySet()) {
                try {
                    Class<?> type = Class.forName(name.replace('/', '.'), true, mod);
                    readFields(type);
                    Callback callback = fixtures.implementations().get(name);
                    if (callback == null) continue;
                    events.clear();
                    callEntry(fake, instanceOf(type), callback);
                    boolean ran = events.contains(name);
                    if (callback.served() && !ran) problems.add(name + ": did not run when the host called it");
                    if (!callback.served() && ran) problems.add(name + ": an unserved shape ran");
                } catch (InvocationTargetException e) {
                    problems.add(name + ": " + e.getCause());
                } catch (ReflectiveOperationException | LinkageError e) {
                    problems.add(name + ": " + e);
                }
            }
        }
        return problems;
    }

    private static void readFields(Class<?> type) throws ReflectiveOperationException {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals("read") && method.getParameterCount() == 0) method.invoke(null);
        }
    }

    private static Object instanceOf(Class<?> type) throws ReflectiveOperationException {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals("make") && method.getParameterCount() == 0) return method.invoke(null);
        }
        return type.getConstructor().newInstance();
    }

    /** Calls {@code instance} as the host reaches {@code callback}. */
    private static void callEntry(FakeHost fake, Object instance, Callback callback) throws Exception {
        Object graphics = fake.type("net.minecraft.client.gui.GuiGraphicsExtractor")
                .getConstructor(String.class).newInstance("frame");
        Object screen = fake.type("net.minecraft.client.gui.screens.Screen").getConstructor(String.class)
                .newInstance("title");
        switch (callback.entry()) {
            case HUD -> {
                Class<?> shared = fake.type("com.retromod.loaderapi.fabric.v26.screen.shared.LegacyHudRenderCallback");
                Object tracker = fake.type("net.minecraft.client.DeltaTracker").getMethod("of", float.class)
                        .invoke(null, 0.25f);
                shared.getMethod("renderLegacyHud", Object.class, Object.class).invoke(instance, graphics, tracker);
            }
            case AFTER_SCREEN, BEFORE_SCREEN -> {
                String host = callback.entry() == Entry.AFTER_SCREEN ? "AfterExtract" : "BeforeExtract";
                Class<?> type = fake.type("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$" + host);
                String method = callback.entry() == Entry.AFTER_SCREEN ? "afterExtract" : "beforeExtract";
                type.getMethod(method, screen.getClass(), graphics.getClass(), int.class, int.class, float.class)
                        .invoke(instance, screen, graphics, 1, 2, 0.5f);
            }
            case TOOLTIP -> {
                Class<?> type = fake.type("net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback");
                Class<?> stack = fake.type("net.minecraft.world.item.ItemStack");
                Class<?> context = fake.type("net.minecraft.world.item.Item$TooltipContext");
                Class<?> flag = fake.type("net.minecraft.world.item.TooltipFlag");
                type.getMethod("getTooltip", stack, context, flag, List.class).invoke(instance, null,
                        context.getField("EMPTY").get(null), flag.getField("NORMAL").get(null), new ArrayList<>());
            }
            case SHARED -> callSharedMethods(instance);
        }
    }

    /** Calls every abstract method of each shared type {@code instance} implements. */
    private static void callSharedMethods(Object instance) throws ReflectiveOperationException {
        for (Class<?> current = instance.getClass(); current != null; current = current.getSuperclass()) {
            for (Class<?> iface : allInterfaces(current)) {
                if (!LoaderApiRuntime.isSharedTypeName(iface.getName().replace('.', '/'))) continue;
                for (Method method : iface.getDeclaredMethods()) {
                    if (!Modifier.isAbstract(method.getModifiers())) continue;
                    method.invoke(instance, new Object[method.getParameterCount()]);
                }
            }
        }
    }

    private static Set<Class<?>> allInterfaces(Class<?> type) {
        Set<Class<?>> all = new java.util.LinkedHashSet<>();
        java.util.Deque<Class<?>> pending = new java.util.ArrayDeque<>(List.of(type.getInterfaces()));
        while (!pending.isEmpty()) {
            Class<?> iface = pending.pop();
            if (all.add(iface)) pending.addAll(List.of(iface.getInterfaces()));
        }
        return all;
    }

    // ---- Checks -----------------------------------------------------------------------------

    /** Problems with one translated class: verifier errors, stale names and members the host lacks. */
    private List<String> check(String name, byte[] out, HostSurface surface) {
        List<String> problems = new ArrayList<>();
        try {
            new ClassReader(out).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        } catch (RuntimeException e) {
            problems.add(name + ": does not verify: " + e.getMessage());
            return problems;
        }
        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);
        Set<String> types = new TreeSet<>();
        if (node.superName != null) types.add(node.superName);
        types.addAll(node.interfaces);
        for (FieldNode field : node.fields) collectTypes(Type.getType(field.desc), types);
        for (MethodNode method : node.methods) {
            collectTypes(Type.getMethodType(method.desc), types);
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    types.add(call.owner);
                    collectTypes(Type.getMethodType(call.desc), types);
                    String missing = missingMember(call.owner, call.name, call.desc, surface);
                    if (missing != null) problems.add(name + ": " + missing);
                } else if (insn instanceof FieldInsnNode field) {
                    types.add(field.owner);
                    collectTypes(Type.getType(field.desc), types);
                    if (field.owner.startsWith("net/fabricmc/fabric/")
                            && surface.member(field.owner, field.name, field.desc) == HostSurface.Presence.MISSING) {
                        problems.add(name + ": reads " + field.owner + "." + field.name + ", which the host lacks");
                    }
                    if (field.owner.startsWith("com/retromod/") && !retromodFieldExists(field.owner, field.name)) {
                        problems.add(name + ": reads " + field.owner + "." + field.name + ", which Retromod lacks");
                    }
                } else if (insn instanceof TypeInsnNode type) {
                    collectTypes(Type.getObjectType(type.desc), types);
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    collectTypes(Type.getMethodType(indy.desc), types);
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Type type) collectTypes(type, types);
                        if (arg instanceof Handle handle) types.add(handle.getOwner());
                    }
                }
            }
        }
        for (String type : types) {
            if (INTERMEDIARY.matcher(type).find()) problems.add(name + ": keeps intermediary " + type);
            if (REMOVED.contains(type)) problems.add(name + ": names " + type + ", which 26.x removed");
            if (type.startsWith("net/fabricmc/fabric/") && !surface.hasClass(type)) {
                problems.add(name + ": names " + type + ", which the host lacks");
            }
            if (type.startsWith("com/retromod/") && !retromodClassExists(type)) {
                problems.add(name + ": names " + type + ", which Retromod does not ship");
            }
        }
        return problems;
    }

    private static void collectTypes(Type type, Set<String> into) {
        switch (type.getSort()) {
            case Type.OBJECT -> into.add(type.getInternalName());
            case Type.ARRAY -> collectTypes(type.getElementType(), into);
            case Type.METHOD -> {
                collectTypes(type.getReturnType(), into);
                for (Type arg : type.getArgumentTypes()) collectTypes(arg, into);
            }
            default -> { }
        }
    }

    /** Why a call does not link, or null when it does. */
    private String missingMember(String owner, String name, String desc, HostSurface surface) {
        if (owner.startsWith("net/fabricmc/fabric/")) {
            return surface.member(owner, name, desc) == HostSurface.Presence.MISSING
                    ? "calls " + owner + "." + name + desc + ", which the host lacks" : null;
        }
        if (owner.startsWith("com/retromod/")) {
            return retromodMethodExists(owner, name, desc) ? null
                    : "calls " + owner + "." + name + desc + ", which Retromod does not ship";
        }
        return null;
    }

    private boolean retromodClassExists(String type) {
        return transformer.getSyntheticClasses().containsKey(type) || classNode(type) != null;
    }

    private boolean retromodFieldExists(String owner, String name) {
        ClassNode node = classNode(owner);
        return node != null && node.fields.stream().anyMatch(field -> field.name.equals(name));
    }

    /** Whether a compiled or synthetic Retromod class or one of its supertypes declares the method. */
    private boolean retromodMethodExists(String owner, String name, String desc) {
        ClassNode node = classNode(owner);
        if (node == null) return false;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        for (String iface : node.interfaces) {
            if (iface.startsWith("com/retromod/") && retromodMethodExists(iface, name, desc)) return true;
        }
        return false;
    }

    private ClassNode classNode(String type) {
        byte[] synthetic = transformer.getSyntheticClasses().get(type);
        try (InputStream in = synthetic != null ? new java.io.ByteArrayInputStream(synthetic)
                : getClass().getClassLoader().getResourceAsStream(type + ".class")) {
            if (in == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
            return node;
        } catch (IOException e) {
            return null;
        }
    }
}
