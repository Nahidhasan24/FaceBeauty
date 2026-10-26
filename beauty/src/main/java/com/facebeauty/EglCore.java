package com.facebeauty;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;

/** Off-screen EGL context (1x1 pbuffer) for processing still images on a worker thread. */
final class EglCore {

    private EGLDisplay display;
    private EGLContext context;
    private EGLSurface surface;

    EglCore(boolean preferEs3) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw new IllegalStateException("eglInitialize failed");
        }
        if (!(preferEs3 && tryCreate(3)) && !tryCreate(2)) {
            throw new IllegalStateException("No usable EGL config");
        }
    }

    private boolean tryCreate(int esVersion) {
        int renderable = esVersion == 3 ? EGLExt.EGL_OPENGL_ES3_BIT_KHR : EGL14.EGL_OPENGL_ES2_BIT;
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, renderable,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
            return false;
        }
        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, esVersion, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT) return false;
        int[] surfAttribs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0);
        if (surface == null || surface == EGL14.EGL_NO_SURFACE
                || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
            EGL14.eglDestroyContext(display, context);
            context = null;
            return false;
        }
        return true;
    }

    void release() {
        if (display == null) return;
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        if (surface != null) EGL14.eglDestroySurface(display, surface);
        if (context != null) EGL14.eglDestroyContext(display, context);
        // No eglTerminate: the default display is shared with any GLSurfaceView in the process.
        EGL14.eglReleaseThread();
        display = null;
    }
}
