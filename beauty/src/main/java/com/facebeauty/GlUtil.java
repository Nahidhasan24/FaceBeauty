package com.facebeauty;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

final class GlUtil {

    static final int ATTR_POS = 0;
    static final int ATTR_VAL = 1;

    /** Maps upright uv (v up) onto a texture uploaded from a Bitmap (row 0 at t = 0). */
    static final float[] FLIP_V = {
            1, 0, 0, 0,
            0, -1, 0, 0,
            0, 0, 1, 0,
            0, 1, 0, 1};

    private GlUtil() {
    }

    static boolean supportsEs3(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        return am.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30000;
    }

    static boolean isEs3Context() {
        String v = GLES20.glGetString(GLES20.GL_VERSION);
        return v != null && v.startsWith("OpenGL ES 3");
    }

    static int createProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glBindAttribLocation(p, ATTR_POS, "aPos");
        GLES20.glBindAttribLocation(p, ATTR_VAL, "aVal");
        GLES20.glBindAttribLocation(p, ATTR_VAL, "aUv"); // never in the same program as aVal
        GLES20.glLinkProgram(p);
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new IllegalStateException("Program link failed: " + log);
        }
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new IllegalStateException("Shader compile failed: " + log + "\n" + src);
        }
        return s;
    }

    static int createExternalTexture() {
        int tex = genTexture();
        setupTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
        return tex;
    }

    static int createTexture(int width, int height) {
        int tex = genTexture();
        setupTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        return tex;
    }

    static int createTexture(Bitmap bitmap) {
        int tex = genTexture();
        setupTexture(GLES20.GL_TEXTURE_2D, tex);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        return tex;
    }

    /** Power-of-two RGBA bytes with trilinear mipmaps (stays clean when drawn small). */
    static int createMipmappedTexture(ByteBuffer rgba, int size) {
        int tex = genTexture();
        setupTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, size, size, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgba);
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        return tex;
    }

    static void deleteTexture(int tex) {
        if (tex != 0) GLES20.glDeleteTextures(1, new int[]{tex}, 0);
    }

    private static int genTexture() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        return t[0];
    }

    private static void setupTexture(int target, int tex) {
        GLES20.glBindTexture(target, tex);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    static int maxTextureSize() {
        int[] v = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, v, 0);
        return v[0];
    }

    /** Reads the bound framebuffer (rendered with flipY so rows come out top-down). */
    static Bitmap readBitmap(int width, int height) {
        ByteBuffer buf = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        buf.rewind();
        bmp.copyPixelsFromBuffer(buf);
        return bmp;
    }

    static FloatBuffer floatBuffer(int floats) {
        return ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    static FloatBuffer floatBuffer(float[] data) {
        FloatBuffer b = floatBuffer(data.length);
        b.put(data).position(0);
        return b;
    }

    static ShortBuffer shortBuffer(short[] data) {
        ShortBuffer b = ByteBuffer.allocateDirect(data.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        b.put(data).position(0);
        return b;
    }
}
