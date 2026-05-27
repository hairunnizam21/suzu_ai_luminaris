# Deploy notes

## Backend (FastAPI)

Tested on Ubuntu 22.04. `install.sh` is idempotent — re-running it just
updates packages.

```bash
sudo ./install.sh    # python3.11, JDK 17, apktool, jadx, apksigner
./run.sh             # foreground; for systemd see below
```

### systemd unit (optional)

```ini
# /etc/systemd/system/suzu-luminaris.service
[Unit]
Description=SuzuAI Luminaris backend
After=network.target

[Service]
WorkingDirectory=/root/suzu_ai_luminaris/server
EnvironmentFile=/root/suzu_ai_luminaris/server/.env
ExecStart=/root/suzu_ai_luminaris/server/run.sh
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now suzu-luminaris
```

### Reverse proxy

You probably want HTTPS. A 10-line Caddyfile works:

```caddy
luminaris.example.com {
    reverse_proxy 127.0.0.1:8765
}
```

### Wipe state

```bash
rm -f server/sessions.db
rm -rf server/workspace server/apk_artifacts server/logs
./run.sh    # re-generates schema; SUZU_TOKEN stays the same
```

## Android APKs

Both APKs build from the same Gradle project:

```bash
./gradlew :panel:assembleDebug :client:assembleDebug   # debug
./gradlew :panel:assembleRelease :client:assembleRelease   # release (unsigned)
```

For signed release builds drop a keystore into `android/release.keystore`
and add the following to `~/.gradle/gradle.properties`:

```
SUZU_KEYSTORE_PATH=release.keystore
SUZU_KEYSTORE_PASSWORD=...
SUZU_KEY_ALIAS=...
SUZU_KEY_PASSWORD=...
```

The release build types pick those up automatically.
