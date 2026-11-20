import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'cloud_relay.dart';

const role = String.fromEnvironment('APP_ROLE', defaultValue: 'controller');
const native = MethodChannel('nl.jake.volume_remote/receiver');
const Color defaultAccent = Color(0xFFEE7D32);
const Color pageBackground = Color(0xFF090D14);
const Color panelBackground = Color(0xFF141B26);

Future<Map<String, dynamic>> sendCommand(String host, String pin, String action, [int? percent]) async {
  final socket = await Socket.connect(host, 8765, timeout: const Duration(seconds: 3));
  socket.write('${jsonEncode({'pin': pin, 'action': action, if (percent != null) 'percent': percent})}\n');
  await socket.flush();
  try {
    final line = await utf8.decoder.bind(socket).transform(const LineSplitter()).first.timeout(const Duration(seconds: 3));
    return jsonDecode(line) as Map<String, dynamic>;
  } finally {
    socket.destroy();
  }
}

void main() => runApp(const VolumeApp());

class VolumeApp extends StatelessWidget {
  const VolumeApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: role == 'receiver' ? 'Volume Receiver' : 'Volume Controller',
    theme: ThemeData(
      brightness: Brightness.dark,
      scaffoldBackgroundColor: pageBackground,
      colorScheme: ColorScheme.fromSeed(seedColor: defaultAccent, brightness: Brightness.dark),
      useMaterial3: true,
    ),
    home: role == 'receiver' ? const ReceiverGate() : const ControllerPage(),
  );
}

// RECEIVER: PIN GATE
class ReceiverGate extends StatefulWidget {
  const ReceiverGate({super.key});
  @override
  State<ReceiverGate> createState() => _ReceiverGateState();
}

class _ReceiverGateState extends State<ReceiverGate> with WidgetsBindingObserver {
  final TextEditingController _pinController = TextEditingController();
  bool _unlocked = false;
  bool _exitDialogOpen = false;
  String _error = '';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pinController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if ((state == AppLifecycleState.hidden || state == AppLifecycleState.paused) && _unlocked && mounted) {
      setState(() {
        _unlocked = false;
        _error = '';
        _pinController.clear();
      });
    }
  }

  void _tryUnlock() {
    if (_pinController.text == '0000') {
      FocusScope.of(context).unfocus();
      setState(() {
        _unlocked = true;
        _error = '';
        _pinController.clear();
      });
    } else {
      setState(() => _error = 'Onjuiste pincode. Probeer opnieuw.');
      _pinController.clear();
    }
  }

  Future<void> _askUnlockToExit() async {
    if (_exitDialogOpen) return;
    _exitDialogOpen = true;
    final controller = TextEditingController();
    final allowed = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Receiver vergrendeld'),
        content: Column(mainAxisSize: MainAxisSize.min, children: [
          const Text('Voer de pincode in om de app af te sluiten.'),
          const SizedBox(height: 12),
          TextField(
            controller: controller,
            autofocus: true,
            obscureText: true,
            keyboardType: TextInputType.number,
            maxLength: 4,
            onSubmitted: (value) => Navigator.pop(dialogContext, value == '0000'),
            decoration: const InputDecoration(labelText: 'Pincode', border: OutlineInputBorder()),
          ),
        ]),
        actions: [
          TextButton(onPressed: () => Navigator.pop(dialogContext, false), child: const Text('Annuleren')),
          FilledButton(onPressed: () => Navigator.pop(dialogContext, controller.text == '0000'), child: const Text('Afsluiten')),
        ],
      ),
    );
    controller.dispose();
    _exitDialogOpen = false;
    if (allowed == true && mounted) SystemNavigator.pop();
  }

  Widget _lockScreen() => Scaffold(
    appBar: AppBar(title: const Text('Volume Receiver')),
    body: Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(28),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 360),
          child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
            const Icon(Icons.lock_outline, size: 76, color: defaultAccent),
            const SizedBox(height: 22),
            const Text('Volume Receiver is vergrendeld', textAlign: TextAlign.center, style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
            const SizedBox(height: 10),
            Text('Voer de 4-cijferige pincode in om verder te gaan.', textAlign: TextAlign.center, style: TextStyle(color: Colors.white.withOpacity(.7))),
            const SizedBox(height: 24),
            TextField(
              controller: _pinController,
              autofocus: true,
              obscureText: true,
              keyboardType: TextInputType.number,
              maxLength: 4,
              textAlign: TextAlign.center,
              onSubmitted: (_) => _tryUnlock(),
              decoration: InputDecoration(labelText: 'Pincode', hintText: 'â€¢â€¢â€¢â€¢', errorText: _error.isEmpty ? null : _error, border: const OutlineInputBorder()),
            ),
            const SizedBox(height: 12),
            SizedBox(width: double.infinity, child: FilledButton.icon(onPressed: _tryUnlock, icon: const Icon(Icons.lock_open), label: const Text('Ontgrendelen'))),
          ]),
        ),
      ),
    ),
  );

  @override
  Widget build(BuildContext context) => PopScope(
    canPop: false,
    onPopInvokedWithResult: (didPop, result) {
      if (!didPop) _askUnlockToExit();
    },
    child: _unlocked ? const ReceiverPage() : _lockScreen(),
  );
}

class ReceiverPage extends StatefulWidget {
  const ReceiverPage({super.key});
  @override
  State<ReceiverPage> createState() => _ReceiverPageState();
}

