package com.facebeauty;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Performance knobs. Use {@link #forDevice(Context)} to pick sensible values automatically, or
 * {@link #forTier(Tier)} and then tweak fields. Must be set before the renderer/view starts.
 */
public final class BeautyConfig {

    public enum Tier { LOW, MEDIUM, HIGH }

    public Tier tier;
    /** Faces tracked at once (1 or 2). Each extra face costs a landmark-model run. */
    public int maxFaces;
    /** Long side, in px, of the frame copy handed to the landmark model. */
    public int detectionSize;
    /** Smoothing/statistics passes run at input size divided by this. */
    public float processDownscale;
    /** Target size in px of one warp-mesh cell (smaller = smoother warps, more vertices). */
    public int warpCellPx;
    /**
     * Cap on the preview surface's short side. Larger views render into a smaller buffer that the
     * display hardware scales up for free.
     */
    public int maxRenderShortSide;
    /** Suggested camera stream size (landscape, sensor orientation). */
    public int previewWidth;
    public int previewHeight;
    /**
     * Video pipelines ({@link SyncedBeautyRenderer}) hold each frame until its own landmarks are
     * ready, at most this long. Higher = effects stay aligned even when the model is slow.
     */
    public int maxSyncDelayMs = 250;
    /** Asset path of the MediaPipe face landmarker bundle. */
    public String modelAssetPath = "face_landmarker.task";
    /**
     * Run the landmark model on the GPU. Off by default: on low/mid devices the CPU (XNNPACK)
     * path is as fast and does not compete with the beauty shaders for the GPU.
     */
    public boolean useGpuDelegate;

    public static BeautyConfig forDevice(Context context) {
        return forTier(detectTier(context));
    }

    public static BeautyConfig forTier(Tier tier) {
        BeautyConfig c = new BeautyConfig();
        c.tier = tier;
        switch (tier) {
            case LOW:
                c.maxFaces = 1;
                c.detectionSize = 320;
                c.processDownscale = 4f;
                c.warpCellPx = 16;
                c.maxRenderShortSide = 540;
                c.previewWidth = 1280;
                c.previewHeight = 720;
                break;
            case MEDIUM:
                c.maxFaces = 1;
                c.detectionSize = 416;
                c.processDownscale = 3f;
                c.warpCellPx = 12;
                c.maxRenderShortSide = 720;
                c.previewWidth = 1280;
                c.previewHeight = 720;
                break;
            default:
                c.maxFaces = 2;
                c.detectionSize = 512;
                c.processDownscale = 2f;
                c.warpCellPx = 8;
                c.maxRenderShortSide = 1080;
                c.previewWidth = 1920;
                c.previewHeight = 1080;
                break;
        }
        return c;
    }

    /** Settings for still photos: quality over speed. */
    public static BeautyConfig forPhotos() {
        BeautyConfig c = forTier(Tier.HIGH);
        c.maxFaces = 2;
        c.processDownscale = 2f;
        return c;
    }

    public static Tier detectTier(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long gb = 1L << 30;
        int cores = Runtime.getRuntime().availableProcessors();
        int maxMhz = maxCpuMhz(cores);
        int perfClass = Build.VERSION.SDK_INT >= 31 ? Build.VERSION.MEDIA_PERFORMANCE_CLASS : 0;
        // totalMem reports less than the marketing size (a "4 GB" phone shows ~3.6 GB).
        if (am.isLowRamDevice() || mi.totalMem < 2.5 * gb || cores <= 4
                || (maxMhz > 0 && maxMhz < 1800)) {
            return Tier.LOW;
        }
        // RAM alone is misleading (budget SoCs ship with 6-8 GB); HIGH needs a fast chip too.
        if (mi.totalMem >= 5 * gb && (perfClass >= 31 || maxMhz >= 2400)) return Tier.HIGH;
        return Tier.MEDIUM;
    }

    /** Fastest core's max clock in MHz, or 0 if unreadable. */
    private static int maxCpuMhz(int cores) {
        int best = 0;
        for (int i = 0; i < cores; i++) {
            File f = new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                best = Math.max(best, Integer.parseInt(r.readLine().trim()) / 1000);
            } catch (Exception ignored) {
                // Offline core or restricted sysfs.
            }
        }
        return best;
    }
}
