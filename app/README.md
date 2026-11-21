# A2UI CRM — prototype app (Blocks 5.1–5.6)

Kotlin/Compose prototype of the A2UI sandbox: an agent-driven UI rendered natively from
declarative A2UI JSON (protocol **0.9.1**, `androidx.a2ui` 1.0.0-alpha02), validated by a
two-layer pipeline against the approved component catalog.

## The agent request loop (Block 5.2)

The user dictates (system speech recognition) or types a request — often vague or
incomplete — presses **Apply**, sees a spinner, and gets a complete, consistent screen:

```
(voice | text) -> AgentGateway (DemoAgent simulator; LLM plugs in here for v2)
  -> classifier + INFERENCE: role, metrics, sections, empty branches, dates, CTA
    are filled in per the Home template ("сделай красиво и не грузи вопросами")
  -> raw 0.9.1 JSON (updateDataModel + updateComponents)
  -> CatalogValidator (layer 1) -> A2uiCoreMessageProcessor (layer 2) -> Compose
  -> on validation errors: structured feedback -> agent self-correction (<= 2 attempts)
```

What the agent inferred is shown transparently — the panel is explicitly labeled
**«Агент-симулятор»** (demo): the current build runs the deterministic **DemoAgent
simulator** behind `AgentGateway`; a real LLM plugs into the same interface in v2 without
UI changes. Rendered actions (checkbox / CTA / card tap) dispatch whitelisted events
(9 actions from the catalog) to `FakeCrmConnector` and trigger an automatic refresh of the
composition.

**Known limitation (actions)**: user actions dispatch at the app layer (`CrmActionDispatcher`)
because a public API for submitting `A2uiUserAction` is not available in
`androidx.a2ui` 1.0.0-alpha02 (verified by source inspection: `A2uiActionInterceptor` is
internal middleware). This is protocol-compatible — per the 0.9.1 spec, actions are
client-to-server messages sent to a separate endpoint.

**Known limitation (voice)**: voice input requires a real device — mic passthrough is
unreliable in emulators, and it was **not tested in this sandbox environment** (no
device/emulator available). Graceful fallbacks are implemented
(`ActivityNotFoundException` → hint; non-OK result → hint); text input is the primary path.
Testing on a real device is on the pre-publication checklist.

**Refusal UX**: requests outside the catalog («покажи круговую диаграмму», «отправь письмо»,
«сделай экран с html») are refused with an explanation and a catalog-supported alternative
(`EmptyState` + CTA) — see `catalog/README.md` → "Refusal UX examples"; covered by unit
tests.

## Screenshots (live emulator run, Block 5.5)

Paths are relative to the repository root.

| Native screen | The same tab after an agent request |
|---|---|
| ![Native My day](../docs/screenshots/native-myday.png) | ![Agent-composed My day](../docs/screenshots/a2ui-myday.png) |
| ![Native Analytics](../docs/screenshots/native-analytics.png) | ![Deals under each funnel stage](../docs/screenshots/a2ui-analytics-deals.png) |
| ![Native Team](../docs/screenshots/native-team.png) | ![Augmented Team (deals per member)](../docs/screenshots/a2ui-team.png) |

Also: [TrendChart added by "add a chart"](../docs/screenshots/a2ui-analytics-chart.png)
and the [agent chat sheet](../docs/screenshots/agent-chat.png). UI-dump artifacts of the
verification run: `docs/screenshots/ui-*.xml`.

## Modules

| Module | Purpose |
|---|---|
| `:app` | Demo activity: input bar (mic + text + Apply), loading state, inference notes panel, role switcher |
| `:feature:agentic` | A2UI layer + agent pipeline: `JsonLexerAdapter`, `JsonDom`, `CatalogValidator` (layer 1), `RuntimeCatalog`, `A2uiCoreMessageProcessor` wiring, Compose renderer (8 native + 10 CRM), `AgentGateway`/`DemoAgent`/`AgentSession` (self-correction loop), `CrmActionDispatcher` |
| `:core:domain` | Domain models + `CrmRepository` contract (reference-only, no code copied) |
| `:core:data` | `FakeCrmConnector` — in-memory fake with simulated latency and UTC day buckets |