class _ReceiverPageState extends State<ReceiverPage> with WidgetsBindingObserver {
  Map<String, dynamic> info = {};
  String error = '';
  Uint8List? kidsIcon;
  Timer? cloudTimer;
  String? cloudDeviceId;
  String? cloudDeviceSecret;
  bool cloudReady = false;
  bool cloudBusy = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    init();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    cloudTimer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      refresh();
      _startCloud();
    }
  }

  Future<void> init() async {
    try {
      await native.invokeMethod('startReceiver');
      await refresh();
      await _startCloud();
      kidsIcon = await native.invokeMethod<Uint8List>('getYouTubeKidsIcon');
      if (mounted) setState(() {});
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    }
  }

  Future<Map<String, dynamic>> _localStatus() async {
    final info = Map<String, dynamic>.from(await native.invokeMethod('getReceiverInfo'));
    final result = await sendCommand('127.0.0.1', '${info['pin']}', 'status');
    return {
      'percent': result['percent'] ?? 0,
      'brightnessPercent': result['brightnessPercent'] ?? info['brightnessPercent'] ?? 50,
      'canWriteBrightness': result['canWriteBrightness'] ?? info['canWriteBrightness'] ?? false,
      'lockAdminActive': result['lockAdminActive'] ?? info['lockAdminActive'] ?? false,
    };
  }

  Future<void> _startCloud() async {
    if (cloudBusy) return;
    cloudBusy = true;
    try {
      final credentials = await CloudRelay.deviceCredentials();
      cloudDeviceId = credentials.deviceId;
      cloudDeviceSecret = credentials.deviceSecret;
      final info = Map<String, dynamic>.from(await native.invokeMethod('getReceiverInfo'));
      await CloudRelay.registerDevice(
        deviceId: credentials.deviceId,
        deviceSecret: credentials.deviceSecret,
        pairingCode: info['pin'].toString(),
        status: await _localStatus(),
      );
      if (mounted) setState(() => cloudReady = true);
      cloudTimer?.cancel();
      cloudTimer = Timer.periodic(const Duration(seconds: 2), (_) => _pollCloud());
    } catch (e) {
      if (mounted) setState(() { cloudReady = false; error = 'Cloudverbinding: $e'; });
      cloudTimer?.cancel();
      cloudTimer = Timer(const Duration(seconds: 5), _startCloud);
    } finally {
      cloudBusy = false;
    }
  }

  Future<void> _pollCloud() async {
    if (cloudBusy || cloudDeviceId == null || cloudDeviceSecret == null) return;
    cloudBusy = true;
    try {
      final response = await CloudRelay.pollDevice(
        deviceId: cloudDeviceId!,
        deviceSecret: cloudDeviceSecret!,
        status: await _localStatus(),
      );
      final commands = response['commands'];
      if (commands is List) {
        for (final item in commands) {
          if (item is! Map) continue;
          final id = (item['id'] as num?)?.toInt();
          final action = item['action']?.toString() ?? '';
          final percent = (item['percent'] as num?)?.toInt();
          if (id == null) continue;
          final result = await sendCommand('127.0.0.1', (await native.invokeMethod('getReceiverInfo'))['pin'].toString(), action, percent);
          if (result['ok'] == true) {
            await CloudRelay.acknowledge(deviceId: cloudDeviceId!, deviceSecret: cloudDeviceSecret!, commandId: id);
          }
        }
      }
      if (mounted && !cloudReady) setState(() => cloudReady = true);
    } catch (_) {
      if (mounted && cloudReady) setState(() => cloudReady = false);
    } finally {
      cloudBusy = false;
    }
  }

  Future<void> refresh() async {
    try {
      final m = Map<String, dynamic>.from(await native.invokeMethod('getReceiverInfo'));
      if (mounted) setState(() => info = m);
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    }
  }

  Future<void> requestBrightnessPermission() async {
    try {
      await native.invokeMethod('requestBrightnessPermission');
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    }
  }

  Future<void> requestLockAdmin() async {
    try {
      await native.invokeMethod('requestLockAdmin');
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    }
  }

  Future<void> openKids() async {
    try {
      await native.invokeMethod('openYouTubeKids');
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('YouTube Kids openen lukte niet: $e')),
        );
      }
    }
  }

  Future<void> openWifiSettings() async {
    try {
      await native.invokeMethod('openWifiSettings');
    } catch (e) {
      if (mounted) setState(() => error = e.toString());
    }
  }

  Widget permissionTile({
    required IconData icon,
    required String title,
    required String subtitle,
    required bool enabled,
    required VoidCallback onPressed,
  }) {
    return Container(
      margin: const EdgeInsets.only(top: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: panelBackground,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: Colors.white.withOpacity(.06)),
      ),
      child: Row(
        children: [
          Icon(icon, color: enabled ? const Color(0xFF42D69A) : defaultAccent, size: 25),
          const SizedBox(width: 12),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, style: const TextStyle(fontWeight: FontWeight.w700)),
              const SizedBox(height: 3),
              Text(subtitle, style: TextStyle(fontSize: 11, color: Colors.white.withOpacity(.55))),
            ]),
          ),
          const SizedBox(width: 8),
          if (enabled)
            const Icon(Icons.check_circle_rounded, color: Color(0xFF42D69A))
          else
            IconButton(onPressed: onPressed, icon: const Icon(Icons.arrow_forward_ios_rounded, size: 17)),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final canWriteBrightness = info['canWriteBrightness'] == true;
    final lockAdminActive = info['lockAdminActive'] == true;
    final kidsInstalled = info['youtubeKidsInstalled'] == true;

    return Scaffold(
      appBar: AppBar(title: const Text('Volume Receiver')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(22),
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 520),
              child: Column(children: [
                const Icon(Icons.tablet_android, size: 64, color: defaultAccent),
                const SizedBox(height: 12),
                const Text('Deze tablet ontvangt bediening', textAlign: TextAlign.center, style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
                const SizedBox(height: 22),
                const Text('VERBINDINGSSTATUS', style: TextStyle(letterSpacing: 1.2, fontSize: 11)),
                const Text('Actief', style: TextStyle(color: Colors.green, fontSize: 22, fontWeight: FontWeight.bold)),
                const SizedBox(height: 15),
                const Text('IP-ADRES'),
                SelectableText('${info['ip'] ?? 'Zoeken...'}', style: const TextStyle(fontSize: 20)),
                const SizedBox(height: 14),
                const Text('KOPPELCODE'),
                SelectableText('${info['pin'] ?? '------'}', style: const TextStyle(fontSize: 32, letterSpacing: 8, fontWeight: FontWeight.bold)),
                const SizedBox(height: 14),
                Text(cloudReady ? 'Cloudverbinding actief â€¢ werkt ook via mobiel internet en andere wifi.' : 'Cloudverbinding wordt gestart...', textAlign: TextAlign.center, style: TextStyle(color: Colors.white.withOpacity(.6), fontSize: 12)),
                if (error.isNotEmpty) Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(error, style: const TextStyle(color: Colors.red)),
                ),
                const SizedBox(height: 20),
                Align(
                  alignment: Alignment.centerLeft,
                  child: Text('SNELKOPPELING', style: TextStyle(fontSize: 11, letterSpacing: 1.7, color: Colors.white.withOpacity(.6), fontWeight: FontWeight.w800)),
                ),
                const SizedBox(height: 9),
                Material(
                  color: Colors.transparent,
                  child: InkWell(
                    borderRadius: BorderRadius.circular(20),
                    onTap: openKids,
                    child: Ink(
                      padding: const EdgeInsets.all(16),
                      decoration: BoxDecoration(
                        gradient: const LinearGradient(colors: [Color(0xFFFF4D55), Color(0xFFD91F35)]),
                        borderRadius: BorderRadius.circular(20),
                        boxShadow: [BoxShadow(color: const Color(0xFFE52C40).withOpacity(.22), blurRadius: 18, offset: const Offset(0, 7))],
                      ),
                      child: Row(children: [
                        Container(
                          width: 58,
                          height: 58,
                          padding: const EdgeInsets.all(5),
                          decoration: BoxDecoration(color: Colors.white, borderRadius: BorderRadius.circular(15)),
                          child: kidsIcon != null
                              ? ClipRRect(borderRadius: BorderRadius.circular(11), child: Image.memory(kidsIcon!, fit: BoxFit.contain))
                              : const Icon(Icons.ondemand_video_rounded, color: Color(0xFFE52C40), size: 43),
                        ),
                        const SizedBox(width: 14),
                        Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          const Text('YouTube Kids', style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800, color: Colors.white)),
                          const SizedBox(height: 3),
                          Text(kidsInstalled ? 'Open YouTube Kids op deze tablet' : 'Tik om YouTube Kids te installeren', style: const TextStyle(fontSize: 11, color: Colors.white70)),
                        ])),
                        const Icon(Icons.arrow_forward_rounded, color: Colors.white),
                      ]),
                    ),
                  ),
                ),
                const SizedBox(height: 20),
                Align(
                  alignment: Alignment.centerLeft,
                  child: Text('TOESTEMMINGEN', style: TextStyle(fontSize: 11, letterSpacing: 1.7, color: Colors.white.withOpacity(.6), fontWeight: FontWeight.w800)),
                ),
                permissionTile(
                  icon: Icons.brightness_6_rounded,
                  title: 'Helderheid op afstand',
                  subtitle: canWriteBrightness ? 'Toegestaan' : 'Eenmalig toestaan via Android-instellingen',
                  enabled: canWriteBrightness,
                  onPressed: requestBrightnessPermission,
                ),
                permissionTile(
                  icon: Icons.lock_rounded,
                  title: 'Scherm op afstand vergrendelen',
                  subtitle: lockAdminActive ? 'Toegestaan' : 'Eenmalig bevestigen in Android',
                  enabled: lockAdminActive,
                  onPressed: requestLockAdmin,
                ),
                const SizedBox(height: 16),
                OutlinedButton.icon(
                  onPressed: openWifiSettings,
                  icon: const Icon(Icons.wifi_rounded),
                  label: const Text('Wifi-instellingen op tablet openen'),
                ),
                const SizedBox(height: 5),
                Text('De vergrendelknop zet het scherm uit en vergrendelt de tablet; dit is geen volledige stroomuitschakeling.', textAlign: TextAlign.center, style: TextStyle(fontSize: 11, color: Colors.white.withOpacity(.45))),
                const SizedBox(height: 10),
                FilledButton.icon(onPressed: refresh, icon: const Icon(Icons.refresh), label: const Text('Vernieuw status')),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}

