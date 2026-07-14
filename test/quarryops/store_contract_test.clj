(ns quarryops.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the
  sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [quarryops.robotics :as robotics]
            [quarryops.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "JPN" (:jurisdiction (store/extraction s "extraction-1"))))
      (is (= 200.0 (:claimed-royalty (store/extraction s "extraction-1"))))
      (is (true? (:permit-valid? (store/extraction s "extraction-1"))))
      (is (false? (:involves-blasting? (store/extraction s "extraction-1"))))
      (is (= 150.0 (:claimed-royalty (store/extraction s "extraction-3"))))
      (is (false? (:permit-valid? (store/extraction s "extraction-4"))))
      (is (true? (:involves-blasting? (store/extraction s "extraction-5"))))
      (is (false? (:blast-clearance-confirmed? (store/extraction s "extraction-5"))))
      (is (true? (:blast-clearance-confirmed? (store/extraction s "extraction-6"))))
      (is (false? (:robotics-sim-verified? (store/extraction s "extraction-1"))) "no robotics mission has run yet")
      (is (true? (:robotics-sim-verified? (store/extraction s "extraction-7"))) "seeded as already-on-file")
      (is (= 4.0 (:bench-drop-height-m (store/extraction s "extraction-1"))))
      (is (= 180.0 (:fragment-mass-kg (store/extraction s "extraction-1"))))
      (is (= 10.0 (:catch-bench-rated-height-m (store/extraction s "extraction-1"))))
      (is (number? (:sim-settling-distance-m (store/extraction s "extraction-1"))) "real physics-2d telemetry on file")
      (is (number? (:sim-impact-energy-j (store/extraction s "extraction-1"))) "real physics-2d telemetry on file")
      (is (not (robotics/simulation-out-of-tolerance? (store/extraction s "extraction-1")))
          "extraction-1's real 180kg/4.0m bench-face settling simulation clears the real catch-bench tolerance band")
      (is (= 14.0 (:bench-drop-height-m (store/extraction s "extraction-7")))
          "extraction-7's loose block is deliberately surveyed above its own catch bench's rated height -- see quarryops.store/demo-data")
      (is (robotics/simulation-out-of-tolerance? (store/extraction s "extraction-7"))
          "extraction-7's real simulated settling/impact-energy genuinely exceeds the real catch-bench tolerance band")
      (is (false? (:extracted? (store/extraction s "extraction-1"))))
      (is (false? (:shipped? (store/extraction s "extraction-1"))))
      (is (= ["extraction-1" "extraction-2" "extraction-3" "extraction-4" "extraction-5" "extraction-6" "extraction-7"]
             (mapv :id (store/all-extractions s))))
      (is (nil? (store/assessment-of s "extraction-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/extraction-history s)))
      (is (= [] (store/shipment-history s)))
      (is (zero? (store/next-extraction-sequence s "JPN")))
      (is (zero? (store/next-shipment-sequence s "JPN")))
      (is (false? (store/extraction-already-extracted? s "extraction-1")))
      (is (false? (store/extraction-already-shipped? s "extraction-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :extraction/upsert
                                 :value {:id "extraction-1" :site "North Face"}})
        (is (= "North Face" (:site (store/extraction s "extraction-1"))))
        (is (= 200.0 (:claimed-royalty (store/extraction s "extraction-1"))) "unrelated field preserved"))
      (testing "robotics-sim result commits via :extraction/upsert and reads back"
        (store/commit-record! s {:effect :extraction/upsert
                                 :value {:id "extraction-1" :robotics-sim-verified? true
                                        :robotics-sim-record {:mission-id "m-1" :passed? true}}})
        (is (true? (:robotics-sim-verified? (store/extraction s "extraction-1"))))
        (is (= {:mission-id "m-1" :passed? true} (:robotics-sim-record (store/extraction s "extraction-1"))))
        (is (= 200.0 (:claimed-royalty (store/extraction s "extraction-1"))) "unrelated field still preserved"))
      (testing "assessment payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["extraction-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "extraction-1"))))
      (testing "extraction drafts a record and advances the extraction sequence"
        (store/commit-record! s {:effect :extraction/mark-extracted :path ["extraction-1"]})
        (is (= "JPN-EXT-000000" (get (first (store/extraction-history s)) "record_id")))
        (is (= "extraction-draft" (get (first (store/extraction-history s)) "kind")))
        (is (true? (:extracted? (store/extraction s "extraction-1"))))
        (is (= 1 (count (store/extraction-history s))))
        (is (= 1 (store/next-extraction-sequence s "JPN")))
        (is (true? (store/extraction-already-extracted? s "extraction-1"))))
      (testing "shipment drafts a record and advances the shipment sequence"
        (store/commit-record! s {:effect :extraction/mark-shipped :path ["extraction-1"]})
        (is (= "JPN-SHP-000000" (get (first (store/shipment-history s)) "record_id")))
        (is (= "consignment-shipment-draft" (get (first (store/shipment-history s)) "kind")))
        (is (true? (:shipped? (store/extraction s "extraction-1"))))
        (is (= 1 (count (store/shipment-history s))))
        (is (= 1 (store/next-shipment-sequence s "JPN")))
        (is (true? (store/extraction-already-shipped? s "extraction-1"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/extraction s "nope")))
    (is (= [] (store/all-extractions s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/extraction-history s)))
    (is (= [] (store/shipment-history s)))
    (is (zero? (store/next-extraction-sequence s "JPN")))
    (is (zero? (store/next-shipment-sequence s "JPN")))
    (store/with-extractions s {"x" {:id "x" :site "s" :material-type :aggregate
                                    :permit-id "P" :permit-valid? true
                                    :involves-blasting? false :blast-clearance-confirmed? false
                                    :quantity 10 :royalty-rate 1.0 :claimed-royalty 10.0
                                    :extracted? false :shipped? false
                                    :jurisdiction "JPN" :status :intake}})
    (is (= "s" (:site (store/extraction s "x"))))))
