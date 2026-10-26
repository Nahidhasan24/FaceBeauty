package com.facebeauty.livekit;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;

import livekit.org.webrtc.CameraVideoCapturer;
import livekit.org.webrtc.CapturerObserver;
import livekit.org.webrtc.SurfaceTextureHelper;
import livekit.org.webrtc.VideoFrame;

/**
 * Camera2 capturer for LiveKit tuned for beauty video calls.
 *
 * <p>WebRTC's built-in capturers always pick the widest AE frame-rate range (e.g. 5-30 fps), so
 * in a dim room the camera stretches exposure and the call drops to 10-15 fps. This capturer keeps
 * a frame-rate floor ({@code minFps}, default 20) and turns on face-priority exposure when the
 * camera supports it, so the face is exposed correctly instead of the background.
 *
 * <p>Frames are emitted exactly like WebRTC's Camera2Session (sensor-oriented texture + rotation
 * metadata, front camera un-mirrored), so LiveKit treats it as a regular camera track.
 *
 * <pre>
 * LocalVideoTrack track = room.getLocalParticipant().createVideoTrack(
 *         "camera", BeautyCameraCapturer.create(context, true), options, beautyProcessor);
 * </pre>
 */
public final class BeautyCameraCapturer implements CameraVideoCapturer {

    private static final String TAG = "BeautyCameraCapturer";
    public static final int DEFAULT_MIN_FPS = 20;

    private final CameraManager manager;
    private final int minFps;
    private volatile String cameraId;

    private SurfaceTextureHelper helper;
    private Context appContext;
    private CapturerObserver observer;
    private Handler handler;

    // Camera thread (the SurfaceTextureHelper's thread) only.
    private CameraDevice device;
    private CameraCaptureSession session;
    private Surface surface;
    private int width;
    private int height;
    private int fps;
    private boolean running;
    private boolean front;
    private int sensorOrientation;
    private boolean reportedStart;
    private int generation;

    /** Capturer for the first camera facing the given way; {@code minFps} = 20. */
    public static BeautyCameraCapturer create(Context context, boolean frontFacing) {
        return new BeautyCameraCapturer(context, findCamera(context, frontFacing), DEFAULT_MIN_FPS);
    }

    /**
     * @param cameraId Camera2 id
     * @param minFps   lowest frame rate the camera may drop to in low light (0 = camera default)
     */
    public BeautyCameraCapturer(Context context, String cameraId, int minFps) {
        manager = (CameraManager) context.getApplicationContext().getSystemService(Context.CAMERA_SERVICE);
        this.cameraId = cameraId;
        this.minFps = minFps;
    }

