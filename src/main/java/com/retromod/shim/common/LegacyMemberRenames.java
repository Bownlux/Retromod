/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.function.Predicate;

import static org.objectweb.asm.Opcodes.*;

/**
 * Plain member renames Mojang made after 1.21.1 that keep their owner, descriptor, and meaning.
 *
 * <p>Retromod's intermediary table is composed from 1.21.4, so a Fabric mod built for 1.21.1 still
 * names these members by their old spelling after the remap, and a NeoForge mod carries the old
 * Mojang name directly. Porting Lib and Sophisticated Core (#249) read {@code
 * LivingEntity.lastHurtByPlayerTime} and {@code LootContext.getParamOrNull} from code and from
 * {@code @Shadow} members, which fail with {@code NoSuchFieldError}, {@code NoSuchMethodError}, or a
 * Mixin "was not located" error on 26.1.
 *
 * <p>Each rename applies only when the host proves it: the old name is gone from the owner and the
 * new one is present. That keeps the table safe on every host between 1.21.2 and 26.x without
 * pinning the exact release that renamed each member. A host whose classes cannot be read gets
 * no rename. {@link com.retromod.mixin.MixinCompatibilityTransformer} applies the same table to
 * {@code @Shadow} members, which a call-site redirect does not reach.
 */
public class LegacyMemberRenames implements MinecraftVersionedApiShim {

    /** One rename: {@code field} is false for a method, whose descriptor includes its arguments. */
    public record Rename(String owner, boolean field, String oldName, String desc, String newName) {}

    private static final String LOOT_CONTEXT = "net/minecraft/world/level/storage/loot/LootContext";
    private static final String CONTEXT_KEY_GETTER = "(Lnet/minecraft/util/context/ContextKey;)Ljava/lang/Object;";
    private static final String CONTEXT_KEY_TEST = "(Lnet/minecraft/util/context/ContextKey;)Z";
    static final String PARAMETERS = "com/retromod/generated/LegacyLootParameters";
    private static final String TAG_KEY = "Lnet/minecraft/tags/TagKey;";
    static final String REQUIRE_PARAMETER = "(L" + LOOT_CONTEXT + ";Lnet/minecraft/util/context/ContextKey;)Ljava/lang/Object;";

    public static final List<Rename> RENAMES = List.of(
            new Rename("net/minecraft/world/entity/LivingEntity", true,
                    "lastHurtByPlayerTime", "I", "lastHurtByPlayerMemoryTime"),
            // After 1.21.1 the loot context's parameter calls were renamed when parameters became context keys.
            new Rename(LOOT_CONTEXT, false, "getParamOrNull", CONTEXT_KEY_GETTER, "getOptionalParameter"),
            new Rename(LOOT_CONTEXT, false, "getParam", CONTEXT_KEY_GETTER, "getParameter"),
            new Rename(LOOT_CONTEXT, false, "hasParam", CONTEXT_KEY_TEST, "hasParameter"),
            // 26.3 folded both parameter getters into getOptional, so each older spelling maps there.
            // A call to a getter that threw for a missing parameter goes through requireParameter instead.
            new Rename(LOOT_CONTEXT, false, "getParamOrNull", CONTEXT_KEY_GETTER, "getOptional"),
            new Rename(LOOT_CONTEXT, false, "getOptionalParameter", CONTEXT_KEY_GETTER, "getOptional"),
            new Rename(LOOT_CONTEXT, false, "getParameter", CONTEXT_KEY_GETTER, "getOptional"),
            new Rename(LOOT_CONTEXT, false, "getParam", CONTEXT_KEY_GETTER, "getOptional"),
            // By 1.21.11 the item sub-predicate registry was the data component predicate registry.
            new Rename("net/minecraft/core/registries/BuiltInRegistries", true, "ITEM_SUB_PREDICATE_TYPE",
                    "Lnet/minecraft/core/Registry;", "DATA_COMPONENT_PREDICATE_TYPE"),
            new Rename("net/minecraft/core/registries/Registries", true, "ITEM_SUB_PREDICATE_TYPE",
                    "Lnet/minecraft/resources/ResourceKey;", "DATA_COMPONENT_PREDICATE_TYPE"),
            // 1.21.11's debug screen rework renamed the flag that stops a released F3 from toggling
            // the overlay after an F3 combination; keyPress sets, reads, and clears it as before.
            new Rename("net/minecraft/client/KeyboardHandler", true,
                    "handledDebugKey", "Z", "usedDebugKeyAsModifier"),
            // Codecs that later hosts fold into or rename to a Codec of the same type; the host proof
            // decides when each applies. The fuzzy resolver no longer guesses them, because their classes
            // hold codecs of other types too.
            new Rename("net/minecraft/world/item/component/CustomData", true,
                    "CODEC_WITH_ID", "Lcom/mojang/serialization/Codec;", "CODEC"),
            new Rename("net/minecraft/world/item/crafting/Ingredient", true,
                    "CODEC_NONEMPTY", "Lcom/mojang/serialization/Codec;", "CODEC"),
            new Rename("net/minecraft/util/ExtraCodecs", true,
                    "GAME_PROFILE", "Lcom/mojang/serialization/Codec;", "AUTHLIB_GAME_PROFILE"),
            new Rename("net/minecraft/world/item/enchantment/ItemEnchantments", true,
                    "FULL_CODEC", "Lcom/mojang/serialization/Codec;", "CODEC"),
            // Tag keys whose tag 26.1, 26.2 and 26.3 renamed, as VanillaTagRenames renames the tag files.
            // The fuzzy resolver never guesses a renamed tag key, so each one is listed here.
            blockTag("BAMBOO_PLANTABLE_ON", "SUPPORTS_BAMBOO"),
            blockTag("BIG_DRIPLEAF_PLACEABLE", "SUPPORTS_BIG_DRIPLEAF"),
            blockTag("DRY_VEGETATION_MAY_PLACE_ON", "SUPPORTS_DRY_VEGETATION"),
            blockTag("MUSHROOM_GROW_BLOCK", "OVERRIDES_MUSHROOM_LIGHT_REQUIREMENT"),
            blockTag("SMALL_DRIPLEAF_PLACEABLE", "SUPPORTS_SMALL_DRIPLEAF"),
            blockTag("SNOW_LAYER_CAN_SURVIVE_ON", "SUPPORT_OVERRIDE_SNOW_LAYER"),
            blockTag("SNOW_LAYER_CANNOT_SURVIVE_ON", "CANNOT_SUPPORT_SNOW_LAYER"),
            new Rename("net/minecraft/tags/ItemTags", true, "DYEABLE", TAG_KEY, "CAULDRON_CAN_REMOVE_DYE"),
            blockTag("CONCRETE_POWDER", "CONCRETE_POWDERS"),
            blockTag("CONVERTABLE_TO_MUD", "CONVERTIBLE_TO_MUD"));

