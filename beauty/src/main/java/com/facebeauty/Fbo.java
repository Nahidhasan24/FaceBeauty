package com.facebeauty;

import android.opengl.GLES20;

/** RGBA8 texture + framebuffer pair, resized lazily. */
final class Fbo {

    int texture;
    int framebuffer;
    int width;
    int height;

    void ensure(int w, int h) {
        if (framebuffer != 0 && w == width && h == height) return;
        release();
        width = w;
        height = h;
        texture = GlUtil.createTexture(w, h);
        int[] fb = new int[1];
        GLES20.glGenFramebuffers(1, fb, 0);
        framebuffer = fb[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texture, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
    }

    void bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
        GLES20.glViewport(0, 0, width, height);
    }

    void release() {
        if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, new int[]{framebuffer}, 0);
        GlUtil.deleteTexture(texture);
        framebuffer = 0;
        texture = 0;
        width = 0;
        height = 0;
    }
}
