import 'dart:async';

import 'package:app_links/app_links.dart';
import 'package:flutter/material.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';

import 'app_store.dart';
import 'discovery.dart';
import 'foreground_service.dart';
import 'network_services.dart';
import 'streetpass_crypto.dart';

const statisticsUrl = 'https://streetpass.coolify.megaworld.space';
const releasesUrl = 'https://github.com/SworderZ/streetpass/releases';

void main() {
  ForegroundController.initialize();
  runApp(const StreetPassApp());
}

class StreetPassApp extends StatefulWidget {
  const StreetPassApp({super.key});
  @override
  State<StreetPassApp> createState() => _StreetPassAppState();
}

class _StreetPassAppState extends State<StreetPassApp> {
  AppStore? store;
  late final DiscoveryService discovery = DiscoveryService();
  StreamSubscription<Uri>? _linkSubscription;
  String? startupError;

  @override
  void initState() {
    super.initState();
    if (ForegroundController.supported) {
      FlutterForegroundTask.addTaskDataCallback(_onForegroundData);
    }
    _initialize();
    _linkSubscription = AppLinks().uriLinkStream.listen(
      (uri) => _handleInvite(uri.toString()),
    );
  }

  Future<void> _handleInvite(String text) async {
    final invite = StreetPassCrypto.parseInvite(text);
    if (invite == null || store == null) return;
    await store!.editPeer(
      invite.peerId,
      nickname: invite.nickname,
      friend: true,
    );
  }

  void _onForegroundData(Object data) {
    if (!mounted ||
        store == null ||
        data is! Map ||
        data['type'] != 'meeting') {
      return;
    }
    store!.changed();
  }

  @override
  void dispose() {
    _linkSubscription?.cancel();
    if (ForegroundController.supported) {
      FlutterForegroundTask.removeTaskDataCallback(_onForegroundData);
    }
    super.dispose();
  }

  Future<void> _initialize() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final next = AppStore(prefs);
      await next.initialize();
      if (!mounted) return;
      setState(() => store = next);
      final initialLink = await AppLinks().getInitialLink();
      if (initialLink != null) await _handleInvite(initialLink.toString());
      if (next.settings.autoStart && next.settings.active) {
        unawaited(_toggle(true));
      }
    } catch (error) {
      if (mounted) setState(() => startupError = '$error');
    }
  }

  Future<void> _toggle(bool value) async {
    final current = store;
    if (current == null) return;
    try {
      if (value) {
        if (ForegroundController.supported) {
          current.settings.active = true;
          await current.save();
          final result = await ForegroundController.start();
          if (result is ServiceRequestFailure) throw result.error;
          discovery.setUiActive(true);
        } else {
          await discovery.start(current, (sighting) async {
            if (!sighting.verified && !current.settings.acceptUnsigned) return;
            if (current.record(
              sighting.peerId,
              sighting.time,
              name: sighting.nickname,
              rssi: sighting.rssi,
            )) {
              await current.save();
            }
          });
        }
      } else {
        if (ForegroundController.supported) {
          await ForegroundController.stop();
          discovery.setUiActive(false);
        } else {
          await discovery.stop();
        }
      }
      current.settings.active = value;
      await current.save();
      current.changed();
    } catch (error) {
      if (ForegroundController.supported) {
        await ForegroundController.stop();
        discovery.setUiActive(false);
      } else {
        await discovery.stop();
      }
      current.settings.active = false;
      current.changed();
      if (mounted) {
        setState(() => startupError = _friendlyBleError(error));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (startupError != null && store == null) {
      return MaterialApp(home: ErrorPage(message: startupError!));
    }
    final current = store;
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xff101012),
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xffa8c7fa),
          brightness: Brightness.dark,
        ),
        cardTheme: const CardThemeData(color: Color(0xff171719)),
        inputDecorationTheme: const InputDecorationTheme(
          border: OutlineInputBorder(),
        ),
      ),
      home: current == null
          ? const Scaffold(body: Center(child: CircularProgressIndicator()))
          : AnimatedBuilder(
              animation: Listenable.merge([current, discovery]),
              builder: (_, _) => AppShell(
                store: current,
                discovery: discovery,
                error: startupError,
                onToggle: _toggle,
                onClearError: () => setState(() => startupError = null),
              ),
            ),
    );
  }
}