    private static Rename blockTag(String oldName, String newName) {
        return new Rename("net/minecraft/tags/BlockTags", true, oldName, TAG_KEY, newName);
    }

    @Override
    public String getShimName() {
        return "Legacy Member Renames";
    }

    // Every rename waits for the host to prove it, so the table can apply from the first host
    // that renamed any of its members.
    @Override
    public String getSourceVersion() {
        return "1.21.1";
    }

    @Override
    public String getTargetVersion() {
        return "1.21.2";
    }

    @Override
    public String getModLoaderType() {
        return "common";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // Offline, the indexed host jar answers what the game's own classes answer at runtime.
        register(transformer, rename -> hostRenamed(rename, transformer.getFuzzyResolver()));
    }

    /** Registers every rename the given host check confirms. */
    static void register(RetromodTransformer transformer, Predicate<Rename> hostRenamed) {
        for (Rename rename : RENAMES) {
            if (!hostRenamed.test(rename)) continue;
            if (throwsWhenMissing(rename)) {
                // getOptional returns null for a missing parameter, where the old getter threw
                // NoSuchElementException, and code written for the old getter never checks for null.
                if (!transformer.getSyntheticClasses().containsKey(PARAMETERS)) {
                    transformer.registerSyntheticClass(PARAMETERS, generateParameters());
                }
                transformer.registerMethodRedirect(rename.owner(), rename.oldName(), rename.desc(),
                        PARAMETERS, "requireParameter", REQUIRE_PARAMETER);
            } else if (rename.field()) {
                transformer.registerFieldRedirect(rename.owner(), rename.oldName(), rename.desc(),
                        rename.owner(), rename.newName(), rename.desc());
            } else {
                transformer.registerMethodRedirect(rename.owner(), rename.oldName(), rename.desc(),
                        rename.owner(), rename.newName(), rename.desc());
            }
        }
    }

    /** Whether {@code rename} sends a getter that threw for a missing parameter to one that returns null. */
    static boolean throwsWhenMissing(Rename rename) {
        return rename.owner().equals(LOOT_CONTEXT) && rename.newName().equals("getOptional")
                && (rename.oldName().equals("getParameter") || rename.oldName().equals("getParam"));
    }

    /** {@code requireParameter(context, key)}: the context's parameter, or NoSuchElementException as before 26.3. */
    static byte[] generateParameters() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, PARAMETERS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "requireParameter", REQUIRE_PARAMETER, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, LOOT_CONTEXT, "getOptional", CONTEXT_KEY_GETTER, false);
        mv.visitInsn(DUP);
        Label present = new Label();
        mv.visitJumpInsn(IFNONNULL, present);
        mv.visitTypeInsn(NEW, "java/util/NoSuchElementException");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/NoSuchElementException", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(present);
        mv.visitFrame(F_SAME1, 0, null, 1, new Object[]{"java/lang/Object"});
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }

    /** Whether the running host has the new name and no longer has the old one. */
    public static boolean hostRenamed(Rename rename) {
        ClassNode owner = ClassResourceInspector.read(rename.owner());
        if (owner == null) return false;
        return declares(owner, rename, rename.newName()) && !declares(owner, rename, rename.oldName());
    }

    /** Like {@link #hostRenamed(Rename)}, asking the indexed host jar when the class cannot be read. */
    public static boolean hostRenamed(Rename rename, com.retromod.core.FuzzyMethodResolver index) {
        if (ClassResourceInspector.read(rename.owner()) != null) return hostRenamed(rename);
        // Offline the CLI and AOT have no Minecraft on the classpath, only the --mc-jar index, and
        // they must reach the same answer as the game.
        if (index == null || !index.isIndexed() || !index.hasClass(rename.owner())) return false;
        return indexDeclares(index, rename, rename.newName()) && !indexDeclares(index, rename, rename.oldName());
    }

    private static boolean indexDeclares(com.retromod.core.FuzzyMethodResolver index, Rename rename, String name) {
        if (rename.field()) {
            return index.getDeclaredFields(rename.owner()).stream()
                    .anyMatch(field -> field.name().equals(name) && field.descriptor().equals(rename.desc()));
        }
        return index.getDeclaredMethods(rename.owner()).stream()
                .anyMatch(method -> method.name().equals(name) && method.descriptor().equals(rename.desc()));
    }

    private static boolean declares(ClassNode owner, Rename rename, String name) {
        if (rename.field()) {
            for (FieldNode field : owner.fields) {
                if (field.name.equals(name) && field.desc.equals(rename.desc())) return true;
            }
            return false;
        }
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(rename.desc())) return true;
        }
        return false;
    }
}
