(ns quarryops.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean extraction
  through intake -> jurisdiction assessment -> robot bench-face/
  quarry-face verification mission (escalate/approve) -> material
  extraction (escalate/approve/commit) -> consignment shipment
  (escalate/approve/commit), then a SEPARATE clean blasting extraction
  through the same lifecycle (demonstrating the conditional blast-
  safety-clearance check passing cleanly), then shows HARD-hold
  scenarios: a jurisdiction with no spec-basis, an extraction attempted
  before its robot verification mission ever ran
  (`:robotics-simulation-missing`, ADR-2607142800), a royalty mismatch
  (verified first), an invalid extraction permit, an unconfirmed
  blast-safety clearance on a blasting extraction, a robot mission
  already on file whose own REAL `physics-2d`-simulated bench-face
  loose-block settling telemetry fails an INDEPENDENT recheck
  (`:robotics-simulation-out-of-tolerance`, ADR-2607142800/
  ADR-2607152000), a double extraction, and a double shipment.

  `quarryops.robotics/simulate-quarry-face-verification` now actually
  runs a real `physics-2d`-backed time-stepped free-fall/settling
  simulation of a bench-face loose block (ADR-2607152000, generalizing
  ADR-2607151600's automotive pilot to this vertical) -- see
  `quarryops.robotics` for what is genuinely simulated vs. disclosed
  simplification.

  Like `retailops`/4711's and `freightops`/4920's own new checks,
  this actor's `extraction-permit-invalid?`/`blast-safety-clearance-
  unconfirmed?` checks are evaluated directly at `:extraction/extract`
  time rather than via a separate screening op -- a real extraction
  decision validates permit status and blast-safety clearance at the
  point of the act itself. `quarryops.robotics`'s robot verification
  mission IS its own separate governed op
  (`:robotics/simulate-quarry-face-verification`, never auto-eligible
  at any phase -- see `quarryops.phase`), exercised directly below
  exactly like ADR-2607142800's reference implementation
  (`cloud-itonami-isic-2910`'s `automotive.robotics`). Each check is
  still exercised directly and independently below, one extraction
  per HARD-hold scenario, following the SAME 'exercise the failure
  mode directly, never only via a happy-path actuation' discipline
  `parksafety`'s ADR-2607071922 Decision 5 and every sibling since
  establish."
  (:require [langgraph.graph :as g]
            [quarryops.store :as store]
            [quarryops.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :quarry-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== extraction/intake extraction-1 (JPN, clean, no blasting) ==")
    (println (exec-op actor "t1" {:op :extraction/intake :subject "extraction-1"
                                  :patch {:id "extraction-1" :site "North Face"}} operator))

    (println "== jurisdiction/assess extraction-1 (escalates -- human approves) ==")
    (println (exec-op actor "t2" {:op :jurisdiction/assess :subject "extraction-1"} operator))
    (println (approve! actor "t2"))

    (println "== robotics/simulate-quarry-face-verification extraction-1 (robot bench-face/quarry-face survey mission; escalates -- human approves) ==")
    (println (exec-op actor "t2b" {:op :robotics/simulate-quarry-face-verification :subject "extraction-1"} operator))
    (println (approve! actor "t2b"))

    (println "== extraction/extract extraction-1 (always escalates -- actuation/extract-material) ==")
    (let [r (exec-op actor "t3" {:op :extraction/extract :subject "extraction-1"} operator)]
      (println r)
      (println "-- human quarry operator approves --")
      (println (approve! actor "t3")))

    (println "== consignment/ship extraction-1 (always escalates -- actuation/ship-consignment) ==")
    (let [r (exec-op actor "t4" {:op :consignment/ship :subject "extraction-1"} operator)]
      (println r)
      (println "-- human quarry operator approves --")
      (println (approve! actor "t4")))

    (println "== extraction/intake extraction-6 (JPN, clean, blasting confirmed) ==")
    (println (exec-op actor "t5" {:op :extraction/intake :subject "extraction-6"
                                  :patch {:id "extraction-6" :site "Central Face"}} operator))

    (println "== jurisdiction/assess extraction-6 (escalates -- human approves) ==")
    (println (exec-op actor "t6" {:op :jurisdiction/assess :subject "extraction-6"} operator))
    (println (approve! actor "t6"))

    (println "== extraction/extract extraction-6 before robotics simulation -> HARD hold (robotics-simulation-missing) ==")
    (println (exec-op actor "t6b" {:op :extraction/extract :subject "extraction-6"} operator))

    (println "== robotics/simulate-quarry-face-verification extraction-6 (escalates -- human approves) ==")
    (println (exec-op actor "t6c" {:op :robotics/simulate-quarry-face-verification :subject "extraction-6"} operator))
    (println (approve! actor "t6c"))

    (println "== extraction/extract extraction-6 (blasting, clearance confirmed, robotics-sim on file -- escalates -- human approves) ==")
    (println (exec-op actor "t7" {:op :extraction/extract :subject "extraction-6"} operator))
    (println (approve! actor "t7"))

    (println "== jurisdiction/assess extraction-2 (no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :jurisdiction/assess :subject "extraction-2" :no-spec? true} operator))

    (println "== jurisdiction/assess extraction-3 (escalates -- human approves; sets up the royalty-mismatch test) ==")
    (println (exec-op actor "t9" {:op :jurisdiction/assess :subject "extraction-3"} operator))
    (println (approve! actor "t9"))

    (println "== extraction/extract extraction-3 (claimed 150.0 vs recompute 100.0 -> HARD hold) ==")
    (println (exec-op actor "t10" {:op :extraction/extract :subject "extraction-3"} operator))

    (println "== jurisdiction/assess extraction-4 (escalates -- human approves; sets up the permit-invalid test) ==")
    (println (exec-op actor "t11" {:op :jurisdiction/assess :subject "extraction-4"} operator))
    (println (approve! actor "t11"))

    (println "== extraction/extract extraction-4 (permit invalid -> HARD hold) ==")
    (println (exec-op actor "t12" {:op :extraction/extract :subject "extraction-4"} operator))

    (println "== jurisdiction/assess extraction-5 (escalates -- human approves; sets up the blast-safety-clearance test) ==")
    (println (exec-op actor "t13" {:op :jurisdiction/assess :subject "extraction-5"} operator))
    (println (approve! actor "t13"))

    (println "== extraction/extract extraction-5 (blasting, clearance unconfirmed -> HARD hold) ==")
    (println (exec-op actor "t14" {:op :extraction/extract :subject "extraction-5"} operator))

    (println "== jurisdiction/assess extraction-7 (escalates -- human approves; sets up the robotics-simulation-out-of-tolerance test) ==")
    (println (exec-op actor "t14b" {:op :jurisdiction/assess :subject "extraction-7"} operator))
    (println (approve! actor "t14b"))

    (println "== extraction/extract extraction-7 (robotics-sim on file, but a real re-run physics-2d free-fall/settling simulation shows this 340kg block's 14.0m survey height exceeding the catch bench's real 10.0m rated containment height on independent recheck -> HARD hold) ==")
    (println (exec-op actor "t14c" {:op :extraction/extract :subject "extraction-7"} operator))

    (println "== extraction/extract extraction-1 AGAIN (double-extraction -> HARD hold) ==")
    (println (exec-op actor "t15" {:op :extraction/extract :subject "extraction-1"} operator))

    (println "== consignment/ship extraction-1 AGAIN (double-shipment -> HARD hold) ==")
    (println (exec-op actor "t16" {:op :consignment/ship :subject "extraction-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft extraction records ==")
    (doseq [r (store/extraction-history db)] (println r))

    (println "== draft shipment records ==")
    (doseq [r (store/shipment-history db)] (println r))))