String _friendlyBleError(Object error) {
  final value = error.toString().replaceFirst('Bad state: ', '');
  if (value.contains('poweredOff') || value.contains('Bluetooth is off')) {
    return 'Включите Bluetooth и попробуйте ещё раз.';
  }
  if (value.contains('peripheral advertising')) {
    return 'Адаптер не умеет BLE-рекламу. Сканирование всё ещё можно включить отдельно.';
  }
  if (value.contains('BlueZ')) {
    return '$value. Проверьте, что Bluetooth включён и запущен сервис bluez.';
  }
  return 'Bluetooth не запустился: $value';
}

class ErrorPage extends StatelessWidget {
  const ErrorPage({super.key, required this.message});
  final String message;
  @override
  Widget build(BuildContext context) => Scaffold(
    body: Center(
      child: Padding(padding: const EdgeInsets.all(24), child: Text(message)),
    ),
  );
}

class AppShell extends StatefulWidget {
  const AppShell({
    super.key,
    required this.store,
    required this.discovery,
    required this.onToggle,
    required this.onClearError,
    this.error,
  });
  final AppStore store;
  final DiscoveryService discovery;
  final ValueChanged<bool> onToggle;
  final VoidCallback onClearError;
  final String? error;
  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> {
  int tab = 0;
  @override
  Widget build(BuildContext context) {
    final pages = [
      HomePage(
        store: widget.store,
        discovery: widget.discovery,
        onToggle: widget.onToggle,
      ),
      HistoryPage(store: widget.store),
      StatsPage(store: widget.store),
      SettingsPage(store: widget.store, discovery: widget.discovery),
    ];
    return Scaffold(
      appBar: AppBar(
        title: const Text('StreetPass'),
        actions: [
          IconButton(
            tooltip: 'Статистика сообщества',
            icon: const Icon(Icons.public),
            onPressed: () => launchUrl(
              Uri.parse(statisticsUrl),
              mode: LaunchMode.externalApplication,
            ),
          ),
          IconButton(
            tooltip: 'Настройки',
            icon: const Icon(Icons.settings_outlined),
            onPressed: () => setState(() => tab = 3),
          ),
        ],
      ),
      body: Column(
        children: [
          if (widget.error != null)
            MaterialBanner(
              content: Text(widget.error!),
              leading: const Icon(Icons.warning_amber_rounded),
              actions: [
                TextButton(
                  onPressed: widget.onClearError,
                  child: const Text('Закрыть'),
                ),
              ],
            ),
          Expanded(child: pages[tab]),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: tab,
        onDestinationSelected: (value) => setState(() => tab = value),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.radar), label: 'Рядом'),
          NavigationDestination(icon: Icon(Icons.history), label: 'История'),
          NavigationDestination(
            icon: Icon(Icons.insights),
            label: 'Статистика',
          ),
          NavigationDestination(
            icon: Icon(Icons.settings_outlined),
            label: 'Настройки',
          ),
        ],
      ),
    );
  }
}

