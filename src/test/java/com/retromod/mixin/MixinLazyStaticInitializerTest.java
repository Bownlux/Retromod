/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.testsupport.StaticItemStackMixinFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.retromod.testsupport.StaticItemStackMixinFixture.ITEM_STACK;
import static com.retromod.testsupport.StaticItemStackMixinFixture.MIXIN;
import static com.retromod.testsupport.StaticItemStackMixinFixture.SEEN_FIELD;
import static com.retromod.testsupport.StaticItemStackMixinFixture.STACK_FIELD;
import static org.junit.jupiter.api.Assertions.*;

/** A Mixin's static ItemStack waits for its first use instead of running in the target's class initializer. */
public class MixinLazyStaticInitializerTest {

    private static final String HOOK = "com/retromod/mixin/MixinLazyStaticInitializerTest";
    private static final String SUFFIX = MIXIN.replace('/', '_');

    static volatile boolean componentsBound;
    static volatile long buildDelayMillis;
    static final AtomicInteger stacksBuilt = new AtomicInteger();

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private String savedHost;
    private Field resolverField;
    private FuzzyMethodResolver savedResolver;

    /** Called by the stand-in ItemStack constructor; throws as 26.x does before components are bound. */
    public static void onStackBuilt() {
        if (!componentsBound) throw new NullPointerException("Components not bound yet");
        stacksBuilt.incrementAndGet();
        try {
            Thread.sleep(buildDelayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @BeforeEach
    void isolate() throws Exception {
        componentsBound = false;
        buildDelayMillis = 0;
        stacksBuilt.set(0);
        savedHost = RetromodVersion.TARGET_MC_VERSION;
        // No indexed jar, so the host gate falls back to the target version this test sets.
        resolverField = RetromodTransformer.class.getDeclaredField("fuzzyResolver");
        resolverField.setAccessible(true);
        savedResolver = (FuzzyMethodResolver) resolverField.get(transformer);
        resolverField.set(transformer, null);
    }

    @AfterEach
    void restore() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = savedHost;
        transformer.setModSourceVersion(null);
        resolverField.set(transformer, savedResolver);
    }

    @Test
    @DisplayName("The unrepaired Mixin fails in its class initializer before components are bound")
    void originalInitializerFailsEarly() {
        ClassLoader loader = loaderWith(StaticItemStackMixinFixture.mixinClass(MIXIN));

        ExceptionInInitializerError error = assertThrows(ExceptionInInitializerError.class,
                () -> Class.forName(MIXIN.replace('/', '.'), true, loader));
        assertEquals("Components not bound yet", error.getCause().getMessage());
    }

    @Test
    @DisplayName("The repaired Mixin verifies, its field is no longer final, and every access goes through the guard")
    void repairedClassVerifiesAndGuardsEveryAccess() {
        byte[] repaired = repair(StaticItemStackMixinFixture.mixinClass(MIXIN));
        ClassNode node = read(repaired);

        assertNull(method(node, "<clinit>"), "the initializer must no longer run with the target's");
        FieldNode stack = field(node, STACK_FIELD);
        assertEquals(0, stack.access & Opcodes.ACC_FINAL, "a field set outside <clinit> cannot be final");
        assertEquals(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, stack.access);
        assertEquals(0, field(node, SEEN_FIELD).access & Opcodes.ACC_FINAL);

        String ensure = MixinLazyStaticInitializer.ENSURE + SUFFIX;
        for (MethodNode method : node.methods) {
            if (method.name.equals(MixinLazyStaticInitializer.BODY + SUFFIX)) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode access && access.owner.equals(MIXIN)
                        && (access.name.equals(STACK_FIELD) || access.name.equals(SEEN_FIELD))) {
                    AbstractInsnNode before = access.getPrevious();
                    assertTrue(before instanceof MethodInsnNode call && call.name.equals(ensure),
                            method.name + " reads " + access.name + " without the guard");
                }
            }
        }

        // Mixin keeps private static members, and @Unique lets it rename them on a clash.
        MethodNode init = method(node, MixinLazyStaticInitializer.INIT + SUFFIX);
        assertEquals(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED, init.access);
        for (String name : List.of(MixinLazyStaticInitializer.BODY, MixinLazyStaticInitializer.INIT,
                MixinLazyStaticInitializer.ENSURE)) {
            MethodNode added = method(node, name + SUFFIX);
            assertEquals(0, added.access & Opcodes.ACC_PUBLIC, name + " must stay private");
            assertTrue(isUnique(added.visibleAnnotations), name + " must be @Unique");
        }
        FieldNode ready = field(node, MixinLazyStaticInitializer.READY + SUFFIX);
        assertEquals(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, ready.access);
        assertTrue(isUnique(field(node, MixinLazyStaticInitializer.STARTED + SUFFIX).visibleAnnotations));

        assertVerifies(repaired);
    }

