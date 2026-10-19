package net.minecraft.resources;

public final class Identifier {
    private final String namespace;
    private final String path;

    private Identifier(String namespace, String path) { this.namespace = namespace; this.path = path; }

    public static Identifier fromNamespaceAndPath(String namespace, String path) {
        return new Identifier(namespace, path);
    }

    @Override public boolean equals(Object other) {
        return other instanceof Identifier id && id.namespace.equals(namespace) && id.path.equals(path);
    }
    @Override public int hashCode() { return namespace.hashCode() * 31 + path.hashCode(); }
    @Override public String toString() { return namespace + ":" + path; }
}
