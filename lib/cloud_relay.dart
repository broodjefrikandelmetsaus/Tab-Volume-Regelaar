import 'dart:convert';
import 'dart:math';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:http/http.dart' as http;

class CloudRelay {
  static const String projectUrl = 'https://nstpzmuimwjbvlvvbrsr.supabase.co';
  static const String publishableKey = 'sb_publishable__jkGIXaG3xYh8123o9BusA_RYneYnex';
  static const FlutterSecureStorage _storage = FlutterSecureStorage();
  static final Uri _endpoint = Uri.parse('$projectUrl/functions/v1/volume-remote');

  static Future<Map<String, dynamic>> _post(Map<String, dynamic> body) async {
    final response = await http.post(
      _endpoint,
      headers: {'Content-Type': 'application/json', 'apikey': publishableKey},
      body: jsonEncode(body),
    ).timeout(const Duration(seconds: 8));
    final decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic>) throw Exception('Ongeldige reactie van de cloudserver');
    if (response.statusCode < 200 || response.statusCode >= 300 || decoded['ok'] == false) {
      throw Exception(decoded['error'] ?? 'Cloudverbinding mislukt (HTTP ${response.statusCode})');
    }
    return decoded;
  }

  static String _randomToken([int length = 32]) {
    final random = Random.secure();
    return base64Url.encode(List<int>.generate(length, (_) => random.nextInt(256))).replaceAll('=', '');
  }

  static String _newUuid() {
    final bytes = List<int>.generate(16, (_) => Random.secure().nextInt(256));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
    return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
  }

  static Future<({String deviceId, String deviceSecret})> deviceCredentials() async {
    var id = await _storage.read(key: 'cloud_device_id');
    var secret = await _storage.read(key: 'cloud_device_secret');
    if (id == null || secret == null) {
      id = _newUuid();
      secret = _randomToken();
      await _storage.write(key: 'cloud_device_id', value: id);
      await _storage.write(key: 'cloud_device_secret', value: secret);
    }
    return (deviceId: id, deviceSecret: secret);
  }

  static Future<void> registerDevice({
    required String deviceId,
    required String deviceSecret,
    required String pairingCode,
    required Map<String, dynamic> status,
  }) async {
    await _post({'action': 'register', 'deviceId': deviceId, 'deviceSecret': deviceSecret, 'pairingCode': pairingCode, 'status': status});
  }

  static Future<Map<String, dynamic>> pollDevice({
    required String deviceId,
    required String deviceSecret,
    required Map<String, dynamic> status,
  }) => _post({'action': 'poll', 'deviceId': deviceId, 'secret': deviceSecret, 'status': status});

  static Future<void> acknowledge({
    required String deviceId,
    required String deviceSecret,
    required int commandId,
  }) async {
    await _post({'action': 'ack', 'deviceId': deviceId, 'secret': deviceSecret, 'commandId': commandId});
  }

  static Future<({String deviceId, String controllerSecret})> pair(String code) async {
    final response = await _post({'action': 'pair', 'pairingCode': code});
    final id = response['deviceId'] as String?;
    final secret = response['controllerSecret'] as String?;
    if (id == null || secret == null) throw Exception('Koppelen is niet gelukt');
    await _storage.write(key: 'cloud_controller_device_id', value: id);
    await _storage.write(key: 'cloud_controller_secret', value: secret);
    return (deviceId: id, controllerSecret: secret);
  }

  static Future<String?> controllerDeviceId() => _storage.read(key: 'cloud_controller_device_id');
  static Future<String?> controllerSecret() => _storage.read(key: 'cloud_controller_secret');

  static Future<Map<String, dynamic>> controllerStatus({
    required String deviceId,
    required String controllerSecret,
  }) => _post({'action': 'status', 'deviceId': deviceId, 'secret': controllerSecret});

  static Future<void> sendCommand({
    required String deviceId,
    required String controllerSecret,
    required String command,
    int? percent,
    Map<String, dynamic>? payload,
  }) async {
    await _post({
      'action': 'send',
      'deviceId': deviceId,
      'secret': controllerSecret,
      'command': command,
      if (percent != null) 'percent': percent,
      if (payload != null) 'payload': payload,
    });
  }
}