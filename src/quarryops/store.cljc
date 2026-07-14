(ns quarryops.store
  "SSoT for the community-quarry actor, behind a `Store` protocol so
  the backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/quarryops/store_contract_test.clj), which is the whole point:
  the actor, the Quarry Governor and the audit ledger never know which
  SSoT they run on.

  Like `freightops`/4920's own `shipment`, the primary entity here is
  an `extraction` -- extraction and shipment actuation events apply
  SEQUENTIALLY to the SAME extraction record (extract first, ship
  later), matching the repair-shop cluster's own sequential entity
  shape. Dedicated double-actuation-guard booleans
  (`:extracted?`/`:shipped?`, never a `:status` value).

  The ledger stays append-only on every backend: 'which extraction was
  screened for an invalid permit or an unconfirmed blast-safety
  clearance, which material was extracted, which consignment was
  shipped, on what jurisdictional basis, approved by whom' is always a
  query over an immutable log -- the audit trail a municipality or
  material cooperative trusting a quarry operator needs, and the
  evidence an operator needs if an extraction or a shipment is later
  disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [quarryops.registry :as registry]
            [quarryops.robotics :as robotics]
            [langchain.db :as d]))

(defprotocol Store
  (extraction [s id])
  (all-extractions [s])
  (assessment-of [s extraction-id] "committed jurisdiction assessment, or nil")
  (ledger [s])
  (extraction-history [s] "the append-only material-extraction history (quarryops.registry drafts)")
  (shipment-history [s] "the append-only consignment-shipment history (quarryops.registry drafts)")
  (next-extraction-sequence [s jurisdiction] "next extraction-number sequence for a jurisdiction")
  (next-shipment-sequence [s jurisdiction] "next shipment-number sequence for a jurisdiction")
  (extraction-already-extracted? [s extraction-id] "has this extraction already been extracted?")
  (extraction-already-shipped? [s extraction-id] "has this extraction's consignment already been shipped?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-extractions [s extractions] "replace/seed the extraction directory (map id->extraction)"))

;; ----------------------------- demo data -----------------------------

(defn- with-settling-telemetry
  "Merges REAL bench-face settling telemetry onto a demo extraction's
  base fields -- `quarryops.robotics/bench-face-settling-telemetry-for`
  actually runs the `physics-2d`-stepped free-fall/settling simulation
  for this extraction's own `:fragment-mass-kg`/`:bench-drop-height-m`
  (ADR-2607152000), so even the 'already on file' seed data (as if
  from an earlier real robot-survey mission) is genuinely simulation-
  derived, never hand-typed doubles."
  [base]
  (merge base (select-keys (robotics/bench-face-settling-telemetry-for base)
                           [:sim-settling-distance-m :sim-impact-energy-j
                            :sim-impact-velocity-mps])))

(defn demo-data
  "A small, self-contained extraction set covering both actuation
  lifecycles (extraction, shipment) plus the governor's own new
  checks, so the actor + tests run offline. `:fragment-mass-kg`/
  `:bench-drop-height-m`/`:catch-bench-rated-height-m`
  (ADR-2607152000) are permanent extraction-design fields (like
  `:claimed-royalty`); `:sim-settling-distance-m`/`:sim-impact-
  energy-j` are the REAL `quarryops.robotics`-computed physics-2d
  telemetry for those fields (`with-settling-telemetry`), the ground
  truth `quarryops.robotics/simulation-out-of-tolerance?`
  independently rechecks. extraction-7's loose block is genuinely
  surveyed HIGHER on the face (14.0m) than its own catch bench's
  rated containment height (10.0m) -- a genuine design-record
  inconsistency (the bench below was never engineered to catch a
  fall from that height) that the real, re-run simulation catches on
  independent recheck even though `:robotics-sim-verified?` was
  seeded `true` (\"already on file\", i.e. someone/something marked it
  passed without this real check ever having run) -- the quarry-face
  analog of extraction-1..6's genuinely-consistent drop-height/
  catch-bench-rating combinations, which all clear the real tolerance
  band with margin (see `quarryops.robotics/catch-bench-energy-
  budget-j`)."
  []
  {:extractions
   (into {}
         (map (fn [v] [(:id v) (with-settling-telemetry v)]))
         [{:id "extraction-1" :site "North Face" :material-type :aggregate
           :permit-id "PERMIT-001" :permit-valid? true
           :involves-blasting? false :blast-clearance-confirmed? false
           :quantity 100 :royalty-rate 2.0 :claimed-royalty 200.0
           :fragment-mass-kg 180.0 :bench-drop-height-m 4.0 :catch-bench-rated-height-m 10.0
           :robotics-sim-verified? false :robotics-sim-record nil
           :extracted? false :shipped? false
           :jurisdiction "JPN" :status :intake}
          {:id "extraction-2" :site "Atlantis Face" :material-type :aggregate
           :permit-id "PERMIT-ATL" :permit-valid? true
           :involves-blasting? false :blast-clearance-confirmed? false
           :quantity 50 :royalty-rate 3.0 :claimed-royalty 150.0
           :fragment-mass-kg 220.0 :bench-drop-height-m 5.0 :catch-bench-rated-height-m 10.0
           :robotics-sim-verified? false :robotics-sim-record nil
           :extracted? false :shipped? false
           :jurisdiction "ATL" :status :intake}
          {:id "extraction-3" :site "South Face" :material-type :dimension-stone
           :permit-id "PERMIT-003" :permit-valid? true
           :involves-blasting? false :blast-clearance-confirmed? false
           :quantity 20 :royalty-rate 5.0 :claimed-royalty 150.0
           :fragment-mass-kg 95.0 :bench-drop-height-m 3.0 :catch-bench-rated-height-m 10.0
           :robotics-sim-verified? false :robotics-sim-record nil
           :extracted? false :shipped? false
           :jurisdiction "JPN" :status :intake}
          {:id "extraction-4" :site "East Face" :material-type :aggregate
           :permit-id "PERMIT-004" :permit-valid? false
           :involves-blasting? false :blast-clearance-confirmed? false
           :quantity 80 :royalty-rate 2.0 :claimed-royalty 160.0
           :fragment-mass-kg 150.0 :bench-drop-height-m 4.0 :catch-bench-rated-height-m 10.0
           :robotics-sim-verified? false :robotics-sim-record nil
           :extracted? false :shipped? false
           :jurisdiction "JPN" :status :intake}
          {:id "extraction-5" :site "West Face" :material-type :aggregate
           :permit-id "PERMIT-005" :permit-valid? true
           :involves-blasting? true :blast-clearance-confirmed? false
           :quantity 200 :royalty-rate 2.0 :claimed-royalty 400.0
           :fragment-mass-kg 300.0 :bench-drop-height-m 6.0 :catch-bench-rated-height-m 10.0
           :robotics-sim-verified? false :robotics-sim-record nil
           :extracted? false :shipped? false
           :jurisdiction "JPN" :status :intake}
          {:id "extraction-6" :site "Central Face" :material-type :aggregate
           :permit-id "PERMIT-006" :permit-valid? true
           :involves-blasting? true :blast-clearance-confirmed? true
           :quantity 150 :royalty-rate 2.0 :claimed-royalty 300.0
           :fragment-mass-kg 260.0 :bench-drop-height-m 5.0 :catch-bench-rated-height-m 10.0
           :robotics-sim-verified? false :robotics-sim-record nil
           :extracted? false :shipped? false
           :jurisdiction "JPN" :status :intake}
          {:id "extraction-7" :site "Quarry Face 7" :material-type :aggregate
           :permit-id "PERMIT-007" :permit-valid? true
           :involves-blasting? false :blast-clearance-confirmed? false
           :quantity 100 :royalty-rate 2.0 :claimed-royalty 200.0
           :fragment-mass-kg 340.0 :bench-drop-height-m 14.0 :catch-bench-rated-height-m 10.0
           :robotics-sim-verified? true :robotics-sim-record nil
           :extracted? false :shipped? false
           :jurisdiction "JPN" :status :intake}])})

;; ----------------------------- shared commit logic -----------------------------

(defn- extract-material!
  "Backend-agnostic `:extraction/mark-extracted` -- looks up the
  extraction via the protocol and drafts the extraction record, and
  returns {:result .. :extraction-patch ..} for the caller to
  persist."
  [s extraction-id]
  (let [e (extraction s extraction-id)
        seq-n (next-extraction-sequence s (:jurisdiction e))
        result (registry/register-extraction extraction-id (:jurisdiction e) seq-n)]
    {:result result
     :extraction-patch {:extracted? true
                        :extraction-number (get result "extraction_number")}}))

(defn- ship-consignment!
  "Backend-agnostic `:extraction/mark-shipped` -- looks up the
  extraction via the protocol and drafts the shipment record, and
  returns {:result .. :extraction-patch ..} for the caller to
  persist."
  [s extraction-id]
  (let [e (extraction s extraction-id)
        seq-n (next-shipment-sequence s (:jurisdiction e))
        result (registry/register-consignment-shipment extraction-id (:jurisdiction e) seq-n)]
    {:result result
     :extraction-patch {:shipped? true
                        :shipment-number (get result "shipment_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (extraction [_ id] (get-in @a [:extractions id]))
  (all-extractions [_] (sort-by :id (vals (:extractions @a))))
  (assessment-of [_ extraction-id] (get-in @a [:assessments extraction-id]))
  (ledger [_] (:ledger @a))
  (extraction-history [_] (:extraction-records @a))
  (shipment-history [_] (:shipment-records @a))
  (next-extraction-sequence [_ jurisdiction] (get-in @a [:extraction-sequences jurisdiction] 0))
  (next-shipment-sequence [_ jurisdiction] (get-in @a [:shipment-sequences jurisdiction] 0))
  (extraction-already-extracted? [_ extraction-id] (boolean (get-in @a [:extractions extraction-id :extracted?])))
  (extraction-already-shipped? [_ extraction-id] (boolean (get-in @a [:extractions extraction-id :shipped?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :extraction/upsert
      (swap! a update-in [:extractions (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :extraction/mark-extracted
      (let [extraction-id (first path)
            {:keys [result extraction-patch]} (extract-material! s extraction-id)
            jurisdiction (:jurisdiction (extraction s extraction-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:extraction-sequences jurisdiction] (fnil inc 0))
                       (update-in [:extractions extraction-id] merge extraction-patch)
                       (update :extraction-records registry/append result))))
        result)

      :extraction/mark-shipped
      (let [extraction-id (first path)
            {:keys [result extraction-patch]} (ship-consignment! s extraction-id)
            jurisdiction (:jurisdiction (extraction s extraction-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:shipment-sequences jurisdiction] (fnil inc 0))
                       (update-in [:extractions extraction-id] merge extraction-patch)
                       (update :shipment-records registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-extractions [s extractions] (when (seq extractions) (swap! a assoc :extractions extractions)) s))

(defn seed-db
  "A MemStore seeded with the demo extraction set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {}
                           :ledger [] :extraction-sequences {} :extraction-records []
                           :shipment-sequences {} :shipment-records []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment payloads, ledger facts,
  extraction/shipment records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses."
  {:extraction/id                {:db/unique :db.unique/identity}
   :assessment/extraction-id     {:db/unique :db.unique/identity}
   :ledger/seq                   {:db/unique :db.unique/identity}
   :extraction-record/seq        {:db/unique :db.unique/identity}
   :shipment-record/seq          {:db/unique :db.unique/identity}
   :extraction-sequence/jurisdiction {:db/unique :db.unique/identity}
   :shipment-sequence/jurisdiction   {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- extraction->tx [{:keys [id site material-type permit-id permit-valid?
                              involves-blasting? blast-clearance-confirmed?
                              quantity royalty-rate claimed-royalty
                              fragment-mass-kg bench-drop-height-m catch-bench-rated-height-m
                              sim-settling-distance-m sim-impact-energy-j sim-impact-velocity-mps
                              robotics-sim-verified? robotics-sim-record
                              extracted? shipped?
                              jurisdiction status extraction-number shipment-number]}]
  (cond-> {:extraction/id id}
    site                                          (assoc :extraction/site site)
    material-type                                   (assoc :extraction/material-type material-type)
    permit-id                                         (assoc :extraction/permit-id permit-id)
    (some? permit-valid?)                               (assoc :extraction/permit-valid? permit-valid?)
    (some? involves-blasting?)                            (assoc :extraction/involves-blasting? involves-blasting?)
    (some? blast-clearance-confirmed?)                      (assoc :extraction/blast-clearance-confirmed? blast-clearance-confirmed?)
    quantity                                                  (assoc :extraction/quantity quantity)
    royalty-rate                                                (assoc :extraction/royalty-rate royalty-rate)
    claimed-royalty                                               (assoc :extraction/claimed-royalty claimed-royalty)
    fragment-mass-kg                                                (assoc :extraction/fragment-mass-kg fragment-mass-kg)
    bench-drop-height-m                                               (assoc :extraction/bench-drop-height-m bench-drop-height-m)
    catch-bench-rated-height-m                                          (assoc :extraction/catch-bench-rated-height-m catch-bench-rated-height-m)
    sim-settling-distance-m                                               (assoc :extraction/sim-settling-distance-m sim-settling-distance-m)
    (some? sim-impact-energy-j)                                             (assoc :extraction/sim-impact-energy-j sim-impact-energy-j)
    (some? sim-impact-velocity-mps)                                           (assoc :extraction/sim-impact-velocity-mps sim-impact-velocity-mps)
    (some? robotics-sim-verified?)                                              (assoc :extraction/robotics-sim-verified? robotics-sim-verified?)
    (some? robotics-sim-record)                                                   (assoc :extraction/robotics-sim-record (enc robotics-sim-record))
    (some? extracted?)                                                              (assoc :extraction/extracted? extracted?)
    (some? shipped?)                                                                  (assoc :extraction/shipped? shipped?)
    jurisdiction                                                                        (assoc :extraction/jurisdiction jurisdiction)
    status                                                                                (assoc :extraction/status status)
    extraction-number                                                                       (assoc :extraction/extraction-number extraction-number)
    shipment-number                                                                           (assoc :extraction/shipment-number shipment-number)))

(def ^:private extraction-pull
  [:extraction/id :extraction/site :extraction/material-type :extraction/permit-id
   :extraction/permit-valid? :extraction/involves-blasting? :extraction/blast-clearance-confirmed?
   :extraction/quantity :extraction/royalty-rate :extraction/claimed-royalty
   :extraction/fragment-mass-kg :extraction/bench-drop-height-m :extraction/catch-bench-rated-height-m
   :extraction/sim-settling-distance-m :extraction/sim-impact-energy-j :extraction/sim-impact-velocity-mps
   :extraction/robotics-sim-verified? :extraction/robotics-sim-record
   :extraction/extracted? :extraction/shipped?
   :extraction/jurisdiction :extraction/status :extraction/extraction-number :extraction/shipment-number])

(defn- pull->extraction [m]
  (when (:extraction/id m)
    {:id (:extraction/id m) :site (:extraction/site m) :material-type (:extraction/material-type m)
     :permit-id (:extraction/permit-id m) :permit-valid? (boolean (:extraction/permit-valid? m))
     :involves-blasting? (boolean (:extraction/involves-blasting? m))
     :blast-clearance-confirmed? (boolean (:extraction/blast-clearance-confirmed? m))
     :quantity (:extraction/quantity m) :royalty-rate (:extraction/royalty-rate m)
     :claimed-royalty (:extraction/claimed-royalty m)
     :fragment-mass-kg (:extraction/fragment-mass-kg m)
     :bench-drop-height-m (:extraction/bench-drop-height-m m)
     :catch-bench-rated-height-m (:extraction/catch-bench-rated-height-m m)
     :sim-settling-distance-m (:extraction/sim-settling-distance-m m)
     :sim-impact-energy-j (:extraction/sim-impact-energy-j m)
     :sim-impact-velocity-mps (:extraction/sim-impact-velocity-mps m)
     :robotics-sim-verified? (boolean (:extraction/robotics-sim-verified? m))
     :robotics-sim-record (dec* (:extraction/robotics-sim-record m))
     :extracted? (boolean (:extraction/extracted? m)) :shipped? (boolean (:extraction/shipped? m))
     :jurisdiction (:extraction/jurisdiction m) :status (:extraction/status m)
     :extraction-number (:extraction/extraction-number m) :shipment-number (:extraction/shipment-number m)}))

(defrecord DatomicStore [conn]
  Store
  (extraction [_ id]
    (pull->extraction (d/pull (d/db conn) extraction-pull [:extraction/id id])))
  (all-extractions [_]
    (->> (d/q '[:find [?id ...] :where [?e :extraction/id ?id]] (d/db conn))
         (map #(pull->extraction (d/pull (d/db conn) extraction-pull [:extraction/id %])))
         (sort-by :id)))
  (assessment-of [_ extraction-id]
    (dec* (d/q '[:find ?p . :in $ ?eid
                :where [?a :assessment/extraction-id ?eid] [?a :assessment/payload ?p]]
              (d/db conn) extraction-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (extraction-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :extraction-record/seq ?s] [?e :extraction-record/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (shipment-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :shipment-record/seq ?s] [?e :shipment-record/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-extraction-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :extraction-sequence/jurisdiction ?j] [?e :extraction-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-shipment-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :shipment-sequence/jurisdiction ?j] [?e :shipment-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (extraction-already-extracted? [s extraction-id]
    (boolean (:extracted? (extraction s extraction-id))))
  (extraction-already-shipped? [s extraction-id]
    (boolean (:shipped? (extraction s extraction-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :extraction/upsert
      (d/transact! conn [(extraction->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/extraction-id (first path) :assessment/payload (enc payload)}])

      :extraction/mark-extracted
      (let [extraction-id (first path)
            {:keys [result extraction-patch]} (extract-material! s extraction-id)
            jurisdiction (:jurisdiction (extraction s extraction-id))
            next-n (inc (next-extraction-sequence s jurisdiction))]
        (d/transact! conn
                     [(extraction->tx (assoc extraction-patch :id extraction-id))
                      {:extraction-sequence/jurisdiction jurisdiction :extraction-sequence/next next-n}
                      {:extraction-record/seq (count (extraction-history s)) :extraction-record/record (enc (get result "record"))}])
        result)

      :extraction/mark-shipped
      (let [extraction-id (first path)
            {:keys [result extraction-patch]} (ship-consignment! s extraction-id)
            jurisdiction (:jurisdiction (extraction s extraction-id))
            next-n (inc (next-shipment-sequence s jurisdiction))]
        (d/transact! conn
                     [(extraction->tx (assoc extraction-patch :id extraction-id))
                      {:shipment-sequence/jurisdiction jurisdiction :shipment-sequence/next next-n}
                      {:shipment-record/seq (count (shipment-history s)) :shipment-record/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-extractions [s extractions]
    (when (seq extractions) (d/transact! conn (mapv extraction->tx (vals extractions)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:extractions ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [extractions]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-extractions s extractions))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo extraction set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