class ControllerSettings {
  const ControllerSettings({required this.ip, required this.pin, required this.pairingCode, required this.accent});
  final String ip;
  final String pin;
  final String pairingCode;
  final Color accent;
}

class ControllerPage extends StatefulWidget {
  const ControllerPage({super.key});
  @override
  State<ControllerPage> createState() => _ControllerPageState();
}

class _ControllerPageState extends State<ControllerPage> {
  final ip = TextEditingController();
  final pin = TextEditingController();
  bool connected = false;
  bool busy = false;
  int percent = 0;
  int brightnessPercent = 50;
  String selectedControl = 'volume';
  bool canWriteBrightness = false;
  bool lockAdminActive = false;
  String pairingCode = '';
  String? cloudDeviceId;
  String? cloudControllerSecret;
  Color accent = defaultAccent;
  Timer? timer;

  @override
  void initState() {
    super.initState();
    _loadSavedSettings();
  }

  Future<void> _loadSavedSettings() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      ip.text = prefs.getString('controller_ip') ?? '';
      pin.text = prefs.getString('controller_pin') ?? '';
      accent = Color(prefs.getInt('controller_accent') ?? defaultAccent.value);
    });
    cloudDeviceId = await CloudRelay.controllerDeviceId();
    cloudControllerSecret = await CloudRelay.controllerSecret();
    if (cloudDeviceId != null && cloudControllerSecret != null) connect();
  }

  @override
  void dispose() {
    timer?.cancel();
    ip.dispose();
    pin.dispose();
    super.dispose();
  }

  Future<void> _openSettings() async {
    final result = await Navigator.of(context).push<ControllerSettings>(
      MaterialPageRoute(
        builder: (_) => ControllerSettingsPage(
          initialIp: ip.text,
          initialPin: pin.text,
          initialPairingCode: '',
          initialAccent: accent,
        ),
      ),
    );
    if (result == null || !mounted) return;
    ip.text = result.ip.trim();
    pin.text = result.pin.trim();
    pairingCode = result.pairingCode.trim();
    if (pairingCode.length == 6) {
      cloudDeviceId = null;
      cloudControllerSecret = null;
    }
    setState(() => accent = result.accent);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('controller_ip', ip.text);
    await prefs.setString('controller_pin', pin.text);
    await prefs.setInt('controller_accent', accent.value);
    await connect();
  }

  Future<void> connect() async {
    if (mounted) setState(() => busy = true);
    try {
      if ((cloudDeviceId == null || cloudControllerSecret == null) && pairingCode.length == 6) {
        final paired = await CloudRelay.pair(pairingCode);
        cloudDeviceId = paired.deviceId;
        cloudControllerSecret = paired.controllerSecret;
        pairingCode = '';
      }
      if (cloudDeviceId != null && cloudControllerSecret != null) {
        final response = await CloudRelay.controllerStatus(deviceId: cloudDeviceId!, controllerSecret: cloudControllerSecret!);
        final status = Map<String, dynamic>.from(response['status'] ?? {});
        final lastSeen = DateTime.tryParse(response['lastSeenAt']?.toString() ?? '');
        final online = lastSeen != null && DateTime.now().difference(lastSeen).inSeconds < 12;
        if (!mounted) return;
        setState(() {
          connected = online;
          if (status['percent'] is num) percent = (status['percent'] as num).round().clamp(0, 100).toInt();
          if (status['brightnessPercent'] is num) brightnessPercent = (status['brightnessPercent'] as num).round().clamp(0, 100).toInt();
          canWriteBrightness = status['canWriteBrightness'] == true;
          lockAdminActive = status['lockAdminActive'] == true;
        });
        timer?.cancel();
        timer = Timer.periodic(const Duration(seconds: 2), (_) => check());
        if (!online) throw Exception('Tablet is nog niet online via de cloud');
        return;
      }
    } catch (e) {
      if (mounted) {
        setState(() => connected = false);
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Cloud koppelen mislukt: $e')));
      }
      return;
    } finally {
      if (mounted) setState(() => busy = false);
    }

    if (ip.text.trim().isEmpty || pin.text.trim().length != 6) {
      if (mounted) {
        setState(() => connected = false);
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Vul in Instellingen het IP-adres en de volledige 6-cijferige koppelcode in.')));
      }
      return;
    }
    if (mounted) setState(() => busy = true);
    try {
      final r = await sendCommand(ip.text.trim(), pin.text.trim(), 'status');
      if (r['ok'] != true) throw Exception(r['error'] ?? 'Koppelen mislukt');
      if (!mounted) return;
      setState(() {
        connected = true;
        if (r['percent'] is num) percent = (r['percent'] as num).round().clamp(0, 100).toInt();
        if (r['brightnessPercent'] is num) brightnessPercent = (r['brightnessPercent'] as num).round().clamp(0, 100).toInt();
        canWriteBrightness = r['canWriteBrightness'] == true;
        lockAdminActive = r['lockAdminActive'] == true;
      });
      timer?.cancel();
      timer = Timer.periodic(const Duration(seconds: 2), (_) => check());
    } catch (_) {
      if (mounted) {
        setState(() => connected = false);
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Verbinden mislukt. Controleer IP-adres, koppelcode en wifi.')));
      }
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  Future<void> check() async {
    try {
      if (cloudDeviceId != null && cloudControllerSecret != null) {
        final response = await CloudRelay.controllerStatus(deviceId: cloudDeviceId!, controllerSecret: cloudControllerSecret!);
        final status = Map<String, dynamic>.from(response['status'] ?? {});
        final lastSeen = DateTime.tryParse(response['lastSeenAt']?.toString() ?? '');
        final online = lastSeen != null && DateTime.now().difference(lastSeen).inSeconds < 12;
        if (!mounted) return;
        setState(() {
          connected = online;
          if (status['percent'] is num) percent = (status['percent'] as num).round().clamp(0, 100).toInt();
          if (status['brightnessPercent'] is num) brightnessPercent = (status['brightnessPercent'] as num).round().clamp(0, 100).toInt();
          canWriteBrightness = status['canWriteBrightness'] == true;
          lockAdminActive = status['lockAdminActive'] == true;
        });
        return;
      }
      final r = await sendCommand(ip.text.trim(), pin.text.trim(), 'status');
      if (!mounted) return;
      if (r['ok'] == true) {
        setState(() {
          connected = true;
          if (r['percent'] is num) percent = (r['percent'] as num).round().clamp(0, 100).toInt();
          if (r['brightnessPercent'] is num) brightnessPercent = (r['brightnessPercent'] as num).round().clamp(0, 100).toInt();
          canWriteBrightness = r['canWriteBrightness'] == true;
          lockAdminActive = r['lockAdminActive'] == true;
        });
      } else {
        setState(() => connected = false);
      }
    } catch (_) {
      if (mounted) setState(() => connected = false);
    }
  }

  Future<void> change(int value) async {
    final next = value.clamp(0, 100).toInt();
    final isBrightness = selectedControl == 'brightness';
    if (isBrightness && !canWriteBrightness) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Geef eerst op de tablet toestemming voor helderheidsaanpassing.')));
      return;
    }
    setState(() {
      if (isBrightness) {
        brightnessPercent = next;
      } else {
        percent = next;
      }
    });
    if (!connected) return;
    try {
      if (cloudDeviceId != null && cloudControllerSecret != null) {
        await CloudRelay.sendCommand(
          deviceId: cloudDeviceId!,
          controllerSecret: cloudControllerSecret!,
          command: isBrightness ? 'setBrightness' : 'set',
          percent: next,
        );
        return;
      }
      final r = await sendCommand(ip.text.trim(), pin.text.trim(), isBrightness ? 'setBrightness' : 'set', next);
      if (r['ok'] == true && mounted) {
        setState(() {
          if (isBrightness && r['brightnessPercent'] is num) {
            brightnessPercent = (r['brightnessPercent'] as num).round().clamp(0, 100).toInt();
          } else if (!isBrightness && r['percent'] is num) {
            percent = (r['percent'] as num).round().clamp(0, 100).toInt();
          }
        });
      } else if (r['error'] == 'BRIGHTNESS_PERMISSION_REQUIRED' && mounted) {
        setState(() => canWriteBrightness = false);
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Zet de helderheidstoestemming aan in Volume Receiver op de tablet.')));
      } else if (r['ok'] != true && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${r['error'] ?? 'Bediening mislukt'}')));
      }
    } catch (_) {
      if (mounted) setState(() => connected = false);
    }
  }

  Future<void> _confirmLock() async {
    if (!connected) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('De tablet is niet verbonden.')));
      return;
    }
    if (!lockAdminActive) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Activeer eerst Scherm op afstand vergrendelen op de tablet.')));
      return;
    }
    final approved = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Tablet vergrendelen?'),
        content: const Text('Weet je het zeker? Het scherm van de tablet gaat uit en wordt vergrendeld. Je moet de tablet daarna weer ontgrendelen.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(dialogContext, false), child: const Text('Annuleren')),
          FilledButton(onPressed: () => Navigator.pop(dialogContext, true), child: const Text('Ja, vergrendelen')),
        ],
      ),
    );
    if (approved != true || !mounted) return;
    try {
      if (cloudDeviceId != null && cloudControllerSecret != null) {
        await CloudRelay.sendCommand(deviceId: cloudDeviceId!, controllerSecret: cloudControllerSecret!, command: 'lock');
        if (mounted) {
          setState(() => connected = false);
          ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Vergrendelopdracht via internet verzonden.')));
        }
        return;
      }
      final r = await sendCommand(ip.text.trim(), pin.text.trim(), 'lock');
      if (r['ok'] == true) {
        setState(() => connected = false);
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Tablet wordt vergrendeld.')));
      } else {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('${r['error'] ?? 'Vergrendelen mislukt'}')));
      }
    } catch (_) {
      if (mounted) {
        setState(() => connected = false);
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Verbinding verbroken bij vergrendelen.')));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final isBrightness = selectedControl == 'brightness';
    final activeValue = isBrightness ? brightnessPercent : percent;
    final activeTitle = isBrightness ? 'SCHERMHELDERHEID' : 'MEDIA VOLUME';
    final activeIcon = isBrightness ? Icons.brightness_6_rounded : Icons.volume_up_rounded;

    return Scaffold(
      backgroundColor: pageBackground,
      appBar: AppBar(
        backgroundColor: pageBackground,
        elevation: 0,
        titleSpacing: 22,
        title: const Text('Volume Controller', style: TextStyle(fontWeight: FontWeight.w700, letterSpacing: .2)),
        actions: [
          IconButton(
            tooltip: 'Instellingen',
            onPressed: busy ? null : _openSettings,
            icon: const Icon(Icons.tune_rounded),
            style: IconButton.styleFrom(backgroundColor: panelBackground, foregroundColor: Colors.white),
          ),
          const SizedBox(width: 14),
        ],
      ),
      body: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(22, 14, 22, 18),
          child: Column(children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 11),
              decoration: BoxDecoration(
                color: panelBackground,
                borderRadius: BorderRadius.circular(30),
                border: Border.all(color: Colors.white.withOpacity(.06)),
              ),
              child: Row(mainAxisSize: MainAxisSize.min, children: [
                Container(
                  width: 9,
                  height: 9,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: connected ? const Color(0xFF42D69A) : const Color(0xFFFF626D),
                    boxShadow: [BoxShadow(color: (connected ? const Color(0xFF42D69A) : const Color(0xFFFF626D)).withOpacity(.45), blurRadius: 9)],
                  ),
                ),
                const SizedBox(width: 10),
                Text(connected ? 'Verbonden' : 'Niet verbonden', style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
              ]),
            ),
            const SizedBox(height: 18),
            SegmentedButton<String>(
              segments: const [
                ButtonSegment<String>(value: 'volume', label: Text('Volume'), icon: Icon(Icons.volume_up_rounded)),
                ButtonSegment<String>(value: 'brightness', label: Text('Helderheid'), icon: Icon(Icons.brightness_6_rounded)),
              ],
              selected: {selectedControl},
              showSelectedIcon: false,
              onSelectionChanged: (selection) => setState(() => selectedControl = selection.first),
            ),
            const SizedBox(height: 12),
            Expanded(
              child: LayoutBuilder(builder: (context, constraints) {
                final sliderHeight = math.min(390.0, math.max(180.0, constraints.maxHeight - 64));
                return Center(
                  child: Row(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.center, children: [
                    Column(mainAxisSize: MainAxisSize.min, children: [
                      Text('100', style: TextStyle(fontSize: 12, color: Colors.white.withOpacity(.5), fontWeight: FontWeight.w600)),
                      const SizedBox(height: 8),
                      VerticalVolumeControl(height: sliderHeight, value: activeValue, accent: accent, icon: activeIcon, onChanged: change),
                      const SizedBox(height: 8),
                      Text('0', style: TextStyle(fontSize: 12, color: Colors.white.withOpacity(.5), fontWeight: FontWeight.w600)),
                    ]),
                    const SizedBox(width: 34),
                    SizedBox(
                      width: 132,
                      child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text(activeTitle, style: TextStyle(fontSize: 10, letterSpacing: 1.5, color: Colors.white.withOpacity(.45), fontWeight: FontWeight.w700)),
                        const SizedBox(height: 8),
                        FittedBox(
                          fit: BoxFit.scaleDown,
                          alignment: Alignment.centerLeft,
                          child: Text('$activeValue%', style: TextStyle(fontSize: 58, height: 1, fontWeight: FontWeight.w800, letterSpacing: -2, color: accent)),
                        ),
                        const SizedBox(height: 12),
                        Container(width: 42, height: 3, decoration: BoxDecoration(color: accent, borderRadius: BorderRadius.circular(4))),
                        const SizedBox(height: 12),
                        Text(
                          !connected ? 'Wacht op verbinding' : (isBrightness && !canWriteBrightness ? 'Toestemming nodig op tablet' : 'Live geregeld'),
                          style: TextStyle(fontSize: 11, color: Colors.white.withOpacity(.55)),
                        ),
                      ]),
                    ),
                  ]),
                );
              }),
            ),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: connected ? _confirmLock : null,
                icon: const Icon(Icons.power_settings_new_rounded),
                label: const Text('Tablet vergrendelen / scherm uit'),
                style: OutlinedButton.styleFrom(
                  foregroundColor: const Color(0xFFFF7B82),
                  side: BorderSide(color: const Color(0xFFFF626D).withOpacity(.55)),
                  padding: const EdgeInsets.symmetric(vertical: 13),
                ),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              isBrightness && !canWriteBrightness
                  ? 'Geef eerst op de tablet toestemming voor helderheidsaanpassing'
                  : 'Sleep omhoog of omlaag om ${isBrightness ? 'de helderheid' : 'het volume'} aan te passen',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 11, color: Colors.white.withOpacity(.42)),
            ),
            const SizedBox(height: 5),
          ]),
        ),
      ),
    );
  }
}

