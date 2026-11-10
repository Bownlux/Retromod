/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.FabricModTransformer;
import com.retromod.core.ForgeModTransformer;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.core.VersionShim;
import com.retromod.loaderapi.CliHarness;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Forge 26.2 known-good set stopped in RegisterEvent on a fence gate built from sounds, a
 * cooking pot that implements the 1.20.1 recipe interface, a patch feature's configuration, a
 * packed item color, a food effect given as a supplier, a flower built from an effect, and an
 * entity's spawn packet. The vanilla ones reach NeoForge and Fabric 26.x too. Each case is checked
 * on the copy embedded in a jar translated by each loader's runtime and by the CLI.
 */
class RegisterEventLayerPathParityTest {

    private static final String PROPS = "net/minecraft/world/level/block/state/BlockBehaviour$Properties";
    private static final String SOUND = "net/minecraft/sounds/SoundEvent";
    private static final String GATE = "mod/RopeGate";
    private static final String POT = "mod/CookingPot";
    private static final String PATCHES = "mod/Patches";
    private static final String CALLS = "mod/Calls";
    private static final String OLD_PATCH = "net/minecraft/world/level/levelgen/feature/configurations/RandomPatchConfiguration";
    private static final String FOOD_BUILDER = "net/minecraft/world/food/FoodProperties$Builder";
    private static final String FLOWER = "mod/WildCrop";
    private static final String FLOWER_HOST_DESC = "(Lnet/minecraft/core/Holder;FL" + PROPS + ";)V";
    // The same classes in the intermediary names a Fabric 1.20.1 mod is built against.
    private static final String I_PROPS = "net/minecraft/class_4970$class_2251";
    private static final String I_SOUND = "net/minecraft/class_3414";
    private static final String I_GATE = "net/minecraft/class_2349";
    private static final String I_FLOWER = "net/minecraft/class_2356";
    private static final String I_EFFECT = "net/minecraft/class_1291";
    private static final String I_PATCH = "net/minecraft/class_4638";
    private static final String I_HOLDER = "net/minecraft/class_6880";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @BeforeEach
    void forgeTarget() {
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(true);
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"forge, 26.2", "forge, 26.3", "neoforge, 26.2", "neoforge, 26.3", "fabric, 26.2", "fabric, 26.3"})
    @DisplayName("Each loader's runtime and the CLI batch embed the same registration-time bridges")
    void everyPathAgrees(String loader, String host, @TempDir Path dir) throws Exception {
        boolean fabric = loader.equals("fabric");
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        Path input = dir.resolve("registermod.jar");
        Files.write(input, fabric ? fabricModJar() : modJar());

        Map<String, Map<String, String>> outputs = new LinkedHashMap<>();
        outputs.put(loader + " runtime", summary(runtime(loader, input, dir, host), loader));
        Path batchIn = Files.createDirectory(dir.resolve("batch-in"));
        Path batchOut = Files.createDirectory(dir.resolve("batch-out"));
        Files.copy(input, batchIn.resolve("registermod.jar"));
        if (fabric) {
            CliHarness.batch(batchIn, batchOut, host);
        } else {
            CliHarness.batch(batchIn, batchOut, host, "--target-loader", loader);
        }
        outputs.put("CLI batch", summary(LegacyAutoSubscribePathParityTest.onlyJar(batchOut), loader));

        Map<String, String> expected = new TreeMap<>();
        // These runs read no host classes, so every loader gets the no-host gate shape. A real NeoForge
        // host keeps the sound constructor and passes it straight through, which
        // RegisterEventVanillaBridgesTest.soundGatePassesThroughWhereTheHostHasIt covers.
        expected.put("fence gate", "extends the embedded gate, which takes the old sounds and builds an oak type from them");
        expected.put("item color", "calls the embedded color helper");
        expected.put("flower", "builds through the holder constructor with the effect stashed");
        expected.put("flower effect", "wraps the effect through a registry whose class is not public");
        // 26.3 folded configurations into features, so the old configuration has nothing to implement there.
        expected.put("random patch", host.equals("26.2") ? "builds the embedded configuration" : "builds the removed class");
        if (!fabric) {
            expected.put("recipe holder", "implements the embedded interface with the old recipe methods");
            expected.put("food effect", "calls the embedded consumable helper with the supplier");
        }
        if (loader.equals("forge")) expected.put("spawn packet", "takes Forge's current spawn packet");
        for (Map.Entry<String, Map<String, String>> output : outputs.entrySet()) {
            assertEquals(expected, output.getValue(), output.getKey() + " on " + host);
        }
    }

    /** What each loader's entry point registers on the host, then one runtime transform. */
    private Path runtime(String loader, Path input, Path dir, String host) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        Set<String> types = switch (loader) {
            case "fabric" -> Set.of("fabric", "common");
            case "neoforge" -> Set.of("neoforge", "forge", "common");
            default -> Set.of("forge", "common");
        };
        // A 26.x Fabric host runs Mojang names, so the game remaps intermediary first.
        if (loader.equals("fabric")) IntermediaryToMojangMapper.applyTo(transformer);
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (types.contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        Path outDir = Files.createDirectory(dir.resolve("runtime"));
        Path out = loader.equals("fabric") ? new FabricModTransformer(host).transformMod(input, outDir)
                : new ForgeModTransformer(host).transformMod(input, outDir);
        assertNotNull(out, "the " + loader + " runtime wrote a jar");
        return out;
    }

    private static Map<String, String> summary(Path jarPath, String loader) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ClassNode gate = node(jar, GATE);
            String sounds = "(L" + PROPS + ";L" + SOUND + ";L" + SOUND + ";)V";
            boolean gateOk = embedded(jar, gate.superName, "com/retromod/generated/LegacyFenceGateBlock")
                    && superCall(gate).desc.equals(sounds)
                    && node(jar, gate.superName).methods.stream().anyMatch(m -> m.name.equals("<init>")
                            && m.desc.equals(sounds) && calls(m).stream().anyMatch(c -> c.name.equals("retromod$soundWood")));
            found.put("fence gate", gateOk ? "extends the embedded gate, which takes the old sounds and builds an oak type from them"
                    : "extends " + gate.superName + " calling " + superCall(gate).desc);

            MethodInsnNode color = calls(method(node(jar, CALLS), "color")).get(0);
            found.put("item color", embedded(jar, color.owner, "com/retromod/generated/LegacyMthColor")
                    ? "calls the embedded color helper" : "calls " + color.owner + "." + color.name);

            ClassNode crop = node(jar, FLOWER);
            boolean stashed = calls(method(crop, "<init>")).stream().anyMatch(c -> c.name.equals("stashEffect")
                    && embedded(jar, c.owner, "com/retromod/shim/forge/embedded/LegacyFlowerEffects"));
            found.put("flower", stashed && superCall(crop).desc.equals(FLOWER_HOST_DESC)
                    ? "builds through the holder constructor with the effect stashed" : "calls " + superCall(crop).desc);
            String stash = calls(method(crop, "<init>")).stream().filter(c -> c.name.equals("stashEffect"))
                    .map(c -> c.owner).findFirst().orElse(null);
            found.put("flower effect", stash == null ? "has no stash call" : HiddenRegistryHost.wrapsEffect(jar, stash));

            MethodInsnNode built = calls(method(node(jar, PATCHES), "build")).get(0);
            found.put("random patch", embedded(jar, built.owner,
                    "com/retromod/legacyapi/minecraft/world/level/levelgen/feature/configurations/RandomPatchConfiguration")
                    ? "builds the embedded configuration" : built.owner.equals(OLD_PATCH) ? "builds the removed class"
                    : "builds " + built.owner);

            if (loader.equals("fabric")) return found;

            ClassNode pot = node(jar, POT);
            String holder = pot.interfaces.isEmpty() ? "none" : pot.interfaces.get(0);
            boolean holderOk = embedded(jar, holder, "com/retromod/legacyapi/minecraft/world/inventory/RecipeHolder")
                    && node(jar, holder).methods.stream().anyMatch(m -> m.name.equals("getRecipeUsed"));
            found.put("recipe holder", holderOk ? "implements the embedded interface with the old recipe methods"
                    : "implements " + pot.interfaces);

            MethodInsnNode effect = calls(method(node(jar, CALLS), "food")).stream()
                    .filter(c -> c.name.equals("effect")).findFirst().orElseThrow();
            found.put("food effect", embedded(jar, effect.owner, "com/retromod/shim/common/embedded/LegacyFoodConsumableBridge")
                    && effect.desc.equals("(L" + FOOD_BUILDER + ";Ljava/util/function/Supplier;F)L" + FOOD_BUILDER + ";")
                    ? "calls the embedded consumable helper with the supplier" : "calls " + effect.owner + effect.desc);

            if (!loader.equals("forge")) return found;
            String packet = method(node(jar, CALLS), "spawn").desc;
            found.put("spawn packet", packet.equals("(Lnet/minecraftforge/network/packets/SpawnEntity;)V")
                    ? "takes Forge's current spawn packet" : "takes " + packet);
        }
        return found;
    }

    private static boolean embedded(JarFile jar, String name, String original) {
        return name.startsWith(SyntheticEmbedder.PREFIX) && name.endsWith("/" + original)
                && jar.getJarEntry(name + ".class") != null;
    }

    private static ClassNode node(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static java.util.List<MethodInsnNode> calls(MethodNode method) {
        java.util.List<MethodInsnNode> found = new java.util.ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) found.add(call);
        }
        return found;
    }

    private static MethodInsnNode superCall(ClassNode node) {
        return calls(method(node, "<init>")).stream().filter(c -> c.name.equals("<init>")).findFirst().orElseThrow();
    }

    /** A Forge 1.20.1 mod with each registration-time shape the known-good set reached. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("META-INF/mods.toml", LegacyAutoSubscribePathParityTest.toml("registermod"));
        mod.put(GATE + ".class", gate("net/minecraft/world/level/block/FenceGateBlock", PROPS, SOUND));
        mod.put(POT + ".class", pot());
        mod.put(PATCHES + ".class", patches(OLD_PATCH, "net/minecraft/core/Holder"));
        mod.put(CALLS + ".class", calls());
        mod.put(FLOWER + ".class", flower(FLOWER_SUPER, "net/minecraft/world/effect/MobEffect", PROPS));
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    private static final String FLOWER_SUPER = "net/minecraft/world/level/block/FlowerBlock";

    /** The same mod built for Fabric 1.20.1: intermediary names, and only the vanilla calls. */
    private static byte[] fabricModJar() throws Exception {
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"registermod\",\"version\":\"1.0\","
                + "\"depends\":{\"minecraft\":\"1.20.1\"}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mod.put(GATE + ".class", gate(I_GATE, I_PROPS, I_SOUND));
        mod.put(FLOWER + ".class", flower(I_FLOWER, I_EFFECT, I_PROPS));
        mod.put(PATCHES + ".class", patches(I_PATCH, I_HOLDER));
        mod.put(CALLS + ".class", fabricCalls());
        return LegacyAutoSubscribePathParityTest.jar(mod);
    }

    /** {@code WildCrop(MobEffect effect, int seconds, Properties p) { super(effect, seconds, p); }}, as Farmer's Delight writes it. */
    private static byte[] flower(String base, String effect, String props) {
        String desc = "(L" + effect + ";IL" + props + ";)V";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, FLOWER, null, base, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, base, "<init>", desc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The item color in the intermediary names of 1.20.1's Mth. */
    private static byte[] fabricCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, CALLS, null, "java/lang/Object", null);
        MethodVisitor color = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "color", "()I", null, null);
        color.visitCode();
        color.visitInsn(Opcodes.FCONST_1);
        color.visitLdcInsn(0.5f);
        color.visitInsn(Opcodes.FCONST_0);
        color.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/class_3532", "method_15353", "(FFF)I", false);
        color.visitInsn(Opcodes.IRETURN);
        color.visitMaxs(0, 0);
        color.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code RopeGate(Properties p) { super(p, OPEN, CLOSE); }}, as Farmer's Delight writes it. */
    private static byte[] gate(String base, String props, String sound) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, GATE, null, base, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + props + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, base, "<init>", "(L" + props + ";L" + sound + ";L" + sound + ";)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A block entity that implements the 1.20.1 recipe interface with its two abstract methods. */
    private static byte[] pot() {
        String recipe = "Lnet/minecraft/world/item/crafting/Recipe;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, POT, null, "java/lang/Object",
                new String[] {"net/minecraft/world/inventory/RecipeHolder"});
        MethodVisitor set = cw.visitMethod(Opcodes.ACC_PUBLIC, "setRecipeUsed", "(" + recipe + ")V", null, null);
        set.visitCode();
        set.visitInsn(Opcodes.RETURN);
        set.visitMaxs(0, 0);
        set.visitEnd();
        MethodVisitor get = cw.visitMethod(Opcodes.ACC_PUBLIC, "getRecipeUsed", "()" + recipe, null, null);
        get.visitCode();
        get.visitInsn(Opcodes.ACONST_NULL);
        get.visitInsn(Opcodes.ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Builds a patch configuration by hand, as Farmer's Delight's wild rice feature's data does through its codec. */
    private static byte[] patches(String patch, String holder) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, PATCHES, null, "java/lang/Object", null);
        MethodVisitor build = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build",
                "(L" + holder + ";)Ljava/lang/Object;", null, null);
        build.visitCode();
        build.visitTypeInsn(Opcodes.NEW, patch);
        build.visitInsn(Opcodes.DUP);
        build.visitIntInsn(Opcodes.BIPUSH, 32);
        build.visitInsn(Opcodes.ICONST_2);
        build.visitInsn(Opcodes.ICONST_3);
        build.visitVarInsn(Opcodes.ALOAD, 0);
        build.visitMethodInsn(Opcodes.INVOKESPECIAL, patch, "<init>", "(IIIL" + holder + ";)V", false);
        build.visitInsn(Opcodes.ARETURN);
        build.visitMaxs(0, 0);
        build.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The item color, the supplied food effect, and a client factory's spawn packet parameter. */
    private static byte[] calls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, CALLS, null, "java/lang/Object", null);
        MethodVisitor color = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "color", "()I", null, null);
        color.visitCode();
        color.visitInsn(Opcodes.FCONST_1);
        color.visitLdcInsn(0.5f);
        color.visitInsn(Opcodes.FCONST_0);
        color.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/util/Mth", "color", "(FFF)I", false);
        color.visitInsn(Opcodes.IRETURN);
        color.visitMaxs(0, 0);
        color.visitEnd();
        MethodVisitor food = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "food",
                "(L" + FOOD_BUILDER + ";Ljava/util/function/Supplier;)L" + FOOD_BUILDER + ";", null, null);
        food.visitCode();
        food.visitVarInsn(Opcodes.ALOAD, 0);
        food.visitVarInsn(Opcodes.ALOAD, 1);
        food.visitInsn(Opcodes.FCONST_1);
        food.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FOOD_BUILDER, "effect",
                "(Ljava/util/function/Supplier;F)L" + FOOD_BUILDER + ";", false);
        food.visitInsn(Opcodes.ARETURN);
        food.visitMaxs(0, 0);
        food.visitEnd();
        MethodVisitor spawn = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "spawn",
                "(Lnet/minecraftforge/network/PlayMessages$SpawnEntity;)V", null, null);
        spawn.visitCode();
        spawn.visitInsn(Opcodes.RETURN);
        spawn.visitMaxs(0, 0);
        spawn.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Runs the embedded flower helper against a mob effect registry whose class is not public, as Forge 26.2's NamespacedWrapper is. */
    private static final class HiddenRegistryHost {
        private static final String HOLDER = "net/minecraft/core/Holder";
        private static final String DIRECT = "net/minecraft/core/DirectHolder";
        private static final String REGISTRY = "net/minecraft/core/Registry";
        private static final String MAPPED = "net/minecraft/core/MappedRegistry";
        private static final String WRAPPER = "net/minecraftforge/registries/NamespacedWrapper";
        private static final String WRAPPERS = "net/minecraftforge/registries/Wrappers";
        private static final String BUILT_IN = "net/minecraft/core/registries/BuiltInRegistries";
        private static final String EFFECT = "net/minecraft/world/effect/MobEffect";
        private static final String WRAP_DESC = "(Ljava/lang/Object;)L" + HOLDER + ";";

        static String wrapsEffect(JarFile jar, String helper) throws Exception {
            Map<String, byte[]> classes = new java.util.HashMap<>();
            classes.put(helper, jar.getInputStream(jar.getJarEntry(helper + ".class")).readAllBytes());
            classes.put(HOLDER, type(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, HOLDER, "java/lang/Object", null));
            classes.put(REGISTRY, registry());
            classes.put(DIRECT, direct());
            classes.put(MAPPED, mapped(MAPPED, Opcodes.ACC_PUBLIC, "java/lang/Object", new String[] {REGISTRY}));
            classes.put(WRAPPER, mapped(WRAPPER, 0, MAPPED, null));
            classes.put(WRAPPERS, wrappers());
            classes.put(BUILT_IN, builtIn());
            classes.put(EFFECT, type(Opcodes.ACC_PUBLIC, EFFECT, "java/lang/Object", null));
            ClassLoader loader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] bytes = classes.get(name.replace('.', '/'));
                    if (bytes == null) throw new ClassNotFoundException(name);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            };
            Class<?> effects = Class.forName(helper.replace('/', '.'), true, loader);
            Object effect = Class.forName(EFFECT.replace('/', '.'), true, loader).getConstructor().newInstance();
            Object properties = new Object();
            try {
                Object passed = effects.getMethod("stashEffect", Object.class, int.class, Object.class)
                        .invoke(null, effect, 6, properties);
                Object holder = effects.getMethod("holder").invoke(null);
                float seconds = (Float) effects.getMethod("seconds").invoke(null);
                Object value = holder == null ? null : holder.getClass().getField("value").get(holder);
                return passed == properties && value == effect && seconds == 6f
                        ? "wraps the effect through a registry whose class is not public"
                        : "stashed " + holder + " for " + seconds + " seconds";
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause().getCause() != null ? e.getCause().getCause() : e.getCause();
                return "fails with " + cause;
            }
        }

        private static byte[] type(int access, String name, String superName, String[] interfaces) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, access, name, null, superName, interfaces);
            if ((access & Opcodes.ACC_INTERFACE) == 0) constructor(cw, superName, "()V");
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] registry() {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, REGISTRY, null,
                    "java/lang/Object", null);
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "wrapAsHolder", WRAP_DESC, null, null).visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        /** A holder that keeps its value in a public field. */
        private static byte[] direct() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, DIRECT, null, "java/lang/Object", new String[] {HOLDER});
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "value", "Ljava/lang/Object;", null, null).visitEnd();
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/Object;)V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitVarInsn(Opcodes.ALOAD, 1);
            init.visitFieldInsn(Opcodes.PUTFIELD, DIRECT, "value", "Ljava/lang/Object;");
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        /** A registry class whose wrapAsHolder hands back a direct holder; Forge's subclass overrides it. */
        private static byte[] mapped(String name, int access, String superName, String[] interfaces) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, access | Opcodes.ACC_SUPER, name, null, superName, interfaces);
            constructor(cw, superName, "()V");
            MethodVisitor wrap = cw.visitMethod(Opcodes.ACC_PUBLIC, "wrapAsHolder", WRAP_DESC, null, null);
            wrap.visitCode();
            wrap.visitTypeInsn(Opcodes.NEW, DIRECT);
            wrap.visitInsn(Opcodes.DUP);
            wrap.visitVarInsn(Opcodes.ALOAD, 1);
            wrap.visitMethodInsn(Opcodes.INVOKESPECIAL, DIRECT, "<init>", "(Ljava/lang/Object;)V", false);
            wrap.visitInsn(Opcodes.ARETURN);
            wrap.visitMaxs(0, 0);
            wrap.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        /** Builds the non-public wrapper from its own package, as Forge does. */
        private static byte[] wrappers() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, WRAPPERS, null, "java/lang/Object", null);
            MethodVisitor make = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()L" + REGISTRY + ";", null, null);
            make.visitCode();
            make.visitTypeInsn(Opcodes.NEW, WRAPPER);
            make.visitInsn(Opcodes.DUP);
            make.visitMethodInsn(Opcodes.INVOKESPECIAL, WRAPPER, "<init>", "()V", false);
            make.visitInsn(Opcodes.ARETURN);
            make.visitMaxs(0, 0);
            make.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] builtIn() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, BUILT_IN, null, "java/lang/Object", null);
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "MOB_EFFECT", "L" + REGISTRY + ";",
                    null, null).visitEnd();
            MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitCode();
            clinit.visitMethodInsn(Opcodes.INVOKESTATIC, WRAPPERS, "make", "()L" + REGISTRY + ";", false);
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, BUILT_IN, "MOB_EFFECT", "L" + REGISTRY + ";");
            clinit.visitInsn(Opcodes.RETURN);
            clinit.visitMaxs(0, 0);
            clinit.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static void constructor(ClassWriter cw, String superName, String desc) {
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
        }
    }
}
