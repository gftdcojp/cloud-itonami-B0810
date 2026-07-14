# cloud-itonami-isic-0810

Open Business Blueprint for **ISIC Rev.5 0810**: Community Quarry and
Stone Supply -- dimension-stone quarrying and aggregate supply for
local construction.

This repository publishes a community-quarry actor -- extraction
intake, per-jurisdiction mine-safety/explosives-blast-safety
regulatory assessment, material extraction and consignment shipment
-- as an OSS business that any qualified operator can fork, deploy,
run, improve and sell, so a local quarry operator never surrenders
extraction and royalty data to a closed SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet (88 prior actors) -- here it is
**QuarryOps-LLM ⊣ Quarry Governor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:quarry-governor`, is a
UNIQUE keyword fleet-wide (grep-verified: no other blueprint declares
it) -- a fresh, independent build.

> **Why an actor layer at all?** An LLM is great at drafting an
> extraction summary, normalizing records, and checking whether a
> claimed royalty actually equals the extraction's own recorded
> quantity times royalty rate -- but it has **no notion of which
> jurisdiction's mine-safety/explosives-blast-safety law is official,
> no license to extract real material or ship a real consignment, and
> no way to know on its own whether an extraction permit is actually
> still valid or whether a blasting operation's own blast-safety
> clearance has actually been confirmed**. Letting it extract material
> or ship a consignment directly invites fabricated regulatory
> citations, a royalty mismatch being charged to a customer,
> extraction proceeding on an expired/invalid permit, and a blast
> proceeding without a confirmed safety clearance -- exposing workers
> to real physical danger and the operator to real regulatory
> liability -- and liability, for whoever runs it. This project seals
> the QuarryOps-LLM into a single node and wraps it with an
> independent **Quarry Governor**, a human **approval workflow**, and
> an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers extraction intake through mine-safety/explosives-
blast-safety regulatory assessment, material extraction and
consignment shipment. It does **not**, by itself, hold any operating
permit required to run a quarry in a given jurisdiction, and it does
not claim to. It also does not perform the actual physical extraction
work itself, or judge geological/grade quality --
`quarryops.registry/royalty-matches-claim?` is a pure ground-truth
recompute against the extraction's own recorded fields, not a grade or
geological judgment. Whoever deploys and operates a live instance (a
qualified quarry operator/shift supervisor) supplies any jurisdiction-
specific permit, the real extraction/haul-equipment integration and
the real royalty-accounting-system integrations, and bears that
jurisdiction's liability -- the software supplies the governed, spec-
cited, audited execution scaffold so that operator does not have to
build the compliance layer from scratch.

### Actuation

**Extracting real material and shipping a real consignment are never
autonomous, at any phase, by construction.** Two independent layers
enforce this (`quarryops.governor`'s `:actuation/extract-material`/
`:actuation/ship-consignment` high-stakes gate and `quarryops.phase`'s
phase table, which never puts either op in any phase's `:auto` set) --
see `quarryops.phase`'s docstring and `test/quarryops/phase_test.clj`'s
`extraction-extract-never-auto-at-any-phase`/`consignment-ship-never-
auto-at-any-phase`. The actor may draft, check and recommend; a human
quarry operator is always the one who actually extracts material or
ships a consignment. Grounded directly in this blueprint's own `docs/
business-model.md` Trust Controls text ("extraction outside permit is
blocked; safety gates are auditable; royalty records are immutable")
-- a genuine DUAL-actuation shape, applied SEQUENTIALLY to the SAME
extraction record (extract first, ship later), matching `freightops`/
4920's own sequential shape rather than `retailops`/4711's own
alternative-kind shape.

## The core contract

```
extraction intake + jurisdiction facts (quarryops.facts, spec-cited)
        |
        v
   ┌───────────────────────┐   proposal      ┌───────────────────────┐
   │ QuarryOps-LLM         │ ─────────────▶ │ Quarry Governor               │  (independent system)
   │ (sealed)              │  + citations    │ spec-basis · evidence-       │
   └───────────────────────┘                 │ incomplete · robotics-        │
          │                 commit ◀┼ simulation missing/out-of-        │
          │                         │ tolerance (NEW, ADR-2607150600) ·       │
    record + ledger        escalate ┼ royalty-mismatch (ground-truth) ·        │
          │              (ALWAYS for│ extraction-permit-invalid (FLAGSHIP       │
          │       :actuation/extract│ NEW) · blast-safety-clearance-             │
          │       -material/        │ unconfirmed (conditional, NEW) ·            │
          │       :actuation/ship-  │ already-extracted · already-shipped          │
          │       consignment)       │                                            │
          ▼                          └───────────────────────┘
      human approval
