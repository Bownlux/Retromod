/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyRecipes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import static org.objectweb.asm.Opcodes.*;

/** Keeps a 1.20.1 mod's recipes, recipe serializers, and recipe lookups working on 1.20.5 to 1.21.1 hosts. */
public final class LegacyRecipeApiBridge {

    static final String SUPPORT = "com/retromod/shim/common/embedded/LegacyRecipes";
    static final String CALLS = "com/retromod/generated/LegacyRecipeCalls";
    static final String INPUT = "com/retromod/generated/LegacyContainerInput";
    static final String STREAM = "com/retromod/generated/LegacyRecipeStreamCodec";

    private static final String CRAFTING = "net/minecraft/world/item/crafting/";
    static final String RECIPE = CRAFTING + "Recipe";
    static final String CRAFTING_RECIPE = CRAFTING + "CraftingRecipe";
    static final String SERIALIZER = CRAFTING + "RecipeSerializer";
    static final String MANAGER = CRAFTING + "RecipeManager";
    static final String RECIPE_INPUT = CRAFTING + "RecipeInput";
    private static final String RECIPE_TYPE = CRAFTING + "RecipeType";
    private static final String INGREDIENT = CRAFTING + "Ingredient";
    private static final String SHAPED = CRAFTING + "ShapedRecipe";
    private static final String CONTAINER = "net/minecraft/world/Container";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String STACK = "net/minecraft/world/item/ItemStack";
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String ID = "net/minecraft/resources/ResourceLocation";
    private static final String BUF = "net/minecraft/network/FriendlyByteBuf";
    private static final String ACCESS = "net/minecraft/core/RegistryAccess";
    private static final String PROVIDER = "net/minecraft/core/HolderLookup$Provider";
    private static final String LIST = "net/minecraft/core/NonNullList";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String STREAM_CODEC = "net/minecraft/network/codec/StreamCodec";
    private static final String OBJECT = "Ljava/lang/Object;";

    // Assembled at runtime so the release jar's Gson relocation leaves the game's Gson names alone.
    private static final String GSON = String.join("/", "com", "google", "gson") + "/";
    private static final String JSON_OBJECT = "L" + GSON + "JsonObject;";
    private static final String JSON_ELEMENT = "L" + GSON + "JsonElement;";

    static final String OLD_MATCHES = "(L" + CONTAINER + ";L" + LEVEL + ";)Z";
    static final String OLD_ASSEMBLE = "(L" + CONTAINER + ";L" + ACCESS + ";)L" + STACK + ";";
    static final String OLD_RESULT = "(L" + ACCESS + ";)L" + STACK + ";";
    static final String OLD_REMAINING = "(L" + CONTAINER + ";)L" + LIST + ";";
    static final String OLD_FROM_JSON = "(L" + ID + ";" + JSON_OBJECT + ")";
    static final String OLD_FROM_NETWORK = "(L" + ID + ";L" + BUF + ";)";

    /** Which 1.20.5 to 1.21.1 recipe API the host has: recipes read a {@code RecipeInput} from 1.21. */
    record HostShape(boolean inputs) {
        String input() {
            return inputs ? RECIPE_INPUT : CONTAINER;
        }
    }

    // The shape register() found, which the class repair builds the new methods for.
    private static volatile HostShape registeredShape;

    private LegacyRecipeApiBridge() {}

