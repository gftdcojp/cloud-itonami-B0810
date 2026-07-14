(ns quarryops.quarryopsllm
  "QuarryOps-LLM client -- the *contained intelligence node* for the
  community-quarry actor.

  It normalizes extraction intake, drafts a per-jurisdiction mine-
  safety/explosives-blast-safety evidence checklist, drafts the
  material-extraction action, and drafts the consignment-shipment
  action. CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record or a real extraction/shipment. Every output is
  censored downstream by `quarryops.governor` before anything touches
  the SSoT, and `:extraction/extract`/`:consignment/ship` proposals
  NEVER auto-commit at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/extract-material | :actuation/ship-consignment | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [quarryops.facts :as facts]
            [quarryops.registry :as registry]
            [quarryops.robotics :as robotics]
            [quarryops.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the permit, quantities or jurisdiction. High
  confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "採掘記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :extraction/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction mine-safety/explosives-blast-safety evidence
  checklist draft. `:no-spec?` injects the failure mode we must defend
  against: proposing a checklist for a jurisdiction with NO official
  spec-basis in `quarryops.facts` -- the Quarry Governor must reject
  this (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [e (store/extraction db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction e))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "quarryops.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- simulate-quarry-face-verification
  "Runs the robot bench-face/quarry-face verification mission
  (`quarryops.robotics`) and drafts its result as a proposal. This now
  ACTUALLY runs a real `physics-2d`-stepped bench-face loose-block
  free-fall/settling simulation (ADR-2607152000, see `quarryops.
  robotics/simulate-quarry-face-verification`'s docstring). High
  confidence -- the mission itself is deterministic simulated
  telemetry derived from the extraction's own recorded `:fragment-
  mass-kg`/`:bench-drop-height-m` fields (never an LLM guess); the
  Quarry Governor still independently re-derives :passed? from the
  real telemetry fields this drafts before any `:actuation/extract-
  material` proposal may commit -- see `quarryops.governor`'s
  `robotics-simulation-violations`."
  [db {:keys [subject]}]
  (let [e (store/extraction db subject)]
    (if (nil? e)
      {:summary "対象採掘記録が見つかりません" :rationale "no extraction record"
       :cites [] :effect :extraction/upsert :value {:id subject :robotics-sim-verified? false}
       :stake nil :confidence 0.0}
      (let [{:keys [mission actions passed? sim-settling-distance-m sim-impact-energy-j]}
            (robotics/simulate-quarry-face-verification subject e)]
        {:summary    (str subject ": ベンチフェイス/採石場面検証ロボットミッション " (if passed? "合格" "不合格"))
         :rationale  (str "mission=" (:mission/id mission) " actions=" (count actions)
                          " sim-settling-distance-m=" sim-settling-distance-m
                          " sim-impact-energy-j=" sim-impact-energy-j)
         :cites      [(:mission/id mission)]
         :effect     :extraction/upsert
         :value      {:id subject
                      :robotics-sim-verified? passed?
                      :sim-settling-distance-m sim-settling-distance-m
                      :sim-impact-energy-j sim-impact-energy-j
                      :robotics-sim-record {:mission-id (:mission/id mission)
                                            :actions (mapv #(dissoc % :action) actions)
                                            :passed? passed?}}
         :stake      nil
         :confidence 0.95}))))

(defn- propose-extraction
  "Draft the actual MATERIAL-EXTRACTION action -- extracting real
  material from the quarry face. ALWAYS `:stake :actuation/extract-
  material` -- this is a REAL-WORLD act (a robot/crew physically
  extracts stone/aggregate), never a draft the actor may auto-run.
  See README `Actuation`: no phase ever adds this op to a phase's
  `:auto` set (`quarryops.phase`); the governor also always escalates
  on `:actuation/extract-material`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [e (store/extraction db subject)
        royalty-ok? (and e (registry/royalty-matches-claim? e))
        permit-ok? (and e (:permit-valid? e))
        blast-ok? (and e (or (not (:involves-blasting? e)) (:blast-clearance-confirmed? e)))]
    {:summary    (str subject " 向け採掘提案"
                      (when e (str " (site=" (:site e) ")")))
     :rationale  (if e
                   (str "claimed-royalty=" (:claimed-royalty e)
                        " independent-recompute=" (registry/compute-royalty e)
                        " permit-valid?=" permit-ok?
                        " blast-clearance-ok?=" blast-ok?)
                   "extractionが見つかりません")
     :cites      (if e [subject] [])
     :effect     :extraction/mark-extracted
     :value      {:extraction-id subject}
     :stake      :actuation/extract-material
     :confidence (if (and royalty-ok? permit-ok? blast-ok?) 0.9 0.3)}))

(defn- propose-shipment
  "Draft the actual CONSIGNMENT-SHIPMENT action -- shipping a real
  consignment (loadout and haul). ALWAYS `:stake :actuation/ship-
  consignment` -- this is a REAL-WORLD act (real material leaves the
  site, royalty accrues), never a draft the actor may auto-run. See
  README `Actuation`: no phase ever adds this op to a phase's `:auto`
  set (`quarryops.phase`); the governor also always escalates on
  `:actuation/ship-consignment`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [e (store/extraction db subject)
        extracted? (and e (:extracted? e))]
    {:summary    (str subject " 向け出荷提案"
                      (when e (str " (site=" (:site e) ")")))
     :rationale  (if e
                   (str "extracted?=" extracted?)
                   "extractionが見つかりません")
     :cites      (if e [subject] [])
     :effect     :extraction/mark-shipped
     :value      {:extraction-id subject}
     :stake      :actuation/ship-consignment
     :confidence (if extracted? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :extraction/intake           (normalize-intake db request)
    :jurisdiction/assess             (assess-jurisdiction db request)
    :robotics/simulate-quarry-face-verification (simulate-quarry-face-verification db request)
    :extraction/extract                  (propose-extraction db request)
    :consignment/ship                        (propose-shipment db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地域採石業者の採掘・出荷エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:extraction/upsert|:assessment/set|:extraction/mark-extracted|"
       ":extraction/mark-shipped) "
       "(:robotics/simulate-quarry-face-verification も :extraction/upsert で "
       ":robotics-sim-verified? を提案する) "
       ":stake(:actuation/extract-material か :actuation/ship-consignment か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"
       "採掘許可の有効性や発破安全確認の状況を偽って報告してはいけません。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess    {:extraction (store/extraction st subject)}
    :robotics/simulate-quarry-face-verification {:extraction (store/extraction st subject)}
    :extraction/extract     {:extraction (store/extraction st subject)}
    :consignment/ship       {:extraction (store/extraction st subject)}
    {:extraction (store/extraction st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Quarry Governor escalates/
  holds -- an LLM hiccup can never auto-extract material or auto-ship
  a consignment."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :quarryopsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