```

**The QuarryOps-LLM never extracts material or ships a consignment the
Quarry Governor would reject, and never does so without a human sign-
off.** Hard violations (fabricated regulatory requirements; unsupported
evidence; a robot verification mission that never ran or that
independently re-checks out-of-tolerance; a royalty mismatch; an
invalid extraction permit; an unconfirmed blast-safety clearance on a
blasting extraction; a double extraction/shipment) force **hold** and
*cannot* be approved past; a clean extraction/shipment proposal still
always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk two clean extraction+shipment lifecycles (no-blast, blast-confirmed), plus six HARD-hold cases, through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Ops

| Op | Effect |
|---|---|
| `:extraction/intake` | normalize extraction directory patch (phase 3 may auto-commit when clean) |
| `:jurisdiction/assess` | per-jurisdiction mine-safety/explosives-blast-safety evidence checklist (always human) |
| `:robotics/simulate-quarry-face-verification` | robot bench-face/quarry-face verification mission (always human; required on file before extraction) |
| `:extraction/extract` | draft material-extraction record (always human; HARD hold if robotics-sim missing or independently out-of-tolerance, royalty mismatch, invalid permit, or unconfirmed blast-safety clearance) |
| `:consignment/ship` | draft consignment-shipment record (always human) |

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here an extraction robot performs
drilling, cutting and loadout at the quarry face, under the actor,
gated by the independent **Quarry Governor**. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such
as operating at a quarry face, near personnel or near blast zones)
require human sign-off.

**Robot process simulation is concrete, not just a flag**
(ADR-2607150600, extending ADR-2607142800/ADR-2607011000):
`quarryops.robotics` walks every extraction through a robot-executed
bench-face/quarry-face verification mission (`kotoba.robotics`
mission/action/telemetry-proof contracts) -- bench-face dimensional
survey, core-sample quality assay, dust/particulate-emissions scan --
before `:actuation/extract-material` is proposable.

**This is now a REAL time-stepped physics simulation, not a synthetic
field comparison (ADR-2607152000, generalizing ADR-2607151600's
automotive pilot to this vertical).** This repository takes a REAL
git-coordinate dependency on
[`kotoba-lang/physics-2d`](https://github.com/kotoba-lang/physics-2d)
(pinned by SHA in `deps.edn`), and `quarryops.robotics/simulate-
quarry-face-verification` actually calls it directly (no design-
library sibling repo needed, unlike automotive): a bench-face loose
block/fragment is modeled as an actual `physics-2d` rigid body (real
gravity `[0 -9.81]`) that ACTUALLY free-falls tick-by-tick
(`world-step`) toward a static catch-bench-floor body until it
genuinely settles or a max-tick budget is reached. The Quarry Governor
independently re-derives the extraction's own real simulated telemetry
(`:sim-settling-distance-m`/`:sim-impact-energy-j`) against a real
tolerance band anchored on this site's own recorded `:catch-bench-
rated-height-m` design rating (grounded in Ritchie's (1963) empirical
catch-bench-geometry criteria widely used in open-pit/quarry slope
design), never trusting the mission's self-reported verdict alone.
Honest scope: the physics is a 2D projection (vertical free-fall only,
no lateral rollout distance modeled), and the fragment is a single
cubic AABB block sized from its own recorded mass at a disclosed
typical rock-density prior -- see `quarryops.robotics`'s namespace
docstring for the full, disclosed derivation. This real-engine wiring
is now live for both the automotive (isic-2910) and quarryops
(isic-0810) verticals; the remaining cloud-itonami manufacturing
actors touched by ADR-2607142800 remain on the symbolic robotics-
simulation layer until a similar integration is built for each.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Quarry Governor, extraction/shipment draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`0810`). This vertical's service/member records are practice-specific
rather than a shared cross-operator data contract, so `quarryops.*`
runs on the generic robotics/identity/forms/dmn/bpmn/audit-ledger/
telemetry stack only -- no bespoke domain capability lib to reference
at all (unlike `retailops`/4711's own `kotoba-lang/retail` and
`freightops`/4920's own `kotoba-lang/logistics` integrations).

## Layout

| File | Role |
|---|---|
| `src/quarryops/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + extraction AND shipment history (dual history). The double-actuation guard checks dedicated `:extracted?`/`:shipped?` booleans rather than a `:status` value |
| `src/quarryops/registry.cljc` | Extraction/shipment draft records, plus `royalty-matches-claim?` -- an honest reapplication of the SAME ground-truth-recompute discipline every sibling actor's own cost/total-matching check establishes |
| `src/quarryops/facts.cljc` | Per-jurisdiction mine-safety AND explosives/blast-safety catalog with an official spec-basis citation per entry, honest coverage reporting -- ALL FOUR seeded jurisdictions have a blast-safety sub-citation here |
| `src/quarryops/quarryopsllm.cljc` | **QuarryOps-LLM** -- `mock-advisor` ‖ `llm-advisor`; intake/jurisdiction-assessment/robotics-simulation/extraction/shipment proposals |
| `src/quarryops/robotics.cljc` | Robot bench-face/quarry-face verification mission (`kotoba.robotics` mission/action/telemetry-proof) + a REAL `physics-2d`-backed time-stepped bench-face loose-block free-fall/settling simulation + `bench-face-settling-out-of-tolerance?` ground-truth check + `simulation-out-of-tolerance?` independent recheck for the governor (ADR-2607142800/ADR-2607150600/ADR-2607152000) |
| `src/quarryops/governor.cljc` | **Quarry Governor** -- 6 HARD checks (spec-basis · evidence-incomplete · robotics-simulation missing/out-of-tolerance, NEW (ADR-2607150600) · royalty-mismatch · extraction-permit-invalid, FLAGSHIP NEW, the 76th unconditional-evaluation-discipline grounding · blast-safety-clearance-unconfirmed, CONDITIONAL, the 77th grounding) + 2 double-actuation guards + 1 soft (confidence/actuation gate) |
| `src/quarryops/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess (+ robotics simulation) → supervised (extraction/shipment always human; extraction intake is the ONLY auto-eligible op, no direct capital risk) |
| `src/quarryops/operation.cljc` | **OperationActor** -- langgraph StateGraph |
| `src/quarryops/sim.cljc` | demo driver |
| `test/quarryops/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers extraction intake through mine-safety/explosives-
blast-safety regulatory assessment, material extraction and
consignment shipment -- the core governed lifecycle this blueprint's
own `docs/business-model.md` names in its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Extraction intake + per-jurisdiction evidence checklisting, HARD-gated on an official spec-basis citation (`:extraction/intake`/`:jurisdiction/assess`) | Real extraction/haul-equipment integration, real geological/grade-quality judgment (see `quarryops.facts`'s docstring) |
| Robot bench-face/quarry-face verification mission, HARD-gated as required-on-file before extraction (`:robotics/simulate-quarry-face-verification`) -- a real simulated robot mission, not merely a declared `:robotics` flag (ADR-2607142800/ADR-2607150600) | Real robot-cell telemetry integration -- `quarryops.robotics` remains "policy, not control" by design |
| Material extraction, HARD-gated on full evidence, a robot verification mission on file that INDEPENDENTLY re-checks in-tolerance, a matching royalty claim, a valid extraction permit and a confirmed blast-safety clearance (when applicable), plus a double-extraction guard (`:actuation/extract-material`) | |
| Consignment shipment, HARD-gated on full evidence, plus a double-shipment guard (`:actuation/ship-consignment`) | |
| Immutable audit ledger for every intake/assessment/extraction/shipment decision | |

Extending coverage is additive: add the next gate (e.g. a stockpile-
grading-verification check) as its own governed op with its own HARD
checks and tests, following the SAME "an independent governor re-
verifies against the actor's own records before any real-world act"
pattern this repo's flagship ops already establish.

## Jurisdiction coverage (honest)

`quarryops.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `quarryops.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `quarryops.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to
make coverage look bigger. Note that the explosives/blast-safety sub-
citation is FULL coverage rather than a gap: ALL FOUR seeded
jurisdictions (JPN, USA, GBR, DEU) actually have a real explosives/
blast-safety enforcement regime, reported honestly.

## Maturity

`:implemented` -- `QuarryOps-LLM` + `Quarry Governor` run as real,
tested code (see `Run` above), promoted from the originally-published
`:blueprint`-tier scaffold, following the SAME governed-actor
architecture as the 88 other prior actors across this fleet, with its
own distinct, independently-named governor. See
`docs/adr/0001-architecture.md` for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