    @Test
    @DisplayName("The repaired Mixin initializes before components are bound and builds its stack once, on first use")
    void stackIsBuiltOnFirstUse() throws Exception {
        Class<?> mixin = Class.forName(MIXIN.replace('/', '.'), true,
                loaderWith(repair(StaticItemStackMixinFixture.mixinClass(MIXIN))));
        assertEquals(0, stacksBuilt.get(), "class initialization must not build the stack");

        componentsBound = true;
        // The constructor's field initializer runs after super(), so its guard must sit there too.
        Object instance = mixin.getConstructor().newInstance();
        Field initial = mixin.getDeclaredField("initialStack");
        initial.setAccessible(true);
        Object stack = initial.get(instance);
        assertNotNull(stack);
        assertEquals(ITEM_STACK.replace('/', '.'), stack.getClass().getName());

        assertSame(stack, call(mixin, "dummy"));
        assertSame(stack, call(mixin, "seen"), "a read during initialization re-enters the guard and sees the stack");
        assertSame(stack, call(mixin, "onGetFreeSlot", instance));
        assertEquals(1, stacksBuilt.get());
    }

    @Test
    @DisplayName("Threads that reach the stack together all wait for the one that builds it")
    void concurrentFirstUseBuildsOneStack() throws Exception {
        Class<?> mixin = Class.forName(MIXIN.replace('/', '.'), true,
                loaderWith(repair(StaticItemStackMixinFixture.mixinClass(MIXIN))));
        componentsBound = true;
        buildDelayMillis = 50;

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Object>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return call(mixin, "dummy");
                }));
            }
            start.countDown();
            Object first = results.get(0).get(10, TimeUnit.SECONDS);
            assertNotNull(first);
            for (Future<Object> result : results) {
                assertSame(first, result.get(10, TimeUnit.SECONDS), "every thread must see the finished stack");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, stacksBuilt.get());
    }

    @Test
    @DisplayName("Two Mixins of the same target get their own guard, flags, and initializer")
    void twoMixinsOfOneTargetDoNotShareMembers() {
        String other = "fixture/carry/OtherInventoryMixin";
        ClassNode first = read(repair(StaticItemStackMixinFixture.mixinClass(MIXIN)));
        ClassNode second = read(repair(StaticItemStackMixinFixture.mixinClass(other)));

        List<String> added = new ArrayList<>();
        first.methods.stream().filter(m -> m.name.startsWith("retromod$")).forEach(m -> added.add(m.name));
        first.fields.stream().filter(f -> f.name.startsWith("retromod$")).forEach(f -> added.add(f.name));
        assertEquals(5, added.size(), "three methods and two flags: " + added);
        for (MethodNode method : second.methods) assertFalse(added.contains(method.name), method.name);
        for (FieldNode field : second.fields) assertFalse(added.contains(field.name), field.name);
    }

    @Test
    @DisplayName("An initializer that sets a shadowed target field keeps running with the target's")
    void leavesShadowWritesAlone() {
        ClassNode node = read(StaticItemStackMixinFixture.mixinClass(MIXIN));
        FieldNode shadow = new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "LIMIT", "I", null, null);
        shadow.visibleAnnotations = new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Shadow;")));
        node.fields.add(shadow);
        MethodNode clinit = method(node, "<clinit>");
        clinit.instructions.insert(new FieldInsnNode(Opcodes.PUTSTATIC, MIXIN, "LIMIT", "I"));
        clinit.instructions.insert(new InsnNode(Opcodes.ICONST_2));

        assertFalse(MixinLazyStaticInitializer.apply(node));
        assertNotNull(method(node, "<clinit>"));
    }

    @Test
    @DisplayName("A static initializer that builds no ItemStack is left alone")
    void leavesOtherInitializersAlone() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MIXIN, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "NAMES", "Ljava/util/List;",
                null, null).visitEnd();
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/List", "of", "()Ljava/util/List;", true);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, MIXIN, "NAMES", "Ljava/util/List;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        cw.visitEnd();

        ClassNode node = read(cw.toByteArray());
        assertFalse(MixinLazyStaticInitializer.handles(node));
        assertFalse(MixinLazyStaticInitializer.apply(node));
    }

    @Test
    @DisplayName("A Mixin with nested classes is left alone, since they could read the field past the guard")
    void leavesMixinsWithNestMembersAlone() {
        ClassNode node = read(StaticItemStackMixinFixture.mixinClass(MIXIN));
        node.nestMembers = new ArrayList<>(List.of(MIXIN + "$1"));

        assertFalse(MixinLazyStaticInitializer.apply(node));
        assertNotNull(method(node, "<clinit>"));
    }

    @Test
    @DisplayName("The repair runs only on a 26.1 or newer host and only for mods built before 26.1")
    void gateFollowsHostAndSourceVersions() {
        byte[] original = StaticItemStackMixinFixture.mixinClass(MIXIN);
        MixinCompatibilityTransformer mixins = new MixinCompatibilityTransformer(transformer);

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        transformer.setModSourceVersion("[1.20.1,1.21)");
        assertNull(method(read(mixins.applyPostRemapRepairs(original)), "<clinit>"),
                "a 1.20.1 mod on 26.2 must be repaired by the shared post-remap pipeline");

        RetromodVersion.TARGET_MC_VERSION = "26.3";
        assertNull(method(read(mixins.deferStaticItemStacks(original)), "<clinit>"),
                "the newest host binds components late too");

        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        assertSame(original, mixins.deferStaticItemStacks(original), "1.21.11 builds stacks without bound components");

        RetromodVersion.TARGET_MC_VERSION = "26.2";
        transformer.setModSourceVersion("26.1");
        assertSame(original, mixins.deferStaticItemStacks(original), "a mod built for 26.1 already works there");

        transformer.setModSourceVersion(null);
        assertSame(original, mixins.deferStaticItemStacks(original), "an unknown source version is never old");
    }

    @Test
    @DisplayName("An indexed host jar decides the host gate before the target version does")
    void indexedHostJarDecidesTheHostGate(@TempDir Path tmp) throws Exception {
        FuzzyMethodResolver binding = indexHolderReference(tmp.resolve("binding.jar"), true);
        FuzzyMethodResolver plain = indexHolderReference(tmp.resolve("plain.jar"), false);

        assertTrue(MixinLazyStaticInitializer.hostBindsComponentsLate("1.21.11", binding));
        assertFalse(MixinLazyStaticInitializer.hostBindsComponentsLate("26.2", plain));
        assertTrue(MixinLazyStaticInitializer.hostBindsComponentsLate("26.1", null));
        assertFalse(MixinLazyStaticInitializer.hostBindsComponentsLate("1.21.11", null));
    }

    private static FuzzyMethodResolver indexHolderReference(Path jar, boolean bindsComponents) throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MixinLazyStaticInitializer.HOLDER_REFERENCE, null,
                "java/lang/Object", null);
        if (bindsComponents) {
            MethodVisitor bound = cw.visitMethod(Opcodes.ACC_PUBLIC, "areComponentsBound", "()Z", null, null);
            bound.visitCode();
            bound.visitInsn(Opcodes.ICONST_1);
            bound.visitInsn(Opcodes.IRETURN);
            bound.visitMaxs(0, 0);
            bound.visitEnd();
        }
        cw.visitEnd();
        StaticItemStackMixinFixture.writeJar(jar,
                Map.of(MixinLazyStaticInitializer.HOLDER_REFERENCE + ".class", cw.toByteArray()));
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(jar);
        return resolver;
    }

    private static byte[] repair(byte[] mixin) {
        ClassNode node = read(mixin);
        assertTrue(MixinLazyStaticInitializer.apply(node), "the fixture must be repairable");
        ClassWriter cw = new ClassWriter(0);
        node.accept(cw);
        return cw.toByteArray();
    }

    /** Structural and data-flow checks, then a type-checked verify against the stand-in classes. */
    private static void assertVerifies(byte[] repaired) {
        new ClassReader(repaired).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        StringWriter problems = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(repaired), loaderWith(repaired), false, new PrintWriter(problems));
        assertEquals("", problems.toString().trim(), "the repaired Mixin must verify");
    }

    private static ClassLoader loaderWith(byte[] mixin) {
        Map<String, byte[]> classes = new HashMap<>(StaticItemStackMixinFixture.minecraftStubs(HOOK));
        classes.put(MIXIN, mixin);
        return new ClassLoader(MixinLazyStaticInitializerTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    /** Calls a fixture method by name; an instance handler gets a null CallbackInfo. */
    private static Object call(Class<?> owner, String name, Object... receiver) throws Exception {
        Method method = Arrays.stream(owner.getDeclaredMethods())
                .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
        method.setAccessible(true);
        return Modifier.isStatic(method.getModifiers())
                ? method.invoke(null) : method.invoke(receiver[0], (Object) null);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
    }

    private static FieldNode field(ClassNode node, String name) {
        return node.fields.stream().filter(f -> f.name.equals(name)).findFirst().orElseThrow();
    }

    private static boolean isUnique(List<AnnotationNode> annotations) {
        return annotations != null && annotations.stream()
                .anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Unique;"));
    }
}
