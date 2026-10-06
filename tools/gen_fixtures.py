#!/usr/bin/env python3
"""Generate golden test fixtures from the desktop app's own functions.

Usage (from the desktop repo's venv, so tkinter/paramiko import):
    /path/to/desktop/venv/bin/python tools/gen_fixtures.py /path/to/desktop

Writes core/src/test/resources/fixtures/: a scripts/ tree saved by the desktop's
save_server(), its export_config() output, and expected results for the helpers.
Re-run it whenever the desktop's file formats or helpers change.
"""
import json
import os
import shutil
import sys
import tempfile

desktop = os.path.abspath(sys.argv[1])
sys.path.insert(0, desktop)
import ssh_deck as d  # noqa: E402

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "core", "src", "test", "resources", "fixtures")
out = os.path.normpath(out)

SERVERS = [
    {
        "name": "linux-box", "host": "build.example.test", "port": 22, "username": "dev",
        "key_path": "/home/dev/.ssh/id_ed25519", "login_shell": True,
        "commands": [
            {"label": "Git Pull", "script": "git-pull.sh", "color": "#007bff", "project": "AM"},
            {"label": "Déploiement ✓", "script": "deploiement.sh", "color": "#00c900", "project": "AM",
             "confirm": True},
            {"label": "Disk", "script": "disk.sh", "color": "#ff0000"},
            {"label": "Fetch build", "script": "download-build.sh", "color": "#666cff", "project": "CC"},
        ],
    },
    {
        "name": "win-pc", "host": "10.0.0.5", "port": 2222, "username": "me",
        "key_path": "~/.ssh/win \"key\"", "login_shell": False,
        "commands": [
            {"label": "Download APK", "script": "get-apk.sh", "color": "#28ceff", "project": "DD"},
        ],
    },
]

SCRIPTS = {
    "_shared/git-pull.sh": "cd ~/Projects/$PROJECT && git pull\n",
    "linux-box/AM/deploiement.sh": "echo 'déployé'\n./deploy.sh --prod\n",
    "linux-box/ALL/disk.sh": "  df -h  \n\n",
    "linux-box/_shared/download-build.sh":
        "#@download\n# comment line\nremote: /srv/builds/$PROJECT\nlocal: ~/Downloads/$PROJECT-build\n",
    "win-pc/DD/get-apk.sh":
        "#@download\nremote: C:\\Users\\me\\Builds\\Android\\app.apk\nLOCAL: ~/Downloads/app.apk\n",
}

tmp = tempfile.mkdtemp()
try:
    d.SCRIPTS_DIR = os.path.join(tmp, "scripts")
    d.save_servers(json.loads(json.dumps(SERVERS)))
    d.write_imported_scripts(SCRIPTS)
    servers, errors = d.load_servers()
    assert not errors, errors

    if os.path.exists(out):
        shutil.rmtree(out)
    shutil.copytree(d.SCRIPTS_DIR, os.path.join(out, "scripts"))
    with open(os.path.join(out, "export.json"), "w") as f:
        f.write(d.export_config(servers))

    script_content = {}
    for s in servers:
        for c in s["commands"]:
            key = f'{s["name"]}|{c["project"]}|{c["script"]}'
            script_content[key] = d.load_script_content(s["name"], c["project"], c["script"])

    directive_inputs = list(script_content.values()) + [
        "PROJECT='my proj'\n#@download\nremote: /a/$PROJECT\nlocal: ~/x/$PROJECT",
        "#@download\nremote: /only-remote",
        "echo hi\n#@download\nremote: /a\nlocal: /b",
        "\n\n  #@download  \n  remote :  /a:b  \n local: /c\n",
    ]
    labels = ["Git Pull", "  Build AAB ver++ ", "Déploiement ✓", "---", "rm Library", "UPPER_case 2"]
    ansi = [
        "\x1b[1;32mgreen\x1b[0m text\r\n",
        "a\n\n\n\n\nb",
        "\x1b]0;title\x07after",
        "\x1b(Bplain\x1b=\x1b>",
        "\x1b[?25lhidden\x1b[?25h",
    ]
    sftp = ["C:\\Users\\me\\Builds", "d:/data", "/srv/x", "relative\\path", "C:"]
    quote = ["", "simple", "with space", "it's", "a/b-c.d_e@f%g+h=i:j,k", "ümlaut", "$HOME"]
    golden = {
        "label_to_script_name": {s: d.label_to_script_name(s) for s in labels},
        "strip_ansi": [[s, d.strip_ansi(s)] for s in ansi],
        "to_sftp_path": {s: d.to_sftp_path(s) for s in sftp},
        "shlex_quote": [[s, d.shlex.quote(s)] for s in quote],
        "load_script_content": script_content,
        "parse_download_directive": [[s, d.parse_download_directive(s)] for s in directive_inputs],
    }
    with open(os.path.join(out, "golden.json"), "w") as f:
        json.dump(golden, f, indent=2, ensure_ascii=False)
finally:
    shutil.rmtree(tmp)

print(f"Wrote fixtures to {out}")
