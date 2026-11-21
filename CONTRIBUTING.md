# Contributing to the A2UI CRM Sandbox

Thanks for your interest! This is a **prototype** (not production software), and the
project's core value is the verifiable idea: an agent composes mobile screens from a
**pre-approved component catalog** (A2UI protocol JSON 0.9.1), and the app renders them
natively in Jetpack Compose - no generated code, only validated data.

## Ground rules

- **License**: Apache-2.0 (see `LICENSE`). By contributing you agree your contributions
  are licensed under it.
- **License hygiene is non-negotiable**: the reference app `_reference/TwentyMobile` is
  **AGPL-3.0** and is NEVER read or copied from when writing code - it is kept only as a
  domain/UX reference (patterns, screen inventory). Never paste anything from it. When
  in doubt, re-derive the idea from scratch and say so in the commit message.
- **No `eval`/`DexClassLoader`/JS engines**: the Play-compatibility argument
  ("validated data, not code") is the whole point - do not break it.
- `catalog/catalog.json` is the single source of truth for components: a change to the
  renderer must ship with a matching catalog change (and vice versa), plus tests.

## Environment

- JDK 17 (the reference build pins it via `org.gradle.java.home` in
  `app/gradle.properties`).
- Android SDK; on small `C:` drives set `GRADLE_USER_HOME` to a roomy drive.
- The repo vendors a full Android SDK/NDK under `android-sdk/` and an SDK bundle under
  `tools/` for the probe scripts - you do not need them if you have your own SDK.

## Build & test

```bash
cd app
gradlew.bat :feature:agentic:test :app:assembleDebug   # Windows
./gradlew :feature:agentic:test :app:assembleDebug     # Unix
```

Expected: all unit tests PASS (JVM, run the port of `probe/` + agent loop), plus
`app/build/outputs/apk/debug/app-debug.apk`.

Protocol/catalog compatibility probes (run them whenever `catalog.json` or the message
handling changes):

```bash
probe/run-all.bat   # Windows
./probe/run-all.sh  # Unix
```

All five probes must PASS; `probe/SUMMARY.txt` is the reproducible record.

## Where things live

| Path | What |
|---|---|
| `app/app` | Compose UI shell, native screens, agent chat sheet, persistence |
| `app/feature/agentic` | A2UI engine wiring, renderer, catalog, validator, agent pipeline, JSON DOM |
| `app/core/domain`, `app/core/data` | CRM domain model + fake connector (fixtures, persistence) |
| `catalog/` | `catalog.json` - the approved component catalog (single source of truth) |
| `probe/` | Reproducible verification against the real published `androidx.a2ui` artifacts |
| `docs/` | Screenshots and UI-dump artifacts |
| `analysis.md` | The full working journal (RU) - **read the relevant block before changing the area it covers** |

## Conventions

- Kotlin, Compose, Material3. Colors/typography only via `MaterialTheme` roles - the
  agent-visible style kit maps tokens to theme roles on purpose; no raw hex.
- The renderer whitelist (`A2uiRendererRegistry`) and the icon vocabulary (`ICONS`) are
  closed sets: a new component needs a catalog entry + a renderer + tests.
- Every behavioral fix gets a unit test where feasible (65 and counting) - the project's
  claims are backed by runnable evidence, not prose.
- Keep `README.md` (EN, public-facing) and `analysis.md` (RU, journal) in sync: numbers
  must match everywhere (tests count, probe results, budget).
- Verified-on-an-emulator behavior is documented in `analysis.md`
  (uiautomator dumps live in `docs/screenshots/`).

## PR checklist

- [ ] `gradlew :feature:agentic:test :app:assembleDebug` green
- [ ] `probe/run-all.sh|.bat` - 5/5 PASS (if catalog/protocol touched)
- [ ] No code or prose copied from `_reference/` (AGPL)
- [ ] README + analysis.md updated if numbers/behavior changed
- [ ] New behavior covered by a test or a documented live verification
