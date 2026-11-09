/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

/** Answers the {@code FriendlyByteBuf} members 26.3 removed from Retromod's rebuilt copies, for every loader. */
public final class LegacyFriendlyByteBufApi {

    static final String BUF = "net/minecraft/network/FriendlyByteBuf";
    static final String REGISTRY_BUF = "net/minecraft/network/RegistryFriendlyByteBuf";
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/network/FriendlyByteBufMembers";

    private static final String ENCODER = "Lnet/minecraft/network/codec/StreamEncoder;";
    private static final String DECODER = "Lnet/minecraft/network/codec/StreamDecoder;";
    private static final String INT_FUNCTION = "Ljava/util/function/IntFunction;";

    /** One removed instance member: its name and its descriptor before 26.3. */
    record Member(String name, String desc) {}

    static final List<Member> INSTANCE_MEMBERS = List.of(
            new Member("writeCollection", "(Ljava/util/Collection;" + ENCODER + ")V"),
            new Member("readCollection", "(" + INT_FUNCTION + DECODER + ")Ljava/util/Collection;"),
            new Member("readList", "(" + DECODER + ")Ljava/util/List;"),
            new Member("writeMap", "(Ljava/util/Map;" + ENCODER + ENCODER + ")V"),
            new Member("readMap", "(" + INT_FUNCTION + DECODER + DECODER + ")Ljava/util/Map;"),
            new Member("readMap", "(" + DECODER + DECODER + ")Ljava/util/Map;"),
            new Member("writeIntIdList", "(Lit/unimi/dsi/fastutil/ints/IntList;)V"),
            new Member("readIntIdList", "()Lit/unimi/dsi/fastutil/ints/IntList;"),
            new Member("writeInstant", "(Ljava/time/Instant;)V"),
            new Member("readInstant", "()Ljava/time/Instant;"),
            new Member("writePublicKey", "(Ljava/security/PublicKey;)L" + BUF + ";"),
            new Member("readPublicKey", "()Ljava/security/PublicKey;"),
            new Member("writeBlockHitResult", "(Lnet/minecraft/world/phys/BlockHitResult;)V"),
            new Member("readBlockHitResult", "()Lnet/minecraft/world/phys/BlockHitResult;"));

    static final Member LIMIT_VALUE = new Member("limitValue", "(" + INT_FUNCTION + "I)" + INT_FUNCTION);

    private LegacyFriendlyByteBufApi() {}

    /** Registers a redirect for each member this host's buffer no longer has. */
    public static void register(RetromodTransformer t) {
        ClassNode hostBuf = ClassResourceInspector.read(BUF);
        // Offline the host classes are not readable, so the target version decides.
        boolean offlineRemoved = hostBuf == null
                && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        registerMissing(t, member -> hostBuf != null ? !hasMethod(hostBuf, member) : offlineRemoved);
    }

    /** Registers the members the predicate says are gone, with the rebuilt class they need. */
    static void registerMissing(RetromodTransformer t, java.util.function.Predicate<Member> removed) {
        boolean registeredClass = false;
        for (Member member : INSTANCE_MEMBERS) {
            if (!removed.test(member)) continue;
            if (!registeredClass) {
                RebuiltApiClasses.registerTree(t, REBUILT);
                registeredClass = true;
            }
            String helperDesc = "(L" + BUF + ";" + member.desc().substring(1);
            // Mods call these through a subclass too, most often RegistryFriendlyByteBuf.
            t.registerInheritedMethodRedirect(BUF, member.name(), member.desc(), REBUILT, member.name(), helperDesc);
            // A method reference keeps the exact owner it names, so the registry buffer needs its own key.
            t.registerMethodRedirect(REGISTRY_BUF, member.name(), member.desc(), REBUILT, member.name(), helperDesc, true);
        }
        if (removed.test(LIMIT_VALUE)) {
            if (!registeredClass) RebuiltApiClasses.registerTree(t, REBUILT);
            t.registerMethodRedirect(BUF, LIMIT_VALUE.name(), LIMIT_VALUE.desc(), REBUILT, LIMIT_VALUE.name(), LIMIT_VALUE.desc());
        }
    }

    static boolean hasMethod(ClassNode owner, Member member) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(member.name()) && method.desc.equals(member.desc())) return true;
        }
        return false;
    }
}