class VerticalVolumeControl extends StatelessWidget {
  const VerticalVolumeControl({super.key, required this.height, required this.value, required this.accent, this.icon = Icons.volume_up_rounded, required this.onChanged});
  final double height;
  final int value;
  final Color accent;
  final IconData icon;
  final ValueChanged<int> onChanged;

  void _update(Offset local, double totalHeight) {
    final usable = totalHeight - 56;
    final fraction = (1 - ((local.dy - 28) / usable)).clamp(0.0, 1.0);
    onChanged((fraction * 100).round());
  }

  @override
  Widget build(BuildContext context) => GestureDetector(
    behavior: HitTestBehavior.opaque,
    onTapDown: (details) => _update(details.localPosition, height),
    onVerticalDragUpdate: (details) => _update(details.localPosition, height),
    child: CustomPaint(
      size: Size(92, height),
      painter: _VerticalFaderPainter(value: value, accent: accent, icon: icon),
    ),
  );
}

class _VerticalFaderPainter extends CustomPainter {
  const _VerticalFaderPainter({required this.value, required this.accent, required this.icon});
  final int value;
  final Color accent;
  final IconData icon;

  @override
  void paint(Canvas canvas, Size size) {
    final cx = size.width / 2;
    const top = 28.0;
    final bottom = size.height - 28.0;
    final thumbY = bottom - (bottom - top) * (value / 100.0);
    final track = RRect.fromRectAndRadius(Rect.fromLTRB(cx - 7, top, cx + 7, bottom), const Radius.circular(8));

    canvas.drawRRect(track, Paint()..color = const Color(0xFF252E3B));
    final activeRect = Rect.fromLTRB(cx - 7, thumbY, cx + 7, bottom);
    if (activeRect.height > 0) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(activeRect, const Radius.circular(8)),
        Paint()..shader = LinearGradient(begin: Alignment.topCenter, end: Alignment.bottomCenter, colors: [accent, accent.withOpacity(.48)]).createShader(activeRect),
      );
    }

    for (var i = 0; i <= 4; i++) {
      final y = bottom - (bottom - top) * (i / 4);
      final active = i * 25 <= value;
      canvas.drawLine(Offset(cx + 17, y), Offset(cx + 25, y), Paint()
        ..color = active ? accent.withOpacity(.9) : Colors.white.withOpacity(.24)
        ..strokeWidth = 2
        ..strokeCap = StrokeCap.round);
    }

    canvas.drawCircle(Offset(cx, thumbY), 25, Paint()..color = accent.withOpacity(.22)..maskFilter = const MaskFilter.blur(BlurStyle.normal, 12));
    canvas.drawCircle(Offset(cx, thumbY), 21, Paint()..color = accent);
    canvas.drawCircle(Offset(cx, thumbY), 21, Paint()..color = Colors.white.withOpacity(.9)..style = PaintingStyle.stroke..strokeWidth = 2);
    final iconPainter = TextPainter(
      text: TextSpan(
        text: String.fromCharCode(icon.codePoint),
        style: TextStyle(fontSize: 22, fontFamily: icon.fontFamily, package: icon.fontPackage, color: Colors.white),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    iconPainter.paint(canvas, Offset(cx - iconPainter.width / 2, thumbY - iconPainter.height / 2));
  }

  @override
  bool shouldRepaint(covariant _VerticalFaderPainter oldDelegate) => oldDelegate.value != value || oldDelegate.accent != accent || oldDelegate.icon != icon;
}