class HomePage extends StatelessWidget {
  const HomePage({
    super.key,
    required this.store,
    required this.discovery,
    required this.onToggle,
  });
  final AppStore store;
  final DiscoveryService discovery;
  final ValueChanged<bool> onToggle;
  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final nearby = store.nearby(now);
    final today = store.between(
      DateTime(now.year, now.month, now.day),
      now.add(const Duration(days: 1)),
    );
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
      children: [
        Text('Обнаружение', style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 4),
        Text(
          discovery.running ? _discoveryStatus(discovery) : 'Выключено',
          style: TextStyle(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
        const SizedBox(height: 16),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              children: [
                Row(
                  children: [
                    const Expanded(
                      child: Text(
                        'Искать людей рядом',
                        style: TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ),
                    Switch(value: discovery.running, onChanged: onToggle),
                  ],
                ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    _Metric(value: '${today.length}', label: 'встреч сегодня'),
                    _Metric(
                      value: '${store.peers.length}',
                      label: 'людей всего',
                    ),
                    _Metric(
                      value: store.nickname.isEmpty ? '—' : store.nickname,
                      label: 'ваш ник',
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
        Card(
          child: ListTile(
            leading: const Icon(Icons.person_add_alt_1),
            title: const Text('Добавить по приглашению'),
            subtitle: const Text('Вставьте ссылку из сообщения или QR'),
            onTap: () => showDialog<void>(
              context: context,
              builder: (_) => ImportInviteDialog(store: store),
            ),
          ),
        ),
        if (discovery.running) ...[
          const SizedBox(height: 12),
          Card(
            child: ListTile(
              leading: Icon(
                discovery.broadcasting
                    ? Icons.bluetooth_connected
                    : Icons.bluetooth_searching,
                color: Theme.of(context).colorScheme.primary,
              ),
              title: Text(
                discovery.broadcasting
                    ? 'Вас видят и вы ищете'
                    : 'Поиск работает',
              ),
              subtitle: Text(
                'Пакетов: ${discovery.receivedPackets} · подтверждено: ${discovery.verifiedProofs}',
              ),
            ),
          ),
        ],
        const SizedBox(height: 22),
        _SectionTitle(title: 'Рядом сейчас', trailing: '${nearby.length}'),
        if (nearby.isEmpty)
          const _EmptyCard(
            text: 'Пока никого нет. Оставьте обнаружение включённым и подойдите ближе к другому устройству.',
          )
        else
          ...nearby.map((peer) => PeerTile(store: store, peer: peer)),
        const SizedBox(height: 22),
        _SectionTitle(
          title: 'Последние встречи',
          trailing: '${store.encounters.length}',
        ),
        if (store.encounters.isEmpty)
          const _EmptyCard(
            text: 'Встречи появятся здесь после подтверждённого BLE-обмена.',
          )
        else
          ...store.encounters.take(5).map((entry) {
            final peer = store.peers[entry.peerId];
            return ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const CircleAvatar(
                child: Icon(Icons.waving_hand_outlined),
              ),
              title: Text(peer?.name ?? entry.peerId),
              subtitle: Text(
                '${_formatTime(entry.time)} · встреча №${entry.number}',
              ),
              trailing: peer?.friend == true
                  ? const Icon(Icons.favorite, color: Colors.pinkAccent)
                  : null,
            );
          }),
      ],
    );
  }

  static String _discoveryStatus(DiscoveryService service) =>
      service.error ??
      (service.scanning && service.broadcasting
          ? 'Сканирование и реклама работают'
          : service.scanning
          ? 'Сканирование работает'
          : 'Реклама работает');
}

class HistoryPage extends StatelessWidget {
  const HistoryPage({super.key, required this.store});
  final AppStore store;
  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.all(16),
    children: [
      Text('История встреч', style: Theme.of(context).textTheme.headlineSmall),
      const SizedBox(height: 12),
      if (store.encounters.isEmpty)
        const _EmptyCard(text: 'История пока пустая.')
      else
        ...store.encounters.map((entry) {
          final peer = store.peers[entry.peerId];
          return Card(
            margin: const EdgeInsets.only(bottom: 8),
            child: ListTile(
              leading: const CircleAvatar(
                child: Icon(Icons.handshake_outlined),
              ),
              title: Text(peer?.name ?? entry.peerId),
              subtitle: Text(
                '${_formatDate(entry.time)} · встреча №${entry.number}',
              ),
              trailing: peer?.friend == true
                  ? const Icon(Icons.favorite, color: Colors.pinkAccent)
                  : null,
              onTap: peer == null
                  ? null
                  : () => showDialog<void>(
                      context: context,
                      builder: (_) => PeerDialog(store: store, peer: peer),
                    ),
            ),
          );
        }),
    ],
  );
}

class StatsPage extends StatelessWidget {
  const StatsPage({super.key, required this.store});
  final AppStore store;
  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final today = store.between(
      DateTime(now.year, now.month, now.day),
      now.add(const Duration(days: 1)),
    );
    final week = store.between(
      DateTime(now.year, now.month, now.day).subtract(const Duration(days: 6)),
      now.add(const Duration(days: 1)),
    );
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text('Статистика', style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 12),
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Wrap(
              spacing: 24,
              runSpacing: 18,
              children: [
                _StatBlock('${today.length}', 'сегодня'),
                _StatBlock('${store.people(today)}', 'людей сегодня'),
                _StatBlock('${week.length}', 'за 7 дней'),
                _StatBlock('${store.peers.length}', 'людей всего'),
                _StatBlock('${store.encounters.length}', 'встреч всего'),
                _StatBlock('${store.friends.length}', 'друзей'),
              ],
            ),
          ),
        ),
        const SizedBox(height: 18),
        _SectionTitle(title: 'Друзья', trailing: '${store.friends.length}'),
        Card(
          child: ListTile(
            leading: const Icon(Icons.qr_code_2),
            title: const Text('Моё приглашение'),
            subtitle: const Text('Покажите QR-код другому пользователю'),
            onTap: () => showDialog<void>(
              context: context,
              builder: (_) => InviteDialog(store: store),
            ),
          ),
        ),
        if (store.friends.isEmpty)
          const _EmptyCard(text: 'Отметьте человека другом в истории встреч.'),
        ...store.friends.map((peer) => PeerTile(store: store, peer: peer)),
        const SizedBox(height: 18),
        _SectionTitle(
          title: 'Достижения',
          trailing: '${store.unlocked.length}/${Achievement.all.length}',
        ),
        ...Achievement.all.map((achievement) {
          final value = store.metric(achievement.kind);
          final unlocked = store.unlocked.containsKey(achievement.id);
          return Card(
            child: ListTile(
              leading: Icon(
                unlocked ? Icons.emoji_events : Icons.lock_outline,
                color: unlocked ? Colors.amber : null,
              ),
              title: Text(
                '${_achievementName(achievement.kind)} · ${achievement.threshold}',
              ),
              subtitle: LinearProgressIndicator(
                value: (value / achievement.threshold).clamp(0, 1).toDouble(),
              ),
              trailing: Text('$value/${achievement.threshold}'),
            ),
          );
        }),
      ],
    );
  }
}

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key, required this.store, required this.discovery});
  final AppStore store;
  final DiscoveryService discovery;
  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  final nicknameController = TextEditingController();
  String? version;
  bool checking = false;
  String? updateMessage;
  @override
  void initState() {
    super.initState();
    nicknameController.text = widget.store.nickname;
    _version();
  }

  @override
  void dispose() {
    nicknameController.dispose();
    super.dispose();
  }

  Future<void> _version() async {
    final info = await PackageInfo.fromPlatform();
    if (mounted) setState(() => version = info.version);
  }

  Future<void> _save() async {
    widget.store.nickname =
        StreetPassCrypto.nicknameBytes(
          nicknameController.text,
          maxBytes: 24,
        ).isEmpty
        ? ''
        : nicknameController.text.trim();
    await widget.store.save();
    if (mounted) {
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Настройки сохранены')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.store.settings;
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
      children: [
        Text('Настройки', style: Theme.of(context).textTheme.headlineSmall),
        const SizedBox(height: 12),
        TextField(
          controller: nicknameController,
          maxLength: 24,
          decoration: const InputDecoration(
            labelText: 'Никнейм',
            helperText: 'Передаётся рядом стоящим устройствам открытым текстом',
          ),
        ),
        FilledButton.icon(
          onPressed: _save,
          icon: const Icon(Icons.save_outlined),
          label: const Text('Сохранить'),
        ),
        const SizedBox(height: 18),
        _SettingSwitch(
          title: 'Передавать свой ID',
          value: s.advertise,
          onChanged: (value) {
            s.advertise = value;
            widget.store.save();
            setState(() {});
          },
        ),
        _SettingSwitch(
          title: 'Искать других',
          value: s.scan,
          onChanged: (value) {
            s.scan = value;
            widget.store.save();
            setState(() {});
          },
        ),
        _SettingSwitch(
          title: 'Запускать после перезагрузки',
          value: s.autoStart,
          onChanged: (value) {
            s.autoStart = value;
            widget.store.save();
            setState(() {});
          },
        ),
        _SettingSwitch(
          title: 'Принимать неподписанные ID',
          value: s.acceptUnsigned,
          onChanged: (value) {
            s.acceptUnsigned = value;
            widget.store.save();
            setState(() {});
          },
        ),
        _SettingSwitch(
          title: 'Участвовать в общей статистике',
          value: s.shareStats,
          onChanged: (value) {
            s.shareStats = value;
            widget.store.save();
            unawaited(TelemetryService.send(widget.store));
            setState(() {});
          },
        ),
        const SizedBox(height: 12),
        Text(
          'Профиль энергопотребления',
          style: Theme.of(context).textTheme.titleMedium,
        ),
        SegmentedButton<String>(
          segments: const [
            ButtonSegment(value: 'saver', label: Text('Эконом')),
            ButtonSegment(value: 'balanced', label: Text('Баланс')),
            ButtonSegment(value: 'max', label: Text('Макс')),
          ],
          selected: {s.powerMode},
          onSelectionChanged: (value) {
            s.powerMode = value.first;
            widget.store.save();
            setState(() {});
          },
        ),
        const SizedBox(height: 12),
        Text('Повторная встреча через ${s.cooldownMinutes} мин'),
        Slider(
          min: 5,
          max: 720,
          divisions: 143,
          value: s.cooldownMinutes.toDouble(),
          label: '${s.cooldownMinutes}',
          onChanged: (value) {
            s.cooldownMinutes = value.round();
            setState(() {});
          },
          onChangeEnd: (_) => widget.store.save(),
        ),
        Text('Минимальный сигнал: ${s.minRssi} dBm'),
        Slider(
          min: -100,
          max: -40,
          divisions: 60,
          value: s.minRssi.toDouble(),
          onChanged: (value) {
            s.minRssi = value.round();
            setState(() {});
          },
          onChangeEnd: (_) => widget.store.save(),
        ),
        const SizedBox(height: 16),
        Card(
          child: ListTile(
            title: Text('Версия ${version ?? '…'}'),
            subtitle: Text(
              updateMessage ?? 'Релизы Flutter-клиента публикуются на GitHub',
            ),
            trailing: checking
                ? const CircularProgressIndicator()
                : TextButton(
                    onPressed: () async {
                      setState(() {
                        checking = true;
                        updateMessage = null;
                      });
                      try {
                        final info = await UpdateService.check(
                          version ?? '0.0.0',
                        );
                        if (mounted) {
                          updateMessage = info.available
                              ? 'Доступна новая версия ${info.latest}'
                              : 'Установлена последняя версия';
                        }
                        if (info.available) {
                          await launchUrl(
                            Uri.parse(info.url),
                            mode: LaunchMode.externalApplication,
                          );
                        }
                      } catch (error) {
                        if (mounted) updateMessage = 'Ошибка проверки: $error';
                      } finally {
                        if (mounted) setState(() => checking = false);
                      }
                    },
                    child: const Text('Проверить'),
                  ),
          ),
        ),
        Card(
          child: ListTile(
            leading: const Icon(Icons.public),
            title: const Text('Карта сообщества'),
            subtitle: const Text(statisticsUrl),
            onTap: () => launchUrl(
              Uri.parse(statisticsUrl),
              mode: LaunchMode.externalApplication,
            ),
          ),
        ),
        Card(
          child: ListTile(
            leading: const Icon(Icons.vpn_key_outlined),
            title: const Text('Ваш ID'),
            subtitle: Text(widget.store.ownId),
            onTap: () => showDialog<void>(
              context: context,
              builder: (_) => AlertDialog(
                title: const Text('Анонимный ID'),
                content: SelectableText(widget.store.ownId),
              ),
            ),
          ),
        ),
        OutlinedButton.icon(
          onPressed: () async {
            await widget.store.clearHistory();
            if (mounted) setState(() {});
          },
          icon: const Icon(Icons.delete_outline),
          label: const Text('Очистить историю'),
        ),
        OutlinedButton.icon(
          onPressed: () async {
            await widget.store.rotateIdentity();
            if (mounted) setState(() {});
          },
          icon: const Icon(Icons.refresh),
          label: const Text('Сменить ID'),
        ),
      ],
    );
  }
}

