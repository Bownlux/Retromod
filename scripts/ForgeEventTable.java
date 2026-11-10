/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Writes src/main/resources/retromod/forge-eventbus7-cancellable.tsv from Forge's EventBus 7 jars.
 *
 * <p>Run: {@code java -cp ~/.m2/repository/org/ow2/asm/asm/9.11.1/asm-9.11.1.jar scripts/ForgeEventTable.java
 * --old <forge-1.20.1-universal.jar> <fmlcore-1.20.1.jar> --section 26.2 <forge-universal.jar> [more jars]
 * --section 26.3 <forge-universal.jar> [more jars] > src/main/resources/retromod/forge-eventbus7-cancellable.tsv}.
 * Pass each version's Forge universal, fmlcore and eventbus jars; Minecraft's client jar may be added, it only resolves
 * supertypes. The Forge 1.20.1 jars tell which events old mods heard on the mod bus.
 */
public class ForgeEventTable {

    private static final String FORGE = "net/minecraftforge/";
    private static final String CANCELLABLE_BUS = "Lnet/minecraftforge/eventbus/api/bus/CancellableEventBus;";
    private static final String CANCELLABLE = "net/minecraftforge/eventbus/api/event/characteristic/Cancellable";
    private static final String INHERITABLE = "net/minecraftforge/eventbus/api/event/InheritableEvent";
    private static final String MOD_BUS_EVENT = "net/minecraftforge/fml/event/IModBusEvent";

    // Behavior the class files cannot show, read from Forge's sources of each version: events Forge fires without
    // reading a cancel, and events whose cancel only stops listeners while their DENY result stops the action.
    private static final List<String> PLAIN = List.of("net/minecraftforge/client/event/RenderNameTagEvent");
    private static final List<String> DENIES = List.of("net/minecraftforge/event/level/BlockEvent$BreakEvent");

    private static final String HEADER = """
            # Forge events by how they take a cancel, written by scripts/ForgeEventTable.java from the Forge jars named below.
            # cancellable: the event posts on a cancellable bus. EventBus 7 builds one for every event that implements
            #   Cancellable, whatever type its BUS field names (PlayLevelSoundEvent$AtEntity names a plain EventBus).
            # parent: a plain-bus parent and its cancellable children, which take the parent's listeners.
            # busless: a parent with no BUS, a class or an interface, and the events below it, at any depth, that have a bus
            #   of their own and do not already take the listeners of another one listed. Nothing posts the parent, so a
            #   listener on it reaches no event unless it is added to these; the rest below it take it through them.
            #   An event that sat on the other bus in Forge 1.20.1 is left out, since an old listener never heard it.
            # heirs: a cancellable event and the cancellable events below it whose buses take its listeners. EventBus 7
            #   copies a monitor only into a child bus that already exists, so a monitor goes onto each of these too.
            # plain: implements Cancellable, but Forge fires it without reading a cancel (RenderNameTagEvent).
            # denies: also a cancellable row, but Forge fires it without reading a cancel and refuses the action when its
            #   result is DENY (BreakEvent). A cancel only stops later listeners, so Retromod carries an old cancel onto DENY.
            # Columns: the Minecraft version a section was read for, the kind, the event, and the children where listed.
            # Each section is complete; an offline run reads the newest section at or below its target.
            """;

    /** What the table needs of one class: its supertypes and its static BUS field's type, or null without one. */
    record Info(String name, String superName, List<String> interfaces, String busDesc) {}

