/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Forge to NeoForge registry migration (#85). {@code ForgeRegistries.BLOCKS} maps to
 * {@code BuiltInRegistries.BLOCK}, covering both registration
 * ({@code DeferredRegister.create(ForgeRegistries.BLOCKS, id)}) and lookups
 * ({@code ForgeRegistries.ITEMS.getValue(loc)} to {@code Registry.get(loc)}).
 *
 * <p>The field redirect carries each registry's declared type (DefaultedRegistry for
 * BLOCK/ITEM/ENTITY_TYPE/FLUID, Registry for the rest).
 */
class ForgeRegistryMigrationTest {

    private static final String FORGE_REGS = "net/minecraftforge/registries/ForgeRegistries";
    private static final String IFORGE_REG = "net/minecraftforge/registries/IForgeRegistry";
    private static final String L_IFORGE_REG = "L" + IFORGE_REG + ";";
    private static final String FORGE_DR = "net/minecraftforge/registries/DeferredRegister";
    private static final String NEO_DR = "net/neoforged/neoforge/registries/DeferredRegister";
    private static final String BUILTIN = "net/minecraft/core/registries/BuiltInRegistries";
    private static final String REGISTRY = "net/minecraft/core/Registry";
    private static final String L_REGISTRY = "L" + REGISTRY + ";";
    private static final String L_DEFAULTED = "Lnet/minecraft/core/DefaultedRegistry;";
    private static final String NEO_REGS = "net/neoforged/neoforge/registries/NeoForgeRegistries";
    private static final String RESLOC = "net/minecraft/resources/ResourceLocation";

