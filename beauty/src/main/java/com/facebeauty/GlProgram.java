package com.facebeauty;

import android.opengl.GLES20;

import java.util.HashMap;

/** Linked program with cached uniform locations. */
final class GlProgram {

    final int id;
    private final HashMap<String, Integer> locations = new HashMap<>();

    GlProgram(String vs, String fs) {
        id = GlUtil.createProgram(vs, fs);
    }

    void use() {
        GLES20.glUseProgram(id);
    }

    int loc(String name) {
        Integer l = locations.get(name);
        if (l == null) {
            l = GLES20.glGetUniformLocation(id, name);
            locations.put(name, l);
        }
        return l;
    }

    void set1i(String name, int v) {
        GLES20.glUniform1i(loc(name), v);
    }

    void set1f(String name, float v) {
        GLES20.glUniform1f(loc(name), v);
    }

    void set2f(String name, float x, float y) {
        GLES20.glUniform2f(loc(name), x, y);
    }

    void set4f(String name, float x, float y, float z, float w) {
        GLES20.glUniform4f(loc(name), x, y, z, w);
    }

    void set4fv(String name, float[] v, int count) {
        if (count > 0) GLES20.glUniform4fv(loc(name), count, v, 0);
    }

    void setMat4(String name, float[] m) {
        GLES20.glUniformMatrix4fv(loc(name), 1, false, m, 0);
    }

    void release() {
        GLES20.glDeleteProgram(id);
    }
}
