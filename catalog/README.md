# A2UI CRM Catalog

**Approved component catalog for the A2UI sandbox of a mobile CRM.**

**Verification: `./probe/run-all.sh`** (Windows: `probe\run-all.bat`) — protocol compatibility 8/8,
round-trip 10/10, catalog validation 10/10 + 6/6 negative controls (enum, missing required,
unsupported renderer type x3, nesting), custom catalog API 6/6, MessageProcessor end-to-end
PASS (surface actor performs full schema validation).

`catalog.json` is the single source of truth for what the app's Compose renderer can actually
render. The agent (LLM) never generates code — it returns declarative A2UI messages that are
parsed, validated, and rendered natively against this catalog.

## Protocol: A2UI 0.9.1 (only)

Built on `androidx.a2ui:a2ui-model` + `a2ui-engine` **1.0.0-alpha02**. A protocol compatibility
probe against the published artifacts confirmed the parser accepts envelope versions `v0.9` and
`v0.9.1` only, and rejects `1.0` with a structured `VALIDATION_FAILED` error. Spec 1.0 features
(custom catalogs, two-way function calls, inline components in `createSurface`) are **not**
available and must not be used.

Envelope reminder (0.9.1):

```json
{"version":"v0.9.1","createSurface":{"surfaceId":"home","catalogId":"dev.a2uicrm:crm-catalog"}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"home","components":[{"id":"root","component":"Column","children":["sh1","m1"]}...]}}
{"version":"v0.9.1","updateDataModel":{"surfaceId":"home","path":"/stats","value":{...}}}
```

### Message ordering (spec rule, verified)

`updateDataModel` must be sent **after** `createSurface` for the same surface — this is a
**specification requirement of 0.9.1**, not an engine quirk. Quote from
`specification/v0_9_1/docs/a2ui_protocol.md` ("Protocol overview & data flow"):

> "3. **Update Data Model:** Once a surface has been created, the server can send
> `updateDataModel` messages at any time to populate or change the data that the UI
> components will display."

Consequence for agents (including the future LLM): the safe order per surface is
`createSurface` → `updateDataModel`* → `updateComponents`. (Also verified behaviorally
against `a2ui-engine` 1.0.0-alpha02: data sent before the surface exists is dropped.)

## `spec_support` semantics

- **`native`** — the type is part of the A2UI v0.9.1 *basic catalog* (composition, text, images,
  buttons). Each entry cites the exact line in `specification/v0_9_1/catalogs/basic/catalog.json`.
- **`app_extension`** — the type does **not** exist in 0.9.1. The app implements it in its own
  Compose renderer and registers it by name. If the agent emits a type that is not `native` and
  not listed here, the validator returns `UNKNOWN_COMPONENT` and the agent is expected to
  self-correct.

The catalog is the source of truth for what the renderer can do — not for what a demo wants
to show. Anything not in this file fails validation.

## Renderer support (v1)

**8 of 18 native types are implemented in v1; 10 are deferred.** Implemented: `Text`, `Row`,
`Column`, `Card`, `Divider`, `Button`, plus `Icon` (budgeted in 5.1) and `List` (budgeted in
5.2 for ActionItem lists). Deferred (`rendererSupport: "not_implemented_v1"`): `Image`, `Tabs`,
`Modal`, `Video`, `AudioPlayer` — not budgeted in v1; and the five input components
(`TextField`, `CheckBox`, `ChoicePicker`, `Slider`, `DateTimeInput`) — deferred to v1.1. All
deferred types are valid per the 0.9.1 specification, but this renderer cannot draw them yet.
The CatalogValidator rejects them with a dedicated `UNSUPPORTED_RENDERER_TYPE` error (with a
hint pointing to the budgeted catalog types) — distinct from `UNKNOWN_COMPONENT`, which is
returned for types that do not exist in any catalog.

## Renderer-side semantics

Three conventions implemented by the renderer and the CatalogValidator — **not** part of the
A2UI 0.9.1 protocol (declared in `catalog.json` → `rendererSemantics`):

1. **Default actions.** If a component omits `action`, the renderer injects the default action
   with context taken from the component's own props (e.g., `ActionItem` →
   `completeTask` with `context.taskId = props.taskId`; `EntityCard` → `openEntity` with
   `entityType`/`entityId` from props).
2. **Placeholders.** The string `"<this X>"` inside an action context value is replaced at
   render time with `props[X]` of the component that owns the action.
