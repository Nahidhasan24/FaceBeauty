package com.facebeauty.sample;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.facebeauty.BeautyPanelView;
import com.facebeauty.BeautyParams;
import com.facebeauty.livekit.BeautyLiveKit;
import com.facebeauty.livekit.BeautyVideoProcessor;

import io.livekit.android.LiveKit;
import io.livekit.android.LiveKitOverrides;
import io.livekit.android.RoomOptions;
import io.livekit.android.room.Room;
import io.livekit.android.room.track.CameraPosition;
import io.livekit.android.room.track.LocalVideoTrack;
import io.livekit.android.room.track.VideoPreset169;
import livekit.org.webrtc.RendererCommon;
import livekit.org.webrtc.SurfaceViewRenderer;

/**
 * The LiveKit integration in plain Java: beautified camera track, local preview and effect panel.
 * Optionally joins a room and publishes:
 * adb shell am start -n com.facebeauty.sample/.LiveKitJavaActivity --es url wss://... --es token ...
 */
public class LiveKitJavaActivity extends ComponentActivity {

    private static final String TAG = "LiveKitJava";

    private Room room;
    private BeautyVideoProcessor beauty;
    private SurfaceViewRenderer renderer;
    private LocalVideoTrack track;
    private boolean front = true;

    private final ActivityResultLauncher<String> permission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else finish();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        room = LiveKit.INSTANCE.create(getApplicationContext(), new RoomOptions(), new LiveKitOverrides());
        beauty = new BeautyVideoProcessor(this, room.getLkObjects().getEglBase());
        beauty.setParams(BeautyParams.natural());

        renderer = new SurfaceViewRenderer(this);
        room.initVideoRenderer(renderer);
        renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
        renderer.setMirror(true);

        BeautyPanelView panel = new BeautyPanelView(this);
        panel.setParams(BeautyParams.natural());
        panel.setListener(beauty::setParams);
        panel.setCompareListener(original -> beauty.setEnabled(!original));

        Button flip = new Button(this);
        flip.setText("Flip");
        flip.setAllCaps(false);
        flip.setOnClickListener(v -> {
            front = !front;
            if (track != null) track.switchCamera(null, front ? CameraPosition.FRONT : CameraPosition.BACK);
            renderer.setMirror(front);
        });

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams flipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flipLp.gravity = Gravity.END;
        bottom.addView(flip, flipLp);
        bottom.addView(panel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottom.setPadding(0, 0, 0, 32);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(renderer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(bottom, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permission.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        // Front camera, >= 20 fps in dim rooms, beautified before encoding; preview works offline.
        track = BeautyLiveKit.createCameraTrack(this, room, beauty, true, VideoPreset169.H720.getCapture());
        track.addRenderer(renderer);

        String url = getIntent().getStringExtra("url");
        String token = getIntent().getStringExtra("token");
        if (url != null && token != null) joinAndPublish(url, token);
    }

    private void joinAndPublish(String url, String token) {
        BeautyLiveKit.connect(room, url, token, new BeautyLiveKit.Callback<Object>() {
            @Override
            public void onSuccess(Object unused) {
                BeautyLiveKit.publish(room, track, new BeautyLiveKit.Callback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean published) {
                        Toast.makeText(LiveKitJavaActivity.this, "Published: " + published, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(Throwable error) {
                        Log.e(TAG, "Publish failed", error);
                    }
                });
            }

            @Override
            public void onError(Throwable error) {
                Log.e(TAG, "Connect failed", error);
                Toast.makeText(LiveKitJavaActivity.this, "Connect failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (track != null) {
            track.removeRenderer(renderer);
            track.stopCapture();
            track.dispose();
        }
        beauty.dispose();
        renderer.release();
        room.disconnect();
        room.release();
        super.onDestroy();
    }
}