    @BeforeEach
    void registerMigration() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        // bypass the isNeoForge() gate; drive the worker directly
        new ForgeRegistryApiShim().registerRegistryRedirects(t);
    }

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "26.1"; // other shim tests expect 26.1
    }

    @Test
    @DisplayName("create(ForgeRegistries.BLOCKS, id): field→BuiltInRegistries instance + call→create(Registry)")
    void canonicalBlocks() {
        byte[] out = transform(canonicalRegister("BLOCKS"));
        MethodNode go = go(out);

        FieldInsnNode field = first(go, FieldInsnNode.class);
        assertEquals(BUILTIN, field.owner, "ForgeRegistries owner must become vanilla BuiltInRegistries");
        assertEquals("BLOCK", field.name, "the plural BLOCKS field must map to the singular BLOCK instance");
        assertEquals(L_DEFAULTED, field.desc, "BLOCK is a DefaultedRegistry on 26.1 (exact field type)");

        MethodInsnNode call = first(go, MethodInsnNode.class);
        assertEquals(NEO_DR, call.owner, "create() owner must become the neoforged DeferredRegister");
        assertEquals("create", call.name);
        assertEquals("(" + L_REGISTRY + "Ljava/lang/String;)L" + NEO_DR + ";", call.desc,
                "create() must accept a Registry instance and return a neoforged DeferredRegister");

        assertNoRefs(out, FORGE_REGS, IFORGE_REG, NEO_REGS);
    }

    @Test
    @DisplayName("registry field table maps to BuiltInRegistries with the correct exact type per registry")
    void multipleRegistries() {
        // {forgeField, vanillaField, exactType}
        String[][] cases = {
                {"ITEMS", "ITEM", L_DEFAULTED}, {"ENTITY_TYPES", "ENTITY_TYPE", L_DEFAULTED},
                {"FLUIDS", "FLUID", L_DEFAULTED}, {"MENU_TYPES", "MENU", L_REGISTRY},
                {"BLOCK_ENTITY_TYPES", "BLOCK_ENTITY_TYPE", L_REGISTRY},
                {"CREATIVE_MODE_TABS", "CREATIVE_MODE_TAB", L_REGISTRY},
        };
        for (String[] c : cases) {
            FieldInsnNode field = first(go(transform(canonicalRegister(c[0]))), FieldInsnNode.class);
            assertEquals(BUILTIN, field.owner, c[0] + " owner");
            assertEquals(c[1], field.name, c[0] + " must map to the vanilla field " + c[1]);
            assertEquals(c[2], field.desc, c[0] + " must carry its exact registry type");
        }
    }

    @Test
    @DisplayName("ForgeRegistries.ITEMS.getValue(loc): field→instance + getValue→Registry.get (pattern 3)")
    void instanceLookup() {
        ClassWriter cw = fixture();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go",
                "(L" + RESLOC + ";)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, FORGE_REGS, "ITEMS", L_IFORGE_REG);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, IFORGE_REG, "getValue",
                "(L" + RESLOC + ";)Ljava/lang/Object;", true);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = transform(cw.toByteArray());
        MethodNode go = go(out);

        FieldInsnNode field = first(go, FieldInsnNode.class);
        assertEquals(BUILTIN, field.owner);
        assertEquals("ITEM", field.name);
        assertEquals(L_DEFAULTED, field.desc, "ITEM is a DefaultedRegistry");

        MethodInsnNode call = first(go, MethodInsnNode.class);
        assertEquals(REGISTRY, call.owner, "getValue must re-point onto net/minecraft/core/Registry");
        assertEquals("get", call.name, "IForgeRegistry.getValue is Registry.get on 26.1 (the rename)");
        assertEquals("(L" + RESLOC + ";)Ljava/lang/Object;", call.desc);
        assertEquals(Opcodes.INVOKEINTERFACE, call.getOpcode(), "Registry is an interface");
        assertNoRefs(out, FORGE_REGS, IFORGE_REG, NEO_REGS);
    }

    @Test
    @DisplayName("RegistryObject type reference → DeferredHolder")
    void registryObjectToDeferredHolder() {
        ClassWriter cw = fixture();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go",
                "(Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraftforge/registries/RegistryObject");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        TypeInsnNode cast = first(go(transform(cw.toByteArray())), TypeInsnNode.class);
        assertEquals("net/neoforged/neoforge/registries/DeferredHolder", cast.desc,
                "RegistryObject must redirect to DeferredHolder");
    }

    @Test
    @DisplayName("#306: a vanilla key in ForgeRegistries.Keys reads Registries; a Forge-only key stays")
    void registryKeysSplitBetweenVanillaAndNeoForge() {
        assertKeyRead("ITEMS", "net/minecraft/core/registries/Registries", "ITEM");
        assertKeyRead("POI_TYPES", "net/minecraft/core/registries/Registries", "POINT_OF_INTEREST_TYPE");
        assertKeyRead("FLUID_TYPES", NEO_REGS + "$Keys", "FLUID_TYPES");
    }

    private static void assertKeyRead(String forgeKey, String owner, String name) {
        String resourceKey = "Lnet/minecraft/resources/ResourceKey;";
        ClassWriter cw = fixture();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, FORGE_REGS + "$Keys", forgeKey, resourceKey);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        FieldInsnNode field = first(go(transform(cw.toByteArray())), FieldInsnNode.class);
        assertEquals(owner + "." + name, field.owner + "." + field.name,
                "ForgeRegistries.Keys." + forgeKey + " must read the host's own key");
        assertEquals(resourceKey, field.desc);
    }

    private static byte[] transform(byte[] in) {
        return RetromodTransformer.getInstance().transformClass(in, "test/RegFixture");
    }

    private static ClassWriter fixture() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/RegFixture", null, "java/lang/Object", null);
        return cw;
    }

    /** {@code static void go()} doing {@code DeferredRegister.create(ForgeRegistries.<field>, "modid")}. */
    private static byte[] canonicalRegister(String forgeField) {
        ClassWriter cw = fixture();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "go", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, FORGE_REGS, forgeField, L_IFORGE_REG);
        mv.visitLdcInsn("modid");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, FORGE_DR, "create",
                "(" + L_IFORGE_REG + "Ljava/lang/String;)L" + FORGE_DR + ";", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodNode go(byte[] b) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        return cn.methods.stream().filter(m -> m.name.equals("go")).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static <T extends AbstractInsnNode> T first(MethodNode m, Class<T> type) {
        return (T) Arrays.stream(m.instructions.toArray()).filter(type::isInstance).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type.getSimpleName() + " in go()"));
    }

    /** Fail if any instruction still references one of the banned internal names (owner or descriptor). */
    private static void assertNoRefs(byte[] b, String... bannedNames) {
        Set<String> banned = Set.of(bannedNames);
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, 0);
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode in : m.instructions.toArray()) {
                if (in instanceof FieldInsnNode fi) {
                    assertFalse(banned.contains(fi.owner), "leftover field-owner ref to " + fi.owner);
                    assertFalse(descMentions(banned, fi.desc), "leftover type in field desc: " + fi.desc);
                } else if (in instanceof MethodInsnNode mi) {
                    assertFalse(banned.contains(mi.owner), "leftover call-owner ref to " + mi.owner);
                    assertFalse(descMentions(banned, mi.desc), "leftover type in method desc: " + mi.desc);
                } else if (in instanceof TypeInsnNode ti) {
                    assertFalse(banned.contains(ti.desc), "leftover type ref to " + ti.desc);
                }
            }
        }
    }

    private static boolean descMentions(Set<String> banned, String desc) {
        for (String name : banned) {
            if (desc.contains(name)) return true;
        }
        return false;
    }
}
