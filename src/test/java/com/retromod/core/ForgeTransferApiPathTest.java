/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.ShimRegistration;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A Forge 1.20.1 mod's item, fluid and energy handlers on NeoForge 1.21.1 and 26.3, on every translation path. */
class ForgeTransferApiPathTest {

    private static final String POT = "mod/Pot";
    private static final String FORGE_ITEMS = "net/minecraftforge/items/";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String COMPOUND = "Lnet/minecraft/nbt/CompoundTag;";
    private static final String REBUILT = "com/retromod/legacyapi/";

    @TempDir
    Path tempDir;

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceNeoForge(false);
        McReflect.setForceForge(false);
        ModVersionDetector.setDefaultPreferredLoader(null);
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} on NeoForge {1}")
    @CsvSource({"runtime, 26.3", "transform, 26.3", "batch, 26.3", "aot, 26.3", "runtime, 1.21.1", "transform, 1.21.1"})
    @DisplayName("Forge's handler classes reach NeoForge's or the rebuilt ones, and RecipeWrapper keeps its container shape")
    void handlersReachWorkingClasses(String path, String host) throws Exception {
        Path output = translate(path, host, writeMod());
        boolean rebuilt = host.equals("26.3");

        Set<String> names = classNames(output, POT);
        assertTrue(names.stream().noneMatch(name -> name.startsWith(FORGE_ITEMS) || name.startsWith("net/minecraftforge/fluids/capability")
                || name.startsWith("net/minecraftforge/energy")), "no Forge handler name is left: " + names);
        String handlers = rebuilt ? embedded(names, REBUILT + "neoforge/items/ItemStackHandler") : "net/neoforged/neoforge/items/ItemStackHandler";
        assertTrue(names.contains(handlers), "the handler is " + handlers + ": " + names);
        assertTrue(names.contains(rebuilt ? embedded(names, REBUILT + "neoforge/energy/IEnergyStorage") : "net/neoforged/neoforge/energy/IEnergyStorage"),
                names.toString());
        if (rebuilt) {
            assertTrue(names.stream().noneMatch(name -> name.startsWith("net/neoforged/neoforge/items/")
                    || name.startsWith("net/neoforged/neoforge/energy/")), "26.3 has none of NeoForge's handler classes: " + names);
        }

        // The old wrapper is a container again, built from whichever modifiable handler the host has.
        String wrapper = embedded(names, REBUILT + "forge/items/wrapper/RecipeWrapper");
        ClassNode wrapperClass = node(output, wrapper);
        assertTrue(wrapperClass.interfaces.contains("net/minecraft/world/Container"), wrapperClass.interfaces.toString());
        String modifiable = rebuilt ? embedded(names, REBUILT + "neoforge/items/IItemHandlerModifiable") : "net/neoforged/neoforge/items/IItemHandlerModifiable";
        assertTrue(wrapperClass.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals("(L" + modifiable + ";)V")),
                "the wrapper takes " + modifiable);
        if (rebuilt) node(output, modifiable);

        // Forge's fluid stack and type are NeoForge's on every version.
        assertTrue(names.contains("net/neoforged/neoforge/fluids/FluidStack") && names.contains("net/neoforged/neoforge/fluids/FluidType"),
                names.toString());
        assertTrue(names.stream().noneMatch(name -> name.startsWith("net/minecraftforge/fluids/")), names.toString());

        List<String> calls = calls(node(output, POT));
        // The handler load learns the data version the 1.20.1 mod saved its stacks at.
        assertTrue(calls.stream().anyMatch(call -> call.contains("LegacyForgeItemHandlers.deserializeNBT(Ljava/lang/Object;Lnet/minecraft/nbt/CompoundTag;I)V")),
                calls.toString());
        // NeoForge 1.21.1 still compares fluid stacks itself; from 1.21.11 the helper does.
        assertEquals(rebuilt, calls.stream().anyMatch(call -> call.contains("LegacyForgeFluidStacks.isFluidEqual(")), calls.toString());
        assertEquals(!rebuilt, calls.contains("net/neoforged/neoforge/fluids/FluidStack.isFluidEqual(Lnet/neoforged/neoforge/fluids/FluidStack;)Z"),
                calls.toString());
        for (String helper : List.of("LegacyForgeItemHandlers.canItemStacksStack(", "LegacyForgeItemHandlers.copyStackWithSize(",
                "LegacyForgeItemHandlers.serializeNBT(", "LegacyForgeItemHandlers.deserializeNBT(",
                "LegacyForgeFluidStacks.loadFluidStackFromNBT(")) {
            String call = calls.stream().filter(c -> c.contains(helper)).findFirst()
                    .orElseThrow(() -> new AssertionError(path + " must call " + helper + ": " + calls));
            assertTrue(call.startsWith(SyntheticEmbedder.PREFIX), "the helper is embedded: " + call);
            node(output, call.substring(0, call.indexOf('.')));
        }
    }

    /** Translates {@code input} for NeoForge {@code host} through one path and returns the output jar. */
    private Path translate(String path, String host, Path input) throws Exception {
        ModVersionDetector.setDefaultPreferredLoader("neoforge");
        McReflect.setForceNeoForge(true);
        Path out = Files.createDirectories(tempDir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> {
                registerNeoForgeHost(host);
                yield new ForgeModTransformer(host).transformMod(input, out);
            }
            case "transform" -> {
                CliHarness.transform(input, out.resolve("out.jar"), host);
                yield out.resolve("out.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("out.jar"), host);
                yield out.resolve("out.jar");
            }
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                yield out.resolve(input.getFileName());
            }
        };
        assertNotNull(output, path + " must produce a jar");
        assertTrue(Files.isRegularFile(output), path + " must produce " + output);
        return output;
    }

    /** Registers what RetromodNeoForge registers on a NeoForge {@code host}: every NeoForge, Forge and common shim it can take. */
    private static void registerNeoForgeHost(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        for (VersionShim shim : ServiceLoader.load(VersionShim.class)) {
            if (Set.of("neoforge", "forge", "common").contains(shim.getModLoaderType()) && ShimRegistry.isAvailableOnHost(shim, host)) {
                ShimRegistration.register(shim, transformer);
            }
        }
        SrgToMojangMapper.getInstance().applyTo(transformer);
        IntermediaryToMojangMapper.applyClassMovesOnly(transformer);
    }

    private Path writeMod() throws Exception {
        Path input = Files.createDirectories(tempDir.resolve("in")).resolve("potmod-1.20.1.jar");
        String toml = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\nlicense=\"MIT\"\n"
                + "[[mods]]\nmodId=\"potmod\"\nversion=\"1.0.0\"\n"
                + "[[dependencies.potmod]]\nmodId=\"forge\"\nmandatory=true\nversionRange=\"[47,)\"\n"
                + "[[dependencies.potmod]]\nmodId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.20.1,1.21)\"\n";
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("META-INF/mods.toml"));
            out.write(toml.getBytes(StandardCharsets.UTF_8));
            out.putNextEntry(new JarEntry(POT + ".class"));
            out.write(pot());
        }
        return input;
    }

    /** A cooking pot as Farmer's Delight writes one: a handler, its recipe wrapper, the helper calls and the save. */
    private static byte[] pot() {
        String handler = "L" + FORGE_ITEMS + "ItemStackHandler;";
        String modifiable = "L" + FORGE_ITEMS + "IItemHandlerModifiable;";
        String wrapper = FORGE_ITEMS + "wrapper/RecipeWrapper";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, POT, null, "java/lang/Object", null);
        cw.visitField(ACC_PUBLIC, "inventory", handler, null, null).visitEnd();
        cw.visitField(ACC_PUBLIC, "fluids", "Lnet/minecraftforge/fluids/capability/IFluidHandler;", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC, "energy", "Lnet/minecraftforge/energy/IEnergyStorage;", null, null).visitEnd();
        cw.visitField(ACC_PUBLIC, "fluidType", "Lnet/minecraftforge/fluids/FluidType;", null, null).visitEnd();

        String fluidStack = "Lnet/minecraftforge/fluids/FluidStack;";
        MethodVisitor fluids = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "fluids", "(" + fluidStack + COMPOUND + ")Z", null, null);
        fluids.visitCode();
        fluids.visitVarInsn(ALOAD, 0);
        fluids.visitVarInsn(ALOAD, 1);
        fluids.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/fluids/FluidStack", "loadFluidStackFromNBT", "(" + COMPOUND + ")" + fluidStack, false);
        fluids.visitMethodInsn(INVOKEVIRTUAL, "net/minecraftforge/fluids/FluidStack", "isFluidEqual", "(" + fluidStack + ")Z", false);
        fluids.visitInsn(IRETURN);
        fluids.visitMaxs(0, 0);
        fluids.visitEnd();

        MethodVisitor wrap = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "wrap", "(" + modifiable + ")Ljava/lang/Object;", null, null);
        wrap.visitCode();
        wrap.visitTypeInsn(NEW, wrapper);
        wrap.visitInsn(DUP);
        wrap.visitVarInsn(ALOAD, 0);
        wrap.visitMethodInsn(INVOKESPECIAL, wrapper, "<init>", "(" + modifiable + ")V", false);
        wrap.visitInsn(ARETURN);
        wrap.visitMaxs(0, 0);
        wrap.visitEnd();

        MethodVisitor stacks = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "merge", "(" + STACK + STACK + ")" + STACK, null, null);
        stacks.visitCode();
        stacks.visitVarInsn(ALOAD, 0);
        stacks.visitVarInsn(ALOAD, 1);
        stacks.visitMethodInsn(INVOKESTATIC, FORGE_ITEMS + "ItemHandlerHelper", "canItemStacksStack", "(" + STACK + STACK + ")Z", false);
        stacks.visitInsn(POP);
        stacks.visitVarInsn(ALOAD, 0);
        stacks.visitInsn(ICONST_2);
        stacks.visitMethodInsn(INVOKESTATIC, FORGE_ITEMS + "ItemHandlerHelper", "copyStackWithSize", "(" + STACK + "I)" + STACK, false);
        stacks.visitInsn(ARETURN);
        stacks.visitMaxs(0, 0);
        stacks.visitEnd();

        MethodVisitor save = cw.visitMethod(ACC_PUBLIC, "save", "()" + COMPOUND, null, null);
        save.visitCode();
        save.visitVarInsn(ALOAD, 0);
        save.visitFieldInsn(GETFIELD, POT, "inventory", handler);
        save.visitInsn(DUP);
        save.visitInsn(ACONST_NULL);
        save.visitMethodInsn(INVOKEVIRTUAL, FORGE_ITEMS + "ItemStackHandler", "deserializeNBT", "(" + COMPOUND + ")V", false);
        save.visitMethodInsn(INVOKEVIRTUAL, FORGE_ITEMS + "ItemStackHandler", "serializeNBT", "()" + COMPOUND, false);
        save.visitInsn(ARETURN);
        save.visitMaxs(0, 0);
        save.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The one name in {@code names} that ends with {@code internalName} under an embedded package. */
    private static String embedded(Set<String> names, String internalName) {
        return names.stream().filter(name -> name.startsWith(SyntheticEmbedder.PREFIX) && name.endsWith("/" + internalName)).findFirst()
                .orElseThrow(() -> new AssertionError("no embedded " + internalName + " in " + names));
    }

    private static ClassNode node(Path jar, String className) throws Exception {
        ClassNode node = new ClassNode();
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(className + ".class");
            assertNotNull(entry, className + " travels in " + jar.getFileName());
            new ClassReader(file.getInputStream(entry).readAllBytes()).accept(node, 0);
        }
        return node;
    }

    /** Every class one class of the jar names, in its header, members and code. */
    private static Set<String> classNames(Path jar, String className) throws Exception {
        Set<String> names = new TreeSet<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            byte[] bytes = file.getInputStream(file.getJarEntry(className + ".class")).readAllBytes();
            new ClassReader(bytes).accept(new ClassRemapper(new ClassNode(), new Remapper() {
                @Override
                public String map(String internalName) {
                    names.add(internalName);
                    return internalName;
                }
            }), 0);
        }
        return names;
    }

    /** Every method call in the class, as owner.name(desc). */
    private static List<String> calls(ClassNode node) {
        List<String> refs = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) refs.add(call.owner + "." + call.name + call.desc);
            }
        }
        return refs;
    }
}
