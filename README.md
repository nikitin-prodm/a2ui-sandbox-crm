# A2UI Sandbox CRM - agent-composed screens without code generation

**I built a PoC in the form of a mobile CRM to test one hypothesis: what if an app on an Android phone could rearrange its own interface without generating any code?**

**The problem, and what got me interested**.
I'm a product manager, and as it happened, vibe-coding became part of my daily work.
While building prototypes and MVPs, I noticed something simple: the cost of a single development iteration – any adjustment, any correction – dropped dramatically. In practice, I stopped asking myself "should we do it or not?", because "do it" now costs pennies. And if the cost of adjustments in general dropped, then the cost of individual adjustments – for a specific user, for a specific task – dropped too.

In parallel, Google shipped the Antigravity Agent: you type a prompt – you get a complete app. "The future is here." Great software for people who know how to build from scratch: small dev studios, and, I think, quasi-freelance micro-businesses that build apps on order.

But I'm convinced users and businesses need something else: a cheap way to tweak the interface of an already installed app.
- Users don't know how to write requirements. That's what systems analysts are for. And even if an agent played that role perfectly, nobody wants to sit there answering a bot's questions in the "what exactly do you mean?" format.
- Businesses hit the same wall from the other side. Waiting for the vendor to ship your customization, or paying extra for it – slow, expensive, and often impossible to get approved.
The obvious path is to let an agent modify the app after install – but that runs straight into Google Play policy: no code changes after install.

**So I went the other way**.
A month ago Google published an Android implementation of the open A2UI protocol (androidx.a2ui, version 1.0.0-alpha02). The idea inside: an AI agent doesn't write the code for a screen, it writes a description – structured JSON with a defined schema. Essentially, HTML for AI agents: the agent "writes the page", the app acts as the browser that renders it.

**What I found out**.
A2UI does have a published Flutter renderer (maintained by the Flutter team, works great on Android). So if you're building a Flutter app – you're covered, no renderer needed. What hasn't been published anywhere is a renderer for Jetpack Compose / native Android. In androidx.a2ui, that branch ships only a parser and a validator, not a drawing layer. That gap is partly what this project fills: a Compose renderer built on top of the androidx.a2ui alpha.

**What I took from androidx.a2ui**
- the JSON format the agent produces (create a surface, update components – all parsed and validated by Google's own code, protocol version 0.9.1),
- schema validation with machine-readable errors, so the agent can fix its own JSON,
- the ability to register my own UI component types on top of the built-in ones.

**What I had to build around it**.
- A component catalog. The single source of truth: 10 CRM components (a KPI card, a sales funnel strip, a trend chart, a leaderboard row…), each with its allowed properties and values, plus a whitelist of allowed actions. Anything not in the catalog cannot reach the screen.
- The renderer. Google didn't publish the drawing layer, so it had to come from somewhere. I designed what the catalog should contain and how validation works, set the task – and an AI coding agent (GLM-5.3 in Cline, inside VS Code) wrote the Compose code that draws the components on screen. Descriptions composed by the in-app agent turn into real native UI.
- A local action dispatcher. In a normal app, every button's handler is written by the developer in advance. But buttons the agent creates on the fly – the developer never anticipated those, there's no ready handler. Shipping executable logic along with the button isn't allowed (that would be exactly the "code modification after install" we're avoiding). So a tap is treated as data: an action name + context. The dispatcher matches the name against the catalog's action whitelist and invokes one of the pre-compiled handlers (complete a task, create a task, open a deal). In the full A2UI design, taps travel back to the agent instead – the engine classes for that already exist, but in the alpha only Google's own (unpublished) renderer can feed actions into them; for third-party renderers there's no way in, so taps are handled locally.

**How it works**.
The user types (or says) "show deals under each funnel stage" → the agent returns A2UI JSON → two validation layers check it (my catalog validator first, then Google's engine) → out-of-catalog requests are rejected with an error the agent can self-correct on → the renderer draws the result natively, as a composition of approved parts – no code generation.

**Conclusions, and why this matters to me**.
The concept itself is no longer in question – the A2UI ecosystem has working renderers for React, Flutter, Angular, Lit, and SwiftUI. But on Android the picture is different: Google published only the protocol layer (and it's an alpha), no renderer and no public API for user actions. In other words, the "native" trail on Android is the one nobody has walked yet.
My question was: "Can a regular app developer fill in the missing pieces on top of the alpha and end up with working, agent-editable screens?" If yes, developers ship less hardcoded UI, integration costs for businesses go down, and users stop deleting apps over a button's placement or a missing filter. The answer turned out to be "yes" – with caveats, which I described above.
My hypothesis: the new core job of dev teams will be populating the component catalog and designing the contract for how agents interact with those components.

That’s the idea I vibe-coded into this PoC. It’s not a product. It’s a testbed for the architecture - a CRM domain, chosen because the “one UI for every role” problem is real and measurable.

Reference domain/UX: [TwentyMobile](https://github.com/adlucio87/TwentyMobile) (reference only, AGPL-3.0 - no code copied). Protocol: **A2UI 0.9.1** on `androidx.a2ui` 1.0.0-alpha02.

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


