# SSH Deck Android: plan

Android port of the desktop SSH Deck (Python/Tkinter/Paramiko), in Kotlin and Jetpack Compose.

**Scope:** the phone is a runner, not an editor. The desktop app is the only place where servers, commands and scripts are created or edited. The phone imports that config and runs it.

## Stack

| Concern         | Desktop             | Android                                                     |
|-----------------|---------------------|-------------------------------------------------------------|
| Language/UI     | Python + Tkinter    | Kotlin + Jetpack Compose (Material 3)                       |
| SSH exec + SFTP | Paramiko            | sshj (+ BouncyCastle)                                       |
| Concurrency     | threads + root.after| Coroutines + StateFlow                                      |
| Storage         | `scripts/` dir      | Same tree under `context.filesDir/scripts/`                 |

## Config flow (import only)

- Copy Config on the desktop, then Import on the phone (clipboard or the exported `.json` file).
- `server.json`, `commands.json` and the export JSON stay byte-compatible with the desktop, so the same config round-trips.
- Importing replaces or updates the phone's copy. There is no editing on the phone.
- `tools/gen_fixtures.py` regenerates the golden fixtures from the desktop app's own functions, and the tests check the Kotlin port against them.

## What is ported

- Config load/export/import and safe script paths (`ConfigStore`).
- Script lookup chain: project, then `<server>/_shared`, then global `_shared`, plus the `PROJECT=` prefix.
- `#@download` directive parsing, `label_to_script_name`, `strip_ansi`, `format_elapsed`.
- SSH exec as a stream of output lines (command/output/error), and recursive SFTP download.
- Windows servers: login-shell toggle and `to_sftp_path` behaviour.

## Android-specific decisions

1. **Auth:** key-only, as on the desktop. The app generates an Ed25519 key on the phone, encrypted with the Android Keystore. The user adds the shown public key to `authorized_keys` on each server. The `key_path` from an imported config is ignored. No password auth.
2. **Host keys:** trust on first use. A changed key is refused until the user confirms.
3. **Downloads:** `#@download` scripts save into the phone's `Download/` folder under the last segment of their `local:` path.
4. **Output:** each run gets its own full-screen output screen. Runs keep going while the user navigates and are listed under Recent runs.
5. **SSH Terminal button:** launches an `ssh://user@host:port` intent for Termux, JuiceSSH or Termius. No embedded terminal.

## Screens

1. Server list: one card per imported server.
2. Server deck: quick-command buttons grouped by project with their colors, an ad-hoc command input, and Recent runs.
3. Run output: live colored stream, elapsed time, cancel, share/save log.
4. Import: paste or open a config file, and show the device public key.

## Phases

1. **Core (done):** Gradle project, models, file-store port, JSON compatibility tests against desktop fixtures.
2. **SSH layer (done):** connect, exec streaming, SFTP download, host-key verification, tested against an embedded sshd.
3. **MVP UI (done):** server list, deck, run output, import, device key.
4. **Robustness (remaining):**
   - Foreground service with a progress notification, so a long `git pull` or build survives switching apps.
   - Cancellation, connect and read timeouts, and a clear error when a run fails.
   - Share/save log from the output screen.
5. **Extras (optional):** home-screen shortcuts for favorite commands, tablet two-pane layout.

## Out of scope

- Server editor, command editor (add/rename/recolor/reorder/remove).
- Script editor and Open Folder.
- Password auth.
- Embedded terminal.
- Keeping configs in sync automatically. The user re-imports when the desktop config changes.
