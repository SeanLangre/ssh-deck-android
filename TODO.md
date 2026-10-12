# TODO

## Robustness
- [ ] Add a read timeout so a run on a dead connection fails instead of hanging (only a 15s connect timeout exists today).

## Extras (optional)
- [ ] Home-screen shortcuts for favorite commands.
- [ ] Tablet two-pane layout.

## Non-goals
- Server, command and script editing (the desktop app owns the config; the phone only imports and runs it).
- Password auth (key-only).
- Embedded terminal (the SSH Terminal button hands off to another app).
- Automatic config sync (re-import when the desktop config changes).
