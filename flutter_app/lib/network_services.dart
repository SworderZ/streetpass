import 'dart:convert';
import 'dart:io';

import 'app_store.dart';

class UpdateInfo {
  const UpdateInfo({
    required this.current,
    required this.latest,
    required this.url,
  });
  final String current;
  final String latest;
  final String url;
  bool get available => _version(latest) > _version(current);
}

class UpdateService {
  static Future<UpdateInfo> check(String current) async {
    final client = HttpClient();
    try {
      final request = await client.getUrl(
        Uri.parse(
          'https://api.github.com/repos/SworderZ/streetpass/releases/latest',
        ),
      );
      request.headers.set(
        HttpHeaders.acceptHeader,
        'application/vnd.github+json',
      );
      final response = await request.close();
      if (response.statusCode != 200) {
        throw HttpException('GitHub: HTTP ${response.statusCode}');
      }
      final data = jsonDecode(
        await response.transform(utf8.decoder).join(),
      ) as Map<String, dynamic>;
      return UpdateInfo(
        current: current,
        latest: data['tag_name']?.toString() ?? current,
        url:
            data['html_url']?.toString() ??
            'https://github.com/SworderZ/streetpass/releases',
      );
    } finally {
      client.close(force: true);
    }
  }
}

int _version(String value) {
  final parts = value
      .replaceFirst(RegExp(r'^v'), '')
      .split('.')
      .map((part) => int.tryParse(part) ?? 0)
      .toList();
  return (parts.isNotEmpty ? parts[0] : 0) * 1000000 +
      (parts.length > 1 ? parts[1] : 0) * 1000 +
      (parts.length > 2 ? parts[2] : 0);
}

class TelemetryService {
  static Future<bool> send(AppStore store) async {
    if (!store.settings.shareStats || store.settings.country.isEmpty) {
      return false;
    }
    final client = HttpClient();
    try {
      final request = await client.postUrl(
        Uri.parse('https://streetpass.coolify.megaworld.space/v1/telemetry'),
      );
      request.headers.contentType = ContentType.json;
      request.write(
        jsonEncode({
          'installation_id': store.installationId,
          'country': store.settings.country,
          'app_version': '0.8.3',
        }),
      );
      final response = await request.close();
      return response.statusCode >= 200 && response.statusCode < 300;
    } catch (_) {
      return false;
    } finally {
      client.close(force: true);
    }
  }
}
