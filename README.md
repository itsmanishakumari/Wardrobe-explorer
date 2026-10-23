# The Daily Edit — personal wardrobe planner

A private, local wardrobe planner built with **Svelte 5 + TypeScript**, **Java 17 + Spring Boot**, **file-backed H2**, and optional **Ollama** AI.

## What you can do

- Add, edit, search, filter, and remove your own clothes.
- Upload JPEG/PNG photos (up to 10 MB), or enter details manually.
- Ask Ollama to suggest photo attributes, review/correct them, then explicitly save the garment.
- Request one to three outfit options for an occasion. Suggestions use only saved garment IDs and must contain a one-piece or a top plus a bottom.
- Keep using manual wardrobe management when Ollama is offline.

This version is single-user and local-only: no login, cloud service, CSV import, automatic scheduled notification, or weather integration. Open the app and request today's ideas whenever you want.

## Start the app in VS Code

Java 17 and Node.js 22.12+ are required. Java 17 was verified on this Mac. Maven is downloaded automatically by the included wrapper; no separate Maven installation is needed.

1. Open **Terminal → Run Task → Wardrobe: backend**.
2. Open **Terminal → Run Task → Wardrobe: frontend**.
3. Visit **http://127.0.0.1:5173**.

Both tasks run from their correct project directories. On a fresh checkout, install frontend dependencies first with the **Wardrobe: install frontend** task. The first backend start needs internet access to download Maven and dependencies. Both servers intentionally bind to loopback; do not expose this unauthenticated app publicly.

## Enable local AI (optional)

Ollama is not bundled or installed by this project. Install the macOS app from https://ollama.com/download/mac and open it. In Terminal, run `ollama pull gemma3:4b` to download the default vision-capable model. This is a multi-GB download and needs sufficient free memory/disk space. Keep Ollama running, then use **Refresh assistant status** in the wardrobe app.

The same model handles photos and outfits by default. Before starting the backend, optionally set:

| Environment variable | Default |
| --- | --- |
| `OLLAMA_BASE_URL` | `http://127.0.0.1:11434` |
| `OLLAMA_VISION_MODEL` | `gemma3:4b` |
| `OLLAMA_TEXT_MODEL` | `gemma3:4b` |

Use a locally downloaded vision-capable model for image analysis. A larger model may give better results but uses more memory. Readiness checks detect installed names, not hardware suitability. First inference can take a while; requests time out after 120 seconds. AI guesses can be wrong: always review the editable draft. No model is invoked until you explicitly request analysis or outfit ideas.

Photos stay on this device with the default Ollama URL. Changing that URL to a remote service sends image/prompts to it. Do not do that if you want local-only processing.

## Storage and privacy

- Garment records: file-backed H2 database under the backend data directory.
- Photos: sanitized, randomly named files under the backend image data directory; originals are not kept by the app.
- These runtime folders are ignored by Git. Stop the backend before backing up the whole data directory. Run only one backend against a database.
- Uploading stores the photo locally immediately; it does **not** save a garment. Cancelled/replaced uploads and deleted garments can leave unused photos. Automatic orphan cleanup is not included.
- PNG/JPEG only; convert HEIC photos before upload. Images are re-encoded and metadata stripped; EXIF orientation is not currently applied, so rotate an incorrectly oriented photo before uploading.

## Development and tests

Use **Wardrobe: verify** to run all backend tests, frontend type/accessibility checks, frontend tests, and the production frontend build. Backend details and API contracts are documented in [backend/README.md](backend/README.md).

The Vite development server proxies `/api` to the Spring Boot server on port 8080. A frontend build produces static assets, but this project does not yet package those assets into the backend JAR or configure production hosting. Use the two development tasks for the supported local launch.

Tests use mocked AI or a fake local Ollama endpoint; real inference requires your own running Ollama/model. The app has no initial inventory and never silently inserts example clothes.