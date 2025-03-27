package com.example.swimmertracker

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.MPImage // ✅ FIXED IMPORT
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.google.mediapipe.tasks.core.BaseOptions
import java.util.concurrent.Executors

class PoseDetectionHelper(context: Context) {
    private var poseLandmarker: PoseLandmarker? = null

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("pose_landmarker_full.task") // Ensure this is in assets/
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM) // Real-time processing
                .setMinPoseDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            Log.d("PoseDetectionHelper", "Pose Landmarker initialized successfully!")

        } catch (e: Exception) {
            Log.e("PoseDetectionHelper", "Failed to initialize Pose Landmarker: ${e.message}")
        }
    }

    fun detectPose(image: MPImage, callback: (PoseLandmarkerResult?) -> Unit) { // ✅ FIXED FUNCTION PARAMETER
        Executors.newSingleThreadExecutor().execute {
            try {
                val result = poseLandmarker?.detect(image)

                if (result?.landmarks()?.isNotEmpty() == true) {
                    Log.d("PoseDetectionHelper", "Human detected: ${result.landmarks().size} keypoints.")
                } else {
                    Log.d("PoseDetectionHelper", "No human detected.")
                }

                callback(result)
            } catch (e: Exception) {
                Log.e("PoseDetectionHelper", "Error during pose detection: ${e.message}")
                callback(null)
            }
        }
    }

    fun close() {
        poseLandmarker?.close()
        Log.d("PoseDetectionHelper", "Pose Landmarker resources released.")
    }
}
