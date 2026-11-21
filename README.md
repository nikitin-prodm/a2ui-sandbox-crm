# A2UI Sandbox CRM - agent-composed screens without code generation

**The story:** Google shipped AI Studio and the Antigravity Agent - you type a prompt and get a full Kotlin/Jetpack Compose app. That’s great if you’re a developer building something from scratch. But what about the average user who doesn’t want to “create an app” - they just want *their* app to work the way *they* want? And what about companies that need custom UI tweaks but can’t wait for a vendor roadmap or pay for custom development?

The obvious answer - let an agent edit the app after install - runs straight into Play Store policy: **apps can’t modify their own code**. 
So I went a different way: 
1) The app publishes a catalog of pre-approved components.
2) The agent returns declarative A2UI JSON.
3) The app validates it and renders it natively. **Validated data, not code.**

That’s the idea I vibe-coded into this PoC. It’s not a product. It’s a testbed for the architecture - a CRM domain, chosen because the “one UI for every role” problem is real and measurable.

Reference domain/UX: [TwentyMobile](https://github.com/adlucio87/TwentyMobile) (reference only, AGPL-3.0 - no code copied). Protocol: **A2UI 0.9.1** on `androidx.a2ui` 1.0.0-alpha02.

## Why I built this

I’m a product manager, not an Android engineer. I vibe-coded the renderer to test whether the idea holds. It does. The CRM is just the proving ground.

**License:** Apache-2.0 (see `LICENSE`). Contribution guidelines in `CONTRIBUTING.md`.

---

## 📹 Demos

All clips were recorded on a real emulator, sped up where the agent latency was noticeable. Each clip is standalone - you don’t need to watch them in order to get the point.

| # | What it shows | Clip |
|---|---|---|
| 1 | **Analytics - show deals under each funnel stage.** `"show deals"` → the funnel is populated with deal cards under each stage. | [Clip - analytics-1-show-deals.mp4](docs/videos/analytics-1-show-deals.mp4) |
| 2 | **Analytics - cut zeroes and sort by amount.** `"delete grey, sort by amount"` → empty rows disappear, deals are ordered by value. | [Clip - analytics-2-sort-by-amount.mp4](docs/videos/analytics-2-sort-by-amount.mp4) |
| 3 | **Analytics - add a chart and move it down.** `"add a chart, move down"` → a TrendChart appears, positioned below the funnel. | [Clip - analytics-3-chart.mp4](docs/videos/analytics-3-chart.mp4) |
| 4 | **My Day - add completed tasks.** `"add completed tasks"` → the list now includes completed items alongside active ones. | [Clip - myday-1-completed-tasks.mp4](docs/videos/myday-1-completed-tasks.mp4) |
| 5 | **My Day - remove grey background, round checkboxes.** `"delete grey background, checkboxes to circles"` → visual restyle driven entirely by the catalog style-kit. | [Clip - myday-2-restyle.mp4](docs/videos/myday-2-restyle.mp4) |
| 6 | **Team - show deals per member.** `"show deals of members"` → each leaderboard row expands with that member’s deals. | [Clip - team-1-member-deals.mp4](docs/videos/team-1-member-deals.mp4) |
| 7 | **Team - remove grey from deals.** `"remove grey from deals"` → deal cards lose the grey fill. | [Clip - team-2-remove-grey.mp4](docs/videos/team-2-remove-grey.mp4) |
| 8 | **Team - deal icons and rocket.** `"deal icons and rocket"` → deal cards get icons, the top performer gets a rocket. | [Clip - team-3-icons-rocket.mp4](docs/videos/team-3-icons-rocket.mp4) |

What all clips have in common: **the agent never writes code.** It returns A2UI JSON; the app validates it against the catalog and renders it natively. Nothing outside the catalog reaches the screen.

---

## What’s inside

- **`catalog/catalog.json`** - the single source of truth. 10 CRM components (`app_extension`), 18 spec components (8 native + 10 deferred with explicit reasons), 9 whitelisted actions mapped to `CrmRepository`, renderer-side semantics, and machine-readable nesting rules. Nothing exists outside this file.
- **`catalog/README.md`** - catalog documentation (`spec_support` semantics, validation contract, renderer-side conventions).
- **`app/`** - the Kotlin/Compose prototype: `:app`, `:feature:agentic` (A2UI layer + agent pipeline), `:core:domain`, `:core:data`.
- **`probe/`** - reproducible verification against the real published `androidx.a2ui` artifacts.

**Architecture notes:**

- **Two-layer validation with separate error codes.** `CatalogValidator` (agent-facing) uses `VALIDATION_FAILED` for envelope/parse errors; engine errors are `ENGINE_ERROR`. This matters for self-correction: the agent knows if it broke the message structure or the component schema.
- **Runtime catalog: 18 types, not 21.** My own notes said “21+”, but the code registers 8 native + 10 CRM. Corrected in the docs.
- **Actions are app-layer.** No public `A2uiUserAction` in alpha02, so the dispatcher (whitelist from catalog → `CrmRepository`) sits outside the engine. Documented as a known limitation, not hidden.
- **The Compose renderer wasn’t in Maven.** Only `a2ui-engine` and `a2ui-model` are published. I developed the renderer with a **GLM-5.3 agent in Cline (VS Code)** on top of `A2uiCoreMessageProcessor`.
- **Real LLM behind `AgentGateway`.** Any OpenAI-compatible endpoint works (configured in Settings). `DemoAgent` is a deterministic fallback.

**Hybrid UX:** native screens (My Day / Team / Analytics / Settings) with bottom navigation; the A2UI agent lives behind a FAB chat sheet - voice or typed request → Apply → LLM (or DemoAgent fallback) composes the screen → two-layer validated Compose UI. Out-of-catalog requests are refused (no automatic alternative - that’s a developer decision via the catalog contract). Screen actions dispatch whitelisted events to the fake CRM repository and refresh native data instantly; `openEntity(task)` / `navigate` are wired.

---

## Known limitations

- The catalog doesn’t “suggest an alternative” out of the box - that’s a developer decision, not an agent feature.
- Role switching doesn’t enforce permissions yet - that’s also a developer concern, not baked into the PoC.
- Actions dispatch at the app layer because `A2uiUserAction` isn’t public in alpha02 (verified by source inspection).
- Only 8 of 18 native components are implemented in v1 (5 input components deferred to v1.1, 5 not budgeted for role screens).

---

## Verification

```
./probe/run-all.sh          # Windows: probe\run-all.bat
```

Expected: **8/8** protocol compatibility, **10/10** round-trip, **10/10 + 6/6** negative controls catalog validation, **6/6** custom catalog API, **PASS** end-to-end MessageProcessor probe (surface actor performs full schema validation).

```
cd app && gradlew.bat :feature:agentic:test :app:assembleDebug
```

Expected: **65/65** unit tests pass + `app-debug.apk` is built.

Verified on a real emulator (LDPlayer) with a real LLM: all reported bug scenarios re-checked via `uiautomator` dumps.

---