    /** Registers on hosts whose serializers are codecs and whose recipes still answer getResultItem. */
    public static void register(RetromodTransformer t) {
        HostShape host = hostShape();
        if (host == null) return;
        registeredShape = host;
        SyntheticEmbedder.registerClassResource(t, SUPPORT, LegacyRecipes.class);
        t.registerSyntheticClass(CALLS, generateCalls(host));
        t.registerSyntheticClass(STREAM, generateStreamCodec());
        if (host.inputs()) t.registerSyntheticClass(INPUT, generateInput());

        // Lookups return holders since 1.20.2, and take inputs since 1.21. Some keep their old
        // descriptor, so only mods built before 1.20.2 are rewritten.
        String manager = "L" + MANAGER + ";";
        String type = "L" + RECIPE_TYPE + ";";
        String lookup = "(" + type + "L" + CONTAINER + ";L" + LEVEL + ";)";
        lookup(t, "getRecipeFor", lookup + "Ljava/util/Optional;", manager);
        lookup(t, "getRecipesFor", lookup + "Ljava/util/List;", manager);
        lookup(t, "getAllRecipesFor", "(" + type + ")Ljava/util/List;", manager);
        lookup(t, "getRecipes", "()Ljava/util/Collection;", manager);
        lookup(t, "byKey", "(L" + ID + ";)Ljava/util/Optional;", manager);
        lookup(t, "replaceRecipes", "(Ljava/lang/Iterable;)V", manager);

        // A recipe's id moved to its holder in 1.20.2.
        for (String owner : List.of(RECIPE, CRAFTING_RECIPE, SHAPED, CRAFTING + "ShapelessRecipe",
                CRAFTING + "AbstractCookingRecipe", CRAFTING + "SingleItemRecipe", CRAFTING + "CustomRecipe")) {
            t.registerMethodRedirect(owner, "getId", "()L" + ID + ";", CALLS, "getId", "(L" + RECIPE + ";)L" + ID + ";", true);
        }

        // Ingredients, stacks, and items read from JSON and buffers by removed helpers.
        String ingredient = "L" + INGREDIENT + ";";
        removed(t, INGREDIENT, "fromJson", "(" + JSON_ELEMENT + ")" + ingredient, "ingredientFromJson", false);
        removed(t, INGREDIENT, "fromJson", "(" + JSON_ELEMENT + "Z)" + ingredient, "ingredientFromJsonAllowingEmpty", false);
        removed(t, INGREDIENT, "toJson", "()" + JSON_ELEMENT, "ingredientToJson", true);
        removed(t, INGREDIENT, "fromNetwork", "(L" + BUF + ";)" + ingredient, "ingredientFromNetwork", false);
        removed(t, INGREDIENT, "toNetwork", "(L" + BUF + ";)V", "ingredientToNetwork", true);
        removed(t, BUF, "readItem", "()L" + STACK + ";", "readItem", true);
        removed(t, BUF, "writeItem", "(L" + STACK + ";)L" + BUF + ";", "writeItem", true);
        removed(t, SHAPED, "itemStackFromJson", "(" + JSON_OBJECT + ")L" + STACK + ";", "itemStackFromJson", false);
        removed(t, SHAPED, "itemFromJson", "(" + JSON_OBJECT + ")L" + ITEM + ";", "itemFromJson", false);
    }

    /** Whether {@link #register} ran, which turns the class repair on. */
    public static boolean isRegistered(Predicate<String> hasSynthetic) {
        return hasSynthetic.test(CALLS);
    }

    private static void lookup(RetromodTransformer t, String name, String desc, String manager) {
        String helperDesc = "(" + manager + desc.substring(1);
        t.registerMethodRedirect(MANAGER, name, desc, CALLS, name, helperDesc, true);
        t.limitRedirectToModsBefore(MANAGER, name, desc, "1.20.2");
    }

    /** A removed member to its adapter; an instance member's receiver becomes the first argument. */
    private static void removed(RetromodTransformer t, String owner, String name, String desc, String helper, boolean instance) {
        if (!removedOnHost(owner, name, desc)) return;
        String helperDesc = instance ? "(L" + owner + ";" + desc.substring(1) : desc;
        t.registerMethodRedirect(owner, name, desc, CALLS, helper, helperDesc, instance);
    }

