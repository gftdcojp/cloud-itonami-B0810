(ns quarryops.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  Trust Controls ('extraction outside permit is blocked; safety gates
  are auditable; royalty records are immutable') implemented
  faithfully. The single invariant under test:

    QuarryOps-LLM never extracts material or ships a consignment the
    Quarry Governor would reject, `:extraction/extract`/`:consignment/
    ship` NEVER auto-commit at any phase, `:extraction/intake` (no
    direct capital risk) MAY auto-commit when clean, and every
    decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [quarryops.store :as store]
            [quarryops.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :quarry-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(defn- simulate-robotics!
  "Walks `subject` through the robot bench-face/quarry-face
  verification mission -> approve, leaving `:robotics-sim-verified?`
  on file. This now ACTUALLY runs the real `physics-2d`-backed bench-
  face loose-block free-fall/settling simulation for the extraction's
  own :fragment-mass-kg/:bench-drop-height-m (ADR-2607152000) -- only
  meaningful to call for an extraction whose real simulated settling
  telemetry is actually within tolerance -- an out-of-tolerance
  extraction still gets :robotics-sim-verified? recorded (per whatever
  the mission itself found), but `quarryops.governor`'s independent
  recheck HARD-holds regardless (see
  `robotics-simulation-out-of-tolerance-is-held`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-robotics") {:op :robotics/simulate-quarry-face-verification :subject subject} operator)
  (approve! actor (str tid-prefix "-robotics")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :extraction/intake :subject "extraction-1"
                   :patch {:id "extraction-1" :site "North Face"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "North Face" (:site (store/extraction db "extraction-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "extraction-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "extraction-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "extraction-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "extraction-1")) "no assessment written"))))

(deftest extraction-without-assessment-is-held
  (testing "extraction/extract before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :extraction/extract :subject "extraction-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest royalty-mismatch-is-held
  (testing "a claimed royalty that doesn't equal quantity x rate -> HOLD (the ground-truth-recompute discipline every sibling's cost/total-matching check establishes)"
    (let [[db actor] (fresh)
          _ (assess! actor "t5pre" "extraction-3")
          res (exec-op actor "t5" {:op :extraction/extract :subject "extraction-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:royalty-mismatch} (-> (store/ledger db) last :basis)))
      (is (empty? (store/extraction-history db))))))

(deftest extraction-permit-invalid-is-held-and-unoverridable
  (testing "an invalid extraction permit -> HOLD, and never reaches request-approval -- the FLAGSHIP genuinely new check this vertical adds, the 76th unconditional-evaluation-discipline grounding overall, grounded in the US Mine Act (MSHA), UK Quarries Regulations 1999 (HSE), Germany's Bundesberggesetz and Japan's own Mine Safety Act"
    (let [[db actor] (fresh)
          _ (assess! actor "t6pre" "extraction-4")
          res (exec-op actor "t6" {:op :extraction/extract :subject "extraction-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:extraction-permit-invalid} (-> (store/ledger db) last :basis)))
      (is (empty? (store/extraction-history db))))))

(deftest blast-safety-clearance-unconfirmed-is-held-and-unoverridable
  (testing "an unconfirmed blast-safety clearance on a blasting extraction -> HOLD, and never reaches request-approval -- a genuinely new check, the 77th unconditional-evaluation-discipline grounding overall, the EIGHTH conditional variant (see this actor's governor ns docstring / the full accumulated ADR-0001 chain: parksafety's ADR-2607071922 Decision 5 through leathergoods's, ictrepair's, retailops's and freightops's own)"
    (let [[db actor] (fresh)
          _ (assess! actor "t7pre" "extraction-5")
          res (exec-op actor "t7" {:op :extraction/extract :subject "extraction-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:blast-safety-clearance-unconfirmed} (-> (store/ledger db) last :basis)))
      (is (empty? (store/extraction-history db))))))

(deftest extraction-extract-is-a-noop-when-no-blasting
  (testing "the blast-safety-clearance check is CONDITIONAL: an extraction that does not involve blasting has no blast-safety requirement at all"
    (let [[_db actor] (fresh)
          _ (assess! actor "t7bpre" "extraction-1")
          _ (simulate-robotics! actor "t7bpre2" "extraction-1")
          res (exec-op actor "t7b" {:op :extraction/extract :subject "extraction-1"} operator)]
      (is (= :interrupted (:status res)) "clean extraction still escalates for human sign-off, but is NOT a HARD hold"))))

(deftest extraction-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, matching-royalty, valid-permit extraction still ALWAYS interrupts for human approval -- actuation/extract-material is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t8pre" "extraction-1")
          _ (simulate-robotics! actor "t8pre2" "extraction-1")
          r1 (exec-op actor "t8" {:op :extraction/extract :subject "extraction-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, extraction record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:extracted? (store/extraction db "extraction-1"))))
          (is (= 1 (count (store/extraction-history db))) "one draft extraction record"))))))

(deftest shipment-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, extracted consignment still ALWAYS interrupts for human approval -- actuation/ship-consignment is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t9pre" "extraction-1")
          r1 (exec-op actor "t9" {:op :consignment/ship :subject "extraction-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, shipment record drafted"
        (let [r2 (approve! actor "t9")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:shipped? (store/extraction db "extraction-1"))))
          (is (= 1 (count (store/shipment-history db))) "one draft shipment record"))))))

(deftest extraction-double-extraction-is-held
  (testing "extracting the same extraction record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t10pre" "extraction-1")
          _ (simulate-robotics! actor "t10pre2" "extraction-1")
          _ (exec-op actor "t10a" {:op :extraction/extract :subject "extraction-1"} operator)
          _ (approve! actor "t10a")
          res (exec-op actor "t10" {:op :extraction/extract :subject "extraction-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-extracted} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/extraction-history db))) "still only the one earlier extraction"))))

(deftest shipment-double-shipment-is-held
  (testing "shipping the same extraction's consignment twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t11pre" "extraction-1")
          _ (exec-op actor "t11a" {:op :consignment/ship :subject "extraction-1"} operator)
          _ (approve! actor "t11a")
          res (exec-op actor "t11" {:op :consignment/ship :subject "extraction-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-shipped} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/shipment-history db))) "still only the one earlier shipment"))))

(deftest robotics-simulation-always-needs-approval
  (testing "robotics/simulate-quarry-face-verification is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t12" {:op :robotics/simulate-quarry-face-verification :subject "extraction-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t12")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:robotics-sim-verified? (store/extraction db "extraction-1"))))))))

(deftest extraction-without-robotics-simulation-is-held
  (testing "extraction/extract before the robot bench-face/quarry-face verification mission ever ran -> HOLD (robotics-simulation-missing)"
    (let [[db actor] (fresh)
          _ (assess! actor "t13pre" "extraction-1")
          res (exec-op actor "t13" {:op :extraction/extract :subject "extraction-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:robotics-simulation-missing} (-> (store/ledger db) last :basis)))
      (is (empty? (store/extraction-history db))))))

(deftest robotics-simulation-out-of-tolerance-is-held
  (testing "extraction-7 has a robotics-sim already on file, but its own REAL physics-2d-simulated bench-face settling telemetry (:sim-settling-distance-m/:sim-impact-energy-j -- ADR-2607152000) falls outside the real tolerance band on INDEPENDENT recheck -> HOLD, never trusts the on-file verdict alone. extraction-7's loose block is deliberately surveyed at 14.0m in the demo fixture (quarryops.store/demo-data) -- higher than its own catch bench's 10.0m rated containment height, a genuine design-record inconsistency (the same 'deliberately misconfigured fixture' technique automotive.store/demo-data uses for its own vehicle-5) -- which the real, re-run simulation catches."
    (let [[db actor] (fresh)
          _ (assess! actor "t14pre" "extraction-7")
          res (exec-op actor "t14" {:op :extraction/extract :subject "extraction-7"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:robotics-simulation-out-of-tolerance} (-> (store/ledger db) last :basis)))
      (is (empty? (store/extraction-history db))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :extraction/intake :subject "extraction-1"
                          :patch {:id "extraction-1" :site "North Face"}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "extraction-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
