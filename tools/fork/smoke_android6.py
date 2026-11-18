#!/usr/bin/env python3
"""Install both APKs on a CI Android 6 emulator only; verify persistent separate data and HTTPS."""
import os
from pathlib import Path
import subprocess


def adb(*args):
    result = subprocess.run(["adb", "-s", "emulator-5554", *args], capture_output=True, text=True, timeout=150)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout.strip()


def install(path):
    result = adb("install", "-r", str(path))
    if "Success" not in result:
        raise RuntimeError(result)


def test(edition, action):
    adb("shell", "am", "force-stop", "app.toque")
    result = adb("shell", "am", "instrument", "-w", "-e", "action", action,
                 "app.toque.test/app.toque.EditionSmoke")
    print(result)
    if f"EDITION_SMOKE_OK:{edition}:{action}" not in result or "EDITION_SMOKE_FAILED" in result:
        raise RuntimeError(f"Android smoke failed: {edition}/{action}")


if __name__ == "__main__":
    if os.environ.get("CI") != "true" or adb("shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("This test is restricted to a CI emulator")
    if adb("shell", "getprop", "ro.build.version.sdk") != "23":
        raise RuntimeError("Expected Android 6 / API 23")
    fork = Path("app/build/outputs/apk/release/app-release.apk")
    reference = Path(os.environ["REFERENCE_BUILD_DIR"]) / "app/build/outputs/apk/release/app-release.apk"
    install(fork)
    install(Path("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    test("fork", "seed")
    install(reference)
    test("reference", "seed")
    test("reference", "tls")
    install(fork)
    test("fork", "check")
    test("fork", "tls")
    install(reference)
    test("reference", "check")
    install(fork)
    test("fork", "check")
    print("ANDROID_6_EDITION_ROUNDTRIP_AND_TLS_OK")
