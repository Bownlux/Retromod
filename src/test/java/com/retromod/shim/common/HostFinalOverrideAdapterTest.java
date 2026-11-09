/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** JEI's shaped recipe and Farmer's Delight's dispenser override methods final on 26.2, which the JVM refuses to load. */
class HostFinalOverrideAdapterTest {

    private static final String NORMAL_RECIPE = "net/minecraft/world/item/crafting/NormalCraftingRecipe";
    private static final String SHAPED_RECIPE = "net/minecraft/world/item/crafting/ShapedRecipe";
    private static final String CATEGORY = "()Lnet/minecraft/world/item/crafting/CraftingBookCategory;";
    private static final String DEFAULT_DISPENSE = "net/minecraft/core/dispenser/DefaultDispenseItemBehavior";
    private static final String OPTIONAL_DISPENSE = "net/minecraft/core/dispenser/OptionalDispenseItemBehavior";
    private static final String DISPENSE = "(Lnet/minecraft/core/dispenser/BlockSource;Lnet/minecraft/world/item/ItemStack;)"
            + "Lnet/minecraft/world/item/ItemStack;";

    @Test
    @DisplayName("JEI's recipe: both final overrides are renamed, its own calls follow, other overrides stay")
    void jeiShapedRecipe() {
        Map<String, HostFinalOverrideAdapter.Shape> host = new HashMap<>();
        host.put(NORMAL_RECIPE, hostShape("java/lang/Object", Map.of(
                "showNotification()Z", ACC_PUBLIC | ACC_FINAL, "category" + CATEGORY, ACC_PUBLIC | ACC_FINAL,
                "group()Ljava/lang/String;", ACC_PUBLIC | ACC_FINAL)));
        host.put(SHAPED_RECIPE, hostShape(NORMAL_RECIPE, Map.of("getWidth()I", ACC_PUBLIC)));

        ClassNode out = apply(recipe(), host);

        assertEquals(List.of("<init>", "retromod$final$showNotification", "retromod$final$category", "getWidth", "bridge"),
                names(out));
        assertEquals(List.of("mod/JeiShapedRecipe.retromod$final$category", "net/minecraft/world/item/crafting/ShapedRecipe.group"),
                calls(out, "bridge"), "a call through the mod's type follows the rename; one through a host type does not");
    }

    @Test
    @DisplayName("Farmer's Delight's dispense is renamed, and its own mixin's call to it follows")
    void farmersDelightDispense() {
        Map<String, HostFinalOverrideAdapter.Shape> host = new HashMap<>();
        host.put(DEFAULT_DISPENSE, hostShape("java/lang/Object", Map.of("dispense" + DISPENSE, ACC_PUBLIC | ACC_FINAL)));
        host.put(OPTIONAL_DISPENSE, hostShape(DEFAULT_DISPENSE, Map.of()));
        byte[] behavior = classWithMethods("mod/CuttingBoardDispenseBehavior", OPTIONAL_DISPENSE, false,
                new String[]{"dispense", DISPENSE, null});
        host.put("mod/CuttingBoardDispenseBehavior", HostFinalOverrideAdapter.shapeOf(read(behavior), false));
        // The mixin declares a method of the same name, and is merged into the dispenser, so it keeps it.
        byte[] mixin = classWithMethods("mod/CuttingBoardDispenserMixin", OPTIONAL_DISPENSE, true,
                new String[]{"dispense", DISPENSE, null},
                new String[]{"onDispense", "()V", "mod/CuttingBoardDispenseBehavior.dispense" + DISPENSE},
                new String[]{"vanilla", "()V", DEFAULT_DISPENSE + ".dispense" + DISPENSE});

        assertEquals(List.of("<init>", "retromod$final$dispense"), names(apply(behavior, host)));
        ClassNode mixinOut = apply(mixin, host);
        assertEquals(List.of("<init>", "dispense", "onDispense", "vanilla"), names(mixinOut));
        assertEquals(List.of("mod/CuttingBoardDispenseBehavior.retromod$final$dispense"), calls(mixinOut, "onDispense"));
        assertEquals(List.of(DEFAULT_DISPENSE + ".dispense"), calls(mixinOut, "vanilla"));
    }

    @Test
    @DisplayName("an override of a method the host left open, a static or private twin, and an unreadable host stay as they are")
    void leavesEverythingElse() {
        Map<String, HostFinalOverrideAdapter.Shape> host = new HashMap<>();
        host.put("host/Open", hostShape("java/lang/Object", Map.of("run()V", ACC_PUBLIC, "hidden()V", ACC_FINAL,
                "stat()V", ACC_PUBLIC | ACC_FINAL)));
        byte[] child = classWithMethods("mod/Child", "host/Open", false,
                new String[]{"run", "()V", null}, new String[]{"hidden", "()V", null});
        byte[] statics = classWithStaticTwin("mod/Twin", "host/Open");
        byte[] unknown = classWithMethods("mod/Lost", "host/Missing", false, new String[]{"run", "()V", null});

        assertSame(child, HostFinalOverrideAdapter.apply(child, host::get),
                "hidden() is package-private in another package, so the JVM does not treat it as overridden");
        assertSame(statics, HostFinalOverrideAdapter.apply(statics, host::get));
        assertSame(unknown, HostFinalOverrideAdapter.apply(unknown, host::get));
    }