3. **Nesting constraints.** Enforced by the CatalogValidator **before** rendering, not by the
   UI: the component tree is traversed from `root` following children ids (adjacency-list)
   with a stack of ancestor types; a violation (e.g., `TrendChart` inside `EntityCard`) fails
   with `NESTING_NOT_ALLOWED` + path + parent chain. Rules are machine-readable in
   `catalog.json` → top-level `nestingRules` (`{"parent": "EntityCard", "forbiddenChildren":
   [...]}`), so the validator reads them directly (verified by probe NEGATIVE 6).

## CRM components (app_extension)

| Component | Purpose | Key constraint |
|---|---|---|
| `MetricCard` | KPI tile: value + label + trend | one metric, no charts |
| `EntityCard` | contact / company / opportunity / task card | whitelisted fields only, no HTML |
| `ActionItem` | task row with checkbox + deadline | checkbox = `completeTask` only |
| `PipelineStrip` | horizontal funnel stages | no pie/radial, 2–8 stages |
| `TrendChart` | single-series bar/line chart | no pie, no multi-series |
| `LeaderboardRow` | team ranking row | single metric, minimal PII |
| `AlertBanner` | risk / warning banner | fixed severity colors |
| `SectionHeader` | section title + count + CTA | leaf, one CTA max |
| `EmptyState` | empty data / validation fallback | fixed icon set |
| `PrimaryActionButton` | main CTA | action whitelist enforced |

## Actions

Every interactive component dispatches one of nine whitelisted `event` names (see `actions` in
`catalog.json`), each mapped to a `CRMRepository` method — never to direct state mutation and
never to executable code. `sendEmail`, `openUrl`, and arbitrary function calls are intentionally
absent.

## Validation & self-correction

Validation errors mirror `A2uiException`: `code: VALIDATION_FAILED` + `context.path` +
`context.error` + `context.allowedValues` + optional `hint`. The app reports the structured error
to the agent (up to 2 retries); if correction fails, the user sees an `EmptyState` with a
plain-language explanation and an alternative suggestion.

## Refusal UX examples (agent-side, implemented in `DemoAgent`)

When a user request asks for something outside the catalog, the agent **refuses with an
explanation and offers a catalog-supported alternative** — the unsupported component is never
rendered. Implemented in the sandbox agent and covered by unit tests (`DemoAgentTest`:
`refusal - ...` x3); user-facing screenshots are pending a real device (see the root README
checklist).

| User request (RU) | Agent response (rendered) |
|---|---|
| «покажи круговую диаграмму» | `EmptyState` «круговая диаграмма (PieChart) — вне каталога» + explanation + CTA «Показать мои задачи» (`navigate`) |
| «отправь письмо клиенту» | `EmptyState` «отправить email — вне каталога» + «действия ограничены whitelist» + CTA «Создать задачу-напоминание» (`createTask`) |
| «сделай экран с html версткой» | `EmptyState` «кастомный HTML-экран — вне каталога» + «UI = валидированные данные, не код» + CTA «Показать мои задачи» |

The refusal notes also appear in the transparency panel ("Агент-симулятор додумал за вас"),
each starting with «Отказ: …».

**Round-trip test (scope: envelope + structure):** every `messageExample` in this file is a
complete 0.9.1 envelope (`version` + `updateComponents` with a root `Column`) and has been
parsed successfully by `androidx.a2ui:a2ui-model:1.0.0-alpha02` (`A2uiJsonMessageParser`):
**10/10 PARSE_OK** (repro: `probe/RoundTripProbe.java`, output: `probe/ROUNDTRIP_OUTPUT.txt`).
The component type discriminator in 0.9.1 is the `"component"` field (basic catalog, line
1332-1334). Note what this does **and does not** prove: the round-trip verifies that the
envelope is well-formed 0.9.1 and that the engine accepts the component structure
(`A2uiComponentPayload(id, type, properties)`); **property-level validation (required fields,
enums, length limits) is the job of the CatalogValidator**, implemented and probed separately
(`probe/CatalogValidationProbe.java`, output: `probe/CATALOG_VALIDATION_OUTPUT.txt`:
10/10 positive PASS + 2/2 negative controls detected).

## Design reference

Domain model and UX patterns are referenced from the open-source
[TwentyMobile](https://github.com/adlucio87/TwentyMobile) Flutter CRM (AGPL-3.0). This project
uses it as a *reference only* — no code is copied; the Kotlin implementation is written from
scratch following the same patterns.

License of this catalog: Apache-2.0.
