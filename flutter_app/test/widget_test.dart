import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:flutter_app/main.dart';

void main() {
  testWidgets('StreetPass home renders', (tester) async {
    SharedPreferences.setMockInitialValues({});
    await tester.pumpWidget(const StreetPassApp());
    await tester.pumpAndSettle();
    expect(find.text('StreetPass'), findsOneWidget);
    expect(find.text('Обнаружение'), findsOneWidget);
  });
}
