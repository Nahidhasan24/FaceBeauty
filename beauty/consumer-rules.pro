# MediaPipe tasks use JNI and protobuf-lite reflection.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# MediaPipe logs through Flogger, which finds its backend by reflection. Without this, optimized
# release builds fail in Graph.<clinit> (ExceptionInInitializerError) and no face is ever detected.
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**