class InviteDialog extends StatelessWidget {
  const InviteDialog({super.key, required this.store});
  final AppStore store;
  @override
  Widget build(BuildContext context) {
    final link = StreetPassCrypto.invite(store.identity, store.nickname);
    return AlertDialog(
      title: const Text('Моё приглашение'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            QrImageView(data: link, size: 220, backgroundColor: Colors.white),
            const SizedBox(height: 12),
            SelectableText(link, style: const TextStyle(fontSize: 11)),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('Закрыть'),
        ),
      ],
    );
  }
}

class ImportInviteDialog extends StatefulWidget {
  const ImportInviteDialog({super.key, required this.store});
  final AppStore store;
  @override
  State<ImportInviteDialog> createState() => _ImportInviteDialogState();
}

class _ImportInviteDialogState extends State<ImportInviteDialog> {
  final controller = TextEditingController();
  String? error;
  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Добавить друга'),
    content: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        TextField(
          controller: controller,
          minLines: 2,
          maxLines: 4,
          decoration: const InputDecoration(labelText: 'Ссылка-приглашение'),
        ),
        Align(
          alignment: Alignment.centerLeft,
          child: TextButton.icon(
            onPressed: () async {
              final value = await Navigator.push<String>(
                context,
                MaterialPageRoute(builder: (_) => const QrScannerPage()),
              );
              if (value != null) controller.text = value;
            },
            icon: const Icon(Icons.qr_code_scanner),
            label: const Text('Сканировать QR'),
          ),
        ),
        if (error != null)
          Text(
            error!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
      ],
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Отмена'),
      ),
      FilledButton(
        onPressed: () async {
          final invite = StreetPassCrypto.parseInvite(controller.text);
          if (invite == null) {
            setState(
              () => error = 'Ссылка повреждена или подпись не совпадает',
            );
            return;
          }
          await widget.store.editPeer(
            invite.peerId,
            nickname: invite.nickname,
            friend: true,
          );
          if (context.mounted) Navigator.pop(context);
        },
        child: const Text('Добавить'),
      ),
    ],
  );
}

