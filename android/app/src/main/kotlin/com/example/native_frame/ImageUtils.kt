package com.example.native_frame

import android.graphics.*
import android.media.Image
import android.os.Environment
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

object ImageUtils {

    fun createSessionDirectory(): File {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val sessionFolder = File(picturesDir, "Session_$timeStamp")

        if (!sessionFolder.exists()) {
            sessionFolder.mkdirs()
        }
        return sessionFolder
    }

    fun imageToNv21(image: Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        return nv21
    }

    fun yuvToBitmap(yuvBytes: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val yuvImage = YuvImage(yuvBytes, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
            val jpegBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * ✅ UPDATED: Fixed time metadata to preserve actual capture time
     */
    fun saveBitmapWithMetadata(bitmap: Bitmap, dir: File, index: Int, latitude: Double, longitude: Double, timestamp: Long, rotationDegrees: Int) {
        val filename = String.format("frame_%03d.jpg", index)
        val file = File(dir, filename)
       
        try {
            // Step 1: Save the bitmap to a file as a JPEG
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            // Step 2: Add EXIF metadata to the saved file
            val exif = ExifInterface(file.absolutePath)
           
            // ✅ FIXED: Use UTC timezone to ensure consistent timestamp across devices
            val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val dateTime = sdf.format(Date(timestamp))
            
            // Set all relevant datetime EXIF tags with the actual capture time
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateTime)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateTime)
           
            // ✅ ADDED: Set the orientation tag based on the calculated rotation.
            val exifOrientation = when (rotationDegrees) {
                0 -> ExifInterface.ORIENTATION_NORMAL
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())

            // Only set GPS data if coordinates are valid
            if (latitude != 0.0 || longitude != 0.0) {
                exif.setLatLong(latitude, longitude)
                exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, "GPS")
                
                // ✅ ADDED: Set GPS timestamp to match capture time
                val gpsDateFormat = SimpleDateFormat("yyyy:MM:dd", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val gpsTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, gpsDateFormat.format(Date(timestamp)))
                exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, gpsTimeFormat.format(Date(timestamp)))
            }
           
            exif.saveAttributes()
           
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}