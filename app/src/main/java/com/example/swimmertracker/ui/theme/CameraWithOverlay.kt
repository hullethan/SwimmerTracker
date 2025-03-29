package com.example.swimmertracker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.ViewGroup
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
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
import java.util.UUID

// Swimmer data class
data class Swimmer(
    val id: UUID = UUID.randomUUID(),
    var box: Rect,
    var status: String,
    var lastSeen: Long = System.currentTimeMillis(),
    var submergedStart: Long? = null,
    var alertTriggered: Boolean = false
)

@Composable
fun CameraWithOverlay(objectDetector: ObjectDetector) {
    var swimmers by remember { mutableStateOf<List<Swimmer>>(emptyList()) }
    var isMenuExpanded by remember { mutableStateOf(false) }

    val personDetected = swimmers.size
    val swimmerDetected = swimmers.count { it.status != "OUT_OF_POOL" }
    val submerged = swimmers.count { it.status == "SUBMERGED" }
    val alertDetected = swimmers.count { it.alertTriggered }
    val animalDetected = 0

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(objectDetector) { updatedSwimmers ->
            swimmers = updatedSwimmers
        }

        BoundingBoxOverlay(swimmers)

        FloatingActionButton(
            onClick = { isMenuExpanded = !isMenuExpanded },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            containerColor = Color.White.copy(alpha = 0.15f),
            contentColor = Color.Black,
            shape = RectangleShape
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Open Menu"
            )
        }

        if (isMenuExpanded) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 72.dp, start = 16.dp)
            ) {
                Text("Settings", color = Color.Black)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Live Detection Stats", color = Color.Black, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Person Detected: $personDetected", color = Color.Black)
                Text("Animal Detected: $animalDetected", color = Color.Black)
                Text("Swimmer Detected: $swimmerDetected", color = Color.Black)
                Text("Submerged: $submerged", color = Color.Black)
                Text("ALERT: Possible Drowning Detected: $alertDetected", color = Color.Red)
            }
        }
    }
}

@Composable
fun CameraPreview(objectDetector: ObjectDetector, onDetected: (List<Swimmer>) -> Unit) {
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
                            val swimmers = processObjectDetection(image, objectDetector, previewView, ctx)
                            onDetected(swimmers)
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
    previewView: PreviewView,
    context: Context
): List<Swimmer> {
    val swimmers = mutableListOf<Swimmer>()
    if (objectDetector == null) {
        image.close()
        return swimmers
    }

    try {
        val rotationDegrees = image.imageInfo.rotationDegrees
        val bitmap = image.toBitmap().rotate(rotationDegrees)
        val waterMask = detectWaterRegions(bitmap)

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

                val box = Rect(left, adjustedTop, right, bottom)
                val centerX = ((left + right) / 2).toInt()
                val centerY = ((top + bottom) / 2).toInt()
                val topY = top.toInt()

                val inWater = centerY in waterMask.indices &&
                        centerX in waterMask[0].indices &&
                        waterMask[centerY][centerX]

                val headSubmerged = topY in waterMask.indices &&
                        centerX in waterMask[0].indices &&
                        waterMask[topY][centerX]

                val status = when {
                    !inWater -> "OUT_OF_POOL"
                    headSubmerged -> "SUBMERGED"
                    else -> "ABOVE_WATER"
                }

                val swimmer = Swimmer(box = box, status = status)

                if (status == "SUBMERGED") {
                    if (swimmer.submergedStart == null) {
                        swimmer.submergedStart = System.currentTimeMillis()
                    } else {
                        val elapsed = System.currentTimeMillis() - swimmer.submergedStart!!
                        if (elapsed >= 30000 && !swimmer.alertTriggered) {
                            swimmer.alertTriggered = true

                            // Vibration
                            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(
                                    VibrationEffect.createOneShot(
                                        1000,
                                        VibrationEffect.DEFAULT_AMPLITUDE
                                    )
                                )
                            } else {
                                vibrator.vibrate(1000)
                            }

                            // Sound
                            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2000)
                        }
                    }
                } else {
                    swimmer.submergedStart = null
                    swimmer.alertTriggered = false
                }

                swimmers.add(swimmer)
            }
        }

    } catch (e: Exception) {
        android.util.Log.e("ObjectDetector", "❌ Detection error: ${e.message}", e)
    } finally {
        image.close()
    }

    return swimmers
}

fun detectWaterRegions(bitmap: Bitmap): Array<BooleanArray> {
    val width = bitmap.width
    val height = bitmap.height
    val mask = Array(height) { BooleanArray(width) }

    for (y in 0 until height step 4) {
        for (x in 0 until width step 4) {
            val pixel = bitmap.getPixel(x, y)
            val red = (pixel shr 16) and 0xff
            val green = (pixel shr 8) and 0xff
            val blue = pixel and 0xff

            val isLikelyWater = blue > 100 && blue > red && blue > green
            if (isLikelyWater) {
                for (dy in 0..3) {
                    for (dx in 0..3) {
                        val px = x + dx
                        val py = y + dy
                        if (px < width && py < height) {
                            mask[py][px] = true
                        }
                    }
                }
            }
        }
    }

    return mask
}

@Composable
fun BoundingBoxOverlay(swimmers: List<Swimmer>) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        for (swimmer in swimmers) {
            val color = if (swimmer.alertTriggered) Color.Red else Color.Black
            val box = swimmer.box
            drawRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(box.left, box.top),
                size = androidx.compose.ui.geometry.Size(box.right - box.left, box.bottom - box.top),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f)
            )
        }
    }
}

fun Bitmap.rotate(degrees: Int): Bitmap {
    if (degrees == 0) return this
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