    private final List<ZipFile> jars = new ArrayList<>();
    private final Map<String, Info> infos = new HashMap<>();
    private final Map<String, Set<String>> supertypes = new HashMap<>();
    // Forge 1.20.1's classes, and the events each busless row leaves out for sitting on the other bus there.
    private ForgeEventTable old;
    private final List<String> leftOut = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        Map<String, List<Path>> sections = new LinkedHashMap<>();
        List<Path> oldJars = new ArrayList<>();
        List<Path> current = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--section")) {
                current = new ArrayList<>();
                sections.put(args[++i], current);
            } else if (args[i].equals("--old")) {
                current = oldJars;
            } else if (current != null) {
                current.add(Path.of(args[i]));
            } else {
                throw new IllegalArgumentException("Name a section first: --section <version> <jar>...");
            }
        }
        if (sections.isEmpty() || oldJars.isEmpty()) {
            throw new IllegalArgumentException("Usage: --old <forge-1.20.1 jar>... --section <version> <jar>... [--section ...]");
        }
        print(oldJars, sections, System.out);
    }

    /** The whole table for the sections, each read from its own jars, with Forge 1.20.1's jars as {@code oldJars}. */
    static void print(List<Path> oldJars, Map<String, List<Path>> sections, PrintStream out) throws IOException {
        out.print(HEADER);
        out.println("# Forge 1.20.1: " + names(oldJars) + ".");
        sections.forEach((version, jars) -> out.println("# " + version + ": " + names(jars) + "."));
        ForgeEventTable old = open(oldJars);
        try {
            for (Map.Entry<String, List<Path>> section : sections.entrySet()) {
                ForgeEventTable table = open(section.getValue());
                table.old = old;
                try {
                    for (String row : table.rows(section.getKey())) out.println(row);
                    for (String note : table.leftOut) out.println("# " + section.getKey() + " busless rows leave out " + note);
                } finally {
                    table.close();
                }
            }
        } finally {
            old.close();
        }
    }

    private static String names(List<Path> jars) {
        return String.join(", ", jars.stream().map(jar -> jar.getFileName().toString()).toList());
    }

    private static ForgeEventTable open(List<Path> jarPaths) throws IOException {
        ForgeEventTable table = new ForgeEventTable();
        for (Path jar : jarPaths) table.jars.add(new ZipFile(jar.toFile()));
        return table;
    }

    private void close() throws IOException {
        for (ZipFile jar : jars) jar.close();
    }

    private List<String> rows(String version) {
        Set<String> events = new TreeSet<>();
        for (ZipFile jar : jars) {
            for (ZipEntry entry : Collections.list(jar.entries())) {
                String name = entry.getName();
                if (name.startsWith(FORGE) && name.endsWith(".class") && !name.endsWith("package-info.class")) {
                    events.add(name.substring(0, name.length() - ".class".length()));
                }
            }
        }
        List<String> rows = new ArrayList<>();
        // A cancellable bus, minus the events Forge fires without reading the cancel.
        for (String event : events) {
            if (cancellableBus(event) && !PLAIN.contains(event)) rows.add(version + "\tcancellable\t" + event);
        }
        // A plain-bus parent whose bus hands its listeners to cancellable children.
        for (String event : events) {
            if (!hasBus(event) || cancellableBus(event) || !inheritable(event)) continue;
            Set<String> children = new TreeSet<>();
            for (String other : events) {
                if (cancellableBus(other) && takesListenersOf(other, event)) children.add(other);
            }
            if (!children.isEmpty()) rows.add(version + "\tparent\t" + event + "\t" + String.join(",", children));
        }
        // A parent with no bus anywhere above it, and the bused events below it that no listed one hands listeners to.
        for (String event : events) {
            if (!isEventType(event) || hasBus(event) || isModBusEvent(event) || hasBusAbove(event)) continue;
            Set<String> below = new TreeSet<>();
            List<String> otherBus = new ArrayList<>();
            boolean heardBefore = false;
            for (String other : events) {
                if (!hasBus(other) || other.equals(event) || !assignable(event, other)) continue;
                // An old listener on a game-bus parent never heard a mod-bus event below it, nor the other way round.
                if (old.onOtherBus(event, other)) {
                    otherBus.add(other + " under " + event + ", on the other bus in Forge 1.20.1");
                    continue;
                }
                heardBefore |= old.info(other) != null;
                below.add(other);
            }
            // A parent whose old events all sat on the other bus heard nothing in Forge 1.20.1, so it stays silent.
            if (!otherBus.isEmpty() && !heardBefore) {
                leftOut.add("the row of " + event + ", whose events Forge 1.20.1 had all sat on the other bus");
                continue;
            }
            leftOut.addAll(otherBus);
            Set<String> hooks = new TreeSet<>(below);
            hooks.removeIf(child -> below.stream().anyMatch(other -> takesListenersOf(child, other)));
            if (!hooks.isEmpty()) rows.add(version + "\tbusless\t" + event + "\t" + String.join(",", hooks));
        }
        // A cancellable event whose bus hands its listeners, but not later monitors, to cancellable events below it.
        for (String event : events) {
            if (!cancellableBus(event) || PLAIN.contains(event) || !inheritable(event)) continue;
            Set<String> heirs = new TreeSet<>();
            for (String other : events) {
                if (cancellableBus(other) && !PLAIN.contains(other) && takesListenersOf(other, event)) heirs.add(other);
            }
            if (!heirs.isEmpty()) rows.add(version + "\theirs\t" + event + "\t" + String.join(",", heirs));
        }
        for (String event : PLAIN) if (events.contains(event)) rows.add(version + "\tplain\t" + event);
        for (String event : DENIES) if (events.contains(event)) rows.add(version + "\tdenies\t" + event);
        return rows;
    }

    /** Whether Forge 1.20.1 had both events, on different buses: one a mod-bus event and the other not. */
    private boolean onOtherBus(String parent, String child) {
        if (info(parent) == null || info(child) == null) return false;
        return supertypes(parent).contains(MOD_BUS_EVENT) != supertypes(child).contains(MOD_BUS_EVENT);
    }

    /** Whether EventBus 7 hands listeners on {@code parent}'s bus to {@code child}'s, as it does along InheritableEvent types. */
    private boolean takesListenersOf(String child, String parent) {
        return !child.equals(parent) && assignable(parent, child) && inheritable(parent);
    }

    // Forge names each event type ...Event, nested types inside one; EventBus 7's own types and characteristics such
    // as HasResult are no events of their own.
    private static boolean isEventType(String type) {
        if (type.startsWith(FORGE + "eventbus/")) return false;
        String outer = type.substring(type.lastIndexOf('/') + 1).split("\\$")[0];
        return outer.endsWith("Event");
    }

    private boolean hasBus(String event) {
        Info info = info(event);
        return info != null && info.busDesc() != null;
    }

    // A parent below a bused type posts on that type's bus, so its listeners need no children.
    private boolean hasBusAbove(String event) {
        return supertypes(event).stream().anyMatch(type -> !type.equals(event) && hasBus(type));
    }

    private boolean cancellableBus(String event) {
        Info info = info(event);
        if (info == null || info.busDesc() == null) return false;
        // EventBus 7 builds a cancellable bus for every Cancellable event, whatever type the field names.
        return info.busDesc().equals(CANCELLABLE_BUS) || supertypes(event).contains(CANCELLABLE);
    }

    private boolean inheritable(String event) {
        return supertypes(event).contains(INHERITABLE);
    }

    private boolean isModBusEvent(String event) {
        return supertypes(event).contains(MOD_BUS_EVENT);
    }

    private boolean assignable(String parent, String child) {
        return supertypes(child).contains(parent);
    }

    /** The type and every supertype the jars can read, itself included. */
    private Set<String> supertypes(String type) {
        Set<String> known = supertypes.get(type);
        if (known != null) return known;
        Set<String> all = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(List.of(type));
        while (!pending.isEmpty()) {
            String current = pending.pop();
            if (!all.add(current)) continue;
            Info info = info(current);
            if (info == null) continue;
            if (info.superName() != null) pending.add(info.superName());
            pending.addAll(info.interfaces());
        }
        supertypes.put(type, all);
        return all;
    }

    private Info info(String name) {
        if (infos.containsKey(name)) return infos.get(name);
        Info info = null;
        for (ZipFile jar : jars) {
            ZipEntry entry = jar.getEntry(name + ".class");
            if (entry == null) continue;
            try (InputStream in = jar.getInputStream(entry)) {
                info = read(in.readAllBytes());
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not read " + name + " from " + jar.getName(), failure);
            }
            break;
        }
        infos.put(name, info);
        return info;
    }

    private static Info read(byte[] bytes) {
        String[] bus = new String[1];
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (name.equals("BUS") && (access & Opcodes.ACC_STATIC) != 0) bus[0] = descriptor;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Info(reader.getClassName(), reader.getSuperName(), List.of(reader.getInterfaces()), bus[0]);
    }
}
