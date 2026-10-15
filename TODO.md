# TODO

## Robustness
- [ ] Add a read timeout so a run on a dead connection fails instead of hanging (only a 15s connect timeout exists today).

## Extras (optional)
- [ ] Home-screen shortcuts for favorite commands.
- [ ] Tablet two-pane layout.

## Non-goals
- Command and script editing (the desktop app owns them; the phone can only edit a server's host, port and username).
- Password auth (key-only).
- Embedded terminal (the SSH Terminal button hands off to another app).
- Automatic config sync (re-import when the desktop config changes).
