/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.mixin.MixinLazyStaticInitializer;
import com.retromod.shim.common.embedded.LegacyRecipes;
import com.retromod.util.McReflect;
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

/** Keeps a 1.20.1 mod's recipes, recipe serializers, and recipe lookups working on hosts from 1.20.5 to 26.3. */
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
    // 1.21.11 renamed ResourceLocation, and a mod's references take the host's name before the class repair runs.
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String CACHED_CHECK = CRAFTING + "RecipeManager$CachedCheck";
    private static final String PLACEMENT = CRAFTING + "PlacementInfo";
    private static final String BOOK_CATEGORY = CRAFTING + "RecipeBookCategory";
    private static final String BOOK_CATEGORIES = CRAFTING + "RecipeBookCategories";
    static final String CRAFTING_HELPER = "net/minecraftforge/common/crafting/CraftingHelper";
    private static final String QUERIES = LegacyRecipeIntrospectionBridge.CALLS;
    private static final String INTROSPECTION = LegacyRecipeIntrospectionBridge.SUPPORT;
    private static final String BUF = "net/minecraft/network/FriendlyByteBuf";
    private static final String ACCESS = "net/minecraft/core/RegistryAccess";
    private static final String PROVIDER = "net/minecraft/core/HolderLookup$Provider";
    private static final String LIST = "net/minecraft/core/NonNullList";
    private static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    private static final String STREAM_CODEC = "net/minecraft/network/codec/StreamCodec";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String ITEM_LIKE = "net/minecraft/world/level/ItemLike";

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

    /**
     * Which recipe API the host has: recipes read a {@code RecipeInput} from 1.21; from 1.21.2 they answer
     * placement and book questions and the manager keeps them by key; from 26.1 {@code assemble} takes no
     * registries; from 1.21.11 ids are {@code Identifier}s.
     */
    record HostShape(boolean inputs, boolean recipeBook, boolean assembleProvider, String id) {
        HostShape(boolean inputs) {
            this(inputs, false, true, ID);
        }

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
        // From 26.1 a stack needs bound components, which a world binds after its recipes first load.
        boolean earlyStacks = MixinLazyStaticInitializer.hostBindsComponentsLate(RetromodVersion.TARGET_MC_VERSION, t.getFuzzyResolver());
        if (earlyStacks) RebuiltApiClasses.registerTree(t, LegacyEarlyItemStackBridge.HELPER);
        t.registerSyntheticClass(CALLS, generateCalls(host, earlyStacks));
        t.registerSyntheticClass(STREAM, generateStreamCodec());
        if (host.inputs()) t.registerSyntheticClass(INPUT, generateInput());

        // Lookups return holders since 1.20.2, and take inputs since 1.21. Some keep their old
        // descriptor, so only mods built before 1.20.2 are rewritten.
        String manager = "L" + MANAGER + ";";
        String type = "L" + RECIPE_TYPE + ";";
        String id = "L" + host.id() + ";";
        String lookup = "(" + type + "L" + CONTAINER + ";L" + LEVEL + ";)";
        lookup(t, MANAGER, "getRecipeFor", lookup + "Ljava/util/Optional;", manager);
        lookup(t, MANAGER, "getRecipesFor", lookup + "Ljava/util/List;", manager);
        lookup(t, MANAGER, "getRecipes", "()Ljava/util/Collection;", manager);
        if (host.recipeBook()) {
            registerRecipeBookLookups(t, host);
        } else {
            lookup(t, MANAGER, "getAllRecipesFor", "(" + type + ")Ljava/util/List;", manager);
            lookup(t, MANAGER, "byKey", "(" + id + ")Ljava/util/Optional;", manager);
            lookup(t, MANAGER, "replaceRecipes", "(Ljava/lang/Iterable;)V", manager);
        }

        // A recipe's id moved to its holder in 1.20.2.
        for (String owner : List.of(RECIPE, CRAFTING_RECIPE, SHAPED, CRAFTING + "ShapelessRecipe",
                CRAFTING + "AbstractCookingRecipe", CRAFTING + "SingleItemRecipe", CRAFTING + "CustomRecipe")) {
            t.registerMethodRedirect(owner, "getId", "()" + id, CALLS, "getId", "(L" + RECIPE + ";)" + id, true);
        }
        registerSerializerCalls(t, host);
        // Forge's IShapedRecipe gave shaped recipes getRecipeWidth and getRecipeHeight; NeoForge has only the vanilla names.
        if (lacksForgeShapedSize()) {
            t.registerMethodRedirect(SHAPED, "getRecipeWidth", "()I", SHAPED, "getWidth", "()I");
            t.registerMethodRedirect(SHAPED, "getRecipeHeight", "()I", SHAPED, "getHeight", "()I");
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

    private static void lookup(RetromodTransformer t, String owner, String name, String desc, String receiver) {
        String helperDesc = "(" + receiver + desc.substring(1);
        t.registerMethodRedirect(owner, name, desc, CALLS, name, helperDesc, true);
        t.limitRedirectToModsBefore(owner, name, desc, "1.20.2");
    }

    /** The lookups 1.21.2 rebuilt around keys and a server-side manager. */
    private static void registerRecipeBookLookups(RetromodTransformer t, HostShape host) {
        String manager = "L" + MANAGER + ";";
        String type = "L" + RECIPE_TYPE + ";";
        String id = "L" + host.id() + ";";
        String container = "L" + CONTAINER + ";L" + LEVEL + ";";
        SyntheticEmbedder.registerClassResource(t, INTROSPECTION, com.retromod.shim.common.embedded.LegacyRecipeIntrospection.class);
        // The 1.21.1 bridge already answers these with holders, so an older mod's call takes one more step to the recipes.
        step(t, "allRecipesFor", "(" + manager + type + ")Ljava/util/List;", "getAllRecipesFor");
        step(t, "byKey", "(" + manager + id + ")Ljava/util/Optional;", "byKey");
        // Sophisticated Core passes the id of the recipe it found last, which only sped the search up.
        lookup(t, MANAGER, "getRecipeFor", "(" + type + container + id + ")Ljava/util/Optional;", manager);
        // A cached check reads a server level's recipes, which a client no longer has.
        String check = "(" + container + ")Ljava/util/Optional;";
        t.registerMethodRedirect(CACHED_CHECK, "getRecipeFor", check, CALLS, "cachedRecipeFor", "(L" + CACHED_CHECK + ";" + check.substring(1), true);
        t.limitRedirectToModsBefore(CACHED_CHECK, "getRecipeFor", check, "1.20.2");
        // The level's own manager went in 1.21.2; a server level still answers one through recipeAccess().
        for (String level : List.of(LEVEL, SERVER_LEVEL)) {
            t.registerMethodRedirect(level, "getRecipeManager", "()" + manager, CALLS, "recipeManager", "(L" + LEVEL + ";)" + manager, true);
        }
        // getGroup() took the name group() in 1.21.2.
        for (String owner : List.of(RECIPE, CRAFTING_RECIPE, SHAPED, CRAFTING + "ShapelessRecipe", CRAFTING + "AbstractCookingRecipe",
                CRAFTING + "SingleItemRecipe", CRAFTING + "CustomRecipe")) {
            t.registerMethodRedirect(owner, "getGroup", "()Ljava/lang/String;", owner, "group", "()Ljava/lang/String;");
        }
        // Ingredient.EMPTY went too; an ingredient of no items stands in.
        ClassNode ingredient = ClassResourceInspector.read(INGREDIENT);
        if (ingredient == null || ingredient.fields.stream().noneMatch(f -> f.name.equals("EMPTY"))) {
            t.registerFieldRedirect(INGREDIENT, "EMPTY", "L" + INGREDIENT + ";", CALLS, "emptyIngredient", "()L" + INGREDIENT + ";");
        }
        // isEmpty now counts a tag's items, and recipes load before their tags are bound; 1.20.1 only asked for a value.
        t.registerMethodRedirect(INGREDIENT, "isEmpty", "()Z", CALLS, "ingredientIsEmpty", "(L" + INGREDIENT + ";)Z", true);
        t.limitRedirectToModsBefore(INGREDIENT, "isEmpty", "()Z", "1.21.2");
    }

    /** An old mod's call to the 1.21.1 bridge's helper continues to the helper that answers recipes. */
    private static void step(RetromodTransformer t, String queriesName, String desc, String callsName) {
        t.registerMethodRedirect(QUERIES, queriesName, desc, CALLS, callsName, desc);
        t.limitRedirectToModsBefore(QUERIES, queriesName, desc, "1.20.2");
    }

    /** Minecraft's own serializers lost their 1.20.1 readers in 1.20.5, and Forge's CraftingHelper its stack reader in 1.21.11. */
    private static void registerSerializerCalls(RetromodTransformer t, HostShape host) {
        String id = "L" + host.id() + ";";
        String recipe = "L" + RECIPE + ";";
        // The receiver is any serializer, since a mod's own no longer claims the interface on 26.1.
        t.registerMethodRedirect(SERIALIZER, "fromJson", "(" + id + JSON_OBJECT + ")" + recipe, CALLS, "serializerFromJson",
                "(" + OBJECT + id + JSON_OBJECT + ")" + recipe, true);
        t.registerMethodRedirect(SERIALIZER, "fromNetwork", "(" + id + "L" + BUF + ";)" + recipe, CALLS, "serializerFromNetwork",
                "(" + OBJECT + id + "L" + BUF + ";)" + recipe, true);
        t.registerMethodRedirect(SERIALIZER, "toNetwork", "(L" + BUF + ";" + recipe + ")V", CALLS, "serializerToNetwork",
                "(" + OBJECT + "L" + BUF + ";" + recipe + ")V", true);
        if (ClassResourceInspector.exists(CRAFTING_HELPER)) return;
        String stack = "L" + STACK + ";";
        t.registerMethodRedirect(CRAFTING_HELPER, "getItemStack", "(" + JSON_OBJECT + "Z)" + stack, CALLS, "craftingItemStack",
                "(" + JSON_OBJECT + "Z)" + stack);
        t.registerMethodRedirect(CRAFTING_HELPER, "getItemStack", "(" + JSON_OBJECT + "ZZ)" + stack, CALLS, "craftingItemStack",
                "(" + JSON_OBJECT + "ZZ)" + stack);
    }

    /** A removed member to its adapter; an instance member's receiver becomes the first argument. */
    private static void removed(RetromodTransformer t, String owner, String name, String desc, String helper, boolean instance) {
        if (!removedOnHost(owner, name, desc)) return;
        String helperDesc = instance ? "(L" + owner + ";" + desc.substring(1) : desc;
        t.registerMethodRedirect(owner, name, desc, CALLS, helper, helperDesc, instance);
    }

    // Offline, where the host cannot be read, the version decides.
    static HostShape hostShape() {
        ClassNode serializer = ClassResourceInspector.read(SERIALIZER);
        ClassNode recipe = ClassResourceInspector.read(RECIPE);
        if (serializer == null || recipe == null) return offlineShape(RetromodVersion.TARGET_MC_VERSION);
        // An interface through 1.21.11, a record of the two codecs from 26.1.
        boolean codecs = declares(serializer, "codec", "()L" + MAP_CODEC + ";")
                && declares(serializer, "streamCodec", "()L" + STREAM_CODEC + ";");
        boolean results = declares(recipe, "getResultItem", "(L" + PROVIDER + ";)L" + STACK + ";");
        boolean recipeBook = declares(recipe, "placementInfo", "()L" + PLACEMENT + ";");
        if (!codecs || !(results || recipeBook)) return null;
        boolean provider = recipe.methods.stream().anyMatch(m -> m.name.equals("assemble") && m.desc.contains(PROVIDER));
        String id = ClassResourceInspector.exists(IDENTIFIER) ? IDENTIFIER : ID;
        return new HostShape(ClassResourceInspector.exists(RECIPE_INPUT), recipeBook, provider, id);
    }

    static HostShape offlineShape(String host) {
        if (RetromodVersion.compareMcVersions(host, "1.20.5") < 0) return null;
        if (RetromodVersion.compareMcVersions(host, "1.21.2") < 0) {
            return new HostShape(RetromodVersion.compareMcVersions(host, "1.21") >= 0);
        }
        return new HostShape(true, true, RetromodVersion.compareMcVersions(host, "26.1") < 0,
                RetromodVersion.compareMcVersions(host, "1.21.11") >= 0 ? IDENTIFIER : ID);
    }

    private static boolean removedOnHost(String owner, String name, String desc) {
        ClassNode node = ClassResourceInspector.read(owner);
        return node == null || !declares(node, name, desc);
    }

    /** Whether the host's shaped recipe lacks Forge's size getters: read from the host, else true on NeoForge. */
    static boolean lacksForgeShapedSize() {
        ClassNode shaped = ClassResourceInspector.read(SHAPED);
        if (shaped != null) return !declares(shaped, "getRecipeWidth", "()I");
        return McReflect.isNeoForge();
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
        if ((node.access & ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/")
                || SyntheticEmbedder.isRetromodClass(node.name)) {
            return classBytes;
        }
        boolean changed = false;
        // 26.1 drops the serializer interface before this repair runs, so there the readers alone say what the class is.
        if (node.interfaces.contains(SERIALIZER) ? isOldSerializer(node, false) : host.recipeBook() && isOldSerializer(node, true)) {
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

    /** Whether the class has the 1.20.1 readers: either one, or with {@code both} the JSON and the network one. */
    private static boolean isOldSerializer(ClassNode node, boolean both) {
        boolean json = false;
        boolean network = false;
        for (MethodNode m : node.methods) {
            if ((m.access & ACC_STATIC) != 0) continue;
            for (String id : List.of(ID, IDENTIFIER)) {
                json |= m.name.equals("fromJson") && m.desc.startsWith("(L" + id + ";" + JSON_OBJECT + ")");
                network |= m.name.equals("fromNetwork") && m.desc.startsWith("(L" + id + ";L" + BUF + ";)");
            }
        }
        return both ? json && network : json || network;
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
        }
        if (host.recipeBook()) {
            // 26.1 dropped the registries from assemble; the old one gets the running game's.
            String assemble = host.assembleProvider() ? "(" + input + "L" + PROVIDER + ";)L" + STACK + ";" : "(" + input + ")L" + STACK + ";";
            changed |= addForwarder(node, "assemble", OLD_ASSEMBLE, assemble, true, true);
            changed |= addRecipeBookAnswers(node);
            return changed;
        }
        if (host.inputs()) {
            changed |= addForwarder(node, "getRemainingItems", OLD_REMAINING, "(" + input + ")L" + LIST + ";", true, false);
        }
        changed |= addForwarder(node, "assemble", OLD_ASSEMBLE, "(" + input + "L" + PROVIDER + ";)L" + STACK + ";",
                host.inputs(), true);
        changed |= addForwarder(node, "getResultItem", OLD_RESULT, "(L" + PROVIDER + ";)L" + STACK + ";", false, true);
        return changed;
    }

    /**
     * The questions a 1.21.2 recipe answers for the recipe book, which an old recipe cannot: it places nothing,
     * files under the miscellaneous crafting tab, shows its unlock notification as 1.20.1 did by default, and
     * names the group its old {@code getGroup} gave.
     */
    private static boolean addRecipeBookAnswers(ClassNode node) {
        if (!node.interfaces.contains(RECIPE) && !node.interfaces.contains(CRAFTING_RECIPE)) return false;
        boolean changed = false;
        if (findInstance(node, "placementInfo", "()L" + PLACEMENT + ";") == null) {
            node.methods.add(constant(GETSTATIC, PLACEMENT, "NOT_PLACEABLE", "placementInfo", PLACEMENT));
            changed = true;
        }
        // CraftingRecipe answers its own category.
        if (node.interfaces.contains(RECIPE) && findInstance(node, "recipeBookCategory", "()L" + BOOK_CATEGORY + ";") == null) {
            node.methods.add(constant(GETSTATIC, BOOK_CATEGORIES, "CRAFTING_MISC", "recipeBookCategory", BOOK_CATEGORY));
            changed = true;
        }
        if (findInstance(node, "showNotification", "()Z") == null) {
            MethodNode show = new MethodNode(ACC_PUBLIC, "showNotification", "()Z", null, null);
            show.instructions.add(new InsnNode(ICONST_1));
            show.instructions.add(new InsnNode(IRETURN));
            node.methods.add(show);
            changed = true;
        }
        if (findInstance(node, "group", "()Ljava/lang/String;") == null) {
            MethodNode group = new MethodNode(ACC_PUBLIC, "group", "()Ljava/lang/String;", null, null);
            if (findInstance(node, "getGroup", "()Ljava/lang/String;") != null) {
                group.instructions.add(new VarInsnNode(ALOAD, 0));
                group.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, node.name, "getGroup", "()Ljava/lang/String;", false));
            } else {
                group.instructions.add(new org.objectweb.asm.tree.LdcInsnNode(""));
            }
            group.instructions.add(new InsnNode(ARETURN));
            node.methods.add(group);
            changed = true;
        }
        return changed;
    }

    /** {@code public T name() { return Owner.FIELD; }}. */
    private static MethodNode constant(int opcode, String owner, String field, String name, String type) {
        MethodNode method = new MethodNode(ACC_PUBLIC, name, "()L" + type + ";", null, null);
        method.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(opcode, owner, field, "L" + type + ";"));
        method.instructions.add(new InsnNode(ARETURN));
        return method;
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
        // A host form without registries still owes the old method its registry access: the running game's.
        if (convertProvider && Type.getArgumentTypes(oldDesc).length > arguments.length) {
            method.instructions.add(new InsnNode(ACONST_NULL));
            method.instructions.add(new MethodInsnNode(INVOKESTATIC, CALLS, "registryAccess",
                    "(L" + PROVIDER + ";)L" + ACCESS + ";", false));
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
        return generateCalls(host, false);
    }

    /** As above; with {@code earlyStacks} the stacks a serializer reads are built to wait for bound components. */
    static byte[] generateCalls(HostShape host, boolean earlyStacks) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CALLS, null, "java/lang/Object", null);
        String manager = "L" + MANAGER + ";";
        String type = "L" + RECIPE_TYPE + ";";
        String container = "L" + CONTAINER + ";";
        String level = "L" + LEVEL + ";";
        String hostLookup = "(" + type + "L" + host.input() + ";" + level + ")";

        String id = "L" + host.id() + ";";
        lookupCall(cw, host, "getRecipeFor", "(" + manager + type + container + level + ")Ljava/util/Optional;",
                hostLookup + "Ljava/util/Optional;", "value");
        lookupCall(cw, host, "getRecipes", "(" + manager + ")Ljava/util/Collection;", "()Ljava/util/Collection;", "values");
        if (host.recipeBook()) {
            recipeBookCalls(cw, manager, type, container, level, id);
        } else {
            lookupCall(cw, host, "getRecipesFor", "(" + manager + type + container + level + ")Ljava/util/List;",
                    hostLookup + "Ljava/util/List;", "values");
            lookupCall(cw, host, "getAllRecipesFor", "(" + manager + type + ")Ljava/util/List;",
                    "(" + type + ")Ljava/util/List;", "values");
            lookupCall(cw, host, "byKey", "(" + manager + id + ")Ljava/util/Optional;",
                    "(" + id + ")Ljava/util/Optional;", "value");
            replaceRecipes(cw, manager);
        }
        String recipe = "L" + RECIPE + ";";
        String buf = "L" + BUF + ";";
        forward(cw, "serializerFromJson", "(" + OBJECT + id + JSON_OBJECT + ")" + recipe, "serializerFromJson");
        forward(cw, "serializerFromNetwork", "(" + OBJECT + id + buf + ")" + recipe, "serializerFromNetwork");
        forward(cw, "serializerToNetwork", "(" + OBJECT + buf + recipe + ")V", "serializerToNetwork");
        stackFromJson(cw, "craftingItemStack", "(" + JSON_OBJECT + "Z)L" + STACK + ";", earlyStacks);
        stackFromJson(cw, "craftingItemStack", "(" + JSON_OBJECT + "ZZ)L" + STACK + ";", earlyStacks);

        forward(cw, "getId", "(L" + RECIPE + ";)" + id, "id");
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
        stackFromJson(cw, "itemStackFromJson", "(" + JSON_OBJECT + ")L" + STACK + ";", earlyStacks);
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

    /**
     * The 1.21.2 lookups: the 1.21.1 bridge's helper finds holders, which are unwrapped; a container travels as an
     * input; the level answers its manager; and a check runs only on a server level.
     */
    private static void recipeBookCalls(ClassWriter cw, String manager, String type, String container, String level, String id) {
        unwrapped(cw, "getAllRecipesFor", "(" + manager + type + ")Ljava/util/List;", "allRecipesFor", "values", -1);
        unwrapped(cw, "byKey", "(" + manager + id + ")Ljava/util/Optional;", "byKey", "value", -1);
        unwrapped(cw, "getRecipesFor", "(" + manager + type + container + level + ")Ljava/util/List;", "recipesFor", "values", 2);
        supportCall(cw, SUPPORT, "getRecipeFor", "(" + manager + type + container + level + id + ")Ljava/util/Optional;", "recipeWithId", 2, 4);
        supportCall(cw, SUPPORT, "cachedRecipeFor", "(L" + CACHED_CHECK + ";" + container + level + ")Ljava/util/Optional;", "cachedRecipeFor", 1, -1);
        supportCall(cw, SUPPORT, "recipeManager", "(" + level + ")" + manager, "recipeManager", -1, -1);
        supportCall(cw, INTROSPECTION, "emptyIngredient", "()L" + INGREDIENT + ";", "emptyIngredient", -1, -1);
        MethodVisitor empty = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "ingredientIsEmpty", "(L" + INGREDIENT + ";)Z", null, null);
        empty.visitCode();
        empty.visitVarInsn(ALOAD, 0);
        empty.visitMethodInsn(INVOKESTATIC, INTROSPECTION, "ingredientIsEmpty", "(" + OBJECT + ")Z", false);
        empty.visitInsn(IRETURN);
        empty.visitMaxs(0, 0);
        empty.visitEnd();
    }

    /** {@code static R name(args)}: the 1.21.1 bridge's helper, its holders then unwrapped by {@code unwrap}. */
    private static void unwrapped(ClassWriter cw, String name, String desc, String helper, String unwrap, int containerArgument) {
        Type[] arguments = Type.getArgumentTypes(desc);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        pushArguments(mv, arguments, containerArgument, -1);
        mv.visitMethodInsn(INVOKESTATIC, INTROSPECTION, helper, "(" + OBJECT.repeat(arguments.length) + ")" + OBJECT, false);
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, unwrap, "(" + OBJECT + ")" + OBJECT, false);
        mv.visitTypeInsn(CHECKCAST, Type.getReturnType(desc).getInternalName());
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** {@code static R name(args)}: a helper of {@code owner}, a container passed as an input; {@code dropped} is left out. */
    private static void supportCall(ClassWriter cw, String owner, String name, String desc, String helper, int containerArgument,
            int dropped) {
        Type[] arguments = Type.getArgumentTypes(desc);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        int passed = pushArguments(mv, arguments, containerArgument, dropped);
        mv.visitMethodInsn(INVOKESTATIC, owner, helper, "(" + OBJECT.repeat(passed) + ")" + OBJECT, false);
        mv.visitTypeInsn(CHECKCAST, Type.getReturnType(desc).getInternalName());
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** Loads each argument but {@code dropped}, the one at {@code containerArgument} wrapped as an input; returns how many. */
    private static int pushArguments(MethodVisitor mv, Type[] arguments, int containerArgument, int dropped) {
        int passed = 0;
        for (int i = 0; i < arguments.length; i++) {
            if (i == dropped) continue;
            if (i == containerArgument) {
                mv.visitTypeInsn(NEW, INPUT);
                mv.visitInsn(DUP);
                mv.visitVarInsn(ALOAD, i);
                mv.visitMethodInsn(INVOKESPECIAL, INPUT, "<init>", "(L" + CONTAINER + ";)V", false);
            } else {
                mv.visitVarInsn(ALOAD, i);
            }
            passed++;
        }
        return passed;
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

    /** {@code static ItemStack name(json, ...)}: the stack {@code item} and {@code count} name, early when the host binds late. */
    private static void stackFromJson(ClassWriter cw, String name, String desc, boolean earlyStacks) {
        if (!earlyStacks) {
            forward(cw, name, desc, "itemStackFromJson");
            return;
        }
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, "itemFromJson", "(" + OBJECT + ")" + OBJECT, false);
        mv.visitTypeInsn(CHECKCAST, ITEM_LIKE);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESTATIC, SUPPORT, "countFromJson", "(" + OBJECT + ")I", false);
        mv.visitMethodInsn(INVOKESTATIC, LegacyEarlyItemStackBridge.HELPER, "of", "(L" + ITEM_LIKE + ";I)L" + STACK + ";", false);
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