## Build

Requirements: JDK 17, Android SDK with `platforms;android-37.0` (note: the package is
named `android-37.0`, not `android-37`). Gradle 9.9.1 via wrapper. AGP 9.1.0 — required by
the `androidx.a2ui` AAR metadata (compileSdk 37 + AGP 9.1.0+); AGP 9 uses **built-in
Kotlin**, so `org.jetbrains.kotlin.android` is intentionally absent.

```
cd app
gradlew.bat :feature:agentic:test :app:assembleDebug   # Windows
./gradlew :feature:agentic:test :app:assembleDebug     # Unix
```

Result: 65/65 unit tests PASS, `app/build/outputs/apk/debug/app-debug.apk`.

**APK breakdown (measured)**: compressed APK **~15.9 MB**; uncompressed dex ~52 MB
(classes.dex 41.9 + classes11.dex 10.3 + small ones) — dominated by
`material-icons-extended` (the full ~2000-icon set compiles into dex; the sandbox uses ~12
icons). App code + resources are small in comparison. If size ever matters, switch to
vector drawables for the used icons (−8–10 MB compressed). Not a priority for the
prototype.

Note (small `C:` drives): set `GRADLE_USER_HOME` to a roomy drive (e.g.
`set GRADLE_USER_HOME=d:\gradle-home`) so dependency caches and wrapper distributions do
not fill the system drive. The reference build machine also pins JDK 17 via
`org.gradle.java.home` in `gradle.properties`.

## Verification

- Unit tests (JVM, port of `probe/` + agent loop): 10/10 catalog messageExamples, 7
  negative controls; 4 engine wiring tests; 3 lexer tests; 5 agent-loop tests (partial
  request → full template, inferred defaults for create-task, data binding via
  updateDataModel, self-correction with a fault-injecting agent); 5 action-dispatcher
  tests (whitelist, repository mutation, refresh callback, defaults, ISO round-trip).
- The catalog is a single source of truth: `../catalog/catalog.json` is wired both as an
  asset (runtime) and as a test resource — no copies.

## Status

Blocks 5.1–5.6 complete.

- 5.5 augment contract & twins: ADD/SHOW semantics with a structural guard
  (`validateStructure`/`validatePreservation` — a rebuilt tree is rejected), an analytics
  twin that is a visual clone of the native screen (stage rows, per-stage icons,
  «deals: N · conv X%»), a task-list twin with section dividers, «show deals under each
  funnel stage», «add chart» (TrendChart from real repository numbers), and agent-editable
  tab icons (`{"shell":{"tabIcons":{...}}}`, validated against the renderer icon
  vocabulary — pure data).
  Live-verified on an emulator (LDPlayer, LLM qwen3.8-flash on): all 8 bug scenarios pass
  via `uiautomator dump` + `shared_prefs` ground truth (see `../analysis.md`, «Блок 5.5 →
  Живая верификация»). Two live-found fixes included: stage-row internals are no longer
  listed flat at root (the renderer drew every funnel stage twice), and the native money
  formatter is pinned to `Locale.US` so native and twin amounts match on any device.

- 5.4 hybrid UX: native screens (Мой день / Команда / Аналитика / Настройки) with bottom
  navigation; the A2UI agent lives behind a FAB chat sheet (LLM via settings, DemoAgent
  fallback). After Apply the sheet collapses and the composed screen is shown with a
  «Экран собран A2UI-агентом» banner.
- Native task Detail (tap a card, not the checkbox); `openEntity(task)` and `navigate`
  (home | pipeline | settings) are wired through `CrmActionDispatcher`.
- Unit tests: 65/65 PASS. APK: `app/build/outputs/apk/debug/app-debug.apk`
  (`versionName 0.1.0-block54`).

See `../analysis.md` §"Блок 5.4" (RU) for details.
