import 'package:flutter/services.dart';

class CameraService {
  static const _channel = MethodChannel('com.example.native_frame/camera');

  static Future<void> startCamera() async {
    try {
      await _channel.invokeMethod('startCamera');
    } on PlatformException catch (e) {
      print('Failed to start camera: ${e.message}');
    }
  }
}
