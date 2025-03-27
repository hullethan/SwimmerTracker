package com.example.swimmertracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.swimmertracker.ui.theme.SwimmerTrackerTheme
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.swimmertracker.ui.theme.HomeScreen

import navigation.Screen
import com.example.swimmertracker.ui.CameraWithOverlay






class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private var objectDetector: ObjectDetector? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        requestCameraPermission()
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupDetectorAndCamera()
        } else {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) setupDetectorAndCamera()
                else Log.e("MainActivity", "❌ Camera permission denied.")
            }.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupDetectorAndCamera() {
        initializeObjectDetector(this) { detector ->
            objectDetector = detector
            setContent {
                SwimmerTrackerTheme {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = Screen.Home.route) {
                        composable(Screen.Home.route) {
                            HomeScreen(onStartClick = {
                                navController.navigate(Screen.Camera.route)
                            })
                        }
                        composable(Screen.Camera.route) {
                            CameraWithOverlay(objectDetector = detector)
                        }
                    }
                }
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        objectDetector?.close()
    }

    private fun initializeObjectDetector(context: Context, onReady: (ObjectDetector) -> Unit) {
        try {
            val modelFileName = "efficientdet_lite0.tflite"
            val modelFile = copyAssetToCache(context, modelFileName)

            if (modelFile == null || !modelFile.exists()) {
                Log.e("ObjectDetector", "❌ Model file is missing or failed to copy!")
                return
            }

            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelFile.absolutePath)
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setMaxResults(5)
                .setScoreThreshold(0.5f)
                .setRunningMode(RunningMode.IMAGE)
                .build()

            val detector = ObjectDetector.createFromOptions(context, options)
            onReady(detector)

        } catch (e: Exception) {
            Log.e("ObjectDetector", "❌ Initialization failed: ${e.localizedMessage}", e)
        }
    }

    private fun copyAssetToCache(context: Context, fileName: String): File? {
        return try {
            val cacheFile = File(context.cacheDir, fileName)
            context.assets.open(fileName).use { inputStream ->
                FileOutputStream(cacheFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            cacheFile
        } catch (e: Exception) {
            Log.e("FileCopy", "❌ Error copying model file: ${e.message}", e)
            null
        }
    }
}

@Composable
fun CameraPreview(objectDetector: ObjectDetector, onDetected: (List<Rect>) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalContext.current as LifecycleOwner
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(factory = { ctx ->
        val previewView = androidx.camera.view.PreviewView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { image ->
                            val boxes = processObjectDetection(image, objectDetector, previewView)
                            onDetected(boxes)
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("CameraX", "❌ Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(ctx))

        previewView
    })
}

fun processObjectDetection(
    image: ImageProxy,
    objectDetector: ObjectDetector?,
    previewView: androidx.camera.view.PreviewView
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
                Log.d("Detection", "✅ Person: ($left, $adjustedTop) to ($right, $bottom)")
            }
        }

    } catch (e: Exception) {
        Log.e("ObjectDetector", "❌ Detection error: ${e.message}", e)
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
