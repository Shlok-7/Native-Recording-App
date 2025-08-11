import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import '../services/camera_service.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({Key? key}) : super(key: key);

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  String _status = 'Idle';

  Future<void> _requestPermissions() async {
    final statuses = await [
      Permission.camera,
      Permission.storage,
      Permission.photos, // for iOS fallback
    ].request();

    if (statuses[Permission.camera]!.isGranted &&
        (statuses[Permission.storage]!.isGranted ||
         statuses[Permission.photos]!.isGranted)) {
      setState(() {
        _status = 'Permissions Granted';
      });
    } else {
      setState(() {
        _status = 'Permissions Denied';
      });
    }
  }

  Future<void> _startCamera() async {
    await CameraService.startCamera();
    setState(() {
      _status = 'Camera Started';
    });
  }

  @override
  void initState() {
    super.initState();
    _requestPermissions();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Frame Capture Home')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(_status),
            const SizedBox(height: 20),
            ElevatedButton(
              onPressed: _startCamera,
              child: const Text('Start Camera'),
            ),
          ],
        ),
      ),
    );
  }
}