class QrScannerPage extends StatelessWidget {
  const QrScannerPage({super.key});
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Сканировать приглашение')),
    body: MobileScanner(
      onDetect: (capture) {
        final value = capture.barcodes
            .map((barcode) => barcode.rawValue)
            .whereType<String>()
            .firstOrNull;
        if (value != null) Navigator.pop(context, value);
      },
    ),
  );
}

class PeerDialog extends StatefulWidget {
  const PeerDialog({super.key, required this.store, required this.peer});
  final AppStore store;
  final Peer peer;
  @override
  State<PeerDialog> createState() => _PeerDialogState();
}

class _PeerDialogState extends State<PeerDialog> {
  late final controller = TextEditingController(text: widget.peer.alias);
  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.peer.name),
    content: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('Встреч: ${widget.peer.count}'),
        TextField(
          controller: controller,
          decoration: const InputDecoration(labelText: 'Локальное имя'),
        ),
      ],
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Отмена'),
      ),
      FilledButton(
        onPressed: () async {
          await widget.store.editPeer(
            widget.peer.id,
            alias: controller.text,
            friend: !widget.peer.friend,
          );
          if (context.mounted) Navigator.pop(context);
        },
        child: Text(
          widget.peer.friend ? 'Убрать из друзей' : 'Добавить в друзья',
        ),
      ),
    ],
  );
}

