#!/usr/bin/env python3
"""External ADB tests: real process boundaries/reboot/Doze, never an instrumentation process.
Only the disposable emulator and the opt-in .f04test package may be reset.
Observable-state polling has a deadline; it does not simulate WorkManager timing guarantees.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from zoneinfo import ZoneInfo

PACKAGE = 'com.glebskorobogatov.subscriptiontracker.f04test'
NAMESPACE = 'com.glebskorobogatov.subscriptiontracker'
REPORT = Path('app/build/reports/reminders-system-api35')
REPORT.mkdir(parents=True, exist_ok=True)
RESULTS = []


def adb(*args, timeout=40, check=True):
    p = subprocess.run(['adb', *args], capture_output=True, text=True, timeout=timeout)
    if check and p.returncode:
        raise AssertionError(f'adb {args}: {p.stdout} {p.stderr}')
    return p.stdout.strip()


def shell(*args, **kwargs):
    return adb('shell', *args, **kwargs)


def until(label, read, valid, timeout=30):
    end = time.monotonic() + timeout
    value = None
    while time.monotonic() < end:
        value = read()
        if valid(value):
            return value
        time.sleep(.3)
    raise AssertionError(f'{label}: deadline exceeded; last={value}')


def probe(command='inspect'):
    out = shell('am', 'broadcast', '-f', '0x10000000', '-n', f'{PACKAGE}/{NAMESPACE}.app.ReminderSystemProbeReceiver',
                '--es', 'command', command)
    match = re.search(r'Broadcast completed: result=(\d+), data="(.*)"', out)
    assert match, f'No ordered probe result: {out}'
    payload = json.loads(match[2])
    assert match[1] == '0', payload
    return payload


def start():
    shell('input', 'keyevent', 'KEYCODE_WAKEUP')
    shell('wm', 'dismiss-keyguard')
    shell('am', 'start', '-W', '-n', f'{PACKAGE}/{NAMESPACE}.app.MainActivity')


def ui_xml():
    shell('uiautomator', 'dump', '/sdcard/f04-ui.xml')
    return shell('cat', '/sdcard/f04-ui.xml')


def fixture():
    # Destructive ONLY for a fresh CI sandbox, behind emulator + exact package guards in main().
    assert shell('getprop', 'ro.kernel.qemu') == '1'
    assert PACKAGE.endswith('.f04test')
    shell('pm', 'clear', PACKAGE)
    shell('pm', 'grant', PACKAGE, 'android.permission.POST_NOTIFICATIONS')
    start()
    until('Main screen loaded', ui_xml, lambda x: 'Подписки' in x)
    until('Startup reconciliation completed', probe,
          lambda s: all(w['state'] not in ('RUNNING', 'ENQUEUED', 'BLOCKED') for w in s['work'] if w['control']))
    shell('input', 'keyevent', 'KEYCODE_BACK')
    until('Activity finished', lambda: shell('dumpsys', 'activity', 'activities'),
          lambda x: not any(PACKAGE in line and 'mResumedActivity' in line for line in x.splitlines()))
    state = probe('prepare')
    assert len(state['rows']) == 2 and all(r['state'] == 'PENDING' for r in state['rows']), state
    assert state['payments'] == 0 and not state['notifications'], state
    return state


def recover(before, kick=True):
    state = probe()
    assert state['fixture'] == before['fixture'], (before, state)
    old_id = before['fixture']['work']
    assert any(w['id'] == old_id for w in state['work']), state
    if kick:
        probe('kick')
    state = until('Native publish and durable acknowledgment', probe,
                  lambda s: len(s['rows']) == 2 and all(r['state'] == 'DELIVERED' for r in s['rows'])
                  and len(s['notifications']) == 1, timeout=45)
    assert state['payments'] == 0, state
    expected = f"reminder:v1:{state['fixture']['generation']}:{state['fixture']['subscription']}"
    assert state['notifications'][0]['tag'] == expected, state
    text = state['notifications'][0]['text']
    assert 'ожидается платёж' in text and 'крайний срок' in text, state
    probe('kick')
    repeated = probe()
    assert [r['id'] for r in state['rows']] == [r['id'] for r in repeated['rows']]
    assert len(repeated['notifications']) == 1, repeated
    return repeated


def kill_cached():
    old = shell('pidof', PACKAGE, check=False)
    assert old, 'No process to terminate'
    shell('am', 'kill', PACKAGE)
    until('Cached process actually terminated', lambda: shell('pidof', PACKAGE, check=False), lambda x: not x)
    assert 'stopped=true' not in shell('dumpsys', 'package', PACKAGE)
    return old


def activity_finished():
    before = fixture()
    after = recover(before)
    assert not any(PACKAGE in x and 'mResumedActivity' in x for x in shell('dumpsys', 'activity', 'activities').splitlines())
    return {'work': before['fixture']['work'], 'covered': len(after['rows'])}


def process_restart():
    before = fixture()
    old = kill_cached()
    after = probe()
    assert str(after['pid']) != old
    assert {r['id'] for r in after['rows']} == {r['id'] for r in before['rows']}
    recover(before)
    # Actual cold process launch via a SystemUI notification click, not Activity recreation.
    until('Workers completed', probe, lambda s: not any(w['state'] == 'RUNNING' for w in s['work']))
    kill_cached()
    shell('cmd', 'statusbar', 'expand-notifications')
    xml = until('Native notification visible in SystemUI', ui_xml, lambda x: 'F04 Test Service' in x)
    (REPORT / 'cold-notification.xml').write_text(xml, encoding='utf-8')
    node = next(n for n in ET.fromstring(xml).iter('node') if 'F04 Test Service' in n.get('text', ''))
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.attrib['bounds']))
    shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
    xml = until('Cold PendingIntent opens details', ui_xml,
                lambda x: 'F04 Test Service' in x and ('Изменить' in x or 'Редактировать' in x))
    (REPORT / 'cold-details.xml').write_text(xml, encoding='utf-8')
    (REPORT / 'cold-details.png').write_bytes(subprocess.check_output(['adb', 'exec-out', 'screencap', '-p']))
    shell('input', 'keyevent', 'KEYCODE_BACK')
    until('Back returns to subscriptions', ui_xml, lambda x: 'Подписки' in x and 'Редактировать' not in x)
    return {'old_pid': old, 'new_pid': after['pid'], 'cold_pending_intent': 'PASS'}


def reboot():
    before = fixture()
    boot_id = shell('cat', '/proc/sys/kernel/random/boot_id')
    adb('reboot')
    adb('wait-for-device', timeout=150)
    until('Real guest reboot completes', lambda: shell('getprop', 'sys.boot_completed'), lambda x: x == '1', timeout=180)
    assert boot_id != shell('cat', '/proc/sys/kernel/random/boot_id'), 'No genuine reboot'
    shell('input', 'keyevent', 'KEYCODE_WAKEUP'); shell('wm', 'dismiss-keyguard')
    # sys.boot_completed alone is not proof that credential-encrypted app data is available.
    user_state = until('User credential storage unlocked after reboot',
                       lambda: shell('am', 'get-started-user-state', '0'),
                       lambda x: 'RUNNING_UNLOCKED' in x, timeout=90)
    persisted = until('Saved fixture readable after unlock',
                      lambda: shell('run-as', PACKAGE, 'cat', 'files/f04-system-fixture.json', check=False),
                      lambda x: before['fixture']['work'] in x, timeout=30)
    assert json.loads(persisted) == before['fixture']
    after = recover(before)
    return {'same_work': before['fixture']['work'], 'covered': len(after['rows']),
            'real_reboot': True, 'user_state': user_state}



def force_stop():
    before = fixture()
    shell('am', 'force-stop', PACKAGE)
    assert 'stopped=true' in shell('dumpsys', 'package', PACKAGE)
    # No expectation of background execution while stopped. Resume only through launcher.
    start()
    after = recover(before, kick=False)
    return {'restored_after_allowed_launch': True, 'covered': len(after['rows'])}


def doze():
    before = fixture()
    try:
        shell('dumpsys', 'battery', 'unplug')
        shell('input', 'keyevent', 'KEYCODE_SLEEP')
        response = shell('dumpsys', 'deviceidle', 'force-idle')
        idle = shell('dumpsys', 'deviceidle')
        assert 'mState=IDLE' in idle, (response, idle)
        jobs = shell('dumpsys', 'jobscheduler')
        (REPORT / 'doze-jobs.txt').write_text(jobs, encoding='utf-8')
        assert PACKAGE in jobs, 'No persisted OS job in Doze'
    finally:
        shell('dumpsys', 'deviceidle', 'unforce', check=False)
        shell('dumpsys', 'battery', 'reset', check=False)
        shell('input', 'keyevent', 'KEYCODE_WAKEUP')
        shell('wm', 'dismiss-keyguard')
    after = recover(before)
    return {'deep_idle': True, 'covered': len(after['rows']), 'one_current_notification': True}


def timezone_receiver():
    before = fixture()
    recover(before)
    initial = shell('getprop', 'persist.sys.timezone')
    automatic = shell('settings', 'get', 'global', 'auto_time_zone')
    visited = []
    try:
        shell('settings', 'put', 'global', 'auto_time_zone', '0')
        for zone in ('Europe/Amsterdam', 'Asia/Yekaterinburg', 'Europe/Amsterdam'):
            old_controls = {w['id'] for w in probe()['work'] if w['control']}
            response = shell('cmd', 'alarm', 'set-timezone', zone)
            assert 'SecurityException' not in response and 'Unknown command' not in response, response
            until('System timezone changed', lambda: shell('getprop', 'persist.sys.timezone'), lambda x: x == zone)
            state = until('Receiver reconciliation observed', probe,
                          lambda x: x['zone'] == zone and any(w['control'] and w['id'] not in old_controls and w['state'] == 'SUCCEEDED' for w in x['work']))
            assert state['currentTarget'] == before['fixture']['target']
            assert state['time'] == before['time']
            assert len(state['rows']) == 2 and all(r['state'] == 'DELIVERED' for r in state['rows'])
            assert len(state['notifications']) <= 1
            visited.append(zone)
    finally:
        shell('cmd', 'alarm', 'set-timezone', initial, check=False)
        if automatic == 'null':
            shell('settings', 'delete', 'global', 'auto_time_zone', check=False)
        else:
            shell('settings', 'put', 'global', 'auto_time_zone', automatic, check=False)
    return {'zones': visited, 'same_target_date': True, 'no_second_processing': True}


class NotRun(Exception):
    """Only explicit missing system capability, never an application assertion failure."""


def set_clock(iso):
    epoch = int(datetime.fromisoformat(iso).replace(tzinfo=ZoneInfo('Europe/Amsterdam')).timestamp()*1000)
    result = shell('cmd', 'alarm', 'set-time', str(epoch), check=False)
    if any(word in result for word in ('SecurityException', 'Permission Denial', 'Unknown command')):
        raise NotRun('Emulator shell cannot set wall clock: ' + result)
    until('Actual wall clock moved', lambda: int(shell('date', '+%s')),
          lambda x: abs(x-epoch//1000) < 20)


def wall_clock_receiver():
    automatic = shell('settings', 'get', 'global', 'auto_time')
    automatic_zone = shell('settings', 'get', 'global', 'auto_time_zone')
    initial_zone = shell('getprop', 'persist.sys.timezone')
    try:
        shell('settings', 'put', 'global', 'auto_time', '0')
        shell('settings', 'put', 'global', 'auto_time_zone', '0')
        shell('cmd', 'alarm', 'set-timezone', 'Europe/Amsterdam')
        set_clock('2027-12-31T23:55:00')
        before = fixture()
        assert before['currentTarget'] == '2028-01-01'
        set_clock('2028-01-01T00:00:10')
        after = recover(before, kick=False)  # TIME_SET receiver must request real reconciliation.
        assert 'Сегодня' in after['notifications'][0]['text'], after
        set_clock('2027-12-31T23:56:00')
        after = until('Clock rollback cleans stale day copy', probe,
                      lambda x: not x['notifications'] and all(r['state'] == 'DELIVERED' for r in x['rows']))
        assert {r['id'] for r in before['rows']} == {r['id'] for r in after['rows']}
        set_clock('2028-01-02T00:00:10')
        after = until('Expired manual target has no delivery work', probe,
                      lambda x: not x['notifications'] and not any(w['delivery'] and w['state'] in ('ENQUEUED', 'RUNNING', 'BLOCKED') for w in x['work']))
        assert sum(w['state'] in ('ENQUEUED', 'RUNNING', 'BLOCKED') for w in after['work']) <= 2
        return {'local_midnight': True, 'year_rollover': True, 'rollback_no_duplicate': True, 'expired_target': True}
    finally:
        # Host UTC is unaffected by guest changes; restore wall clock and original automatic settings.
        shell('cmd', 'alarm', 'set-time', str(int(time.time()*1000)), check=False)
        shell('cmd', 'alarm', 'set-timezone', initial_zone, check=False)
        for key, value in [('auto_time', automatic), ('auto_time_zone', automatic_zone)]:
            if value == 'null':
                shell('settings', 'delete', 'global', key, check=False)
            else:
                shell('settings', 'put', 'global', key, value, check=False)


def main():
    assert shell('getprop', 'ro.kernel.qemu') == '1', 'Refusing to run on a physical device'
    assert PACKAGE == 'com.glebskorobogatov.subscriptiontracker.f04test'
    apk = 'app/build/outputs/apk/debug/app-debug.apk'
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    assert sdk, 'Missing Android SDK for APK identity check'
    badging = subprocess.check_output([str(Path(sdk)/'build-tools/35.0.0/aapt'), 'dump', 'badging', apk], text=True)
    assert re.search(r"^package: name='" + re.escape(PACKAGE) + r"'", badging), 'Refusing non-isolated APK'
    adb('install', '-r', '-t', apk)
    for name, test in [('finished_activity', activity_finished), ('process_and_cold_intent', process_restart),
                       ('real_reboot', reboot), ('force_stop_resume', force_stop), ('doze_recovery', doze), ('timezone_receiver', timezone_receiver), ('wall_clock_receiver', wall_clock_receiver)]:
        try:
            result = {'scenario': name, 'status': 'PASS', 'evidence': test()}
        except NotRun as error:
            result = {'scenario': name, 'status': 'NOT RUN', 'reason': str(error)}
        except Exception as error:
            result = {'scenario': name, 'status': 'FAIL', 'error': str(error)}
            try:
                platform = adb('logcat', '-d', '-t', '300', '-s', 'ActivityManager', 'BroadcastQueue', 'AndroidRuntime', 'ReminderWorker')
                (REPORT / f'{name}-logcat.txt').write_text(platform, encoding='utf-8')
                print('SYSTEM_DIAGNOSTICS ' + name + '\n' + '\n'.join(platform.splitlines()[-35:]), flush=True)
            except Exception as diagnostic_error:
                result['diagnostic_error'] = str(diagnostic_error)
        RESULTS.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
        (REPORT / 'results.json').write_text(json.dumps(RESULTS, ensure_ascii=False, indent=2), encoding='utf-8')
    assert all(r['status'] in ('PASS', 'NOT RUN') for r in RESULTS), 'System scenarios failed; inspect results.json'


if __name__ == '__main__':
    main()
