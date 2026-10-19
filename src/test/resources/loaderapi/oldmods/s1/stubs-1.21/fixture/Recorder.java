package fixture;

/** Compile-only copy of the fake host's recorder. */
public final class Recorder {
    private Recorder() {}

    public static void note(String what) {
        throw new AssertionError("compile-only");
    }
}
