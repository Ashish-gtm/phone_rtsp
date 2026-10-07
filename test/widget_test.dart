import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:phone_rtsp/main.dart';

void main() {
  testWidgets('shows the LAN RTSP camera controls', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const RtspCameraApp());
    await tester.pump();

    expect(find.text('Phone RTSP'), findsOneWidget);
    expect(find.text('READY TO STREAM'), findsOneWidget);
    expect(find.text('rtsp://8554/live'), findsNothing);
    expect(find.text('1080p'), findsOneWidget);
    expect(find.text('25 FPS'), findsNWidgets(2));
    expect(find.text('MAIN STREAM SETTINGS'), findsOneWidget);
    expect(find.text('Resolution'), findsOneWidget);
    expect(find.text('Frame rate'), findsOneWidget);
    expect(find.text('4 Mbps'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('RTSP LOGIN'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('RTSP LOGIN'), findsOneWidget);
    expect(find.text('Username'), findsOneWidget);
    expect(find.text('Password'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('Start streaming'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('Start streaming'), findsOneWidget);
    expect(find.text('RTSP ADDRESSES'), findsOneWidget);
    expect(find.text('Main stream'), findsOneWidget);
    expect(find.text('Substream'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('DVR CONNECTION MODE'),
      300,
      scrollable: find.byType(Scrollable).first,
    );
    expect(find.text('DVR CONNECTION MODE'), findsOneWidget);
    expect(find.text('ONVIF'), findsOneWidget);
    await tester.tap(find.text('ONVIF'));
    await tester.pump();
    expect(
      find.text(
        'ONVIF discovery is enabled; video still uses RTSP. Discovery requires multicast between the phone and DVR.',
      ),
      findsOneWidget,
    );
    expect(find.text('ONVIF device service'), findsOneWidget);
  });
}
