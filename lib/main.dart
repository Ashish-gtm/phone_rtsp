import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const RtspCameraApp());
}

class RtspCameraApp extends StatelessWidget {
  const RtspCameraApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Phone RTSP',
      // debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF36D6A0),
          brightness: Brightness.dark,
        ),
        scaffoldBackgroundColor: const Color(0xFF0B1118),
        useMaterial3: true,
      ),
      home: const StreamHomePage(),
    );
  }
}

class StreamHomePage extends StatefulWidget {
  const StreamHomePage({super.key});

  @override
  State<StreamHomePage> createState() => _StreamHomePageState();
}

class _StreamHomePageState extends State<StreamHomePage> {
  static const _streamer = MethodChannel('phone_rtsp/streamer');
  static const _events = EventChannel('phone_rtsp/events');

  StreamSubscription<Object?>? _subscription;
  String _state = 'stopped';
  String? _message;
  String? _address;
  bool _frontCamera = false;
  bool _busy = false;
  bool _showPassword = false;
  String _resolution = '1920x1080';
  int _frameRate = 25;
  double _bitrateMbps = 4;
  final _usernameController = TextEditingController();
  final _passwordController = TextEditingController();

  bool get _isActive => _state == 'starting' || _state == 'live';
  String get _mainUrl => _streamUrl('live');
  String get _subUrl => _streamUrl('sub');

  String _streamUrl(String path) =>
      _address == null ? 'Waiting for Wi-Fi' : 'rtsp://$_address:8554/$path';

  @override
  void initState() {
    super.initState();
    _subscription = _events.receiveBroadcastStream().listen(
      _onEvent,
      onError: (Object error) {
        if (error is MissingPluginException) return;
        if (mounted) {
          setState(() {
            _state = 'error';
            _message = error.toString();
          });
        }
      },
    );
    _refreshStatus();
  }

