/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Writes src/main/resources/retromod/neoforge-abstract-events.tsv from NeoForge's jars.
 *
 * <p>Run: {@code java -cp ~/.m2/repository/org/ow2/asm/asm/9.11.1/asm-9.11.1.jar scripts/NeoForgeEventTable.java
 * --section 1.21.1 <neoforge-universal.jar> <fancymodloader loader.jar> <bus.jar> [--section ...]
 * > src/main/resources/retromod/neoforge-abstract-events.tsv}. Each section names one NeoForge release's jars.
 */
public class NeoForgeEventTable {

    private static final String EVENT = "net/neoforged/bus/api/Event";
    private static final String MOD_BUS_EVENT = "net/neoforged/fml/event/IModBusEvent";

    private static final String HEADER = """
            # NeoForge's abstract events, written by scripts/NeoForgeEventTable.java from the NeoForge jars named below.
            # NeoForge refuses a listener on an abstract event. An old Forge listener on one of these parents is added
            # instead to each concrete event below it whose parent is abstract, so every event below the parent reaches
            # it once. Only children on the parent's own bus are listed: a mod bus event never posts on the game bus.
            # Columns: the Minecraft version a section was read for, the abstract event, and its concrete children.
            # Each section is complete; a run reads the newest section at or below its host. A release whose rows match
            # the one before it has no section of its own.""";

    /** One class's access, superclass and interfaces. */
    private record Shape(int access, String superName, String[] interfaces) {}

    private final Map<String, Shape> classes = new HashMap<>();

    public static void main(String[] args) throws IOException {
        Map<String, List<Path>> sections = new LinkedHashMap<>();
        List<Path> current = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--section")) {
                current = new ArrayList<>();
                sections.put(args[++i], current);
            } else if (current != null) {
                current.add(Path.of(args[i]));
            } else {
                throw new IllegalArgumentException("Name a section first: --section <version> <jar>...");
            }
        }
        if (sections.isEmpty()) throw new IllegalArgumentException("Usage: --section <version> <jar>... [--section ...]");
        print(sections, System.out);
    }

    static void print(Map<String, List<Path>> sections, PrintStream out) throws IOException {
        out.println(HEADER);
        sections.forEach((version, jars) -> out.println("# " + version + ": " + names(jars) + "."));
        Map<String, TreeSet<String>> previous = null;
        for (Map.Entry<String, List<Path>> section : sections.entrySet()) {
            NeoForgeEventTable table = new NeoForgeEventTable();
            for (Path jar : section.getValue()) table.read(jar);
            Map<String, TreeSet<String>> rows = table.rows();
            // A release with the same rows as the one before is read through that section.
            if (rows.equals(previous)) continue;
            rows.forEach((parent, children) ->
                    out.println(section.getKey() + "\t" + parent + "\t" + String.join(",", children)));
            previous = rows;
        }
    }

    private static String names(List<Path> jars) {
        List<String> names = new ArrayList<>();
        for (Path jar : jars) names.add(jar.getFileName().toString());
        return String.join(", ", names);
    }

    private void read(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(int version, int access, String name, String signature, String superName,
                                String[] interfaces) {
                            classes.put(name, new Shape(access, superName, interfaces == null ? new String[0] : interfaces));
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }
    }

    /** Every abstract event and the concrete events below it whose parent is abstract, on the same bus. */
    private Map<String, TreeSet<String>> rows() {
        Map<String, TreeSet<String>> rows = new TreeMap<>();
        for (Map.Entry<String, Shape> entry : classes.entrySet()) {
            String child = entry.getKey();
            Shape shape = entry.getValue();
            if ((shape.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0 || !isEvent(child)) continue;
            // Only the topmost concrete event; one below it already takes that event's listeners.
            if (!isAbstract(shape.superName())) continue;
            for (String parent = shape.superName(); isAbstract(parent); parent = classes.get(parent).superName()) {
                if (modBus(parent) == modBus(child)) rows.computeIfAbsent(parent, p -> new TreeSet<>()).add(child);
            }
        }
        return rows;
    }

    private boolean isAbstract(String name) {
        Shape shape = classes.get(name);
        return shape != null && !name.equals(EVENT) && (shape.access() & Opcodes.ACC_ABSTRACT) != 0;
    }

    private boolean isEvent(String name) {
        for (String current = name; current != null; ) {
            if (current.equals(EVENT)) return true;
            Shape shape = classes.get(current);
            current = shape == null ? null : shape.superName();
        }
        return false;
    }

    private boolean modBus(String name) {
        Shape shape = classes.get(name);
        if (shape == null) return false;
        if (name.equals(MOD_BUS_EVENT)) return true;
        for (String iface : shape.interfaces()) {
            if (modBus(iface)) return true;
        }
        return shape.superName() != null && modBus(shape.superName());
    }
}