class ControllerSettingsPage extends StatefulWidget {
  const ControllerSettingsPage({super.key, required this.initialIp, required this.initialPin, required this.initialPairingCode, required this.initialAccent});
  final String initialIp;
  final String initialPin;
  final String initialPairingCode;
  final Color initialAccent;

  @override
  State<ControllerSettingsPage> createState() => _ControllerSettingsPageState();
}

class _ControllerSettingsPageState extends State<ControllerSettingsPage> {
  late final TextEditingController ipController;
  late final TextEditingController pinController;
  late final TextEditingController pairingCodeController;
  late Color selectedAccent;

  @override
  void initState() {
    super.initState();
    ipController = TextEditingController(text: widget.initialIp);
    pinController = TextEditingController(text: widget.initialPin);
    pairingCodeController = TextEditingController(text: widget.initialPairingCode);
    selectedAccent = widget.initialAccent;
  }

  @override
  void dispose() {
    ipController.dispose();
    pinController.dispose();
    pairingCodeController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: pageBackground,
    appBar: AppBar(
      backgroundColor: pageBackground,
      title: const Text('Instellingen', style: TextStyle(fontWeight: FontWeight.w700)),
    ),
    body: ListView(
      padding: const EdgeInsets.fromLTRB(22, 10, 22, 28),
      children: [
        _SettingsSection(
          title: 'VERBINDING',
          subtitle: 'Gegevens van de Volume Receiver op je tablet',
          child: Column(children: [
            TextField(
              controller: ipController,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9.]'))],
              decoration: const InputDecoration(labelText: 'IP-adres', hintText: 'Bijv. 192.168.178.50', prefixIcon: Icon(Icons.wifi_rounded), border: OutlineInputBorder()),
            ),
            const SizedBox(height: 14),
            TextField(
              controller: pinController,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(6)],
              obscureText: true,
              decoration: const InputDecoration(labelText: 'Zescijferige koppelcode (lokaal)', hintText: 'â€¢â€¢â€¢â€¢â€¢â€¢', prefixIcon: Icon(Icons.key_rounded), border: OutlineInputBorder()),
            ),
            const SizedBox(height: 18),
            const Divider(),
            const SizedBox(height: 12),
            const Text('KOPPELEN VIA INTERNET', style: TextStyle(fontSize: 11, letterSpacing: 1.5, fontWeight: FontWeight.w800, color: Color(0xFF9AA6B8))),
            const SizedBox(height: 8),
            const Text('Vul de 6-cijferige code in die op de tablet staat. De telefoon en tablet mogen op verschillende wifi-netwerken zitten.', style: TextStyle(fontSize: 12, color: Colors.white70)),
            const SizedBox(height: 12),
            TextField(
              controller: pairingCodeController,
              keyboardType: TextInputType.number,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(6)],
              decoration: const InputDecoration(labelText: 'Internet-koppelcode', hintText: '123456', prefixIcon: Icon(Icons.cloud_rounded), border: OutlineInputBorder()),
            ),
          ]),
        ),
        const SizedBox(height: 18),
        _SettingsSection(
          title: 'KLEUR VAN DE REGELAAR',
          subtitle: 'Kies zelf een kleur uit het volledige kleurenspectrum',
          child: Column(children: [
            ColorWheelPicker(color: selectedAccent, onChanged: (color) => setState(() => selectedAccent = color)),
            const SizedBox(height: 14),
            Row(mainAxisAlignment: MainAxisAlignment.center, children: [
              Container(width: 16, height: 16, decoration: BoxDecoration(color: selectedAccent, shape: BoxShape.circle, boxShadow: [BoxShadow(color: selectedAccent.withOpacity(.55), blurRadius: 12)])),
              const SizedBox(width: 9),
              Text('Voorbeeldkleur', style: TextStyle(color: Colors.white.withOpacity(.78), fontWeight: FontWeight.w600)),
              const SizedBox(width: 10),
              Text('#${selectedAccent.value.toRadixString(16).substring(2).toUpperCase()}', style: TextStyle(color: Colors.white.withOpacity(.5))),
            ]),
          ]),
        ),
        const SizedBox(height: 22),
        SizedBox(
          height: 54,
          child: FilledButton.icon(
            style: FilledButton.styleFrom(backgroundColor: selectedAccent, foregroundColor: Colors.black, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16))),
            onPressed: () => Navigator.of(context).pop(ControllerSettings(ip: ipController.text, pin: pinController.text, pairingCode: pairingCodeController.text, accent: selectedAccent)),
            icon: const Icon(Icons.check_rounded),
            label: const Text('Opslaan en verbinden', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700)),
          ),
        ),
      ],
    ),
  );
}