  @override
  void dispose() {
    _subscription?.cancel();
    _usernameController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  void _onEvent(Object? event) {
    if (!mounted || event is! Map) return;
    setState(() {
      _state = event['state'] as String? ?? _state;
      _message = event['message'] as String?;
      _address = event['address'] as String? ?? _address;
      _frontCamera = event['frontCamera'] as bool? ?? _frontCamera;
      final width = event['width'];
      final height = event['height'];
      if (width is int && height is int) {
        final resolution = '${width}x$height';
        if (const ['1920x1080', '1280x720', '640x480'].contains(resolution)) {
          _resolution = resolution;
        }
      }
      final frameRate = event['frameRate'];
      if (frameRate is int && const [10, 15, 20, 25, 30].contains(frameRate)) {
        _frameRate = frameRate;
      }
      final bitRate = event['bitRate'];
      if (bitRate is num) _bitrateMbps = bitRate / 1000000;
    });
  }

  Future<void> _refreshStatus() async {
    try {
      final status = await _streamer.invokeMapMethod<String, Object?>(
        'getStatus',
      );
      if (!mounted || status == null) return;
      _onEvent(status);
    } on PlatformException catch (error) {
      if (mounted) setState(() => _message = error.message);
    } on MissingPluginException {
      // Widget tests do not install the native platform channel.
    }
  }

  Future<void> _toggleStream() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _message = null;
    });
    try {
      if (_isActive) {
        await _streamer.invokeMethod<void>('stop');
      } else {
        await _streamer.invokeMethod<void>('start', <String, Object>{
          'frontCamera': _frontCamera,
          'username': _usernameController.text.trim(),
          'password': _passwordController.text,
          'width': int.parse(_resolution.split('x').first),
          'height': int.parse(_resolution.split('x').last),
          'frameRate': _frameRate,
          'bitRate': (_bitrateMbps * 1000000).round(),
        });
      }
      await _refreshStatus();
    } on PlatformException catch (error) {
      if (mounted) {
        setState(() {
          _state = 'error';
          _message = error.message ?? 'Unable to control the camera stream.';
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _switchCamera() async {
    if (_busy || _isActive) return;
    setState(() => _frontCamera = !_frontCamera);
  }

  Future<void> _copyUrl(String url) async {
    if (_address == null) return;
    await Clipboard.setData(ClipboardData(text: url));
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(const SnackBar(content: Text('RTSP address copied')));
  }

  InputDecoration _settingsDecoration(String label) => InputDecoration(
    labelText: label,
    filled: true,
    fillColor: const Color(0xFF0B1118),
    border: OutlineInputBorder(
      borderRadius: BorderRadius.circular(14),
      borderSide: BorderSide.none,
    ),
  );

  @override
  Widget build(BuildContext context) {
    final isLive = _state == 'live';
    final isError = _state == 'error';
    final accent = isError
        ? const Color(0xFFFF6B6B)
        : isLive
        ? const Color(0xFF36D6A0)
        : const Color(0xFF8C9BAD);

    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Phone RTSP',
          style: TextStyle(fontWeight: FontWeight.w700),
        ),
        centerTitle: false,
        backgroundColor: Colors.transparent,
        actions: [
          IconButton(
            tooltip: 'Refresh network address',
            onPressed: _refreshStatus,
            icon: const Icon(Icons.refresh_rounded),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 28),
          children: [
            Container(
              padding: const EdgeInsets.all(22),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(28),
                gradient: const LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [Color(0xFF172832), Color(0xFF111923)],
                ),
                border: Border.all(color: Colors.white.withValues(alpha: 0.07)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Container(
                        width: 46,
                        height: 46,
                        decoration: BoxDecoration(
                          color: accent.withValues(alpha: 0.14),
                          borderRadius: BorderRadius.circular(15),
                        ),
                        child: Icon(Icons.videocam_rounded, color: accent),
                      ),
                      const SizedBox(width: 13),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              isLive
                                  ? 'STREAMING'
                                  : _state == 'starting'
                                  ? 'STARTING'
                                  : isError
                                  ? 'STREAM ERROR'
                                  : 'READY TO STREAM',
                              style: TextStyle(
                                color: accent,
                                fontSize: 12,
                                letterSpacing: 1.4,
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              isLive
                                  ? 'Camera is available on your LAN'
                                  : 'H.264 live video for your recorder',
                              style: const TextStyle(color: Color(0xFFB7C3CE)),
                            ),
                          ],
                        ),
                      ),
                      if (isLive)
                        Container(
                          width: 9,
                          height: 9,
                          decoration: const BoxDecoration(
                            color: Color(0xFF36D6A0),
                            shape: BoxShape.circle,
                          ),
                        ),
                    ],
                  ),
                  const SizedBox(height: 28),
                  Container(
                    height: 188,
                    width: double.infinity,
                    decoration: BoxDecoration(
                      color: const Color(0xFF0A1016),
                      borderRadius: BorderRadius.circular(21),
                      border: Border.all(
                        color: Colors.white.withValues(alpha: 0.06),
                      ),
                    ),
                    child: Stack(
                      alignment: Alignment.center,
                      children: [
                        Icon(
                          _frontCamera
                              ? Icons.face_retouching_natural
                              : Icons.camera_alt_rounded,
                          size: 66,
                          color: accent.withValues(alpha: 0.55),
                        ),
                        Positioned(
                          left: 14,
                          top: 13,
                          child: _Tag(label: isLive ? 'LIVE' : 'CAMERA'),
                        ),
                        Positioned(
                          right: 14,
                          bottom: 13,
                          child: Text(
                            _resolution.replaceFirst('x', ' × '),
                            style: TextStyle(
                              color: Colors.white.withValues(alpha: 0.63),
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 18),
                  Row(
                    children: [
                      _Spec(
                        icon: Icons.high_quality_rounded,
                        label: _resolution.split('x').first == '1920'
                            ? '1080p'
                            : _resolution.split('x').first == '1280'
                            ? '720p'
                            : '480p',
                      ),
                      const SizedBox(width: 9),
                      _Spec(
                        icon: Icons.speed_rounded,
                        label: '$_frameRate FPS',
                      ),
                      const SizedBox(width: 9),
                      const _Spec(
                        icon: Icons.movie_creation_outlined,
                        label: 'H.264',
                      ),
                    ],
                  ),
                ],
              ),
            ),
            const SizedBox(height: 18),
            Container(
              padding: const EdgeInsets.fromLTRB(18, 17, 18, 18),
              decoration: BoxDecoration(
                color: const Color(0xFF121A23),
                borderRadius: BorderRadius.circular(22),
                border: Border.all(color: Colors.white.withValues(alpha: 0.06)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Row(
                    children: [
                      Icon(
                        Icons.tune_rounded,
                        size: 18,
                        color: Color(0xFF8C9BAD),
                      ),
                      SizedBox(width: 8),
                      Text(
                        'MAIN STREAM SETTINGS',
                        style: TextStyle(
                          color: Color(0xFF8C9BAD),
                          letterSpacing: 1.2,
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 14),
                  DropdownButtonFormField<String>(
                    initialValue: _resolution,
                    decoration: _settingsDecoration('Resolution'),
                    items: const [
                      DropdownMenuItem(
                        value: '1920x1080',
                        child: Text('1920 × 1080'),
                      ),
                      DropdownMenuItem(
                        value: '1280x720',
                        child: Text('1280 × 720'),
                      ),
                      DropdownMenuItem(
                        value: '640x480',
                        child: Text('640 × 480'),
                      ),
                    ],
                    onChanged: _isActive || _busy
                        ? null
                        : (value) {
                            if (value != null) {
                              setState(() => _resolution = value);
                            }
                          },
                  ),
                  const SizedBox(height: 12),
                  DropdownButtonFormField<int>(
                    initialValue: _frameRate,
                    decoration: _settingsDecoration('Frame rate'),
                    items: const [10, 15, 20, 25, 30]
                        .map(
                          (fps) => DropdownMenuItem(
                            value: fps,
                            child: Text('$fps FPS'),
                          ),
                        )
                        .toList(),
                    onChanged: _isActive || _busy
                        ? null
                        : (value) {
                            if (value != null) {
                              setState(() => _frameRate = value);
                            }
                          },
                  ),
                  const SizedBox(height: 14),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Text(
                        'Bitrate',
                        style: TextStyle(fontWeight: FontWeight.w600),
                      ),
                      Text(
                        '${_bitrateMbps.toStringAsFixed(_bitrateMbps % 1 == 0 ? 0 : 1)} Mbps',
                        style: const TextStyle(color: Color(0xFF36D6A0)),
                      ),
                    ],
                  ),
                  Slider(
                    value: _bitrateMbps,
                    min: 1,
                    max: 4,
                    divisions: 6,
                    label:
                        '${_bitrateMbps.toStringAsFixed(_bitrateMbps % 1 == 0 ? 0 : 1)} Mbps',
                    onChanged: _isActive || _busy
                        ? null
                        : (value) => setState(() => _bitrateMbps = value),
                  ),
                  const Text(
                    'Substream uses an automatic low-bandwidth profile: up to 640 × 480, 10 FPS, 1 Mbps.',
                    style: TextStyle(color: Color(0xFF8C9BAD), fontSize: 12),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 18),
            Container(
              padding: const EdgeInsets.fromLTRB(18, 17, 18, 18),
              decoration: BoxDecoration(
                color: const Color(0xFF121A23),
                borderRadius: BorderRadius.circular(22),
                border: Border.all(color: Colors.white.withValues(alpha: 0.06)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Row(
                    children: [
                      Icon(
                        Icons.lock_outline_rounded,
                        size: 18,
                        color: Color(0xFF8C9BAD),
                      ),
                      SizedBox(width: 8),
                      Text(
                        'RTSP LOGIN',
                        style: TextStyle(
                          color: Color(0xFF8C9BAD),
                          letterSpacing: 1.2,
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Optional. Set both to require a login from the NVR/DVR.',
                    style: TextStyle(color: Color(0xFF8C9BAD), fontSize: 12),
                  ),
                  const SizedBox(height: 14),
                  TextField(
                    controller: _usernameController,
                    enabled: !_isActive && !_busy,
                    textInputAction: TextInputAction.next,
                    autocorrect: false,
                    decoration: InputDecoration(
                      labelText: 'Username',
                      prefixIcon: const Icon(Icons.person_outline_rounded),
                      filled: true,
                      fillColor: const Color(0xFF0B1118),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(14),
                        borderSide: BorderSide.none,
                      ),
                    ),
                  ),
                  const SizedBox(height: 11),
                  TextField(
                    controller: _passwordController,
                    enabled: !_isActive && !_busy,
                    obscureText: !_showPassword,
                    autocorrect: false,
                    enableSuggestions: false,
                    textInputAction: TextInputAction.done,
                    decoration: InputDecoration(
                      labelText: 'Password',
                      prefixIcon: const Icon(Icons.key_rounded),
                      suffixIcon: IconButton(
                        tooltip: _showPassword
                            ? 'Hide password'
                            : 'Show password',
                        onPressed: () =>
                            setState(() => _showPassword = !_showPassword),
                        icon: Icon(
                          _showPassword
                              ? Icons.visibility_off_rounded
                              : Icons.visibility_rounded,
                        ),
                      ),
                      filled: true,
                      fillColor: const Color(0xFF0B1118),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(14),
                        borderSide: BorderSide.none,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 18),
            Container(
              padding: const EdgeInsets.fromLTRB(18, 17, 12, 17),
              decoration: BoxDecoration(
                color: const Color(0xFF121A23),
                borderRadius: BorderRadius.circular(22),
                border: Border.all(color: Colors.white.withValues(alpha: 0.06)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Row(
                    children: [
                      Icon(
                        Icons.router_rounded,
                        size: 18,
                        color: Color(0xFF8C9BAD),
                      ),
                      SizedBox(width: 8),
                      Text(
                        'RTSP ADDRESSES',
                        style: TextStyle(
                          color: Color(0xFF8C9BAD),
                          letterSpacing: 1.2,
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 5),
                  const Text(
                    'Main stream',
                    style: TextStyle(
                      color: Color(0xFF8C9BAD),
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  Row(
                    children: [
                      Expanded(
                        child: SelectableText(
                          _mainUrl,
                          style: const TextStyle(
                            fontFamily: 'monospace',
                            fontSize: 15,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                      IconButton(
                        tooltip: 'Copy address',
                        onPressed: () => _copyUrl(_mainUrl),
                        icon: const Icon(Icons.copy_rounded, size: 19),
                      ),
                    ],
                  ),
                  const Text(
                    'Substream',
                    style: TextStyle(
                      color: Color(0xFF8C9BAD),
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  Row(
                    children: [
                      Expanded(
                        child: SelectableText(
                          _subUrl,
                          style: const TextStyle(
                            fontFamily: 'monospace',
                            fontSize: 15,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                      IconButton(
                        tooltip: 'Copy substream address',
                        onPressed: () => _copyUrl(_subUrl),
                        icon: const Icon(Icons.copy_rounded, size: 19),
                      ),
                    ],
                  ),
                  const SizedBox(height: 4),
                  const Text(
                    'Set /live in the DVR main-stream field and /sub in its substream field. Use the configured login for both.',
                    style: TextStyle(color: Color(0xFF8C9BAD), fontSize: 12),
                  ),
                ],
              ),
            ),
            if (_message != null) ...[
              const SizedBox(height: 14),
              Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: isError
                      ? const Color(0xFF351C22)
                      : const Color(0xFF302A1A),
                  borderRadius: BorderRadius.circular(15),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(
                      Icons.info_outline_rounded,
                      color: Color(0xFFFFD27A),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        _message!,
                        style: TextStyle(
                          color: isError
                              ? const Color(0xFFFFB1B1)
                              : const Color(0xFFFFDFA3),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
            const SizedBox(height: 18),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _isActive || _busy ? null : _switchCamera,
                    icon: const Icon(Icons.cameraswitch_rounded),
                    label: Text(_frontCamera ? 'Front camera' : 'Back camera'),
                    style: OutlinedButton.styleFrom(
                      minimumSize: const Size.fromHeight(54),
                      foregroundColor: const Color(0xFFDCE5EC),
                      side: BorderSide(
                        color: Colors.white.withValues(alpha: 0.12),
                      ),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(17),
                      ),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: _busy || _state == 'starting' ? null : _toggleStream,
              icon: _busy
                  ? const SizedBox(
                      width: 19,
                      height: 19,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : Icon(
                      _isActive
                          ? Icons.stop_rounded
                          : Icons.wifi_tethering_rounded,
                    ),
              label: Text(_isActive ? 'Stop streaming' : 'Start streaming'),
              style: FilledButton.styleFrom(
                minimumSize: const Size.fromHeight(58),
                backgroundColor: _isActive
                    ? const Color(0xFF9B3B43)
                    : const Color(0xFF27B987),
                foregroundColor: const Color(0xFF06130F),
                textStyle: const TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w800,
                ),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(17),
                ),
              ),
            ),
            const SizedBox(height: 16),
            const Text(
              'Keep this app open and the phone connected to the same LAN as the recorder.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Color(0xFF758495), fontSize: 12),
            ),
          ],
        ),
      ),
    );
  }
}

class _Tag extends StatelessWidget {
  const _Tag({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: const Color(0xFF25343D),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
        child: Text(
          label,
          style: const TextStyle(
            color: Color(0xFFCEE0E8),
            fontSize: 10,
            fontWeight: FontWeight.w800,
            letterSpacing: 0.7,
          ),
        ),
      ),
    );
  }
}

class _Spec extends StatelessWidget {
  const _Spec({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 10),
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.045),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 15, color: const Color(0xFF36D6A0)),
            const SizedBox(width: 5),
            Text(
              label,
              style: const TextStyle(fontSize: 11, fontWeight: FontWeight.w700),
            ),
          ],
        ),
      ),
    );
  }
}
