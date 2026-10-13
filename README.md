# ssh-deck-android

Android port of the desktop [SSH Deck](https://github.com/SeanLangre/ssh-deck) (Python/Tkinter/Paramiko), in Kotlin and Jetpack Compose.

**Requires the desktop [SSH Deck](https://github.com/SeanLangre/ssh-deck) (Windows/Linux).** The phone app only runs commands; servers, commands and scripts are created and edited in the desktop app and imported from there.

## Using it

1. **Import** a config: Copy Config on the desktop, then Import on the phone (or load the exported `.json` file).
2. **Device key**: the app logs in with an Ed25519 key generated on the phone (its private key is encrypted with the Android Keystore and never leaves the device). Add the shown line to `~/.ssh/authorized_keys` on each server. On Windows, admin accounts use `C:\ProgramData\ssh\administrators_authorized_keys`.
3. Tap a server to open its deck, then tap a command button. Each run gets its own output screen; runs keep going while you navigate and show under **Recent runs**. They also continue when the app is backgrounded or the screen locks: a foreground service keeps the connection alive and shows a notification while commands are running. On Android 13+ the app asks for notification permission at launch; runs work without it, you just won't see the notification.

Host keys are trusted on first connect, like the desktop does. A changed key is refused until you confirm it. `#@download` scripts save into the phone's `Download/` folder under the last part of their `local:` path, so `~/Downloads/android-build` becomes `Download/android-build/`.

## Layout

```
├── core/                 # Pure JVM logic, unit-tested without a device
│   └── src/main/kotlin/dev/sshdeck/core/
│       ├── ConfigStore.kt        # scripts/ folder: load/save servers, export/import, script lookup
│       ├── Model.kt              # Server, QuickCommand
│       ├── DownloadDirective.kt  # #@download scripts
│       ├── Ssh.kt                # SshRunner: exec + SFTP download over sshj, host-key trust
│       ├── PyJson.kt             # JSON writer matching Python's json.dump(indent=2)
│       └── Text.kt               # strip_ansi, label_to_script_name, shlex, ...
├── app/                  # Android app (Compose)
└── tools/gen_fixtures.py # Regenerates core's golden test fixtures from the desktop app
```

The app keeps the desktop's `scripts/` layout in its private storage (`filesDir/scripts`). `server.json` and `commands.json` are written byte-for-byte like the desktop writes them, and the Copy Config JSON works in both directions.

## Prerequisites and packages

- **Android SDK** with platform 36 (`minSdk` 26, `targetSdk` 36), plus `adb` to install on a device.
- **JDK 25** for Gradle, downloaded automatically on first run. The code itself compiles to Java 17.
- **Gradle 9.8** via the included wrapper, so nothing to install. Android Gradle Plugin 9.4 and Kotlin 2.4.
- **Python 3** with the desktop app's dependencies (optional). Building and testing don't need it, because the fixtures are committed. It's only for regenerating them, see [Desktop compatibility fixtures](#desktop-compatibility-fixtures).

Main libraries (versions in `gradle/libs.versions.toml`):

| Package | Used for |
|---------|----------|
| Jetpack Compose (Material 3) | UI |
| sshj + BouncyCastle | SSH exec and SFTP, Ed25519 keys |
| kotlinx-serialization-json | Config import/export |
| kotlinx-coroutines | Background runs and output streaming |
| Apache MINA sshd (tests only) | Embedded SSH server for tests |

## Build and test

Requires the Android SDK (path in `local.properties` as `sdk.dir=...`). The Gradle daemon runs on JDK 25, which Gradle downloads on first run (`gradle/gradle-daemon-jvm.properties`), so the terminal and Android Studio build the same way.

```
./gradlew :core:test           # logic, desktop compatibility, and SSH tests (embedded sshd)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Desktop compatibility fixtures

The phone and the desktop share config files (`server.json`, `commands.json`, the Copy Config JSON), so the Kotlin code has to read and write them exactly like the Python desktop app does. Rather than describing that format by hand, the tests take the desktop app itself as the reference:

1. `tools/gen_fixtures.py` imports the desktop app (`ssh_deck.py`) and runs its own functions (`save_server()`, `export_config()`, the `#@download` parser, `strip_ansi`, `label_to_script_name`, ...) on two made-up servers.
2. It writes what they produce to `core/src/test/resources/fixtures/`: a `scripts/` tree as the desktop saves it, `export.json`, and `golden.json` with the expected helper results.
3. `DesktopCompatTest` runs the Kotlin port on the same input and checks that the output is identical, down to the bytes of the saved files.

The fixtures are committed, so `./gradlew :core:test` needs no Python. Regenerate them only when the desktop app changes its file formats or one of those helpers. Run it from the desktop app's venv so its imports (Tkinter, Paramiko) resolve:

```
/path/to/desktop/venv/bin/python tools/gen_fixtures.py /path/to/desktop
```

Then run `./gradlew :core:test`. A failure shows where the Kotlin port no longer matches the desktop.
