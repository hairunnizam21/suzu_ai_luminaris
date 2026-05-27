# Changelog

## 0.1.0 — Initial bootstrap

### Added
- Two Android APKs from a single Gradle multi-module project
  - `panel-debug.apk` (`com.suzuai.luminaris.panel`) — Server admin panel
  - `client-debug.apk` (`com.suzuai.luminaris.app`) — Devin-style chat client
- Shared Compose library: theme, OkHttp + SSE networking, DataStore-backed setup store
- FastAPI backend (`server/`) with SQLite storage, agent loop, and tools
  - `shell`, `read_file`, `write_file`, `list_dir`
  - `apk_decompile`, `apk_recompile`, `apk_sign` (apktool 2.9.3 + apksigner)
  - `web_get` for fetching public docs
- Provider adapters for **OpenAI-compatible** (covers fiqstr, OpenRouter, Together, Groq, LiteLLM…) and **Anthropic**
- Live log fan-out via SSE (`/v1/admin/logs/stream`)
- APK artifact registry — every signed APK is downloadable from the panel
- **Backup / Transfer** — `/v1/admin/backup/{export,import}` exchange a single zip containing config + SQLite + APK artifacts, so the user can move their entire workspace to a new VPS without losing history
- One-shot installer `server/install.sh` that bootstraps python3.11, jdk-17, apktool, jadx, apksigner, plus a generated `SUZU_TOKEN`
- `systemd` unit so the backend survives reboot
