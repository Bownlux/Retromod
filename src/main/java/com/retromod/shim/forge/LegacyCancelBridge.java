/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.RebuiltApiClasses;
import com.retromod.shim.common.embedded.LegacyLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Sends old cancel calls on Forge 26.2 host events, which keep no cancel state, to Retromod's {@code LegacyCancel}. */
public final class LegacyCancelBridge {

    /** Turns on {@link #apply} in the post-remap class passes. */
    public static final String FLAG = "forge-legacy-cancel";

    static final String CANCEL = RebuiltApiClasses.ROOT + "forge/event/LegacyCancel";
    static final String CANCELLABLE_BUS = "net/minecraftforge/eventbus/api/bus/CancellableEventBus";
    private static final String CANCELLABLE_BUS_DESC = "L" + CANCELLABLE_BUS + ";";
    private static final String CANCELLABLE = "net/minecraftforge/eventbus/api/event/characteristic/Cancellable";
    // Cancellable, but Forge fires it without reading a cancel; Forge 1.20.1 gave it only a result.
    static final String NAME_TAG = "net/minecraftforge/client/event/RenderNameTagEvent";
    private static final String OLD_EVENT = LegacyEventBaseAdapter.EVENT;
    private static final String LEGACY_LOG = LegacyLog.class.getName().replace('.', '/');
    private static final String TABLE = "retromod/forge-eventbus7-cancellable.tsv";

    /** Mods built for Forge 1.21.11 or newer compile against EventBus 7, where host events have no cancel methods. */
    static final String FIRST_EVENTBUS7_SOURCE = "1.21.11";

    // Forge's own event packages; a mod's event class, such as Zeta's ZRightClickBlock, keeps its own methods.
    private static final List<String> OWNER_PACKAGES = List.of(
            "net/minecraftforge/event/", "net/minecraftforge/client/event/", "net/minecraftforge/fml/event/");

    // Forge 26.3 split this event per overlay and dropped getOverlayType, its OverlayType enum and getPoseStack, which
    // old listeners call, so they are not moved onto the children, where they would throw.
    private static final Set<String> NOT_SPLIT = Set.of("net/minecraftforge/client/event/RenderBlockScreenEffectEvent");
    // Parents another bridge already moves onto their events: LegacyTickEventBridge the tick events, and
    // LegacyAttachListenerSplit the generic attach event, whose filter picks one holder kind instead of all of them.
    private static final String TICK = LegacyTickEventBridge.TICK;
    private static final String ATTACH = LegacyAttachListenerSplit.ATTACH;

    // Each old member, by name and descriptor, with the static helper's descriptor that takes the event first.
    private static final Map<String, String> HELPERS = Map.of(
            "setCanceled(Z)V", "(Ljava/lang/Object;Z)V",
            "isCanceled()Z", "(Ljava/lang/Object;)Z",
            "isCancelable()Z", "(Ljava/lang/Object;)Z",
            "cancel()V", "(Ljava/lang/Object;)V");

    // The host's classes do not change while the game runs, so each one is read once per registration.
    private static final Map<String, Optional<ClassNode>> HOST_CLASSES = new ConcurrentHashMap<>();
    static final Function<String, ClassNode> HOST = LegacyCancelBridge::readHost;
    private static volatile Map<String, Section> table;

    private LegacyCancelBridge() {}

    /** Registers the cancel helpers and the class pass where the Forge host runs EventBus 7's cancellable buses. */
    public static void register(RetromodTransformer t) {
        HOST_CLASSES.clear();
        if (!hostCancelsByReturnValue()) return;
        RebuiltApiClasses.registerTree(t, CANCEL);
        // LegacyCancel notes cancels the host cannot carry through the game log.
        SyntheticEmbedder.registerClassResource(t, LEGACY_LOG, LegacyLog.class);
        // Listeners added with addListener become predicates; ForgeEventBusSynthetics registered the bridge first.
        ForgeEventBusSynthetics.enableCancellation(t);
        t.enablePostProcess(FLAG);
    }

    private static ClassNode readHost(String name) {
        return HOST_CLASSES.computeIfAbsent(name, key -> Optional.ofNullable(ClassResourceInspector.read(key))).orElse(null);
    }

