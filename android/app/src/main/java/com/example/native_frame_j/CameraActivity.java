package com.example.native_frame;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.location.Location;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.CancellationTokenSource;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CameraActivity extends AppCompatActivity {

    private TextureView textureView;
    private ImageButton recordButton;

    private boolean isRecording = false;
    private int frameCounter = 0;
    private File sessionDir = null;

    private ImageReader imageReader;
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;

    private final Semaphore cameraLock = new Semaphore(1);
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private ExecutorService imageSavingExecutor;

    private FusedLocationProviderClient fusedLocationClient;
    private Location currentSessionLocation = null;

    private int sensorOrientation = 0;

    private final int PERMISSION_REQUEST_CODE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        textureView = findViewById(R.id.texture_view);
        recordButton = findViewById(R.id.record_button);

        recordButton.setOnClickListener(v -> toggleRecording());

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
    }

    @SuppressLint("MissingPermission")
    private void toggleRecording() {
        if (!isRecording) {
            CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();
            fusedLocationClient.getCurrentLocation(LocationRequest.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.getToken())
                    .addOnSuccessListener(location -> {
                        currentSessionLocation = location;
                        startRecordingSession();
                        Toast.makeText(this, "Recording started.", Toast.LENGTH_SHORT).show();
                    })
                    .addOnFailureListener(e -> {
                        currentSessionLocation = null;
                        startRecordingSession();
                        Toast.makeText(this, "Recording started (No Location)", Toast.LENGTH_SHORT).show();
                    });
        } else {
            isRecording = false;
            recordButton.setImageResource(R.drawable.ic_record);
            Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show();
        }
    }

    private void startRecordingSession() {
        isRecording = true;
        runOnUiThread(() -> recordButton.setImageResource(R.drawable.ic_stop));
        frameCounter = 0;
        sessionDir = ImageUtils.createSessionDirectory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();

        imageSavingExecutor = new ThreadPoolExecutor(
                0,
                Integer.MAX_VALUE,
                10L,
                TimeUnit.SECONDS,
                new SynchronousQueue<>()
        );

        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(listener);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        imageSavingExecutor.shutdown();
        try {
            if (!imageSavingExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                imageSavingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            imageSavingExecutor.shutdownNow();
        }
        stopBackgroundThread();
        super.onPause();
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBG");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private final TextureView.SurfaceTextureListener listener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {}
    };

    private Size chooseOptimalSize(Size[] choices, int width, int height) {
        List<Size> bigEnough = new ArrayList<>();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * height / width &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        if (!bigEnough.isEmpty()) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            return choices.length > 0 ? choices[choices.length - 1] : new Size(1280, 720);
        }
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (imageReader == null) return;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, imageReader.getHeight(), imageReader.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / imageReader.getHeight(),
                    (float) viewWidth / imageReader.getWidth()
            );
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    private void openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, PERMISSION_REQUEST_CODE);
            return;
        }

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

            Size[] outputSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
            Size captureSize = chooseOptimalSize(outputSizes, 1280, 720);

            imageReader = ImageReader.newInstance(captureSize.getWidth(), captureSize.getHeight(),
                    ImageFormat.YUV_420_888, 5);

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image == null) return;

                try {
                    if (isRecording && frameCounter % 6 == 5 && sessionDir != null) {
                        double lat = currentSessionLocation != null ? currentSessionLocation.getLatitude() : 0.0;
                        double lon = currentSessionLocation != null ? currentSessionLocation.getLongitude() : 0.0;
                        long timestamp = System.currentTimeMillis();

                        int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
                        int displayRotationDegrees;
                        switch (displayRotation) {
                            case Surface.ROTATION_90:
                                displayRotationDegrees = 90;
                                break;
                            case Surface.ROTATION_180:
                                displayRotationDegrees = 180;
                                break;
                            case Surface.ROTATION_270:
                                displayRotationDegrees = 270;
                                break;
                            default:
                                displayRotationDegrees = 0;
                        }
                        int jpegOrientation = (sensorOrientation - displayRotationDegrees + 360) % 360;

                        byte[] yuvBytes = ImageUtils.imageToNv21(image);
                        imageSavingExecutor.execute(new ImageSaver(
                                yuvBytes,
                                image.getWidth(),
                                image.getHeight(),
                                sessionDir,
                                frameCounter + 1,
                                lat,
                                lon,
                                timestamp,
                                jpegOrientation
                        ));
                    }
                } finally {
                    image.close();
                }
                frameCounter++;
            }, backgroundHandler);

            manager.openCamera(cameraId, stateCallback, backgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice device) {
            cameraLock.release();
            cameraDevice = device;
            createPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice device) {
            cameraLock.release();
            device.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice device, int error) {
            cameraLock.release();
            device.close();
            cameraDevice = null;
            finish();
        }
    };

    private void createPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(imageReader.getWidth(), imageReader.getHeight());
            Surface surface = new Surface(texture);

            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(surface);
            builder.addTarget(imageReader.getSurface());

            cameraDevice.createCaptureSession(
                    java.util.Arrays.asList(surface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            captureSession = session;
                            try {
                                configureTransform(textureView.getWidth(), textureView.getHeight());
                                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                                session.setRepeatingRequest(builder.build(), null, backgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Toast.makeText(CameraActivity.this, "Unable to start preview", Toast.LENGTH_SHORT).show();
                        }
                    },
                    backgroundHandler
            );

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        try {
            cameraLock.acquire();
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            cameraLock.release();
        }
    }

    private static class ImageSaver implements Runnable {
        private final byte[] yuvBytes;
        private final int width;
        private final int height;
        private final File sessionDir;
        private final int frameIndex;
        private final double latitude;
        private final double longitude;
        private final long timestamp;
        private final int rotationDegrees;

        public ImageSaver(byte[] yuvBytes, int width, int height, File sessionDir, int frameIndex,
                          double latitude, double longitude, long timestamp, int rotationDegrees) {
            this.yuvBytes = yuvBytes;
            this.width = width;
            this.height = height;
            this.sessionDir = sessionDir;
            this.frameIndex = frameIndex;
            this.latitude = latitude;
            this.longitude = longitude;
            this.timestamp = timestamp;
            this.rotationDegrees = rotationDegrees;
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                android.graphics.Bitmap bmp = ImageUtils.yuvToBitmap(yuvBytes, width, height);
                if (bmp != null) {
                    ImageUtils.saveBitmapWithMetadata(bmp, sessionDir, frameIndex, latitude, longitude, timestamp, rotationDegrees);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
