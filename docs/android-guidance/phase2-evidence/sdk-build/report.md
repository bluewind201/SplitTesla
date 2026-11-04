# Official Android SDK installation and debug APK build

The user explicitly confirmed Android SDK license acceptance. Official Android command-line tools were used from `.tools/android-sdk/cmdline-tools/latest` (sdkmanager 19.0); accepted package licenses are recorded in `license-acceptance.log`. The SDK root is `.tools/android-sdk`, excluded by the repository's `.tools/` rule; no `local.properties` was created.

## Installed packages

- Android SDK Platform 35 (`platforms;android-35`, package revision 2), matching `app/build.gradle.kts` `compileSdk = 35`.
- Android SDK Build-Tools 34.0.0 (`build-tools;34.0.0`), compatible with the project AGP 8.7.3 setup; project Gradle/plugin versions were not changed.
- Android SDK Platform-Tools 37.0.1.

Exact installed package output is in `packages-installed.txt`. The project Gradle wrapper is 8.14.3. Java 21 from `/home/orca/project_folder/SplitTesla/tools/jdk-21` was used.

## Build

Command:

```sh
ANDROID_HOME="$PWD/.tools/android-sdk" ANDROID_SDK_ROOT="$PWD/.tools/android-sdk" \
JAVA_HOME=/home/orca/project_folder/SplitTesla/tools/jdk-21 \
./gradlew :app:assembleDebug --no-daemon \
  -Dorg.gradle.jvmargs='-Xmx2048m -XX:MaxMetaspaceSize=1024m'
```

Result: **BUILD SUCCESSFUL** (5m45s, 42 actionable tasks, 35 executed, 7 up-to-date). Full output is `assemble-debug.log`. No application source changes were needed; P2-8 source manifest re-verification is recorded in `p2-8-source-verify.log`.

APK: `app/build/outputs/apk/debug/app-debug.apk`, 42,537,973 bytes. `apk-signature.txt` confirms APK Signature Scheme v2 and the standard Android Debug certificate (`CN=Android Debug, O=Android, C=US`; SHA-256 `8cfca390774f8839e226278834ea825c64f64b2fb4f6c8bf1d06bfebcce83aba`). This is a locally signed debug build, not a release signature.

Warnings: D8 logged repeated `Expected stack map table for method with non-linear control flow` warnings for the third-party Naver map SDK; Kotlin emitted an existing `quadraticBezierTo` deprecation warning in `ManeuverIcon.kt`; two native libraries could not be stripped and were packaged intact. The Kotlin daemon startup reported an unexpected termination, then Kotlin compilation continued and the complete Gradle task succeeded. No install, emulator/device run, distribution, commit, or push was performed in this build task.

## Emulator runtime image attempt

For a real runtime capture, the official Android Emulator 37.2.13 and Google APIs x86_64 System Image for API 35 (revision 9) were installed under the ignored `.tools/android-sdk` path and a Pixel 2 AVD was created. The container has no `/dev/kvm` (`emulator -accel-check`: `/dev/kvm is not found: VT disabled in BIOS or KVM kernel module not loaded`), so the emulator ran in TCG software mode (`-accel off`). It remained at ADB `offline` for more than three minutes; after roughly seven minutes it changed to `device`, but `sys.boot_completed` stayed empty and the Android `package` service was unavailable. Installing the APK failed exactly with `cmd: Can't find service: package`, so no app was launched and no runtime screenshot was produced. The emulator process was stopped. Logs: `emulator.log`, `emulator-app-install.log`, `emulator-final-devices.txt`; package versions are in `packages-installed.txt`. `emulator -version` also reports missing host `libpulse.so.0`, though the emulator process itself launched; no host packages were changed.

The emulator/system image are local and ignored under `.tools`; no APK, SDK, or AVD files are included in source control. This environmental blocker is not represented by a mock or designed image.