    public static String findCamera(Context context, boolean frontFacing) {
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String fallback = null;
            for (String id : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (fallback == null) fallback = id;
                if (facing != null && (facing == CameraMetadata.LENS_FACING_FRONT) == frontFacing) return id;
            }
            return fallback;
        } catch (CameraAccessException e) {
            throw new IllegalStateException("No camera available", e);
        }
    }

    // ------------------------------------------------------------ VideoCapturer

    @Override
    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context context, CapturerObserver capturerObserver) {
        helper = surfaceTextureHelper;
        appContext = context.getApplicationContext();
        observer = capturerObserver;
        handler = surfaceTextureHelper.getHandler();
    }

    @Override
    public void startCapture(int width, int height, int framerate) {
        handler.post(() -> {
            this.width = width;
            this.height = height;
            this.fps = framerate;
            running = true;
            reportedStart = false;
            open();
        });
    }

    @Override
    public void stopCapture() throws InterruptedException {
        runAndWait(() -> {
            if (!running) return;
            running = false;
            close();
            observer.onCapturerStopped();
        });
    }

    @Override
    public void changeCaptureFormat(int width, int height, int framerate) {
        handler.post(() -> {
            this.width = width;
            this.height = height;
            this.fps = framerate;
            if (running) {
                close();
                open();
            }
        });
    }

    @Override
    public void dispose() {
        try {
            stopCapture();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isScreencast() {
        return false;
    }

    // ------------------------------------------------------------ CameraVideoCapturer

    @Override
    public void switchCamera(CameraSwitchHandler switchHandler) {
        handler.post(() -> {
            try {
                boolean wasFront = front;
                String next = null;
                for (String id : manager.getCameraIdList()) {
                    Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && (facing == CameraMetadata.LENS_FACING_FRONT) != wasFront) {
                        next = id;
                        break;
                    }
                }
                switchTo(next, switchHandler);
            } catch (CameraAccessException e) {
                if (switchHandler != null) switchHandler.onCameraSwitchError(e.getMessage());
            }
        });
    }

    @Override
    public void switchCamera(CameraSwitchHandler switchHandler, String cameraName) {
        handler.post(() -> switchTo(cameraName, switchHandler));
    }

    private void switchTo(String id, CameraSwitchHandler switchHandler) {
        if (id == null) {
            if (switchHandler != null) switchHandler.onCameraSwitchError("No camera to switch to");
            return;
        }
        cameraId = id;
        if (running) {
            close();
            open();
        }
        if (switchHandler != null) switchHandler.onCameraSwitchDone(isFrontFacing(id));
    }

    // ------------------------------------------------------------ camera thread

    @SuppressLint("MissingPermission")
    private void open() {
        int gen = ++generation;
        try {
            CameraCharacteristics c = manager.getCameraCharacteristics(cameraId);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            front = facing != null && facing == CameraMetadata.LENS_FACING_FRONT;
            Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation != null ? orientation : 0;

            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = chooseSize(map.getOutputSizes(SurfaceTexture.class), width, height);
            Range<Integer> range = chooseFpsRange(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));
            int[] scenes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
            int[] afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            Log.i(TAG, "Camera " + cameraId + " " + size + " fps " + range);

            helper.setTextureSize(size.getWidth(), size.getHeight());
            surface = new Surface(helper.getSurfaceTexture());
            helper.startListening(this::onTextureFrame);

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    if (gen != generation || !running) {
                        camera.close();
                        return;
                    }
                    device = camera;
                    createSession(gen, range, scenes, afModes);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    if (camera == device) fail("Camera disconnected");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    if (camera == device || gen == generation) fail("Camera error " + error);
                }
            }, handler);
        } catch (CameraAccessException | RuntimeException e) {
            fail("Failed to open camera: " + e);
        }
    }

    @SuppressWarnings("deprecation") // List<Surface> overload is the only one on API 24-27.
    private void createSession(int gen, Range<Integer> range, int[] scenes, int[] afModes) {
        try {
            device.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (gen != generation || device == null) {
                        s.close();
                        return;
                    }
                    session = s;
                    try {
                        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                        b.addTarget(surface);
                        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range);
                        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        b.set(CaptureRequest.CONTROL_AE_LOCK, false);
                        if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        }
                        // Meter exposure/white balance on faces rather than the whole frame.
                        if (contains(scenes, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY)) {
                            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE);
                            b.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY);
                        }
                        s.setRepeatingRequest(b.build(), null, handler);
                        if (!reportedStart) {
                            reportedStart = true;
                            observer.onCapturerStarted(true);
                        }
                    } catch (CameraAccessException | RuntimeException e) {
                        fail("Failed to start capture: " + e);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    s.close();
                    fail("Capture session configuration failed");
                }
            }, handler);
        } catch (CameraAccessException | RuntimeException e) {
            fail("Failed to create session: " + e);
        }
    }

    private void onTextureFrame(VideoFrame frame) {
        if (!running || device == null) return;
        // The OS rotated the texture upright and mirrored the front camera. Undo both and send
        // rotation metadata instead, like Camera2Session, so encoders and renderers agree.
        VideoFrame.TextureBuffer buffer = (VideoFrame.TextureBuffer) frame.getBuffer();
        Matrix m = new Matrix();
        m.preTranslate(0.5f, 0.5f);
        if (front) m.preScale(-1f, 1f);
        m.preRotate(-sensorOrientation);
        m.preTranslate(-0.5f, -0.5f);
        VideoFrame.TextureBuffer modified = buffer.applyTransformMatrix(m, buffer.getWidth(), buffer.getHeight());
        VideoFrame out = new VideoFrame(modified, frameRotation(), frame.getTimestampNs());
        observer.onFrameCaptured(out);
        out.release();
    }

    private void close() {
        generation++;
        helper.stopListening();
        if (session != null) {
            try {
                session.stopRepeating();
            } catch (CameraAccessException | RuntimeException ignored) {
                // Session already gone.
            }
            session.close();
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    private void fail(String message) {
        Log.e(TAG, message);
        boolean wasRunning = running;
        running = false;
        close();
        if (!reportedStart) {
            reportedStart = true;
            observer.onCapturerStarted(false);
        } else if (wasRunning) {
            observer.onCapturerStopped();
        }
    }

    private void runAndWait(Runnable r) throws InterruptedException {
        if (Looper.myLooper() == handler.getLooper()) {
            r.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        handler.post(() -> {
            try {
                r.run();
            } finally {
                done.countDown();
            }
        });
        done.await();
    }

    // ------------------------------------------------------------ choices

    @SuppressWarnings("deprecation")
    private int frameRotation() {
        WindowManager wm = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        int rotation;
        switch (wm.getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: rotation = 90; break;
            case Surface.ROTATION_180: rotation = 180; break;
            case Surface.ROTATION_270: rotation = 270; break;
            default: rotation = 0;
        }
        if (!front) rotation = 360 - rotation;
        return (sensorOrientation + rotation) % 360;
    }

    /** Same rule as WebRTC: closest width + height to the request. */
    private static Size chooseSize(Size[] sizes, int w, int h) {
        Size best = sizes[0];
        int bestDiff = Integer.MAX_VALUE;
        for (Size s : sizes) {
            int diff = Math.abs(s.getWidth() - w) + Math.abs(s.getHeight() - h);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }

    /**
     * Highest-ceiling range whose floor is between {@code minFps} and 24; falls back to the
     * widest range reaching {@code fps} (WebRTC's behaviour).
     */
    private Range<Integer> chooseFpsRange(Range<Integer>[] ranges) {
        // Some legacy HALs report fps x 1000.
        int unit = 1;
        for (Range<Integer> r : ranges) if (r.getUpper() >= 1000) unit = 1000;
        int target = Math.max(1, fps);
        // Floor between minFps and 24: higher floors (e.g. fixed 30) leave too little exposure
        // time and make dim rooms visibly darker.
        int floorMin = Math.min(minFps, target);
        int floorMax = Math.max(floorMin, Math.min(24, target));
        Range<Integer> best = null;
        if (minFps > 0) {
            for (Range<Integer> r : ranges) {
                int lo = r.getLower() / unit, hi = r.getUpper() / unit;
                if (lo < floorMin || lo > floorMax || hi > target) continue;
                if (best == null || hi > best.getUpper() / unit
                        || (hi == best.getUpper() / unit && lo < best.getLower() / unit)) {
                    best = r;
                }
            }
        }
        if (best == null) {
            for (Range<Integer> r : ranges) {
                int lo = r.getLower() / unit, hi = r.getUpper() / unit;
                if (best == null
                        || Math.abs(hi - target) < Math.abs(best.getUpper() / unit - target)
                        || (Math.abs(hi - target) == Math.abs(best.getUpper() / unit - target)
                        && lo < best.getLower() / unit)) {
                    best = r;
                }
            }
        }
        return best;
    }

    private boolean isFrontFacing(String id) {
        try {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            return facing != null && facing == CameraMetadata.LENS_FACING_FRONT;
        } catch (CameraAccessException e) {
            return false;
        }
    }

    private static boolean contains(int[] values, int v) {
        if (values == null) return false;
        for (int x : values) if (x == v) return true;
        return false;
    }
}
