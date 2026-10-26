package com.facebeauty;

/** Landmarks for every face found in one frame. Immutable once published. */
public final class FaceFrame {

    public static final FaceFrame EMPTY = new FaceFrame(new float[0][], 0L);

    /**
     * One array per face: x0, y0, x1, y1, ... (478 MediaPipe landmarks), normalized to [0,1] with
     * the origin at the top-left of the upright image.
     */
    public final float[][] faces;
    /** Per face, per coordinate velocity in normalized units per second; null if unknown. */
    public final float[][] velocities;
    /** Capture time of the frame the landmarks came from (elapsedRealtimeNanos clock). */
    public final long timestampNs;

    public FaceFrame(float[][] faces, long timestampNs) {
        this(faces, null, timestampNs);
    }

    public FaceFrame(float[][] faces, float[][] velocities, long timestampNs) {
        this.faces = faces;
        this.velocities = velocities;
        this.timestampNs = timestampNs;
    }
}
