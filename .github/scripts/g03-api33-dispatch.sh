#!/usr/bin/env bash
set -euo pipefail

adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

if [ "${GITHUB_HEAD_REF:-}" = 'codex/g-03-accessibility-themes-polish' ]; then
  mkdir -p app/build/reports/g03-api33
  adb shell am instrument -w -r \
    -e class 'com.glebskorobogatov.subscriptiontracker.ui.DesignSystemUiTest,com.glebskorobogatov.subscriptiontracker.ui.review.LargeFontWindowUiTest,com.glebskorobogatov.subscriptiontracker.ui.finance.LargeFontChartUiTest' \
    com.glebskorobogatov.subscriptiontracker.test/androidx.test.runner.AndroidJUnitRunner \
    > app/build/reports/g03-api33/targeted.log 2>&1
  cat app/build/reports/g03-api33/targeted.log
  grep -Eq 'OK \([0-9]+ tests?\)' app/build/reports/g03-api33/targeted.log
else
  adb shell pm revoke com.glebskorobogatov.subscriptiontracker android.permission.POST_NOTIFICATIONS
  adb shell pm clear-permission-flags com.glebskorobogatov.subscriptiontracker android.permission.POST_NOTIFICATIONS user-set user-fixed
  bash .github/scripts/notification-api33-tests.sh
fi