class PeerTile extends StatelessWidget {
  const PeerTile({super.key, required this.store, required this.peer});
  final AppStore store;
  final Peer peer;
  @override
  Widget build(BuildContext context) => Card(
    child: ListTile(
      leading: CircleAvatar(
        child: Icon(peer.friend ? Icons.favorite : Icons.person_outline),
      ),
      title: Text(peer.name),
      subtitle: Text('${peer.count} встреч · ${peer.id}'),
      trailing: peer.friend
          ? const Icon(Icons.favorite, color: Colors.pinkAccent)
          : null,
      onTap: () => showDialog<void>(
        context: context,
        builder: (_) => PeerDialog(store: store, peer: peer),
      ),
    ),
  );
}

class _Metric extends StatelessWidget {
  const _Metric({required this.value, required this.label});
  final String value;
  final String label;
  @override
  Widget build(BuildContext context) => Expanded(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          value,
          overflow: TextOverflow.ellipsis,
          style: Theme.of(context).textTheme.titleLarge
              ?.copyWith(color: Theme.of(context).colorScheme.primary),
        ),
        Text(
          label,
          style: TextStyle(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
      ],
    ),
  );
}

class _MetricBlock extends StatelessWidget {
  const _MetricBlock(this.value, this.label);
  final String value, label;
  @override
  Widget build(BuildContext context) => SizedBox(
    width: 120,
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(value, style: Theme.of(context).textTheme.headlineSmall),
        Text(label),
      ],
    ),
  );
}

