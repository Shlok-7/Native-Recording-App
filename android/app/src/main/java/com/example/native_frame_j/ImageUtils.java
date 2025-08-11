package com.example.native_frame;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Environment;

import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class ImageUtils {

    public static File createSessionDirectory() {
        File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File sessionFolder = new File(picturesDir, "Session_" + timeStamp);

        if (!sessionFolder.exists()) {
            sessionFolder.mkdirs();
        }
        return sessionFolder;
    }

    public static byte[] imageToNv21(Image image) {
        Image.Plane[] planes = image.getPlanes();

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        int ySize = planes[0].getBuffer().remaining();
        int uSize = planes[1].getBuffer().remaining();
        int vSize = planes[2].getBuffer().remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        planes[0].getBuffer().get(nv21, 0, ySize);
        planes[2].getBuffer().get(nv21, ySize, vSize);
        planes[1].getBuffer().get(nv21, ySize + vSize, uSize);

        return nv21;
    }

    public static Bitmap yuvToBitmap(byte[] yuvBytes, int width, int height) {
        try {
            YuvImage yuvImage = new YuvImage(yuvBytes, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 100, out);
            byte[] jpegBytes = out.toByteArray();
            return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Saves a bitmap with EXIF metadata (timestamp, GPS coordinates, orientation).
     */
    public static void saveBitmapWithMetadata(Bitmap bitmap, File dir, int index,
                                              double latitude, double longitude,
                                              long timestamp, int rotationDegrees) {
        String filename = String.format(Locale.getDefault(), "frame_%03d.jpg", index);
        File file = new File(dir, filename);

        try {
            // Step 1: Save the bitmap to a file as JPEG
            FileOutputStream out = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
            out.close();

            // Step 2: Add EXIF metadata
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());

            // Set capture datetime in UTC
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault());
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String dateTime = sdf.format(new Date(timestamp));

            exif.setAttribute(ExifInterface.TAG_DATETIME, dateTime);
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime);
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateTime);

            // Set orientation
            int exifOrientation;
            switch (rotationDegrees) {
                case 90:
                    exifOrientation = ExifInterface.ORIENTATION_ROTATE_90;
                    break;
                case 180:
                    exifOrientation = ExifInterface.ORIENTATION_ROTATE_180;
                    break;
                case 270:
                    exifOrientation = ExifInterface.ORIENTATION_ROTATE_270;
                    break;
                default:
                    exifOrientation = ExifInterface.ORIENTATION_NORMAL;
            }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(exifOrientation));

            // Set GPS data if available
            if (latitude != 0.0 || longitude != 0.0) {
                exif.setLatLong(latitude, longitude);
                exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, "GPS");

                // Set GPS date and time
                SimpleDateFormat gpsDateFormat = new SimpleDateFormat("yyyy:MM:dd", Locale.getDefault());
                gpsDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

                SimpleDateFormat gpsTimeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
                gpsTimeFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

                exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, gpsDateFormat.format(new Date(timestamp)));
                exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, gpsTimeFormat.format(new Date(timestamp)));
            }

            exif.saveAttributes();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
