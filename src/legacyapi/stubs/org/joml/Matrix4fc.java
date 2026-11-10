// Compile-only stub of a JOML type the 26.2 host ships, for src/legacyapi; never packaged.
package org.joml;

public interface Matrix4fc {
    default Vector4f transform(Vector4f vector) { throw new UnsupportedOperationException(); }
    default Matrix3f normal(Matrix3f dest) { throw new UnsupportedOperationException(); }
    default float m00() { throw new UnsupportedOperationException(); }
    default float m01() { throw new UnsupportedOperationException(); }
    default float m10() { throw new UnsupportedOperationException(); }
    default float m11() { throw new UnsupportedOperationException(); }
    default float m30() { throw new UnsupportedOperationException(); }
    default float m31() { throw new UnsupportedOperationException(); }
}
