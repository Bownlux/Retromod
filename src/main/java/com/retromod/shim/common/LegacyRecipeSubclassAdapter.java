/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.util.SafeClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rebuilds 1.21.x recipe subclasses on the recipe classes 26.1 restructured.
 *
 * <p>A common 1.21.x pattern wraps a vanilla recipe: the mod's serializer decodes a plain
 * {@code ShapedRecipe} and the mod's subclass copies it with
 * {@code super(r.group(), r.category(), r.pattern, r.result)}, then overrides
 * {@code assemble(input, registries)} to carry data over to the result. 26.1 changed every part of
 * that (verified against the 26.1.2 and 26.2 jars):
 * <ul>
 *   <li>{@code ShapedRecipe}, {@code ShapelessRecipe} and {@code SmithingTransformRecipe} take a
 *       {@code Recipe.CommonInfo}, a {@code CraftingBookInfo} for crafting, optional smithing
 *       ingredients, and an {@code ItemStackTemplate} result.</li>
 *   <li>{@code CustomRecipe} lost its {@code CraftingBookCategory} constructor.</li>
 *   <li>The {@code result} field became a private template, and the smithing ingredient fields
 *       became private optionals with public accessors.</li>
 *   <li>{@code assemble} dropped its {@code HolderLookup.Provider} parameter, so an old override
 *       is never called and a {@code CustomRecipe} subclass has no implementation at all.</li>
 * </ul>
 * Each recipe then failed to decode with {@code NoSuchFieldError} or {@code NoSuchMethodError}
 * (#249: all 28 Sophisticated Backpacks recipes).
 *
 * <p>The adapter converts the old constructor arguments at the call, reads the moved fields
 * through their replacements, drops the provider from calls to vanilla {@code assemble}, and gives
 * a class that overrides the old {@code assemble} the new one, forwarding the running game's
 * registries. A recipe built from the four-argument constructors shows the unlock notification,
 * which was that constructor's default; the five-argument shaped constructor keeps its own
 * setting.
 */
public final class LegacyRecipeSubclassAdapter {

    private static final String CRAFTING = "net/minecraft/world/item/crafting/";
    static final String SHAPED = CRAFTING + "ShapedRecipe";
    static final String SHAPELESS = CRAFTING + "ShapelessRecipe";
    static final String SMITHING = CRAFTING + "SmithingTransformRecipe";
    static final String CUSTOM = CRAFTING + "CustomRecipe";
    private static final String COMMON_INFO = CRAFTING + "Recipe$CommonInfo";
    private static final String BOOK_INFO = CRAFTING + "CraftingRecipe$CraftingBookInfo";
    private static final String CATEGORY = CRAFTING + "CraftingBookCategory";
    private static final String PATTERN = CRAFTING + "ShapedRecipePattern";
    private static final String INGREDIENT = CRAFTING + "Ingredient";
    private static final String RECIPE_INPUT = CRAFTING + "RecipeInput";
    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String TEMPLATE = "net/minecraft/world/item/ItemStackTemplate";
    private static final String PROVIDER = "net/minecraft/core/HolderLookup$Provider";
    private static final String OPTIONAL = "java/util/Optional";
    public static final String INTERNALS = "com/retromod/shim/common/embedded/LegacyRecipeInternals";

    private static final String L_STACK = "L" + ITEM_STACK + ";";
    private static final String L_INGREDIENT = "L" + INGREDIENT + ";";
    private static final String OLD_SHAPED_CTOR = "(Ljava/lang/String;L" + CATEGORY + ";L" + PATTERN
            + ";" + L_STACK + ")V";
    /** The 1.21.x form that also set {@code showNotification}, which 26.1 keeps in CommonInfo. */
    private static final String OLD_SHAPED_NOTIFY_CTOR = "(Ljava/lang/String;L" + CATEGORY + ";L"
            + PATTERN + ";" + L_STACK + "Z)V";
    private static final String NEW_SHAPED_CTOR = "(L" + COMMON_INFO + ";L" + BOOK_INFO + ";L"
            + PATTERN + ";L" + TEMPLATE + ";)V";
    private static final String OLD_SHAPELESS_CTOR = "(Ljava/lang/String;L" + CATEGORY + ";"
            + L_STACK + "Lnet/minecraft/core/NonNullList;)V";
    private static final String NEW_SHAPELESS_CTOR = "(L" + COMMON_INFO + ";L" + BOOK_INFO + ";L"
            + TEMPLATE + ";Ljava/util/List;)V";
    private static final String OLD_SMITHING_CTOR = "(" + L_INGREDIENT + L_INGREDIENT + L_INGREDIENT
            + L_STACK + ")V";
    private static final String NEW_SMITHING_CTOR = "(L" + COMMON_INFO + ";L" + OPTIONAL + ";"
            + L_INGREDIENT + "L" + OPTIONAL + ";L" + TEMPLATE + ";)V";
    private static final String OLD_CUSTOM_CTOR = "(L" + CATEGORY + ";)V";

    private LegacyRecipeSubclassAdapter() {
    }

    /**
     * @return the repaired class, or the same array when nothing in it uses the 1.21.x recipe API
     */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !mentionsRecipeApi(classBytes)) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (RuntimeException unreadable) {
            return classBytes;
        }
        if ((node.access & Opcodes.ACC_INTERFACE) != 0 || node.name.startsWith("net/minecraft/")
                || node.name.startsWith("com/retromod/")) {
            return classBytes;
        }
        boolean changed = false;
        for (MethodNode method : node.methods) {
            changed |= rewriteCalls(node.name, method);
        }
        changed |= addCurrentAssembleOverrides(node);
        if (!changed) {
            return classBytes;
        }
        try {
            ClassWriter writer = new SafeClassWriter(new ClassReader(classBytes),
                    ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (RuntimeException unwritable) {
            return classBytes;
        }
    }

    private static boolean mentionsRecipeApi(byte[] classBytes) {
        String text = new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        return text.contains(PROVIDER) || text.contains(SHAPED) || text.contains(SHAPELESS)
                || text.contains(SMITHING) || text.contains(CUSTOM);
    }

    /**
     * Result reads that feed a converted constructor directly, and those constructors. The copy
     * then passes the template through: building an {@code ItemStack} while recipes decode fails,
     * because item components are bound later.
     */
    private record TemplatePassThrough(Set<FieldInsnNode> reads, Set<MethodInsnNode> constructors) {
        static final TemplatePassThrough NONE = new TemplatePassThrough(Set.of(), Set.of());
    }

    private static boolean rewriteCalls(String owner, MethodNode method) {
        TemplatePassThrough direct = findTemplatePassThrough(owner, method);
        boolean changed = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof MethodInsnNode call) {
                changed |= rewriteMethodCall(method, call, direct);
            } else if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
                changed |= rewriteFieldRead(method, field, direct);
            }
            insn = next;
        }
        return changed;
    }

    private static TemplatePassThrough findTemplatePassThrough(String owner, MethodNode method) {
        List<MethodInsnNode> constructors = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && resultArgument(call) >= 0) {
                constructors.add(call);
            }
        }
        if (constructors.isEmpty()) return TemplatePassThrough.NONE;
        Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
        } catch (AnalyzerException | RuntimeException unanalyzable) {
            return TemplatePassThrough.NONE;
        }
        Set<FieldInsnNode> reads = new HashSet<>();
        Set<MethodInsnNode> fed = new HashSet<>();
        for (MethodInsnNode call : constructors) {
            Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
            if (frame == null) continue;
            int argCount = Type.getArgumentTypes(call.desc).length;
            SourceValue source = frame.getStack(
                    frame.getStackSize() - argCount + resultArgument(call));
            if (source.insns.size() == 1
                    && source.insns.iterator().next() instanceof FieldInsnNode read
                    && isLegacyResultRead(read)) {
                reads.add(read);
                fed.add(call);
            }
        }
        return new TemplatePassThrough(reads, fed);
    }

    /** Index of the old {@code ItemStack} result argument, or -1 for any other call. */
    private static int resultArgument(MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKESPECIAL || !"<init>".equals(call.name)) return -1;
        if (SHAPED.equals(call.owner) && OLD_SHAPED_CTOR.equals(call.desc)) return 3;
        if (SHAPED.equals(call.owner) && OLD_SHAPED_NOTIFY_CTOR.equals(call.desc)) return 3;
        if (SHAPELESS.equals(call.owner) && OLD_SHAPELESS_CTOR.equals(call.desc)) return 2;
        if (SMITHING.equals(call.owner) && OLD_SMITHING_CTOR.equals(call.desc)) return 3;
        return -1;
    }

    private static boolean isLegacyResultRead(FieldInsnNode field) {
        return field.getOpcode() == Opcodes.GETFIELD && "result".equals(field.name)
                && L_STACK.equals(field.desc)
                && (SHAPED.equals(field.owner) || SHAPELESS.equals(field.owner)
                        || SMITHING.equals(field.owner));
    }

    private static boolean rewriteMethodCall(MethodNode method, MethodInsnNode call,
            TemplatePassThrough direct) {
        if ("<init>".equals(call.name) && call.getOpcode() == Opcodes.INVOKESPECIAL) {
            return rewriteConstructor(method, call, direct.constructors().contains(call));
        }
        if ("assemble".equals(call.name) && call.owner.startsWith(CRAFTING)
                && isLegacyAssemble(call.desc)) {
            // The provider is the last argument, so it is on top of the stack.
            method.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
            call.desc = currentAssemble(call.desc);
            return true;
        }
        return false;
    }

    private static boolean rewriteConstructor(MethodNode method, MethodInsnNode call,
            boolean resultIsTemplate) {
        InsnList convert = new InsnList();
        if (SHAPED.equals(call.owner) && OLD_SHAPED_CTOR.equals(call.desc)) {
            int base = reserveLocals(method, 4);
            storeArguments(convert, base, 4);
            pushCommonInfo(convert);
            pushBookInfo(convert, base, base + 1);
            convert.add(new VarInsnNode(Opcodes.ALOAD, base + 2));
            pushTemplate(convert, base + 3, resultIsTemplate);
            call.desc = NEW_SHAPED_CTOR;
        } else if (SHAPED.equals(call.owner) && OLD_SHAPED_NOTIFY_CTOR.equals(call.desc)) {
            int base = reserveLocals(method, 5);
            convert.add(new VarInsnNode(Opcodes.ISTORE, base + 4));
            storeArguments(convert, base, 4);
            pushCommonInfo(convert, base + 4);
            pushBookInfo(convert, base, base + 1);
            convert.add(new VarInsnNode(Opcodes.ALOAD, base + 2));
            pushTemplate(convert, base + 3, resultIsTemplate);
            call.desc = NEW_SHAPED_CTOR;
        } else if (SHAPELESS.equals(call.owner) && OLD_SHAPELESS_CTOR.equals(call.desc)) {
            int base = reserveLocals(method, 4);
            storeArguments(convert, base, 4);
            pushCommonInfo(convert);
            pushBookInfo(convert, base, base + 1);
            pushTemplate(convert, base + 2, resultIsTemplate);
            convert.add(new VarInsnNode(Opcodes.ALOAD, base + 3));
            call.desc = NEW_SHAPELESS_CTOR;
        } else if (SMITHING.equals(call.owner) && OLD_SMITHING_CTOR.equals(call.desc)) {
            int base = reserveLocals(method, 4);
            storeArguments(convert, base, 4);
            pushCommonInfo(convert);
            pushOptional(convert, base);
            convert.add(new VarInsnNode(Opcodes.ALOAD, base + 1));
            pushOptional(convert, base + 2);
            pushTemplate(convert, base + 3, resultIsTemplate);
            call.desc = NEW_SMITHING_CTOR;
        } else if (CUSTOM.equals(call.owner) && OLD_CUSTOM_CTOR.equals(call.desc)) {
            // 26.1 asks the recipe for its category instead of storing one.
            convert.add(new InsnNode(Opcodes.POP));
            call.desc = "()V";
        } else {
            return false;
        }
        method.instructions.insertBefore(call, convert);
        return true;
    }

    private static boolean rewriteFieldRead(MethodNode method, FieldInsnNode field,
            TemplatePassThrough direct) {
        InsnList read = new InsnList();
        if (direct.reads().contains(field)) {
            read.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTERNALS, "resultTemplate",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            read.add(new TypeInsnNode(Opcodes.CHECKCAST, TEMPLATE));
        } else if (isLegacyResultRead(field)) {
            read.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTERNALS, "result",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            read.add(new TypeInsnNode(Opcodes.CHECKCAST, ITEM_STACK));
        } else if (SMITHING.equals(field.owner) && L_INGREDIENT.equals(field.desc)
                && ("template".equals(field.name) || "addition".equals(field.name))) {
            // 1.21.x used an empty ingredient where 26.1 uses an empty optional; null is the
            // closest the old type can say, since 26.1 has no empty ingredient.
            String accessor = "template".equals(field.name)
                    ? "templateIngredient" : "additionIngredient";
            read.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SMITHING, accessor,
                    "()L" + OPTIONAL + ";", false));
            read.add(new InsnNode(Opcodes.ACONST_NULL));
            read.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OPTIONAL, "orElse",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", false));
            read.add(new TypeInsnNode(Opcodes.CHECKCAST, INGREDIENT));
        } else if (SMITHING.equals(field.owner) && L_INGREDIENT.equals(field.desc)
                && "base".equals(field.name)) {
            read.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SMITHING, "baseIngredient",
                    "()" + L_INGREDIENT, false));
        } else {
            return false;
        }
        method.instructions.insertBefore(field, read);
        method.instructions.remove(field);
        return true;
    }

    /**
     * Give each old {@code assemble(input, provider)} override the 26.1 entry points that call it.
     * The erased {@code assemble(RecipeInput)} is added too, because vanilla calls the interface
     * method and a former {@code CustomRecipe} subclass inherits no implementation of it.
     */
    private static boolean addCurrentAssembleOverrides(ClassNode node) {
        List<MethodNode> added = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!"assemble".equals(method.name) || !isLegacyAssemble(method.desc)
                    || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_BRIDGE
                            | Opcodes.ACC_ABSTRACT)) != 0) {
                continue;
            }
            String input = Type.getArgumentTypes(method.desc)[0].getInternalName();
            String current = currentAssemble(method.desc);
            if (!declares(node, added, "assemble", current)) {
                added.add(forwardToLegacy(node.name, input, method.desc, current));
            }
            String erased = "(L" + RECIPE_INPUT + ";)" + L_STACK;
            if (!RECIPE_INPUT.equals(input) && !declares(node, added, "assemble", erased)) {
                added.add(erasedBridge(node.name, input, current, erased));
            }
        }
        node.methods.addAll(added);
        return !added.isEmpty();
    }

    private static MethodNode forwardToLegacy(String owner, String input, String legacyDesc,
            String currentDesc) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "assemble", currentDesc, null, null);
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTERNALS, "registries",
                "()Ljava/lang/Object;", false));
        m.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, PROVIDER));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "assemble",
                legacyDesc, false));
        m.instructions.add(new InsnNode(Opcodes.ARETURN));
        return m;
    }

    private static MethodNode erasedBridge(String owner, String input, String currentDesc,
            String erasedDesc) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_BRIDGE
                | Opcodes.ACC_SYNTHETIC, "assemble", erasedDesc, null, null);
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        m.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, input));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "assemble",
                currentDesc, false));
        m.instructions.add(new InsnNode(Opcodes.ARETURN));
        return m;
    }

    private static boolean declares(ClassNode node, List<MethodNode> added, String name,
            String desc) {
        for (MethodNode m : node.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) return true;
        }
        for (MethodNode m : added) {
            if (m.name.equals(name) && m.desc.equals(desc)) return true;
        }
        return false;
    }

    /** {@code (L<crafting input>;LHolderLookup$Provider;)LItemStack;}, the pre-26.1 shape. */
    static boolean isLegacyAssemble(String desc) {
        Type[] args = Type.getArgumentTypes(desc);
        return args.length == 2
                && args[0].getSort() == Type.OBJECT
                && args[0].getInternalName().startsWith(CRAFTING)
                && PROVIDER.equals(args[1].getInternalName())
                && L_STACK.equals(Type.getReturnType(desc).getDescriptor());
    }

    private static String currentAssemble(String legacyDesc) {
        return "(" + Type.getArgumentTypes(legacyDesc)[0].getDescriptor() + ")" + L_STACK;
    }

    private static int reserveLocals(MethodNode method, int count) {
        int base = method.maxLocals;
        method.maxLocals += count;
        return base;
    }

    /** Pops {@code count} reference arguments into locals {@code base..base+count-1}. */
    private static void storeArguments(InsnList list, int base, int count) {
        for (int i = count - 1; i >= 0; i--) {
            list.add(new VarInsnNode(Opcodes.ASTORE, base + i));
        }
    }

    /** CommonInfo with the 1.21.x default of showing the unlock notification. */
    private static void pushCommonInfo(InsnList list) {
        list.add(new TypeInsnNode(Opcodes.NEW, COMMON_INFO));
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new InsnNode(Opcodes.ICONST_1));
        list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, COMMON_INFO, "<init>", "(Z)V", false));
    }

    private static void pushCommonInfo(InsnList list, int showNotificationLocal) {
        list.add(new TypeInsnNode(Opcodes.NEW, COMMON_INFO));
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new VarInsnNode(Opcodes.ILOAD, showNotificationLocal));
        list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, COMMON_INFO, "<init>", "(Z)V", false));
    }

    private static void pushBookInfo(InsnList list, int groupLocal, int categoryLocal) {
        list.add(new TypeInsnNode(Opcodes.NEW, BOOK_INFO));
        list.add(new InsnNode(Opcodes.DUP));
        list.add(new VarInsnNode(Opcodes.ALOAD, categoryLocal));
        list.add(new VarInsnNode(Opcodes.ALOAD, groupLocal));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTERNALS, "group",
                "(Ljava/lang/String;)Ljava/lang/String;", false));
        list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, BOOK_INFO, "<init>",
                "(L" + CATEGORY + ";Ljava/lang/String;)V", false));
    }

    private static void pushTemplate(InsnList list, int resultLocal, boolean alreadyTemplate) {
        list.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
        if (!alreadyTemplate) {
            list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TEMPLATE, "fromNonEmptyStack",
                    "(" + L_STACK + ")L" + TEMPLATE + ";", false));
        }
    }

    private static void pushOptional(InsnList list, int local) {
        list.add(new VarInsnNode(Opcodes.ALOAD, local));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OPTIONAL, "ofNullable",
                "(Ljava/lang/Object;)L" + OPTIONAL + ";", false));
    }
}
