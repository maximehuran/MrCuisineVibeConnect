"""The test installer is deliberately restricted to an Android 6 CI emulator."""
from pathlib import Path
import subprocess

def adb(*args):
    return subprocess.check_output(['adb', '-s', 'emulator-5554', *args], text=True).strip()

assert adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
assert adb('shell', 'getprop', 'ro.build.version.sdk') == '23'
adb('install', '-r', 'app/build/outputs/apk/release/app-release.apk')
adb('install', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
result = adb('shell', 'am', 'instrument', '-w', 'app.toque.test/app.toque.StandbyWifiSmoke')
print(result)
assert 'STANDBY_WIFI_SMOKE_OK' in result
assert 'STANDBY_WIFI_SMOKE_FAILED' not in result
Path('android-smoke').mkdir(exist_ok=True)
for name in ['settings-wifi.png', 'source-checked-date.png']:
    adb('pull', '/sdcard/Android/data/app.toque/files/' + name, 'android-smoke/' + name)
