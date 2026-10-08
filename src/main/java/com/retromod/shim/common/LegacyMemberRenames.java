/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.function.Predicate;

/**
 * Plain member renames Mojang made after 1.21.4 that keep their owner, descriptor, and meaning.
 *
 * <p>Retromod's intermediary table is composed from 1.21.4, so a Fabric mod built for 1.21.1 still
 * names these members by their old spelling after the remap, and a NeoForge mod carries the old
 * Mojang name directly. Porting Lib and Sophisticated Core (#249) read {@code
 * LivingEntity.lastHurtByPlayerTime} and {@code LootContext.getParamOrNull} from code and from
 * {@code @Shadow} members, which fail with {@code NoSuchFieldError}, {@code NoSuchMethodError}, or a
 * Mixin "was not located" error on 26.1.
 *
 * <p>Each rename applies only when the host proves it: the old name is gone from the owner and the
 * new one is present. That keeps the table safe on every host between 1.21.5 and 26.x without
 * pinning the exact release that renamed each member. A host whose classes cannot be read gets
 * no rename. {@link com.retromod.mixin.MixinCompatibilityTransformer} applies the same table to
 * {@code @Shadow} members, which a call-site redirect does not reach.
 */
public class LegacyMemberRenames implements MinecraftVersionedApiShim {

    /** One rename: {@code field} is false for a method, whose descriptor includes its arguments. */
    public record Rename(String owner, boolean field, String oldName, String desc, String newName) {}

    public static final List<Rename> RENAMES = List.of(
            new Rename("net/minecraft/world/entity/LivingEntity", true,
                    "lastHurtByPlayerTime", "I", "lastHurtByPlayerMemoryTime"),
            new Rename("net/minecraft/world/level/storage/loot/LootContext", false,
                    "getParamOrNull", "(Lnet/minecraft/util/context/ContextKey;)Ljava/lang/Object;",
                    "getOptionalParameter"));

    @Override
    public String getShimName() {
        return "Legacy Member Renames";
    }

    @Override
    public String getSourceVersion() {
        return "1.21.4";
    }

    @Override
    public String getTargetVersion() {
        return "1.21.5";
    }

    @Override
    public String getModLoaderType() {
        return "common";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        register(transformer, LegacyMemberRenames::hostRenamed);
    }

    /** Registers every rename the given host check confirms. */
    static void register(RetromodTransformer transformer, Predicate<Rename> hostRenamed) {
        for (Rename rename : RENAMES) {
            if (!hostRenamed.test(rename)) continue;
            if (rename.field()) {
                transformer.registerFieldRedirect(rename.owner(), rename.oldName(), rename.desc(),
                        rename.owner(), rename.newName(), rename.desc());
            } else {
                transformer.registerMethodRedirect(rename.owner(), rename.oldName(), rename.desc(),
                        rename.owner(), rename.newName(), rename.desc());
            }
        }
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