    // Offline, where the host cannot be read, the version range decides.
    static HostShape hostShape() {
        ClassNode serializer = ClassResourceInspector.read(SERIALIZER);
        ClassNode recipe = ClassResourceInspector.read(RECIPE);
        if (serializer == null || recipe == null) return offlineShape(RetromodVersion.TARGET_MC_VERSION);
        boolean codecs = (serializer.access & ACC_INTERFACE) != 0
                && declares(serializer, "codec", "()L" + MAP_CODEC + ";")
                && declares(serializer, "streamCodec", "()L" + STREAM_CODEC + ";");
        boolean results = declares(recipe, "getResultItem", "(L" + PROVIDER + ";)L" + STACK + ";");
        if (!codecs || !results) return null;
        return new HostShape(ClassResourceInspector.exists(RECIPE_INPUT));
    }

    static HostShape offlineShape(String host) {
        if (RetromodVersion.compareMcVersions(host, "1.20.5") < 0 || RetromodVersion.compareMcVersions(host, "1.21.2") >= 0) {
            return null;
        }
        return new HostShape(RetromodVersion.compareMcVersions(host, "1.21") >= 0);
    }

    private static boolean removedOnHost(String owner, String name, String desc) {
        ClassNode node = ClassResourceInspector.read(owner);
        return node == null || !declares(node, name, desc);
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }

    // ---- class repair ----------------------------------------------------------------------------

    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null) return null;
        if (!new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("Recipe")) return classBytes;
        HostShape host = registeredShape;
        return apply(classBytes, host != null ? host : new HostShape(true), RetromodTransformer::currentJarClassInheritsFrom);
    }

    static byte[] apply(byte[] classBytes, HostShape host, BiPredicate<String, String> inheritsFrom) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        if ((node.access & ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/") || isRetromodHelper(node.name)) {
            return classBytes;
        }
        boolean changed = false;
        if (node.interfaces.contains(SERIALIZER) && isOldSerializer(node)) {
            changed |= addSerializerCodecs(node);
        }
        if (isRecipe(node, inheritsFrom)) {
            changed |= addRecipeMethods(node, host);
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    // Retromod's own helpers are already in the host's shape. Only these packages: a mod may
    // live under com/retromod too, as Retromod's test mods do.
    private static boolean isRetromodHelper(String name) {
        return name.startsWith("com/retromod/generated/") || name.startsWith(SyntheticEmbedder.PREFIX)
                || name.startsWith("com/retromod/shim/");
    }

    private static boolean isOldSerializer(ClassNode node) {
        return node.methods.stream().anyMatch(m -> (m.access & ACC_STATIC) == 0
                && (m.name.equals("fromJson") && m.desc.startsWith(OLD_FROM_JSON)
                    || m.name.equals("fromNetwork") && m.desc.startsWith(OLD_FROM_NETWORK)));
    }

    private static boolean isRecipe(ClassNode node, BiPredicate<String, String> inheritsFrom) {
        if (node.interfaces.contains(RECIPE) || node.interfaces.contains(CRAFTING_RECIPE)) return true;
        return node.superName != null && !node.superName.startsWith("net/minecraft/")
                && !node.superName.equals("java/lang/Object") && inheritsFrom.test(node.superName, RECIPE);
    }

    /** {@code codec()} reads JSON through the old fromJson; {@code streamCodec()} uses fromNetwork and toNetwork. */
    private static boolean addSerializerCodecs(ClassNode node) {
        boolean changed = false;
        if (findInstance(node, "codec", "()L" + MAP_CODEC + ";") == null) {
            MethodNode codec = new MethodNode(ACC_PUBLIC, "codec", "()L" + MAP_CODEC + ";", null, null);
            codec.instructions.add(new VarInsnNode(ALOAD, 0));
            codec.instructions.add(new MethodInsnNode(INVOKESTATIC, SUPPORT, "codec", "(" + OBJECT + ")" + OBJECT, false));
            codec.instructions.add(new TypeInsnNode(CHECKCAST, MAP_CODEC));
            codec.instructions.add(new InsnNode(ARETURN));
            node.methods.add(codec);
            changed = true;
        }
        if (findInstance(node, "streamCodec", "()L" + STREAM_CODEC + ";") == null) {
            MethodNode stream = new MethodNode(ACC_PUBLIC, "streamCodec", "()L" + STREAM_CODEC + ";", null, null);
            stream.instructions.add(new TypeInsnNode(NEW, STREAM));
            stream.instructions.add(new InsnNode(DUP));
            stream.instructions.add(new VarInsnNode(ALOAD, 0));
            stream.instructions.add(new MethodInsnNode(INVOKESPECIAL, STREAM, "<init>", "(" + OBJECT + ")V", false));
            stream.instructions.add(new InsnNode(ARETURN));
            node.methods.add(stream);
            changed = true;
        }
        return changed;
    }

    /** Each old recipe method gets the host's form, which converts its arguments and calls the old one. */
    private static boolean addRecipeMethods(ClassNode node, HostShape host) {
        String input = "L" + host.input() + ";";
        boolean changed = false;
        // From 1.21 matches and getRemainingItems read an input. Before, they still read a container.
        if (host.inputs()) {
            changed |= addForwarder(node, "matches", OLD_MATCHES, "(" + input + "L" + LEVEL + ";)Z", true, false);
            changed |= addForwarder(node, "getRemainingItems", OLD_REMAINING, "(" + input + ")L" + LIST + ";", true, false);
        }
        changed |= addForwarder(node, "assemble", OLD_ASSEMBLE, "(" + input + "L" + PROVIDER + ";)L" + STACK + ";",
                host.inputs(), true);
        changed |= addForwarder(node, "getResultItem", OLD_RESULT, "(L" + PROVIDER + ";)L" + STACK + ";", false, true);
        return changed;
    }

    /**
     * Adds {@code name(newDesc)} calling the old {@code name(oldDesc)} on this object. The first
     * argument becomes a container when {@code convertInput}, and the provider a registry access
     * when {@code convertProvider}.
     */
    private static boolean addForwarder(ClassNode node, String name, String oldDesc, String newDesc,
            boolean convertInput, boolean convertProvider) {
        if (findInstance(node, name, oldDesc) == null || findInstance(node, name, newDesc) != null) return false;
        MethodNode method = new MethodNode(ACC_PUBLIC, name, newDesc, null, null);
        Type[] arguments = Type.getArgumentTypes(newDesc);
        method.instructions.add(new VarInsnNode(ALOAD, 0));
        for (int i = 0; i < arguments.length; i++) {
            method.instructions.add(new VarInsnNode(ALOAD, i + 1));
            String argument = arguments[i].getInternalName();
            if (convertInput && i == 0) {
                method.instructions.add(new MethodInsnNode(INVOKESTATIC, CALLS, "container",
                        "(L" + RECIPE_INPUT + ";)L" + CONTAINER + ";", false));
            } else if (convertProvider && argument.equals(PROVIDER)) {
                method.instructions.add(new MethodInsnNode(INVOKESTATIC, CALLS, "registryAccess",
                        "(L" + PROVIDER + ";)L" + ACCESS + ";", false));
            }
        }
        method.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, node.name, name, oldDesc, false));
        method.instructions.add(new InsnNode(Type.getReturnType(newDesc).getOpcode(IRETURN)));
        node.methods.add(method);
        return true;
    }

    private static MethodNode findInstance(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc) && (method.access & ACC_STATIC) == 0) return method;
        }
        return null;
    }

    // ---- generated classes -----------------------------------------------------------------------

    /** Exact-typed static adapters: the lookups call the host and unwrap holders, the rest forward to the helper. */
    static byte[] generateCalls(HostShape host) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CALLS, null, "java/lang/Object", null);
        String manager = "L" + MANAGER + ";";
        String type = "L" + RECIPE_TYPE + ";";
        String container = "L" + CONTAINER + ";";
        String level = "L" + LEVEL + ";";
        String hostLookup = "(" + type + "L" + host.input() + ";" + level + ")";

        lookupCall(cw, host, "getRecipeFor", "(" + manager + type + container + level + ")Ljava/util/Optional;",
                hostLookup + "Ljava/util/Optional;", "value");
        lookupCall(cw, host, "getRecipesFor", "(" + manager + type + container + level + ")Ljava/util/List;",
                hostLookup + "Ljava/util/List;", "values");
        lookupCall(cw, host, "getAllRecipesFor", "(" + manager + type + ")Ljava/util/List;",
                "(" + type + ")Ljava/util/List;", "values");
        lookupCall(cw, host, "getRecipes", "(" + manager + ")Ljava/util/Collection;", "()Ljava/util/Collection;", "values");
        lookupCall(cw, host, "byKey", "(" + manager + "L" + ID + ";)Ljava/util/Optional;",
                "(L" + ID + ";)Ljava/util/Optional;", "value");
        replaceRecipes(cw, manager);

        forward(cw, "getId", "(L" + RECIPE + ";)L" + ID + ";", "id");
        forward(cw, "registryAccess", "(L" + PROVIDER + ";)L" + ACCESS + ";", "registryAccess");
        if (host.inputs()) container(cw);
        String ingredient = "L" + INGREDIENT + ";";
        forward(cw, "ingredientFromJson", "(" + JSON_ELEMENT + ")" + ingredient, "ingredientFromJson");
        forward(cw, "ingredientFromJsonAllowingEmpty", "(" + JSON_ELEMENT + "Z)" + ingredient, "ingredientFromJson");
        forward(cw, "ingredientToJson", "(" + ingredient + ")" + JSON_ELEMENT, "ingredientToJson");
        forward(cw, "ingredientFromNetwork", "(L" + BUF + ";)" + ingredient, "ingredientFromNetwork");
        forward(cw, "ingredientToNetwork", "(" + ingredient + "L" + BUF + ";)V", "ingredientToNetwork");
        forward(cw, "readItem", "(L" + BUF + ";)L" + STACK + ";", "readItem");
        forward(cw, "writeItem", "(L" + BUF + ";L" + STACK + ";)L" + BUF + ";", "writeItem");
        forward(cw, "itemStackFromJson", "(" + JSON_OBJECT + ")L" + STACK + ";", "itemStackFromJson");
        forward(cw, "itemFromJson", "(" + JSON_OBJECT + ")L" + ITEM + ";", "itemFromJson");
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static R name(manager, args)}: the host lookup, a container wrapped as an input, then the holders unwrapped. */
    private static void lookupCall(ClassWriter cw, HostShape host, String name, String desc, String hostDesc, String unwrap) {
        Type[] arguments = Type.getArgumentTypes(desc);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        for (int i = 0; i < arguments.length; i++) {
            if (host.inputs() && arguments[i].getInternalName().equals(CONTAINER)) {
                mv.visitTypeInsn(NEW, INPUT);
                mv.visitInsn(DUP);
                mv.visitVarInsn(ALOAD, i);
                mv.visitMethodInsn(INVOKESPECIAL, INPUT, "<init>", "(L" + CONTAINER + ";)V", false);
            } else {
                mv.visitVarInsn(ALOAD, i);
            }
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, MANAGER, name, hostDesc, false);
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, unwrap, "(" + OBJECT + ")" + OBJECT, false);
        mv.visitTypeInsn(CHECKCAST, Type.getReturnType(desc).getInternalName());
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void replaceRecipes(ClassWriter cw, String manager) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "replaceRecipes", "(" + manager + "Ljava/lang/Iterable;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, "holders", "(" + OBJECT + ")" + OBJECT, false);
        mv.visitTypeInsn(CHECKCAST, "java/lang/Iterable");
        mv.visitMethodInsn(INVOKEVIRTUAL, MANAGER, "replaceRecipes", "(Ljava/lang/Iterable;)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code container(input)}: the mod's own container when the input wraps one, else a copy. */
    private static void container(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "container",
                "(L" + RECIPE_INPUT + ";)L" + CONTAINER + ";", null, null);
        mv.visitCode();
        Label copy = new Label();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(INSTANCEOF, INPUT);
        mv.visitJumpInsn(IFEQ, copy);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitTypeInsn(CHECKCAST, INPUT);
        mv.visitFieldInsn(GETFIELD, INPUT, "container", "L" + CONTAINER + ";");
        mv.visitInsn(ARETURN);
        mv.visitLabel(copy);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, "container", "(" + OBJECT + ")" + OBJECT, false);
        mv.visitTypeInsn(CHECKCAST, CONTAINER);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static R name(args)}: the reference arguments to the helper, its result cast to R. */
    private static void forward(ClassWriter cw, String name, String desc, String helper) {
        Type[] arguments = Type.getArgumentTypes(desc);
        Type result = Type.getReturnType(desc);
        StringBuilder helperDesc = new StringBuilder("(");
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        // A trailing boolean, such as fromJson's allowEmpty, has no counterpart in the helper.
        for (int i = 0; i < arguments.length; i++) {
            if (arguments[i].getSort() != Type.OBJECT) continue;
            mv.visitVarInsn(ALOAD, i);
            helperDesc.append(OBJECT);
        }
        boolean returns = result.getSort() != Type.VOID;
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, helper, helperDesc + ")" + (returns ? OBJECT : "V"), false);
        if (returns) {
            mv.visitTypeInsn(CHECKCAST, result.getInternalName());
            mv.visitInsn(ARETURN);
        } else {
            mv.visitInsn(RETURN);
        }
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code LegacyContainerInput}: the container an old lookup passes, as the input 1.21 recipes read. */
    static byte[] generateInput() {
        String container = "L" + CONTAINER + ";";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, INPUT, null, "java/lang/Object", new String[]{RECIPE_INPUT});
        cw.visitField(ACC_PUBLIC | ACC_FINAL, "container", container, null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + container + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, INPUT, "container", container);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        inputCall(cw, "getItem", "(I)L" + STACK + ";", "getItem", "(I)L" + STACK + ";", ARETURN);
        inputCall(cw, "size", "()I", "getContainerSize", "()I", IRETURN);
        inputCall(cw, "isEmpty", "()Z", "isEmpty", "()Z", IRETURN);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void inputCall(ClassWriter cw, String name, String desc, String containerName, String containerDesc, int returnOpcode) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, INPUT, "container", "L" + CONTAINER + ";");
        if (desc.startsWith("(I")) mv.visitVarInsn(ILOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, CONTAINER, containerName, containerDesc, true);
        mv.visitInsn(returnOpcode);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code LegacyRecipeStreamCodec}: a serializer's fromNetwork and toNetwork as the stream codec 1.20.5 asks for. */
    static byte[] generateStreamCodec() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, STREAM, null, "java/lang/Object", new String[]{STREAM_CODEC});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "serializer", OBJECT, null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(" + OBJECT + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, STREAM, "serializer", OBJECT);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor decode = cw.visitMethod(ACC_PUBLIC, "decode", "(" + OBJECT + ")" + OBJECT, null, null);
        decode.visitCode();
        decode.visitVarInsn(ALOAD, 0);
        decode.visitFieldInsn(GETFIELD, STREAM, "serializer", OBJECT);
        decode.visitVarInsn(ALOAD, 1);
        decode.visitMethodInsn(INVOKESTATIC, SUPPORT, "decodeNetwork", "(" + OBJECT + OBJECT + ")" + OBJECT, false);
        decode.visitInsn(ARETURN);
        decode.visitMaxs(0, 0);
        decode.visitEnd();

        MethodVisitor encode = cw.visitMethod(ACC_PUBLIC, "encode", "(" + OBJECT + OBJECT + ")V", null, null);
        encode.visitCode();
        encode.visitVarInsn(ALOAD, 0);
        encode.visitFieldInsn(GETFIELD, STREAM, "serializer", OBJECT);
        encode.visitVarInsn(ALOAD, 1);
        encode.visitVarInsn(ALOAD, 2);
        encode.visitMethodInsn(INVOKESTATIC, SUPPORT, "encodeNetwork", "(" + OBJECT + OBJECT + OBJECT + ")V", false);
        encode.visitInsn(RETURN);
        encode.visitMaxs(0, 0);
        encode.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
