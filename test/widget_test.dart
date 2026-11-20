import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:volume_remote/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  testWidgets('Volume Controller starts successfully', (WidgetTester tester) async {
    await tester.pumpWidget(const VolumeApp());
    await tester.pumpAndSettle();

    expect(find.text('Volume Controller'), findsOneWidget);
    expect(find.text('Niet verbonden'), findsOneWidget);
  });
}
