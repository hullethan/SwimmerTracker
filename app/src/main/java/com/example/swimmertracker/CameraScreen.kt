package com.example.swimmertracker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.camera.core.ImageProxy
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.util.concurrent.ExecutorService
import androidx.camera.lifecycle.ProcessCameraProvider
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.*
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

@Composable
fun CameraWithOverlay(objectDetector: ObjectDetector) {
    var boundingBoxes by remember { mutableStateOf<List<Rect>>(emptyList()) }
    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(objectDetector) { boxes -> boundingBoxes = boxes }
        BoundingBoxOverlay(boundingBoxes)
    }
}
