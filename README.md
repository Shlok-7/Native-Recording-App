# Native Frame Capture – Flutter + Java (Camera2 API with EXIF & GPS Metadata)

This project is a **Flutter mobile application** that integrates native **Java Android code** to capture images using the **Camera2 API**, embed **GPS location data**, timestamps, and orientation information into the image's **EXIF metadata**, and save them in organized session folders.

---

## 📸 Features
- **Camera2 API** native implementation in Java for high-performance image capture.
- Embeds **GPS latitude & longitude** into image EXIF metadata.
- Adds **timestamp** and **device orientation** tags.
- Organizes captured images into **timestamped session directories** inside `Pictures/`.
- Flutter UI integration with method channel to launch native Android camera activity.
- Works on **Android 7+** (API 24 and above).

---

## 🛠 Tech Stack
- **Flutter** (Dart) for cross-platform UI.
- **Java (Android)** for Camera2 & EXIF handling.
- **Google Play Services Location API** for GPS coordinates.
- **ExifInterface** for metadata writing.
- **ExecutorService** for background image saving.

---

## 📂 Project Structure
project-root/
│
├── lib/
│ ├── main.dart # Flutter app entry point
│ ├── home_screen.dart # Home screen UI
│ └── camera_service.dart # Flutter service to trigger native camera
│
├── android/
│ ├── app/src/main/java/com/example/native_frame/
│ │ ├── MainActivity.java # Flutter bridge to native Java code
│ │ ├── CameraActivity.java # Handles camera preview, capture, location
│ │ └── ImageUtils.java # Image processing & EXIF metadata
│ └── app/src/main/AndroidManifest.xml
│
└── pubspec.yaml


---

## ⚙️ Permissions
The app requires these Android permissions:
- `CAMERA`
- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`
- `READ_EXTERNAL_STORAGE`
- `WRITE_EXTERNAL_STORAGE` *(for Android 10 and below)*
- `READ_MEDIA_IMAGES` *(for Android 13+)*

---

## 🚀 How It Works
1. The **Flutter UI** calls a native method via a **MethodChannel**.
2. `MainActivity.java` launches `CameraActivity.java` (Java).
3. When the user taps the record button:
   - App fetches **current GPS location**.
   - Starts capturing frames from the Camera2 API.
4. Every few frames:
   - Frame is converted from YUV → JPEG.
   - Metadata (GPS, timestamp, orientation) is embedded using `ImageUtils.java`.
5. Images are saved into:
/Pictures/Session_YYYYMMDD_HHMMSS/

