package com.example.native_frame

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.location.Location
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.File
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.Comparator

class CameraActivity : AppCompatActivity() {

    private lateinit var textureView: TextureView
    private lateinit var recordButton: ImageButton

    private var isRecording = false
    private var frameCounter = 0
    private var sessionDir: File? = null

    private lateinit var imageReader: ImageReader
    private lateinit var cameraId: String
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    private val cameraLock = Semaphore(1)
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private lateinit var imageSavingExecutor: ExecutorService

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentSessionLocation: Location? = null
   
    private var sensorOrientation = 0

    private val PERMISSION_REQUEST_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        textureView = findViewById(R.id.texture_view)
        recordButton = findViewById(R.id.record_button)
        recordButton.setOnClickListener { toggleRecording() }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        if (!isRecording) {
            val cancellationTokenSource = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(LocationRequest.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                .addOnSuccessListener { location: Location? ->
                    currentSessionLocation = location
                    isRecording = true
                    runOnUiThread { recordButton.setImageResource(R.drawable.ic_stop) }
                    Toast.makeText(this, "Recording started.", Toast.LENGTH_SHORT).show()
                    frameCounter = 0
                    sessionDir = ImageUtils.createSessionDirectory()
                }.addOnFailureListener {
                    currentSessionLocation = null
                    isRecording = true
                    runOnUiThread { recordButton.setImageResource(R.drawable.ic_stop) }
                    Toast.makeText(this, "Recording started (No Location)", Toast.LENGTH_SHORT).show()
                    frameCounter = 0
                    sessionDir = ImageUtils.createSessionDirectory()
                }
        } else {
            isRecording = false
            recordButton.setImageResource(R.drawable.ic_record)
            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
        }
    }


    override fun onResume() {
        super.onResume()
        startBackgroundThread()
       
        imageSavingExecutor = ThreadPoolExecutor(0, Integer.MAX_VALUE, 10L, TimeUnit.SECONDS, SynchronousQueue<Runnable>())

        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = listener
        }
    }

    override fun onPause() {
        closeCamera()
        imageSavingExecutor.shutdown()
        try {
            if (!imageSavingExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                imageSavingExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            imageSavingExecutor.shutdownNow()
        }
        stopBackgroundThread()
        super.onPause()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBG").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private val listener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) { openCamera() }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) { configureTransform(width, height) }
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int): Size {
        val bigEnough = choices.filter { it.height == it.width * height / width && it.width >= width && it.height >= height }
        return if (bigEnough.isNotEmpty()) Collections.min(bigEnough, CompareSizesByArea()) else choices.lastOrNull() ?: Size(1280, 720)
    }

    class CompareSizesByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size) =
            java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
    }
   
    /**
     * ✅ CORRECTED: This function now correctly calculates the transformation for the TextureView.
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        val rotation = display?.rotation ?: return
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, imageReader.height.toFloat(), imageReader.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = (viewHeight.toFloat() / imageReader.height).coerceAtLeast(viewWidth.toFloat() / imageReader.width)
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180f, centerX, centerY)
        }
        textureView.setTransform(matrix)
    }


    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQUEST_CODE)
            return
        }

        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            cameraId = manager.cameraIdList[0]
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
           
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
           
            val outputSizes = map.getOutputSizes(ImageFormat.YUV_420_888)
            val captureSize = chooseOptimalSize(outputSizes, 1280, 720)

            imageReader = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.YUV_420_888, 5)

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                try {
                    if (isRecording && frameCounter % 6 == 5 && sessionDir != null) {
                        val lat = currentSessionLocation?.latitude ?: 0.0
                        val lon = currentSessionLocation?.longitude ?: 0.0
                        val timestamp = System.currentTimeMillis()
                       
                        // ✅ CORRECTED: This formula now correctly calculates the final image rotation.
                        val displayRotation = display?.rotation ?: Surface.ROTATION_0
                        val displayRotationDegrees = when(displayRotation) {
                            Surface.ROTATION_0 -> 0
                            Surface.ROTATION_90 -> 90
                            Surface.ROTATION_180 -> 180
                            Surface.ROTATION_270 -> 270
                            else -> 0
                        }
                        val jpegOrientation = (sensorOrientation - displayRotationDegrees + 360) % 360
                       
                        val yuvBytes = ImageUtils.imageToNv21(image)
                        imageSavingExecutor.execute(
                            ImageSaver(yuvBytes, image.width, image.height, sessionDir!!, frameCounter + 1, lat, lon, timestamp, jpegOrientation)
                        )
                    }
                } finally {
                    image.close()
                }
                frameCounter++
            }, backgroundHandler)

            manager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }
   
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                openCamera()
            } else {
                Toast.makeText(this, "Camera and Location permissions are required.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraLock.release()
            cameraDevice = device
            createPreviewSession()
        }
        override fun onDisconnected(device: CameraDevice) {
            cameraLock.release()
            device.close()
            cameraDevice = null
        }
        override fun onError(device: CameraDevice, error: Int) {
            cameraLock.release()
            device.close()
            cameraDevice = null
            finish()
        }
    }

    private fun createPreviewSession() {
        try {
            val texture = textureView.surfaceTexture!!
            texture.setDefaultBufferSize(imageReader.width, imageReader.height)
            val surface = Surface(texture)

            val builder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                addTarget(imageReader.surface)
            }
           
            cameraDevice!!.createCaptureSession(listOf(surface, imageReader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        // Call configureTransform after session is configured
                        configureTransform(textureView.width, textureView.height)
                       
                        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                    } catch (e: CameraAccessException) { e.printStackTrace() }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@CameraActivity, "Unable to start preview", Toast.LENGTH_SHORT).show()
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) { e.printStackTrace() }
    }

    private fun closeCamera() {
        try {
            cameraLock.acquire()
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader.close()
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraLock.release()
        }
    }

    private class ImageSaver(
        private val yuvBytes: ByteArray,
        private val width: Int,
        private val height: Int,
        private val sessionDir: File,
        private val frameIndex: Int,
        private val latitude: Double,
        private val longitude: Double,
        private val timestamp: Long,
        private val rotationDegrees: Int
    ) : Runnable {
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
           
            try {
                val bmp = ImageUtils.yuvToBitmap(yuvBytes, width, height)
               
                if (bmp != null) {
                    ImageUtils.saveBitmapWithMetadata(bmp, sessionDir, frameIndex, latitude, longitude, timestamp, rotationDegrees)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}