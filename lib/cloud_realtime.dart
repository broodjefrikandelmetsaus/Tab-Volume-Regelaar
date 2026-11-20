import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'cloud_relay.dart';

/// Low-latency status listener for one already-paired tablet.
/// Commands themselves never travel in this public channel.
class CloudRealtimeChannel {
  CloudRealtimeChannel({
    required this.deviceId,
    required this.onStatus,
    this.onConnectionChanged,
  });

  final String deviceId;
  final void Function(Map<String, dynamic>) onStatus;
  final void Function(bool connected)? onConnectionChanged;

  WebSocket? _socket;
  StreamSubscription<dynamic>? _subscription;
  Timer? _heartbeat;
  Timer? _retry;
  bool _closed = false;
  bool _connecting = false;
  bool _joined = false;
  bool _retryScheduled = false;
  int _ref = 1;
  int _retrySeconds = 2;

  Future<void> start() async {
    _closed = false;
    await _connect();
  }

  Future<void> _connect() async {
    if (_closed || _connecting || _socket != null) return;
    _connecting = true;
    try {
      final uri = Uri.parse('${CloudRelay.projectUrl}/realtime/v1/websocket')
          .replace(
            queryParameters: {
              'apikey': CloudRelay.publishableKey,
              'vsn': '1.0.0',
            },
          );
      final socket = await WebSocket.connect(uri.toString())
          .timeout(const Duration(seconds: 8));
      if (_closed) {
        await socket.close();
        _connecting = false;
        return;
      }
      _socket = socket;
      _connecting = false;
      _subscription = socket.listen(
        _handleFrame,
        onError: (_) => _handleDisconnect(),
        onDone: _handleDisconnect,
        cancelOnError: true,
      );

      final topic = 'realtime:volume:$deviceId';
      final config = {
        'broadcast': {'ack': false, 'self': false},
        'presence': {'enabled': false, 'key': ''},
        'postgres_changes': <dynamic>[],
        'private': false,
      };
      socket.add(
        jsonEncode({
          'topic': topic,
          'event': 'phx_join',
          'payload': {'config': config},
          'ref': '1',
          'join_ref': '1',
        }),
      );
    } catch (_) {
      _connecting = false;
      _handleDisconnect();
    }
  }

  void _handleFrame(dynamic frame) {
    try {
      final message = jsonDecode(frame.toString());
      if (message is! Map) return;
      final event = message['event']?.toString();

      if (event == 'phx_reply') {
        final payload = message['payload'];
        if (payload is Map && payload['status'] == 'ok') {
          _joined = true;
          _retrySeconds = 2;
          _heartbeat?.cancel();
          _heartbeat = Timer.periodic(
            const Duration(seconds: 20),
            (_) => _sendHeartbeat(),
          );
          onConnectionChanged?.call(true);
        } else {
          _handleDisconnect();
        }
        return;
      }

      if (event == 'broadcast') {
        final payload = message['payload'];
        if (payload is! Map || payload['event'] != 'status_updated') return;
        final data = payload['payload'];
        if (data is Map) {
          onStatus(Map<String, dynamic>.from(data));
        }
      } else if (event == 'phx_error' || event == 'phx_close') {
        _handleDisconnect();
      }
    } catch (_) {
      // Ignore invalid frames; reconnection is handled by the socket lifecycle.
    }
  }

  void _sendHeartbeat() {
    final socket = _socket;
    if (_closed || !_joined || socket == null) return;
    _ref += 1;
    socket.add(
      jsonEncode({
        'topic': 'phoenix',
        'event': 'heartbeat',
        'payload': <String, dynamic>{},
        'ref': '$_ref',
      }),
    );
  }

  void _handleDisconnect() {
    _joined = false;
    _heartbeat?.cancel();
    _heartbeat = null;
    _socket = null;
    _subscription = null;
    onConnectionChanged?.call(false);
    if (_closed || _retryScheduled) return;
    _retryScheduled = true;
    final deadSocket = _socket;
    _socket = null;
    try {
      deadSocket?.close();
    } catch (_) {}
    final delay = _retrySeconds;
    _retrySeconds = (_retrySeconds * 2).clamp(2, 30);
    _retry = Timer(Duration(seconds: delay), () {
      _retry = null;
      _retryScheduled = false;
      _connect();
    });
  }

  Future<void> close() async {
    _closed = true;
    _joined = false;
    _retry?.cancel();
    _heartbeat?.cancel();
    _retry = null;
    _heartbeat = null;
    _retryScheduled = false;
    final subscription = _subscription;
    _subscription = null;
    await subscription?.cancel();
    final socket = _socket;
    _socket = null;
    await socket?.close(WebSocketStatus.normalClosure, 'Controller paused');
    onConnectionChanged?.call(false);
  }
}

[executed on device: PC-van-Jake (1997e53f-651c-4a6a-959d-b2acccb4b133)]