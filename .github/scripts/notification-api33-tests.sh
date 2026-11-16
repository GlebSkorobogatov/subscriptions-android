#!/usr/bin/env bash
set -euo pipefail
report_dir=app/build/reports/notifications-api33
mkdir -p "$report_dir"
# Run permission flow first, with no install -g. Runtime denial must be genuine Android state.
failed=0
for spec in NotificationPermissionUiTest:2 NotificationNavigationUiTest:8 NotificationPublisherTest:18 ReminderSystemRegressionTest:15; do
  test_class="${spec%:*}"
  expected="${spec##*:}"
  test_log="$report_dir/$test_class.log"
  if ! adb shell am instrument -w -r \
    -e class "com.glebskorobogatov.subscriptiontracker.notifications.$test_class" \
    com.glebskorobogatov.subscriptiontracker.test/androidx.test.runner.AndroidJUnitRunner > "$test_log" 2>&1; then
    failed=1
  fi
  # Capture only relevant platform diagnostics; this disposable emulator has fake test data.
  adb logcat -d -s BroadcastQueue ActivityManager AndroidRuntime > "$report_dir/$test_class-platform.log" || true
  cat "$test_log"
  if ! grep -Eq "OK \($expected tests?\)" "$test_log" || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_RESULT: shortMsg=|INSTRUMENTATION_STATUS_CODE: -[1-4]' "$test_log"; then
    failed=1
  fi
done
exit "$failed"