    /** Whether a Forge host has EventBus 7's cancellable buses and lacks EventBus 6's event; offline, a Forge target from 26.2. */
    static boolean hostCancelsByReturnValue() {
        return hostCancelsByReturnValue(ClassResourceInspector::read);
    }

    static boolean hostCancelsByReturnValue(Function<String, ClassNode> host) {
        return ForgeHostShape.matches(List.of(CANCELLABLE_BUS), List.of(OLD_EVENT), ForgeHostShape.FIRST_CHECKED_VERSION, host);
    }

    /** The class with old cancel calls on host events sent to {@code LegacyCancel}, or the same array. */
    public static byte[] apply(byte[] classBytes, String modSourceVersion) {
        return apply(classBytes, modSourceVersion, HOST);
    }

    static byte[] apply(byte[] classBytes, String modSourceVersion, Function<String, ClassNode> host) {
        if (classBytes == null || !mayCallCancel(classBytes)) return classBytes;
        // A mod built for EventBus 7 never names these members on a host event.
        if (builtForEventBus7(modSourceVersion)) return classBytes;
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    changed |= rewriteCall(call, host);
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (int i = 0; i < indy.bsmArgs.length; i++) {
                        if (!(indy.bsmArgs[i] instanceof Handle handle)) continue;
                        Handle helper = helperHandle(handle, host);
                        changed |= helper != handle;
                        indy.bsmArgs[i] = helper;
                    }
                } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle handle) {
                    Handle helper = helperHandle(handle, host);
                    changed |= helper != handle;
                    ldc.cst = helper;
                }
            }
        }
        if (!changed) return classBytes;
        // The static call takes the same arguments the virtual one did, so the frames and stack sizes still hold.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Whether the mod was built against EventBus 7, whose listeners already cancel by their answer. */
    static boolean builtForEventBus7(String modSourceVersion) {
        return modSourceVersion != null
                && RetromodVersion.compareMcVersions(modSourceVersion, FIRST_EVENTBUS7_SOURCE) >= 0;
    }

    /** A cheap text check, so a class that names no cancel member on a Forge type is never parsed. */
    private static boolean mayCallCancel(byte[] classBytes) {
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        return text.contains("ancel") && text.contains("net/minecraftforge/");
    }

    private static boolean rewriteCall(MethodInsnNode call, Function<String, ClassNode> host) {
        int opcode = call.getOpcode();
        if (opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKEINTERFACE) return false;
        String helper = helperDescriptor(call.owner, call.name, call.desc, host);
        if (helper == null) return false;
        // ScreenEvent.KeyPressed is an interface on Forge 26.2, so the call may be either kind.
        call.setOpcode(Opcodes.INVOKESTATIC);
        call.owner = CANCEL;
        call.desc = helper;
        call.itf = false;
        return true;
    }

    // A method reference such as RightClickBlock::isCanceled points at the static helper instead.
    private static Handle helperHandle(Handle handle, Function<String, ClassNode> host) {
        int tag = handle.getTag();
        if (tag != Opcodes.H_INVOKEVIRTUAL && tag != Opcodes.H_INVOKEINTERFACE) return handle;
        String helper = helperDescriptor(handle.getOwner(), handle.getName(), handle.getDesc(), host);
        return helper == null ? handle : new Handle(Opcodes.H_INVOKESTATIC, CANCEL, handle.getName(), helper, false);
    }

    /** The helper's descriptor when the call is an old cancel member on a Forge event the host gives none, else null. */
    private static String helperDescriptor(String owner, String name, String desc, Function<String, ClassNode> host) {
        String helper = HELPERS.get(name + desc);
        if (helper == null || OWNER_PACKAGES.stream().noneMatch(owner::startsWith)) return null;
        return hostLacks(owner, name, desc, host) ? helper : null;
    }

    /** Whether the host's {@code owner} and its supertypes lack the member; offline, a Forge 26.2 or newer host lacks it. */
    static boolean hostLacks(String owner, String name, String desc, Function<String, ClassNode> host) {
        if (host.apply(ForgeHostShape.ANCHOR) == null) return true;
        Deque<String> pending = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            String type = pending.pop();
            if (!seen.add(type)) continue;
            ClassNode node = host.apply(type);
            // A class the host does not have declares nothing, so the old call could not link there either.
            if (node == null) continue;
            if (node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc))) return false;
            if (node.superName != null) pending.add(node.superName);
            if (node.interfaces != null) pending.addAll(node.interfaces);
        }
        return true;
    }

    /** Whether the event posts on a cancellable bus: read from the host, offline from the bundled table. */
    public static boolean hasCancellableBus(String event) {
        return hasCancellableBus(event, HOST);
    }

    static boolean hasCancellableBus(String event, Function<String, ClassNode> host) {
        if (host.apply(ForgeHostShape.ANCHOR) == null) {
            Section section = section(RetromodVersion.TARGET_MC_VERSION);
            return section != null && section.cancellable().contains(event);
        }
        ClassNode node = host.apply(event);
        if (node == null || event.equals(NAME_TAG)) return false;
        for (FieldNode field : node.fields) {
            if (field.name.equals("BUS") && (field.access & Opcodes.ACC_STATIC) != 0) {
                // EventBus 7 builds a cancellable bus for every Cancellable event, whatever type the field names.
                return field.desc.equals(CANCELLABLE_BUS_DESC) || implementsCancellable(event, host);
            }
        }
        return false;
    }

    private static boolean implementsCancellable(String type, Function<String, ClassNode> host) {
        Deque<String> pending = new ArrayDeque<>(List.of(type));
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            String current = pending.pop();
            if (current.equals(CANCELLABLE)) return true;
            if (!seen.add(current)) continue;
            ClassNode node = host.apply(current);
            if (node == null) continue;
            if (node.superName != null) pending.add(node.superName);
            if (node.interfaces != null) pending.addAll(node.interfaces);
        }
        return false;
    }

    /** The cancellable events a plain-bus parent such as PlayerInteractEvent hands its listeners to, from the bundled table. */
    public static Set<String> cancellableChildren(String parent) {
        return cancellableChildren(parent, HOST);
    }

    static Set<String> cancellableChildren(String parent, Function<String, ClassNode> host) {
        Section section = section(RetromodVersion.TARGET_MC_VERSION);
        if (section == null) return Set.of();
        Set<String> children = new LinkedHashSet<>();
        // A host that can be read confirms each child still has a cancellable bus.
        for (String child : section.parents().getOrDefault(parent, List.of())) {
            if (hasCancellableBus(child, host)) children.add(child);
        }
        return children;
    }

    /** The cancellable events a parent with no bus hands its listeners to, each event below it reached once with the plain ones. */
    public static Set<String> buslessChildren(String parent) {
        return buslessChildren(parent, HOST);
    }

    static Set<String> buslessChildren(String parent, Function<String, ClassNode> host) {
        Section section = section(RetromodVersion.TARGET_MC_VERSION);
        if (section == null) return Set.of();
        Set<String> children = new LinkedHashSet<>();
        // Nothing posts the parent, so a listener on it reaches events only through these children.
        for (String child : section.busless().getOrDefault(parent, List.of())) {
            if (hasCancellableBus(child, host)) children.add(child);
        }
        return children;
    }

    /** The plain-bus events a parent with no bus hands its listeners to, such as ScreenEvent.Init's Post or PlayerEvent's PlayerInteractEvent. */
    public static Set<String> buslessPlainChildren(String parent) {
        return buslessPlainChildren(parent, HOST);
    }

    static Set<String> buslessPlainChildren(String parent, Function<String, ClassNode> host) {
        Section section = section(RetromodVersion.TARGET_MC_VERSION);
        if (section == null) return Set.of();
        Set<String> children = new LinkedHashSet<>();
        for (String child : section.busless().getOrDefault(parent, List.of())) {
            if (!hasCancellableBus(child, host) && hasOwnBus(child, host)) children.add(child);
        }
        return children;
    }

    // Offline the table answers; a host that can be read confirms the child has a static BUS of its own.
    private static boolean hasOwnBus(String event, Function<String, ClassNode> host) {
        if (host.apply(ForgeHostShape.ANCHOR) == null) return true;
        ClassNode node = host.apply(event);
        return node != null && node.fields.stream()
                .anyMatch(field -> field.name.equals("BUS") && (field.access & Opcodes.ACC_STATIC) != 0);
    }

    /** The cancellable events below a cancellable event whose buses take its listeners, but not a monitor added after they exist. */
    static Set<String> heirs(String event, Function<String, ClassNode> host) {
        Section section = section(RetromodVersion.TARGET_MC_VERSION);
        if (section == null) return Set.of();
        Set<String> heirs = new LinkedHashSet<>();
        for (String heir : section.heirs().getOrDefault(event, List.of())) {
            if (hasCancellableBus(heir, host)) heirs.add(heir);
        }
        return heirs;
    }

    /** Whether listeners on a parent with no bus are moved onto its children here, and no other bridge moves them. */
    static boolean splitsBusless(String parent) {
        boolean tick = parent.equals(TICK) || parent.startsWith(TICK + "$");
        return !NOT_SPLIT.contains(parent) && !tick && !parent.equals(ATTACH);
    }

    /** The parents that lost their bus between {@code source} and {@code target}, whose listeners move onto their children. */
    static Set<String> buslessSince(String source, String target) {
        Section now = section(target);
        if (now == null) return Set.of();
        // Forge 1.21.11's EventBus 7 events have the shape of 26.2's, the first version the table was read for.
        Section then = section(source);
        if (then == null) then = table().values().iterator().next();
        Set<String> parents = new LinkedHashSet<>(now.busless().keySet());
        parents.removeAll(then.busless().keySet());
        parents.removeIf(parent -> !splitsBusless(parent));
        return parents;
    }

    /**
     * The parents with no bus whose listeners move onto their children on {@code version}, written for the bus
     * bridge as {@code parent=child,child;...} in binary names, or an empty string where there are none.
     */
    static String buslessTable(String version) {
        Section section = section(version);
        if (section == null) return "";
        List<String> rows = new ArrayList<>();
        section.busless().forEach((parent, children) -> {
            if (splitsBusless(parent)) rows.add(parent.replace('/', '.') + "=" + String.join(",", children).replace('/', '.'));
        });
        return String.join(";", rows);
    }

    /** The table's section for {@code version}: the newest one read at or below it, or null below the first. */
    static Section section(String version) {
        Section found = null;
        for (Map.Entry<String, Section> entry : table().entrySet()) {
            if (RetromodVersion.compareMcVersions(entry.getKey(), version) <= 0) found = entry.getValue();
        }
        return found;
    }

    /**
     * One version's rows: cancellable events, plain-bus and bus-less parents with their children, cancellable events
     * with the cancellable events below them, events fired without reading a cancel, and events whose cancel only stops
     * listeners while their DENY result stops the action.
     */
    record Section(Set<String> cancellable, Map<String, List<String>> parents, Map<String, List<String>> busless,
            Map<String, List<String>> heirs, Set<String> plain, Set<String> denies) {}

    private static Map<String, Section> table() {
        Map<String, Section> loaded = table;
        if (loaded == null) {
            loaded = readTable();
            table = loaded;
        }
        return loaded;
    }

    private static Map<String, Section> readTable() {
        // Sorted by version, so section() walks them oldest first.
        Map<String, Section> sections = new TreeMap<>(RetromodVersion::compareMcVersions);
        try (InputStream in = LegacyCancelBridge.class.getClassLoader().getResourceAsStream(TABLE)) {
            if (in == null) throw new IllegalStateException("Retromod's jar is missing " + TABLE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] columns = line.split("\t");
                Section section = sections.computeIfAbsent(columns[0],
                        version -> new Section(new HashSet<>(), new TreeMap<>(), new TreeMap<>(), new TreeMap<>(),
                                new HashSet<>(), new HashSet<>()));
                switch (columns[1]) {
                    case "cancellable" -> section.cancellable().add(columns[2]);
                    case "parent" -> section.parents().put(columns[2], List.of(columns[3].split(",")));
                    case "busless" -> section.busless().put(columns[2], List.of(columns[3].split(",")));
                    case "heirs" -> section.heirs().put(columns[2], List.of(columns[3].split(",")));
                    case "plain" -> section.plain().add(columns[2]);
                    case "denies" -> section.denies().add(columns[2]);
                    default -> throw new IllegalStateException("Unknown row kind in " + TABLE + ": " + line);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Retromod could not read " + TABLE, failure);
        }
        return sections;
    }
}
