package com.facebeauty;

/**
 * One-Euro filter over a whole landmark set. Removes jitter when the face is still (low cutoff)
 * without lagging when it moves fast (cutoff rises with speed). Units are normalized coordinates.
 */
final class LandmarkSmoother {

    static final float DEFAULT_MIN_CUTOFF = 1.2f;
    static final float DEFAULT_BETA = 12f;
    private static final float D_CUTOFF = 1.0f;

    private final float minCutoff;
    private final float beta;
    private float[] x;
    private float[] dx;
    private long lastNs;

    LandmarkSmoother(float minCutoff, float beta) {
        this.minCutoff = minCutoff;
        this.beta = beta;
    }

    float[] filter(float[] in, long timeNs) {
        if (x == null || x.length != in.length) {
            x = in.clone();
            dx = new float[in.length];
            lastNs = timeNs;
            return in.clone();
        }
        float dt = (timeNs - lastNs) / 1e9f;
        if (dt <= 0f || dt > 1f) dt = 1f / 30f;
        lastNs = timeNs;

        float ad = alpha(D_CUTOFF, dt);
        float[] out = new float[in.length];
        for (int i = 0; i < in.length; i++) {
            float d = (in[i] - x[i]) / dt;
            dx[i] += ad * (d - dx[i]);
            float a = alpha(minCutoff + beta * Math.abs(dx[i]), dt);
            x[i] += a * (in[i] - x[i]);
            out[i] = x[i];
        }
        return out;
    }

    /** Smoothed velocity of every coordinate, normalized units per second. */
    float[] velocity() {
        return dx.clone();
    }

    /** Normalized distance from this track's nose tip to the given face's nose tip. */
    float distanceTo(float[] face) {
        int i = FaceRegions.NOSE_TIP * 2;
        float ddx = x[i] - face[i];
        float ddy = x[i + 1] - face[i + 1];
        return (float) Math.sqrt(ddx * ddx + ddy * ddy);
    }

    private static float alpha(float cutoff, float dt) {
        float tau = 1f / (2f * (float) Math.PI * cutoff);
        return 1f / (1f + tau / dt);
    }
}
