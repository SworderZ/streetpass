import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:streetpass_cross_platform/main.dart';

void main() {
  testWidgets('StreetPass home renders', (tester) async {
    SharedPreferences.setMockInitialValues({});
    await tester.pumpWidget(const StreetPassApp());
    await tester.pumpAndSettle();
    expect(find.text('StreetPass'), findsOneWidget);
    expect(find.text('Обнаружение'), findsOneWidget);
  });

  testWidgets('settings opens and saves nickname', (tester) async {
    SharedPreferences.setMockInitialValues({});
    await tester.pumpWidget(const StreetPassApp());
    await tester.pumpAndSettle();

    await tester.tap(find.byIcon(Icons.settings_outlined).first);
    await tester.pumpAndSettle();
    expect(find.byType(TextField), findsOneWidget);

    await tester.enterText(find.byType(TextField), 'Test laptop');
    await tester.tap(find.text('Сохранить'));
    await tester.pumpAndSettle();
    expect(find.text('Test laptop'), findsOneWidget);
  });
}
