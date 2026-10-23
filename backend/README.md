# Local wardrobe planner backend

Java 17, Spring Boot **3.5.7**, Maven **3.9.9**, Spring MVC, Jakarta Validation, JPA, and H2.
All application files are contained in this backend directory. No authentication, CORS allowance,
cloud service, frontend dependency, or AI fallback is configured.

## Build and run

From this directory, run `./mvnw test` for tests, `./mvnw package` for a runnable JAR,
or `./mvnw spring-boot:run` to start the API. The official Apache script-only Maven wrapper
downloads Maven on its first use; no system Maven or wrapper JAR is needed. Java 17 and
network access to Maven Central are required for the first build. Windows can use `mvnw.cmd`.
The Unix wrapper is executable; if an archive loses its permission, restore it with `chmod +x mvnw`.

The server binds **127.0.0.1:8080**. Keep the working directory set to this backend directory:
H2 uses `./data/wardrobe`, and sanitized photos use `./data/images`. Both are ignored by Git.
Back up the data directory while the backend is stopped. Only run one backend instance against
the database. Schema updates use Hibernate `ddl-auto=update` (appropriate for this local MVP,
not a versioned migration system). H2 console access is disabled.

The Svelte development server should proxy `/api` to `http://127.0.0.1:8080`.
There are deliberately no cross-origin access headers. Do not expose this unauthenticated service
to a network or change its loopback binding for deployment.

## Optional local AI

Manual CRUD and images work without Ollama. Configure these environment variables before launch:

| Variable | Default |
| --- | --- |
| `OLLAMA_BASE_URL` | `http://127.0.0.1:11434` |
| `OLLAMA_VISION_MODEL` | `gemma3:4b` |
| `OLLAMA_TEXT_MODEL` | `gemma3:4b` |

Install and start Ollama and obtain these models separately when AI is wanted. The backend never
installs models or starts Ollama. Image analysis requires a vision-capable model. Readiness checks
detect installed model names, not whether a model actually supports vision or will fit in memory.
The HTTP connect timeout is 2 seconds; tags requests time out after 3 seconds and generation after
120 seconds. Redirects are not followed. Keep the default local Ollama endpoint if photos must
remain on this device: setting a remote base URL sends prompts and photos to that endpoint.

## API

All request/response bodies are JSON except multipart upload and image download. Errors are
`{"message":"..."}`: validation **400**, missing resources **404**, image size **413**,
invalid AI output **502**, unavailable Ollama/model **503**. Unexpected failures return a generic
**500**, never exception details. Unknown writable properties (including client IDs, timestamps,
and image URLs) are ignored by the request DTOs.

| Method | Endpoint | Body / result |
| --- | --- | --- |
| GET | `/api/garments` | Array of saved garments, newest first |
| POST | `/api/garments` | Garment input → **201** garment |
| PUT | `/api/garments/{id}` | Full replacement garment input → garment |
| DELETE | `/api/garments/{id}` | **204**, or **404** if missing |
| POST | `/api/images` | Multipart field `file` → `{imageId,imageUrl}` |
| GET | `/api/images/{uuid.extension}` | Sanitized PNG or JPEG bytes |
| POST | `/api/analysis` | `{imageId}` → `{draft,message}`; never saves a garment |
| GET | `/api/status` | `{ollamaAvailable,visionModel,textModel,visionModelReady,textModelReady,message}`; **200** even offline |
| POST | `/api/outfits` | `{occasion,count}` → `{outfits:[{title,explanation,garmentIds}],message}` |

Garments contain `id`, `name`, `category`, `colors`, `pattern`, `sleeves`, `season`, `style`,
`notes`, `imageId`, `imageUrl`, and ISO-8601 `createdAt`. Input excludes `id`, `imageUrl`, and
`createdAt`. `name` is required, nonblank, at most 120 characters. `category` is required and one
of `TOP`, `BOTTOM`, `ONE_PIECE`, `SHOES`, `LAYER`, `ACCESSORY`. `colors` is a comma-separated
string of at most 200 characters; `pattern`, `sleeves`, `season`, and `style` allow 120 each;
`notes` allows 2000. Optional attribute strings omitted or null become empty strings. Names and
attribute strings are trimmed. `imageId` can be null; a nonnull ID must match a stored image.
PUT replaces all writable values, preserving `id` and `createdAt`.

Upload limits: **10 MiB** per input file, **20 million pixels**. File content is inspected using
ImageIO, regardless of supplied filename/MIME. Only real JPEG and PNG are accepted. Dimensions
are checked before raster allocation; photos are re-encoded with a fresh raster and no original
metadata or appended payload, under generated UUID `.jpg`/`.png` names. Original filenames are
never used. Retrieval rejects invalid IDs, missing files, and symbolic links. Deleting a garment
does not delete its photo (photos may be shared). Orphan cleanup is intentionally not implemented.
Re-encoding does not guarantee a smaller output file and does not apply EXIF orientation.

Analysis drafts contain only `name`, `category`, `colors`, `pattern`, `sleeves`, `season`, `style`,
and `notes`. Drafts require user review before an explicit POST/PUT saves them. AI output uses
strict category/type/length validation. Both AI prompts treat image contents, wardrobe text, and
occasion text as untrusted data; no tools are provided to Ollama.

`occasion` is required, nonblank, at most 200 characters; `count` is an integer from 1 through 3.
An empty or incomplete wardrobe returns **200**, an empty array and helpful message without
calling Ollama. A complete outfit contains either exactly one one-piece and no top/bottom,
or exactly one top and one bottom and no one-piece. Optional: at most one shoes, one layer,
and three accessories. Every ID must be an existing saved garment; repeated IDs, repeated
outfit combinations (regardless of ID order), excessive result count, missing/invalid fields,
and incomplete outfits cause **502**, not random substitutions. Title limit is 120 characters,
explanation limit is 2000. Fewer valid suggestions than requested are allowed; an empty AI result
for a complete wardrobe is treated as invalid rather than a successful fallback.

## Tests

`./mvnw test` exercises MockMvc CRUD and repository persistence, DTO validation, missing IDs,
image decoding/re-encoding, shared-photo retention, traversal/symlink rejection, byte/pixel
limits, offline/model-readiness status, unsaved photo analysis, AI errors, incomplete inventory
short-circuiting, strict outfit combinations, hallucinated IDs, duplicate outfits, and bounded
AI text. HTTP adapter tests use a short-lived loopback fake Ollama server; no real model,
Ollama process, persistent application server, or production data directory is needed. API
integration tests use in-memory H2 and a temporary image directory.