    @Test
    @DisplayName("SmartBrainLib opens Behavior.tryStart with its own access transformer, so its override keeps the name")
    void openedFinalKeepsItsName() {
        String behavior = "net/minecraft/world/entity/ai/behavior/Behavior";
        String tryStart = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;J)Z";
        Map<String, HostFinalOverrideAdapter.Shape> host = Map.of(behavior,
                hostShape("java/lang/Object", Map.of("tryStart" + tryStart, ACC_PUBLIC | ACC_FINAL)));
        byte[] extended = classWithMethods("mod/ExtendedBehaviour", behavior, false,
                new String[]{"tryStart", tryStart, null});

        assertSame(extended, HostFinalOverrideAdapter.apply(extended, host::get,
                java.util.Set.of(behavior + ".tryStart" + tryStart)));
        assertEquals(List.of("<init>", "retromod$final$tryStart"),
                names(apply(extended, host)), "without the access transformer the JVM would refuse the override");
    }

    @Test
    @DisplayName("a final the earlier hook adapters handle keeps its calls on the host method that reaches the hook")
    void callsToHookedFinalsStay() {
        String living = "net/minecraft/world/entity/LivingEntity";
        String getDimensions = "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;";
        Map<String, HostFinalOverrideAdapter.Shape> host = new HashMap<>();
        host.put(living, hostShape("java/lang/Object", Map.of("getDimensions" + getDimensions, ACC_PUBLIC | ACC_FINAL)));
        // The jar's mob as shipped still declares the old override, which the hook adapter moves.
        host.put("mod/Mob", HostFinalOverrideAdapter.shapeOf(read(classWithMethods("mod/Mob", living, false,
                new String[]{"getDimensions", getDimensions, null})), false));
        byte[] caller = classWithMethods("mod/Caller", "java/lang/Object", false,
                new String[]{"size", "()V", "mod/Mob.getDimensions" + getDimensions});

        assertSame(caller, HostFinalOverrideAdapter.apply(caller, host::get));
    }

    @Test
    @DisplayName("the JVM loads the repaired class, the mod's own call runs its code, and the host's caller runs the host's")
    void repairedClassLoads() throws Exception {
        byte[] base = hostBase();
        byte[] child = classWithMethods("fixture/mod/Child", "fixture/host/Base", false,
                new String[]{"name", "()Ljava/lang/String;", "const:mod"},
                new String[]{"own", "()Ljava/lang/String;", "fixture/mod/Child.name()Ljava/lang/String;"});
        Map<String, HostFinalOverrideAdapter.Shape> host = Map.of("fixture/host/Base",
                HostFinalOverrideAdapter.shapeOf(read(base), true));

        Throwable refused = assertThrows(Throwable.class, () -> load(base, child).loadClass("fixture.mod.Child")
                .getDeclaredConstructor().newInstance());
        assertInstanceOf(IncompatibleClassChangeError.class, refused, "the old class overrides a final method");

        byte[] repaired = HostFinalOverrideAdapter.apply(child, host::get);
        Object instance = load(base, repaired).loadClass("fixture.mod.Child").getDeclaredConstructor().newInstance();
        assertEquals("mod", instance.getClass().getMethod("own").invoke(instance));
        assertEquals("base", instance.getClass().getMethod("describe").invoke(instance));
    }

    @Test
    @DisplayName("a method reference or method handle through the mod's own type follows the rename; one through the host does not")
    void handlesFollowTheRename() {
        Map<String, HostFinalOverrideAdapter.Shape> host = Map.of("host/Base",
                hostShape("java/lang/Object", Map.of("name()Ljava/lang/String;", ACC_PUBLIC | ACC_FINAL)));
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/Tamed", null, "host/Base", null);
        returningName(cw, "name");
        MethodVisitor refs = cw.visitMethod(ACC_PUBLIC, "refs", "()V", null, null);
        refs.visitCode();
        Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        Handle own = new Handle(H_INVOKEVIRTUAL, "mod/Tamed", "name", "()Ljava/lang/String;", false);
        refs.visitVarInsn(ALOAD, 0);
        refs.visitInvokeDynamicInsn("get", "(Lmod/Tamed;)Ljava/util/function/Supplier;", metafactory,
                Type.getType("()Ljava/lang/Object;"), own, Type.getType("()Ljava/lang/String;"));
        refs.visitInsn(POP);
        refs.visitLdcInsn(own);
        refs.visitInsn(POP);
        refs.visitLdcInsn(new Handle(H_INVOKEVIRTUAL, "host/Base", "name", "()Ljava/lang/String;", false));
        refs.visitInsn(POP);
        refs.visitInsn(RETURN);
        refs.visitMaxs(0, 0);
        refs.visitEnd();
        cw.visitEnd();

        MethodNode out = read(HostFinalOverrideAdapter.apply(cw.toByteArray(), host::get)).methods.stream()
                .filter(m -> m.name.equals("refs")).findFirst().orElseThrow();
        List<String> handles = new ArrayList<>();
        for (var insn : out.instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy) handles.add(((Handle) indy.bsmArgs[1]).getName());
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle handle) {
                handles.add(handle.getOwner() + "." + handle.getName());
            }
        }
        assertEquals(List.of("retromod$final$name", "mod/Tamed.retromod$final$name", "host/Base.name"), handles);
    }

    @Test
    @DisplayName("a mixin's own method and its call to it, and a call into a mod class in a Minecraft package, stay as written")
    void namesNoClassKeepsAreLeftAlone() {
        Map<String, HostFinalOverrideAdapter.Shape> host = new HashMap<>();
        host.put("host/Base", hostShape("java/lang/Object", Map.of("name()Ljava/lang/String;", ACC_PUBLIC | ACC_FINAL)));
        byte[] mixin = classWithMethods("mod/BaseMixin", "host/Base", true,
                new String[]{"name", "()Ljava/lang/String;", "const:mixin"},
                new String[]{"own", "()Ljava/lang/String;", "mod/BaseMixin.name()Ljava/lang/String;"});
        host.put("mod/BaseMixin", HostFinalOverrideAdapter.shapeOf(read(mixin), false));
        byte[] packaged = classWithMethods("net/minecraft/mod/Packaged", "host/Base", false,
                new String[]{"name", "()Ljava/lang/String;", "const:packaged"});
        host.put("net/minecraft/mod/Packaged", HostFinalOverrideAdapter.shapeOf(read(packaged), false));
        byte[] caller = classWithMethods("mod/Caller", "java/lang/Object", false,
                new String[]{"call", "()Ljava/lang/String;", "net/minecraft/mod/Packaged.name()Ljava/lang/String;"});

        assertSame(mixin, HostFinalOverrideAdapter.apply(mixin, host::get));
        assertSame(caller, HostFinalOverrideAdapter.apply(caller, host::get));
    }

    @Test
    @DisplayName("a mod under com/retromod, as the test mods are, is renamed; Retromod's embedded copy is not")
    void testModPackageIsAModEmbeddedCopyIsNot() {
        Map<String, HostFinalOverrideAdapter.Shape> host = Map.of("host/Base",
                hostShape("java/lang/Object", Map.of("name()Ljava/lang/String;", ACC_PUBLIC | ACC_FINAL)));
        byte[] testMod = classWithMethods("com/retromod/testmod/forge/Child", "host/Base", false,
                new String[]{"name", "()Ljava/lang/String;", "const:mod"});
        byte[] embedded = classWithMethods("com/retromod/embedded/kitchen_1_0_0_abc/com/retromod/generated/Child",
                "host/Base", false, new String[]{"name", "()Ljava/lang/String;", "const:retromod"});

        assertEquals(List.of("<init>", "retromod$final$name"), names(apply(testMod, host)));
        assertSame(embedded, HostFinalOverrideAdapter.apply(embedded, host::get));
    }

    @Test
    @DisplayName("with nothing known about the jar's access files, the class is left as it is")
    void unknownAccessFilesChangeNothing() {
        Map<String, HostFinalOverrideAdapter.Shape> host = Map.of("host/Base",
                hostShape("java/lang/Object", Map.of("name()Ljava/lang/String;", ACC_PUBLIC | ACC_FINAL)));
        byte[] child = classWithMethods("mod/Child", "host/Base", false, new String[]{"name", "()Ljava/lang/String;", "const:mod"});

        assertSame(child, HostFinalOverrideAdapter.apply(child, host::get, null));
    }

    private static void returningName(ClassWriter cw, String name) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitLdcInsn("mod");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static HostFinalOverrideAdapter.Shape hostShape(String superName, Map<String, Integer> methods) {
        return new HostFinalOverrideAdapter.Shape(superName, methods, true, false);
    }

    private static ClassNode apply(byte[] bytes, Map<String, HostFinalOverrideAdapter.Shape> host) {
        byte[] out = HostFinalOverrideAdapter.apply(bytes, host::get);
        assertNotSame(bytes, out, "the class should change");
        return read(out);
    }

    /** JEI's recipe: two overrides of host finals, one ordinary override, and a method calling both kinds. */
    private static byte[] recipe() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC, "mod/JeiShapedRecipe", null, SHAPED_RECIPE, null);
        constructor(cw, SHAPED_RECIPE);
        returning(cw, "showNotification", "()Z", ICONST_0, IRETURN);
        returning(cw, "category", CATEGORY, ACONST_NULL, ARETURN);
        returning(cw, "getWidth", "()I", ICONST_3, IRETURN);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "bridge", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "mod/JeiShapedRecipe", "category", CATEGORY, false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, SHAPED_RECIPE, "group", "()Ljava/lang/String;", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code fixture/host/Base}: a final {@code name()} and a {@code describe()} that calls it. */
    private static byte[] hostBase() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC, "fixture/host/Base", null, "java/lang/Object", null);
        constructor(cw, "java/lang/Object");
        MethodVisitor name = cw.visitMethod(ACC_PUBLIC | ACC_FINAL, "name", "()Ljava/lang/String;", null, null);
        name.visitCode();
        name.visitLdcInsn("base");
        name.visitInsn(ARETURN);
        name.visitMaxs(0, 0);
        name.visitEnd();
        MethodVisitor describe = cw.visitMethod(ACC_PUBLIC, "describe", "()Ljava/lang/String;", null, null);
        describe.visitCode();
        describe.visitVarInsn(ALOAD, 0);
        describe.visitMethodInsn(INVOKEVIRTUAL, "fixture/host/Base", "name", "()Ljava/lang/String;", false);
        describe.visitInsn(ARETURN);
        describe.visitMaxs(0, 0);
        describe.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A class whose methods each either return a constant ({@code const:<text>}), make one call
     * ({@code owner.name(desc)}), or do nothing ({@code null}).
     */
    private static byte[] classWithMethods(String name, String superName, boolean mixin, String[]... methods) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        if (mixin) cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitEnd();
        constructor(cw, superName);
        for (String[] method : methods) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method[0], method[1], null, null);
            mv.visitCode();
            if (method[2] != null && method[2].startsWith("const:")) {
                mv.visitLdcInsn(method[2].substring(6));
                mv.visitInsn(ARETURN);
            } else {
                if (method[2] != null) {
                    int dot = method[2].indexOf('.');
                    int paren = method[2].indexOf('(');
                    String desc = method[2].substring(paren);
                    mv.visitVarInsn(ALOAD, 0);
                    for (int i = 0; i < org.objectweb.asm.Type.getArgumentTypes(desc).length; i++) mv.visitInsn(ACONST_NULL);
                    mv.visitMethodInsn(INVOKEVIRTUAL, method[2].substring(0, dot), method[2].substring(dot + 1, paren), desc, false);
                    if (org.objectweb.asm.Type.getReturnType(desc).getSort() != org.objectweb.asm.Type.VOID
                            && org.objectweb.asm.Type.getReturnType(method[1]).getSort() == org.objectweb.asm.Type.VOID) {
                        mv.visitInsn(POP);
                    }
                }
                org.objectweb.asm.Type returns = org.objectweb.asm.Type.getReturnType(method[1]);
                if (returns.getSort() == org.objectweb.asm.Type.VOID) {
                    mv.visitInsn(RETURN);
                } else {
                    if (method[2] == null) mv.visitInsn(ACONST_NULL);
                    mv.visitInsn(ARETURN);
                }
            }
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class with a static method and a private method that share names with host finals. */
    private static byte[] classWithStaticTwin(String name, String superName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        for (String[] method : new String[][]{{"stat", "()V"}, {"run", "()V"}}) {
            int access = method[0].equals("stat") ? ACC_PUBLIC | ACC_STATIC : ACC_PRIVATE;
            MethodVisitor mv = cw.visitMethod(access, method[0], method[1], null, null);
            mv.visitCode();
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void constructor(ClassWriter cw, String superName) {
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
    }

    private static void returning(ClassWriter cw, String name, String desc, int value, int returnOp) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitInsn(value);
        mv.visitInsn(returnOp);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static ClassLoader load(byte[] base, byte[] child) {
        return new ClassLoader(HostFinalOverrideAdapterTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals("fixture.host.Base")) return defineClass(name, base, 0, base.length);
                if (name.equals("fixture.mod.Child")) return defineClass(name, child, 0, child.length);
                throw new ClassNotFoundException(name);
            }
        };
    }

    private static List<String> names(ClassNode node) {
        return node.methods.stream().map(method -> method.name).toList();
    }

    private static List<String> calls(ClassNode node, String method) {
        List<String> calls = new ArrayList<>();
        MethodNode found = node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
        for (var insn : found.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        return calls;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
