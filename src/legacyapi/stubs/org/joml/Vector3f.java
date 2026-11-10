// Compile-only stub of a JOML type the 26.2 and 26.3 hosts ship, for src/legacyapi; never packaged.
package org.joml;

public class Vector3f implements Vector3fc {
    public float x;
    public float y;
    public float z;
    public Vector3f(float x, float y, float z) {}
    public Vector3f(Vector3fc v) {}
    public float x() { throw new UnsupportedOperationException(); }
    public float y() { throw new UnsupportedOperationException(); }
    public float z() { throw new UnsupportedOperationException(); }
    public Vector3f mul(Matrix3fc matrix) { throw new UnsupportedOperationException(); }
    public Vector3f normalize() { throw new UnsupportedOperationException(); }
    public float lengthSquared() { throw new UnsupportedOperationException(); }
}
