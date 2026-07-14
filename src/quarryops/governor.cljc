(ns quarryops.governor
  "Quarry Governor -- the independent compliance layer that earns the
  QuarryOps-LLM the right to commit. The LLM has no notion of
  jurisdictional mine-safety/explosives-blast-safety law, whether an
  extraction's own claimed royalty actually equals quantity times
  royalty-rate, whether an extraction permit is actually still valid,
  whether a blasting operation's own blast-safety clearance has
  actually been confirmed, or when an act stops being a draft and
  becomes a real-world extraction or consignment shipment, so this
  MUST be a separate system able to *reject* a proposal and fall back
  to HOLD.

  `:itonami.blueprint/governor` is `:quarry-governor`, grep-verified
  UNIQUE fleet-wide -- no naming-collision precedent question, a
  fresh independent build following the SAME governed-actor
  architecture (langgraph StateGraph + independent Governor + Phase
  0->3 rollout) established by `cloud-itonami-isic-6511`.

  This blueprint's own text (docs/business-model.md's own Trust
  Controls: 'extraction outside permit is blocked; safety gates are
  auditable; royalty records are immutable') names exactly the checks
  below.

  Six checks, in priority order, ALL HARD violations: a human
  approver CANNOT override them. The confidence/actuation gate is
  SOFT: it asks a human to look (low confidence / actuation), and the
  human may approve -- but see `quarryops.phase`: for `:stake
  :actuation/extract-material`/`:actuation/ship-consignment` (a real
  extraction or shipment) NO phase ever allows auto-commit either.
  Two independent layers agree that actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source
                                       (`quarryops.facts`), or invent
                                       one?
    2. Evidence incomplete         -- for `:extraction/extract`/
                                       `:consignment/ship`, has the
                                       jurisdiction actually been
                                       assessed with a full evidence
                                       checklist on file?
    3. Robot simulation missing or
       independently out-of-
       tolerance                    -- for `:extraction/extract`, has
                                       the robot bench-face/quarry-
                                       face verification mission
                                       (`quarryops.robotics`) actually
                                       run and been recorded on the
                                       extraction
                                       (`:robotics-sim-verified?`)?
                                       AND INDEPENDENTLY recompute
                                       whether the extraction's own
                                       recorded REAL `physics-2d`-
                                       simulated bench-face loose-
                                       block settling telemetry
                                       (`:sim-settling-distance-m`/
                                       `:sim-impact-energy-j`, ADR-
                                       2607152000) falls outside a
                                       real tolerance band derived
                                       from this site's own recorded
                                       `:catch-bench-rated-height-m`
                                       design rating
                                       (`quarryops.robotics/
                                       simulation-out-of-tolerance?`),
                                       ignoring whatever :passed?
                                       verdict the mission run itself
                                       stored -- the same 'ground
                                       truth, not self-report'
                                       discipline check 4 below uses
                                       for royalty.
    4. Royalty mismatch            -- for `:extraction/extract`,
                                       INDEPENDENTLY recompute whether
                                       the extraction's own `:claimed-
                                       royalty` equals `quantity x
                                       royalty-rate`
                                       (`quarryops.registry/royalty-
                                       matches-claim?`) -- an HONEST
                                       reapplication of the SAME
                                       ground-truth-recompute
                                       DISCIPLINE `leathergoods.
                                       registry`'s/`specialtyrepair.
                                       registry`'s/`retailops.
                                       registry`'s own checks
                                       establish, reapplied to a
                                       quarry royalty line -- not
                                       claimed as new.
    5. Extraction permit invalid   -- for `:extraction/extract`,
                                       INDEPENDENTLY verify the
                                       extraction's own `:permit-
                                       valid?` is true -- the FLAGSHIP
                                       genuinely new check this
                                       vertical adds (grep-verified
                                       absent fleet-wide -- zero hits
                                       for 'permit-invalid'/'mining-
                                       permit' as a governor check
                                       function name), the 76th
                                       distinct application of the
                                       unconditional-evaluation
                                       discipline overall (most
                                       recently `freightops.governor/
                                       cargo-liability-disclosure-
                                       violations` at 75th). Grounded
                                       in real mine-safety/extraction-
                                       permit law: the US Mine Act
                                       (enforced by MSHA), the UK's
                                       Quarries Regulations 1999
                                       (enforced by the HSE),
                                       Germany's Bundesberggesetz
                                       (BBergG, enforced by
                                       Landesbergämter), and Japan's
                                       own 鉱山保安法 (Mine Safety Act,
                                       enforced by METI's Industrial
                                       Safety Group) -- directly
                                       grounded in this blueprint's
                                       own text ('extraction outside
                                       permit is blocked'). Evaluated
                                       UNCONDITIONALLY (every
                                       extraction needs a valid
                                       permit).
    6. Blast safety clearance
       unconfirmed                    -- for `:extraction/extract`,
                                       for an extraction whose own
                                       record declares `:involves-
                                       blasting? true` (i.e. this
                                       extraction actually uses
                                       explosives -- not every
                                       extraction method does, e.g.
                                       diamond-wire cutting for
                                       dimension stone vs. drilling/
                                       blasting for aggregate),
                                       INDEPENDENTLY check whether
                                       `:blast-clearance-confirmed?`
                                       is true. A GENUINELY NEW
                                       concept (grep-verified absent
                                       fleet-wide -- zero hits for
                                       'blast-safety'/'blast-
                                       clearance' as a governor check
                                       function name), the 77th
                                       distinct application overall,
                                       the EIGHTH conditional variant
                                       (after `socialresearch`/7220's,
                                       `bizassoc`/9411's, `training`/
                                       8549's, `furniture`/9524's,
                                       `specialtyrepair`/9529's,
                                       `leathergoods`/9523's and
                                       `ictrepair`/9511's own, at 63rd,
                                       64th, 66th, 67th, 68th, 69th
                                       and 71st). CONDITIONAL on the
                                       extraction's own `:involves-
                                       blasting?` ground truth --
                                       mechanical extraction has no
                                       blast-safety concern at all.
                                       Grounded in real explosives/
                                       blast-safety law: the US 30
                                       C.F.R. Part 56 Subpart E
                                       (MSHA), the UK's Quarries
                                       Regulations 1999 shot-firing
                                       provisions / Explosives
                                       Regulations 2014 (HSE),
                                       Germany's Sprengstoffgesetz
                                       (SprengG), and Japan's own
                                       火薬類取締法 (Explosives Control
                                       Act) -- ALL FOUR seeded
                                       jurisdictions actually have a
                                       real regime here, reported
                                       honestly (matching
                                       `leathergoods`/9523's,
                                       `ictrepair`/9511's,
                                       `retailops`/4711's and
                                       `freightops`/4920's own full-
                                       coverage sub-citations).
    7. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:extraction/
                                       extract`/`:consignment/ship`
                                       (REAL acts) -> escalate.

  Two more guards, double-extraction/double-shipment prevention, are
  enforced but NOT listed as numbered HARD checks above because they
  need no upstream comparison at all -- `already-extracted-
  violations`/`already-shipped-violations` refuse to extract/ship the
  SAME extraction twice, off dedicated `:extracted?`/`:shipped?`
  facts (never a `:status` value) -- the SAME 'check a dedicated
  boolean, not status' discipline every prior governor's guards
  establish, informed by `cloud-itonami-isic-6492`'s status-lifecycle
  bug (ADR-2607071320)."
  (:require [quarryops.facts :as facts]
            [quarryops.registry :as registry]
            [quarryops.robotics :as robotics]
            [quarryops.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Extracting real material and shipping a real consignment are the
  two real-world actuation events this actor performs -- a two-member
  set, matching every sibling's own dual-actuation shape."
  #{:actuation/extract-material :actuation/ship-consignment})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:extraction/extract`/`:consignment/
  ship`) proposal with no spec-basis citation is a HARD violation --
  never invent a jurisdiction's mine-safety/explosives-blast-safety
  requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :extraction/extract :consignment/ship} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:extraction/extract`/`:consignment/ship`, the jurisdiction's
  required extraction-permit/survey/haul/blast-clearance evidence must
  actually be satisfied -- do not trust the advisor's self-reported
  confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:extraction/extract :consignment/ship} op)
    (let [e (store/extraction st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction e) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(採掘許可記録/測量記録/運搬記録/発破安全確認記録等)が充足していない状態での提案"}]))))

(defn- robotics-simulation-violations
  "For `:extraction/extract`: HARD hold if the robot bench-face/
  quarry-face verification mission (`quarryops.robotics`) never ran
  and was recorded on the extraction (`:robotics-sim-verified?`), OR
  if it did but an INDEPENDENT recompute of the extraction's own REAL
  `physics-2d`-simulated bench-face settling telemetry (`:sim-
  settling-distance-m`/`:sim-impact-energy-j`, ADR-2607152000 --
  `quarryops.robotics/simulation-out-of-tolerance?`) says out-of-
  tolerance right now -- never trusts the mission's own stored
  :passed? verdict alone, the same discipline `royalty-mismatch-
  violations` below uses for royalty."
  [{:keys [op subject]} st]
  (when (= op :extraction/extract)
    (let [e (store/extraction st subject)]
      (cond
        (not (:robotics-sim-verified? e))
        [{:rule :robotics-simulation-missing
          :detail (str subject " のベンチフェイス/採石場面検証ロボットミッションが未実行・未合格")}]

        (robotics/simulation-out-of-tolerance? e)
        [{:rule :robotics-simulation-out-of-tolerance
          :detail (str subject " の実測落石沈降距離(" (:sim-settling-distance-m e)
                       "m)/衝突エネルギー(" (:sim-impact-energy-j e)
                       "J)が独立再検証でキャッチベンチ定格(" (:catch-bench-rated-height-m e) "m)の許容範囲を逸脱")}]))))

(defn- royalty-mismatch-violations
  "For `:extraction/extract`, INDEPENDENTLY recompute whether the
  extraction's own claimed royalty equals quantity x royalty-rate via
  `quarryops.registry/royalty-matches-claim?` -- needs no proposal
  inspection or stored-verdict lookup at all, an honest reapplication
  of the same discipline every sibling actor's own cost/total-matching
  check establishes."
  [{:keys [op subject]} st]
  (when (= op :extraction/extract)
    (let [e (store/extraction st subject)]
      (when-not (registry/royalty-matches-claim? e)
        [{:rule :royalty-mismatch
          :detail (str subject " の申告採石料(" (:claimed-royalty e)
                      ")が独立再計算値(" (registry/compute-royalty e) ")と一致しない")}]))))

(defn- extraction-permit-invalid-violations
  "For `:extraction/extract`, INDEPENDENTLY verify the extraction's
  own `:permit-valid?` is true -- the flagship genuinely new check
  this vertical adds. Evaluated UNCONDITIONALLY (every extraction
  needs a valid permit)."
  [{:keys [op subject]} st]
  (when (= op :extraction/extract)
    (let [e (store/extraction st subject)]
      (when-not (true? (:permit-valid? e))
        [{:rule :extraction-permit-invalid
          :detail (str subject " の採掘許可(" (:permit-id e) ")が無効")}]))))

(defn- blast-safety-clearance-unconfirmed-violations
  "For `:extraction/extract`, for an extraction whose own record
  declares `:involves-blasting? true`, INDEPENDENTLY check whether
  `:blast-clearance-confirmed?` is true -- a genuinely new concept
  (see ns docstring), CONDITIONAL on the extraction's own `:involves-
  blasting?` ground truth (mechanical extraction has no blast-safety
  requirement at all)."
  [{:keys [op subject]} st]
  (when (= op :extraction/extract)
    (let [e (store/extraction st subject)]
      (when (and (true? (:involves-blasting? e))
                 (not (true? (:blast-clearance-confirmed? e))))
        [{:rule :blast-safety-clearance-unconfirmed
          :detail (str subject " は発破作業を伴うが安全確認が未完了 -- 採掘提案は進められない")}]))))

(defn- already-extracted-violations
  "For `:extraction/extract`, refuses to extract the SAME extraction
  record twice, off a dedicated `:extracted?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :extraction/extract)
    (when (store/extraction-already-extracted? st subject)
      [{:rule :already-extracted
        :detail (str subject " は既に採掘済み")}])))

(defn- already-shipped-violations
  "For `:consignment/ship`, refuses to ship the SAME extraction's
  consignment twice, off a dedicated `:shipped?` fact (never a
  `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :consignment/ship)
    (when (store/extraction-already-shipped? st subject)
      [{:rule :already-shipped
        :detail (str subject " は既に出荷済み")}])))

(defn check
  "Censors a QuarryOps-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (robotics-simulation-violations request st)
                           (royalty-mismatch-violations request st)
                           (extraction-permit-invalid-violations request st)
                           (blast-safety-clearance-unconfirmed-violations request st)
                           (already-extracted-violations request st)
                           (already-shipped-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