class _StatBlock extends _MetricBlock {
  const _StatBlock(super.value, super.label);
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle({required this.title, required this.trailing});
  final String title, trailing;
  @override
  Widget build(BuildContext context) => Row(
    mainAxisAlignment: MainAxisAlignment.spaceBetween,
    children: [
      Text(title, style: Theme.of(context).textTheme.titleLarge),
      Text(
        trailing,
        style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant),
      ),
    ],
  );
}

class _EmptyCard extends StatelessWidget {
  const _EmptyCard({required this.text});
  final String text;
  @override
  Widget build(BuildContext context) => Card(
    child: Padding(padding: const EdgeInsets.all(16), child: Text(text)),
  );
}

class _SettingSwitch extends StatelessWidget {
  const _SettingSwitch({
    required this.title,
    required this.value,
    required this.onChanged,
  });
  final String title;
  final bool value;
  final ValueChanged<bool> onChanged;
  @override
  Widget build(BuildContext context) => SwitchListTile(
    contentPadding: EdgeInsets.zero,
    title: Text(title),
    value: value,
    onChanged: onChanged,
  );
}

String _formatTime(DateTime time) =>
    '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
String _formatDate(DateTime time) =>
    '${time.day.toString().padLeft(2, '0')}.${time.month.toString().padLeft(2, '0')}.${time.year} ${_formatTime(time)}';
String _achievementName(String kind) =>
    {
      'people': 'Людей',
      'encounters': 'Встреч',
      'friends': 'Друзей',
      'friend_encounters': 'Встреч с другом',
      'streak': 'Дней подряд',
    }[kind] ??
    kind;
