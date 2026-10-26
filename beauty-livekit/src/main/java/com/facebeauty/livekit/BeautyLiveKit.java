package com.facebeauty.livekit;

import android.content.Context;

import io.livekit.android.ConnectOptions;
import io.livekit.android.room.Room;
import io.livekit.android.room.participant.VideoTrackPublishOptions;
import io.livekit.android.room.track.CameraPosition;
import io.livekit.android.room.track.LocalVideoTrack;
import io.livekit.android.room.track.LocalVideoTrackOptions;
import io.livekit.android.room.track.VideoCaptureParameter;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.Deferred;

/**
 * Java-friendly helpers for LiveKit, whose API is Kotlin-first (default arguments, suspend
 * functions). Kotlin apps can call LiveKit directly instead.
 *
 * <pre>
 * BeautyVideoProcessor beauty = new BeautyVideoProcessor(context, room.getLkObjects().getEglBase());
 * LocalVideoTrack track = BeautyLiveKit.createCameraTrack(context, room, beauty, true,
 *         VideoPreset169.H720.getCapture());
 * BeautyLiveKit.connect(room, url, token, new BeautyLiveKit.Callback&lt;Object&gt;() {
 *     public void onSuccess(Object unused) { BeautyLiveKit.publish(room, track, null); }
 *     public void onError(Throwable error) { ... }
 * });
 * </pre>
 */
public final class BeautyLiveKit {

    /** Result of an asynchronous LiveKit call, delivered on the main thread. */
    public interface Callback<T> {
        void onSuccess(T result);

        void onError(Throwable error);
    }

    private BeautyLiveKit() {
    }

    /**
     * Creates a camera track that captures with {@link BeautyCameraCapturer} and is beautified by
     * {@code beauty}, and starts capturing (local preview works before connecting).
     *
     * @param capture e.g. {@code VideoPreset169.H720.getCapture()}
     */
    public static LocalVideoTrack createCameraTrack(Context context, Room room, BeautyVideoProcessor beauty,
                                                    boolean frontCamera, VideoCaptureParameter capture) {
        LocalVideoTrackOptions options = new LocalVideoTrackOptions(
                false, null, frontCamera ? CameraPosition.FRONT : CameraPosition.BACK, capture);
        LocalVideoTrack track = room.getLocalParticipant().createVideoTrack(
                "camera", BeautyCameraCapturer.create(context, frontCamera), options, beauty);
        track.startCapture();
        return track;
    }

    /** {@code room.connect(url, token)} for Java. */
    public static void connect(Room room, String url, String token, Callback<Object> callback) {
        run((scope, continuation) -> {
            try {
                return room.connect(url, token, new ConnectOptions(), continuation);
            } catch (Exception e) {
                throw BeautyLiveKit.<RuntimeException>rethrow(e); // the original error reaches onError
            }
        }, callback);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> E rethrow(Throwable t) throws E {
        throw (E) t;
    }

    /** {@code localParticipant.publishVideoTrack(track)} with the room's publish defaults, for Java. */
    public static void publish(Room room, LocalVideoTrack track, Callback<Boolean> callback) {
        VideoTrackPublishOptions options = new VideoTrackPublishOptions(
                null, room.getLocalParticipant().getVideoTrackPublishDefaults(), null, null);
        run((scope, continuation) -> room.getLocalParticipant().publishVideoTrack(track, options, null, continuation),
                callback);
    }

    @SuppressWarnings("unchecked")
    private static <T> void run(kotlin.jvm.functions.Function2<kotlinx.coroutines.CoroutineScope,
            kotlin.coroutines.Continuation<? super Object>, Object> block, Callback<T> callback) {
        // Run the suspend call on the main dispatcher and report its result there.
        Deferred<Object> job = BuildersKt.async(CoroutineScopeKt.MainScope(), EmptyCoroutineContext.INSTANCE,
                CoroutineStart.DEFAULT, block);
        job.invokeOnCompletion(cause -> {
            if (callback != null) {
                if (cause != null) callback.onError(cause);
                else callback.onSuccess((T) job.getCompleted());
            }
            return kotlin.Unit.INSTANCE;
        });
    }
}
