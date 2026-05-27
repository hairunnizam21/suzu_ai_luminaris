# SuzuAI Luminaris

Self-hosted Devin-style coding agent split into two Android apps and a Python backend.

```
┌─────────────────────────┐     HTTPS     ┌──────────────────────────────┐
│ Server Panel APK        │ ◄──────────► │  Backend (FastAPI)            │
│ com.suzuai.luminaris    │               │  - stores SSH + AI config    │
│ .panel                  │               │  - runs the agent loop       │
│                         │               │  - executes shell over SSH   │
│ - SSH form              │               │  - decompile/recompile APK  │
│ - AI provider form      │               │  - serves logs (SSE)         │
│ - Live logs             │               │  - serves rebuilt APKs       │
│ - APK artifacts         │               │                              │
└─────────────────────────┘               └──────────────────────────────┘
                                                    ▲
┌─────────────────────────┐                         │
│ AI Client APK           │ ────────────────────────┘
│ com.suzuai.luminaris    │   refuses to operate
│ .app                    │   until backend reports
│                         │   "config ready".
│ - Sidebar Devin-style:  │
│   Sessions / Ask / Wiki │
│   Review / Automations  │
│ - Streaming chat        │
└─────────────────────────┘
```

## Repo layout

| Path | What it is |
|---|---|
| `android/shared/` | Shared Kotlin library — theme, networking, models, settings store |
| `android/panel/` | **Server Panel APK** (`com.suzuai.luminaris.panel`) |
| `android/client/` | **AI Client APK** (`com.suzuai.luminaris.app`) |
| `server/` | FastAPI backend (Python 3.11) — deploy to your VPS |
| `docs/` | Architecture + deploy notes |

## Status

Functional skeleton:

- [x] Two-APK Android project with shared theme and networking.
- [x] Server Panel: SSH form, AI provider form, live log viewer, APK artifact list.
- [x] AI Client: Devin-style sidebar (Sessions / Ask / Wiki / Review / Automations) with full streaming chat for Sessions + Ask.
- [x] FastAPI backend with config CRUD, agent loop, SSE logs, APK decompile/recompile/sign.
- [x] Inter-app contract: client polls `/v1/ready` and stays disabled until panel posts a complete config.
- [ ] Wiki / Review / Automations are placeholder screens — extend on the same `BackendClient` contract.

## Quick start

### Backend (Ubuntu 22.04, 4 GB RAM is enough for the agent loop)

```bash
git clone https://github.com/hairunnizam21/suzu_ai_luminaris.git
cd suzu_ai_luminaris/server
sudo ./install.sh          # installs Python 3.11, JDK 17, apktool, jadx, apksigner
./run.sh                   # FastAPI on :8765, prints SUZU_TOKEN
```

The auth token is written to `server/.env` as `SUZU_TOKEN=...`. You paste this
token into the Server Panel APK once.

### Android APKs

```bash
cd suzu_ai_luminaris/android
./gradlew :panel:assembleDebug :client:assembleDebug
# -> panel/build/outputs/apk/debug/panel-debug.apk
# -> client/build/outputs/apk/debug/client-debug.apk
```

### First-time setup on phone

1. Install both APKs.
2. Open **SuzuAI Luminaris Panel**:
   - Backend URL: `https://your.server:8765` (or `http://...:8765` for local)
   - Token: paste the value of `SUZU_TOKEN`
   - Fill SSH host / port / user / password (or private key).
   - Fill AI provider kind / base URL / model / API key.
   - Tap **Save & verify** — when both rows go green, config is ready.
3. Open **SuzuAI Luminaris** (client) — it auto-detects the panel via the same
   backend URL and token. Start a session from the sidebar.

## License

MIT — see [`LICENSE`](./LICENSE).