class _SettingsSection extends StatelessWidget {
  const _SettingsSection({required this.title, required this.subtitle, required this.child});
  final String title;
  final String subtitle;
  final Widget child;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(18),
    decoration: BoxDecoration(
      color: panelBackground,
      borderRadius: BorderRadius.circular(22),
      border: Border.all(color: Colors.white.withOpacity(.055)),
    ),
    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text(title, style: const TextStyle(fontSize: 11, letterSpacing: 1.8, fontWeight: FontWeight.w800, color: Color(0xFF9AA6B8))),
      const SizedBox(height: 6),
      Text(subtitle, style: TextStyle(fontSize: 12, color: Colors.white.withOpacity(.55))),
      const SizedBox(height: 18),
      child,
    ]),
  );
}

class ColorWheelPicker extends StatelessWidget {
  const ColorWheelPicker({super.key, required this.color, required this.onChanged});
  final Color color;
  final ValueChanged<Color> onChanged;

  void _pick(Offset point, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final dx = point.dx - center.dx;
    final dy = point.dy - center.dy;
    final hue = (math.atan2(dy, dx) * 180 / math.pi + 360) % 360;
    onChanged(HSVColor.fromAHSV(1, hue, .92, .98).toColor());
  }

  @override
  Widget build(BuildContext context) {
    const wheelSize = 190.0;
    return Center(
      child: GestureDetector(
        onTapDown: (details) => _pick(details.localPosition, const Size(wheelSize, wheelSize)),
        onPanUpdate: (details) => _pick(details.localPosition, const Size(wheelSize, wheelSize)),
        child: CustomPaint(size: const Size(wheelSize, wheelSize), painter: _HueWheelPainter(color)),
      ),
    );
  }
}

