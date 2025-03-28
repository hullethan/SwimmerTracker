package com.example.swimmertracker.ui

import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.swimmertracker.R
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import android.graphics.Matrix
import android.view.ViewGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.graphics.RectangleShape

@Composable
fun CameraWithOverlay(objectDetector: ObjectDetector) {
    var boundingBoxes by remember { mutableStateOf<List<Rect>>(emptyList()) }
    var isMenuExpanded by remember { mutableStateOf(false) }

    // Dummy stat values (will update with real values later)
    val personDetected = boundingBoxes.size
    val animalDetected = 0
    val swimmerDetected = 0
    val submerged = 0
    val alertDetected = 0

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(objectDetector) { boxes ->
            boundingBoxes = boxes
        }

        BoundingBoxOverlay(boundingBoxes)

        // Floating Action Button
        // FAB with default transparent background
        FloatingActionButton(
            onClick = { isMenuExpanded = !isMenuExpanded },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            containerColor = Color.White.copy(alpha = 0.15f), // subtle transparent background
            contentColor = Color.Black, // icon color
            shape = RectangleShape // square, or use CircleShape for default
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Open Menu"
            )
        }

// Transparent dropdown aligned just under the FAB
        if (isMenuExpanded) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 72.dp, end = 16.dp)
            ) {
                Text("Settings", color = Color.Black)

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Live Detection Stats",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text("Person Detected: $personDetected", color = Color.Black)
                Text("Animal Detected: $animalDetected", color = Color.Black)
                Text("Swimmer Detected: $swimmerDetected", color = Color.Black)
                Text("Submerged: $submerged", color = Color.Black)
                Text(
                    "ALERT: Possible Drowning Detected: $alertDetected",
                    color = Color.Red
                )
            }
        }


    }
}

@Composable
fun CameraPreview(objectDetector: ObjectDetector, onDetected: (List<Rect>) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalContext.current as LifecycleOwner
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(factory = { ctx ->
        val previewView = PreviewView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = androidx.camera.core.Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalyzer = androidx.camera.core.ImageAnalysis.Builder()
                    .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { image ->
                            val boxes = processObjectDetection(image, objectDetector, previewView)
                            onDetected(boxes)
                        }
                    }

                val cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                android.util.Log.e("CameraX", "❌ Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(ctx))

        previewView
    })
}

fun processObjectDetection(
    image: ImageProxy,
    objectDetector: ObjectDetector?,
    previewView: PreviewView
): List<Rect> {
    val boxes = mutableListOf<Rect>()
    if (objectDetector == null) {
        image.close()
        return boxes
    }

    try {
        val rotationDegrees = image.imageInfo.rotationDegrees
        val bitmap = image.toBitmap().rotate(rotationDegrees)
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result: ObjectDetectorResult = objectDetector.detect(mpImage)

        val viewWidth = previewView.width
        val viewHeight = previewView.height

        val scaleX = viewWidth.toFloat() / bitmap.width
        val scaleY = viewHeight.toFloat() / bitmap.height

        for (detection in result.detections()) {
            val category = detection.categories().firstOrNull()
            if (category != null && category.categoryName().equals("person", ignoreCase = true)) {
                val bbox = detection.boundingBox()

                val left = bbox.left * scaleX
                val top = bbox.top * scaleY
                val right = bbox.right * scaleX
                val bottom = bbox.bottom * scaleY

                val isLandscape = viewWidth > viewHeight
                val adjustedTop = if (isLandscape) {
                    (top - 80f).coerceAtLeast(0f)
                } else {
                    top
                }

                boxes.add(Rect(left, adjustedTop, right, bottom))
                android.util.Log.d("Detection", "✅ Person: ($left, $adjustedTop) to ($right, $bottom)")
            }
        }

    } catch (e: Exception) {
        android.util.Log.e("ObjectDetector", "❌ Detection error: ${e.message}", e)
    } finally {
        image.close()
    }

    return boxes
}

@Composable
fun BoundingBoxOverlay(boundingBoxes: List<Rect>) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        for (box in boundingBoxes) {
            drawRect(
                color = Color.Black,
                topLeft = androidx.compose.ui.geometry.Offset(box.left, box.top),
                size = androidx.compose.ui.geometry.Size(box.right - box.left, box.bottom - box.top),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f)
            )
        }
    }
}

fun android.graphics.Bitmap.rotate(degrees: Int): android.graphics.Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return android.graphics.Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