class _HueWheelPainter extends CustomPainter {
  const _HueWheelPainter(this.color);
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    const radius = 70.0;
    final wheelRect = Rect.fromCircle(center: center, radius: radius);
    final colors = List<Color>.generate(13, (i) => HSVColor.fromAHSV(1, i * 30.0, .92, .98).toColor());
    final shader = SweepGradient(colors: colors, tileMode: TileMode.clamp).createShader(wheelRect);
    canvas.drawCircle(center, radius, Paint()..style = PaintingStyle.stroke..strokeWidth = 27..shader = shader);
    canvas.drawCircle(center, radius + 14, Paint()..style = PaintingStyle.stroke..strokeWidth = 1..color = Colors.white.withOpacity(.12));
    canvas.drawCircle(center, radius - 14, Paint()..style = PaintingStyle.stroke..strokeWidth = 1..color = Colors.white.withOpacity(.12));

    final hsv = HSVColor.fromColor(color);
    final angle = hsv.hue * math.pi / 180;
    final marker = Offset(center.dx + math.cos(angle) * radius, center.dy + math.sin(angle) * radius);
    canvas.drawCircle(marker, 13, Paint()..color = Colors.black.withOpacity(.35)..maskFilter = const MaskFilter.blur(BlurStyle.normal, 5));
    canvas.drawCircle(marker, 11, Paint()..color = Colors.white);
    canvas.drawCircle(marker, 7, Paint()..color = color);

    canvas.drawCircle(center, 29, Paint()..color = color);
    canvas.drawCircle(center, 29, Paint()..style = PaintingStyle.stroke..strokeWidth = 3..color = Colors.white.withOpacity(.92));
  }

  @override
  bool shouldRepaint(covariant _HueWheelPainter oldDelegate) => oldDelegate.color != color;
}
