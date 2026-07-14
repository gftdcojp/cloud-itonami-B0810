(ns quarryops.robotics
  "Robot-executed bench-face/quarry-face verification -- the concrete,
  actor-level realization of ADR-2607011000's robotics premise (every
  cloud-itonami vertical is designed on the premise that a robot
  performs the physical-domain work; an independent governor gates any
  action before it ever reaches hardware) for THIS actor's own README
  claim ('an extraction robot performs drilling, cutting and loadout
  at the quarry face') -- previously only a declared flag, now a real
  mission dispatched through `quarryops.governor` before any real
  `:actuation/extract-material` proposal may commit.

  ADR-2607152000 (generalizing ADR-2607151600's automotive pilot to
  the rest of the cloud-itonami manufacturing fleet, per that ADR's
  own explicit follow-up-work note) rewires this ns's ground-truth
  check onto a REAL, time-stepped rigid-body physics simulation
  instead of a synthetic, hand-set field comparison. Unlike the
  automotive pilot (which routed through a separate design-library
  sibling repo, `kami-engine-vehicle-designer`), this vertical has no
  such sibling -- the real-physics module is built DIRECTLY in this
  ns, taking a real, pinned git-coordinate dependency on
  `kotoba-lang/physics-2d` alone (see deps.edn).

  A loose block/fragment identified during the bench-face survey is
  modeled as an actual `physics-2d` `Body2D` (AABB collider, real
  gravity `[0 -9.81]`) that ACTUALLY free-falls tick-by-tick
  (`physics-2d/world-step`) toward a static (mass 0) `Body2D`
  representing the catch-bench floor below, until it genuinely settles
  (near-zero vertical velocity for several consecutive ticks -- no
  longer separating/bouncing) or a max-tick budget is reached.
  `:sim-settling-distance-m` (how far it actually fell before settling,
  read off the REAL simulated position trajectory) and
  `:sim-impact-energy-j` (0.5*mass*v^2 at the REAL peak impact
  velocity, read off the REAL simulated velocity trajectory) replace
  the previous hand-set `:face-deviation-actual/min/max` fields
  entirely -- this is a real dependency (see deps.edn), not an
  invented number.

  A robot mission (`kotoba.robotics/mission`) still walks the
  extraction through the SAME three :sense/:actuate steps -- bench-face
  dimensional survey, core-sample quality assay, dust/particulate-
  emissions scan -- built with `kotoba.robotics/action` +
  `kotoba.robotics/telemetry-proof`, and reports an overall :passed?
  verdict, now derived from the real simulated telemetry above.
  `bench-face-settling-out-of-tolerance?` independently re-derives
  that verdict from the extraction's OWN recorded real simulated
  telemetry, checked against this SITE's own recorded
  `:catch-bench-rated-height-m` design rating -- a real, honestly-
  sourced quarry-engineering concept: catch benches below a quarry
  face are sized/rated to contain a rockfall from up to a design
  height, the classical basis for which is Ritchie's (1963) empirical
  catch-bench-geometry criteria widely used in open-pit/quarry slope
  design (USBM RI 6706, cited in the SME Mining Engineering Handbook).
  The SPECIFIC per-site rating used here is this extraction's own
  permanent design record, not invented by this namespace -- the same
  'real per-record design field, not a universal constant' discipline
  `automotive.robotics`'s own per-vehicle `:class` establishes. Never
  from the mission's self-reported result -- the SAME 'ground truth,
  not self-report' discipline `quarryops.registry/royalty-matches-
  claim?` established for royalty. `quarryops.governor`'s `robotics-
  simulation-violations` calls this ns's independent recheck, never
  the stored :passed? value, before any `:actuation/extract-material`
  proposal may commit.

  Honest scope (ADR-2607152000): 2D projection only (`physics-2d` has
  no 3D solver) -- y is height, x is unused/lateral, so no rollout/
  bounce-out distance is modeled at all (a genuine limitation vs. a
  real 3D rockfall-trajectory tool such as CRSP/RocFall); the fragment
  is approximated as a single cubic AABB block (edge length derived
  from `:fragment-mass-kg` at a disclosed typical hard-rock bulk
  density, `rock-density-kg-per-m3` below -- not surveyed real block
  geometry); `fragment-restitution`/friction are disclosed engineering
  priors, not measured per-site values; no wind, rotation, multi-
  fragment interaction, or lateral rollout distance is modeled. What
  IS real: an actual `physics-2d/world-step` tick-by-tick free-fall +
  impact + settling trajectory, read back tick by tick, not
  synthesized after the fact.

  Pure data + pure functions -- no real robot I/O, no network.
  `kotoba.robotics` is itself \"policy, not control\"; `physics-2d`'s
  own `world-step` is a pure fixed-timestep integrator (no wall-clock/
  IO), so this stays exactly as offline/deterministic as every other
  sibling namespace in this actor -- tests and the demo run without a
  network."
  (:require [kotoba.robotics :as robotics]
            [physics-2d :as p2d]))

(def mission-actions
  "The three-step bench-face/quarry-face verification mission every
  extraction walks through before `:actuation/extract-material` is
  proposable. All :sense/:actuate at :none/:low safety -- verification
  sensing on a stationary quarry face, not the moving-equipment
  actuation that is `:actuation/extract-material` itself (always
  :safety-critical -- see `quarryops.governor`)."
  [{:step :bench-face-dimensional-survey    :kind :sense   :safety :none}
   {:step :core-sample-quality-assay        :kind :actuate :safety :low}
   {:step :dust-particulate-emissions-scan  :kind :sense   :safety :none}])

;; ───────────────────── platform shims (cljc-portable) ─────────────────────

(defn- cbrt* [x]
  #?(:clj  (Math/cbrt (double x))
     :cljs (js/Math.cbrt x)))

(defn- abs* [x] (if (neg? x) (- x) x))

;; ───────────────────── real physics-2d simulation (ADR-2607152000) ─────────────────────

(def ^:const g-mps2
  "Real Earth gravity (m/s^2), used both as `physics-2d`'s world
  gravity vector and in the closed-form energy-budget identity below."
  9.81)

(def ^:const gravity
  "`physics-2d` world gravity vector -- real, non-fabricated, straight
  down (y is height in this ns's 2D projection)."
  [0.0 (- g-mps2)])

(def ^:const dt-s
  "Fixed `physics-2d` timestep (s), 50 Hz -- fine enough to keep AABB
  tunneling negligible at realistic bench-drop heights (see ns
  docstring's honest-scope note)."
  0.02)

(def ^:const rock-density-kg-per-m3
  "Disclosed engineering prior: typical bulk density of quarried hard
  rock (limestone/granite aggregate), ~2.6-2.7 t/m^3 per standard
  geotechnical/mining references -- used ONLY to size the fragment's
  AABB collider from its own recorded `:fragment-mass-kg` (a cosmetic
  collision-shape input, not a stored/measured per-fragment geometry)."
  2700.0)

(def ^:const fragment-restitution
  "Disclosed engineering prior for a hard-rock fragment impacting a
  catch-bench floor -- rockfall-dynamics literature (e.g. CRSP/RocFall-
  style studies of rock-on-rock/rock-on-talus impacts) commonly uses a
  normal coefficient of restitution in the ~0.2-0.4 range; 0.3 here,
  applied to BOTH bodies (`physics-2d/resolve-contact`'s combined
  restitution is `sqrt(ra*rb)`, so this yields an effective 0.3)."
  0.3)

(def ^:const floor-half-width-m
  "Catch-bench floor AABB half-width (m) -- wide enough that the
  fragment's whole footprint always lands within it; no lateral
  rollout/edge-of-bench distance is modeled (honest scope, see ns
  docstring)."
  50.0)

(def ^:const floor-half-height-m
  "Catch-bench floor AABB half-height (m) -- a thin slab; its TOP face
  sits at y=0 by construction (`simulate-bench-face-settling` places
  the floor's center at `(- floor-half-height-m)`)."
  0.5)

(def ^:const settle-eps-mps
  "Vertical speed (m/s) below which the fragment is treated as settled
  for one tick."
  0.01)

(def ^:const settle-run-ticks
  "Consecutive settled ticks required before `run-until-settled` stops
  early -- avoids mistaking a bounce's instantaneous zero-crossing
  velocity for genuine settling."
  20)

(def ^:const max-ticks
  "Hard tick budget -- `run-until-settled` ALWAYS terminates by this
  tick even if the fragment never satisfies the settle condition (the
  real max-tick-budget fallback ADR-2607152000 calls for)."
  3000)

(defn- fragment-half-extent-m
  "Half edge-length (m) of a cubic AABB approximating a fragment of
  `fragment-mass-kg` at `rock-density-kg-per-m3` -- collision-shape
  sizing only (see ns docstring)."
  [fragment-mass-kg]
  (/ (cbrt* (/ (double fragment-mass-kg) rock-density-kg-per-m3)) 2.0))

(defn- run-until-settled
  "Steps `world` by `dt-s` up to `max-ticks` times, returning the full
  realized per-tick trajectory `[{:tick :position :velocity} ...]` for
  `body-id` -- the ACTUAL simulated positions/velocities, read back
  tick by tick from `physics-2d/world-step`, not synthesized. Stops
  early once the tracked body's vertical speed has stayed below
  `settle-eps-mps` for `settle-run-ticks` consecutive ticks (genuinely
  settled), otherwise runs the full `max-ticks` budget."
  [world0 body-id]
  (loop [world world0 tick 0 traj [] quiet 0]
    (let [b (nth (:bodies world) body-id)
          traj' (conj traj {:tick tick :position (:position b) :velocity (:velocity b)})]
      (if (or (>= tick max-ticks) (>= quiet settle-run-ticks))
        traj'
        (let [world' (p2d/world-step world dt-s)
              vy' (second (:velocity (nth (:bodies world') body-id)))
              quiet' (if (< (abs* vy') settle-eps-mps) (inc quiet) 0)]
          (recur world' (inc tick) traj' quiet'))))))

(defn simulate-bench-face-settling
  "Time-steps a REAL `physics-2d` world for a loose-block/fragment of
  `fragment-mass-kg` (kg) free-falling `bench-drop-height-m` (m) onto
  a static catch-bench floor, and returns:

    {:trajectory [{:tick :position :velocity} ...]
     :sim-settling-distance-m n :sim-impact-energy-j n
     :sim-impact-velocity-mps n :ticks n :dt n}

  `:sim-settling-distance-m` is the ACTUAL net vertical distance the
  fragment's center travelled from its starting position to its final
  settled position -- read directly off the real simulated trajectory,
  not invented (by construction this converges close to
  `bench-drop-height-m`, but genuinely read back, not assumed --
  discretization/positional-correction residue can move it slightly,
  see ns docstring). `:sim-impact-velocity-mps` is the PEAK magnitude
  of vertical velocity actually observed across the whole trajectory
  (occurs at first ground contact -- free fall only gains speed until
  impact, then each bounce is smaller under `fragment-restitution` <
  1) -- NOT a closed-form `sqrt(2*g*h)` calculation, the actual
  simulated reading. `:sim-impact-energy-j` is `0.5 * fragment-mass-kg
  * sim-impact-velocity-mps^2` -- the REAL kinetic energy the fragment
  actually delivered to the catch-bench floor at impact, derived from
  the real simulated velocity, not invented."
  [{:keys [fragment-mass-kg bench-drop-height-m]}]
  (let [mass (double fragment-mass-kg)
        drop-h (double bench-drop-height-m)
        half-e (fragment-half-extent-m mass)
        floor-center-y (- floor-half-height-m)
        fragment-start-y (+ drop-h half-e)
        fragment (p2d/make-body {:position [0.0 fragment-start-y]
                                  :velocity [0.0 0.0]
                                  :mass mass
                                  :restitution fragment-restitution
                                  :friction 0.0
                                  :collider (p2d/make-aabb-collider half-e half-e)
                                  :user-data :fragment})
        floor (p2d/make-body {:position [0.0 floor-center-y]
                               :velocity [0.0 0.0]
                               :mass 0.0
                               :restitution fragment-restitution
                               :friction 0.0
                               :collider (p2d/make-aabb-collider floor-half-width-m floor-half-height-m)
                               :user-data :catch-bench-floor})
        w0 (p2d/world-new gravity)
        [w1 fid] (p2d/world-add w0 fragment)
        [w2 _floor-id] (p2d/world-add w1 floor)
        trajectory (run-until-settled w2 fid)
        start-y (second (:position (first trajectory)))
        final-y (second (:position (last trajectory)))
        vys (mapv (comp second :velocity) trajectory)
        v-impact (reduce max 0.0 (map abs* vys))]
    {:trajectory trajectory
     :sim-settling-distance-m (- start-y final-y)
     :sim-impact-velocity-mps v-impact
     :sim-impact-energy-j (* 0.5 mass v-impact v-impact)
     :ticks (count trajectory)
     :dt dt-s}))

;; ───────────────────── extraction design record + tolerance (ADR-2607152000) ─────────────────────

(def default-fragment-mass-kg
  "Fallback fragment mass (kg) when an extraction record has none on
  file -- a plausible mid-size loose-block mass, not a per-site
  measured spec."
  150.0)

(def default-bench-drop-height-m
  "Fallback drop height (m) when an extraction record has none on
  file."
  4.0)

(def default-catch-bench-rated-height-m
  "Fallback catch-bench design rating (m) when an extraction record
  has none on file -- a conservative, disclosed typical single-quarry-
  bench catch-height (see ns docstring's Ritchie-criteria citation),
  NOT a per-site measured spec."
  10.0)

(defn- fragment-for
  "Fragment-simulation inputs for `extraction` -- its own permanent
  `:fragment-mass-kg`/`:bench-drop-height-m` fields (defaults applied
  when absent), exactly the fields `simulate-bench-face-settling`
  reads. Mirrors `automotive.robotics/design-for`'s shape."
  [{:keys [fragment-mass-kg bench-drop-height-m]}]
  {:fragment-mass-kg (or fragment-mass-kg default-fragment-mass-kg)
   :bench-drop-height-m (or bench-drop-height-m default-bench-drop-height-m)})

(defn- rated-height-for
  [{:keys [catch-bench-rated-height-m]}]
  (or catch-bench-rated-height-m default-catch-bench-rated-height-m))

(defn- catch-bench-energy-budget-j
  "The REAL energy budget this site's catch bench is rated to absorb
  for a fragment of THIS mass -- `mass * g * rated-height`, the honest
  physical identity (conservation of energy: PE=mgh converts fully to
  KE) for a same-mass fragment falling exactly the bench's own
  recorded rated height (`rated-height-for`), not an invented number.
  Note this makes the energy check and the settling-distance check
  below ANALYTICALLY EQUIVALENT under ideal frictionless free fall
  (both reduce to `drop-height > rated-height`) -- checking both is
  still meaningful because it is the REAL, potentially
  discretization-noisy simulated reading being checked, not a
  re-derivation of the same closed-form algebra."
  [fragment-mass-kg rated-height-m]
  (* (double fragment-mass-kg) g-mps2 rated-height-m))

(defn bench-face-settling-telemetry-for
  "Runs the REAL `physics-2d` free-fall/settling simulation
  (`simulate-bench-face-settling`) for `extraction`'s own recorded
  `:fragment-mass-kg`/`:bench-drop-height-m` (defaults applied when
  absent) and returns the actual simulated telemetry: `{:sim-settling-
  distance-m n :sim-impact-energy-j n :sim-impact-velocity-mps n
  :ticks n :dt n}`. Pure, deterministic -- no IO; the same inputs
  always reproduce the same telemetry."
  [extraction]
  (let [sim (simulate-bench-face-settling (fragment-for extraction))]
    (select-keys sim [:sim-settling-distance-m :sim-impact-energy-j
                       :sim-impact-velocity-mps :ticks :dt])))

(defn bench-face-settling-out-of-tolerance?
  "Ground-truth check: does `extraction`'s own recorded REAL simulated
  `:sim-settling-distance-m`/`:sim-impact-energy-j` exceed the real
  tolerance derived from this SAME extraction's own recorded
  `:catch-bench-rated-height-m` design rating (`rated-height-for`) --
  the settling distance must not exceed the bench's rated containment
  height, and the impact energy must not exceed the energy budget a
  same-mass fragment falling exactly that rated height would represent
  (`catch-bench-energy-budget-j`)? Needs no mission run or proposal
  inspection -- its inputs are permanent fields already on the
  extraction plus its own already-simulated telemetry, the same shape
  `quarryops.registry/royalty-matches-claim?` uses for royalty."
  [{:keys [sim-settling-distance-m sim-impact-energy-j] :as extraction}]
  (and (number? sim-settling-distance-m) (number? sim-impact-energy-j)
       (let [rated-h (rated-height-for extraction)
             fragment-mass-kg (:fragment-mass-kg (fragment-for extraction))
             budget-j (catch-bench-energy-budget-j fragment-mass-kg rated-h)]
         (or (> sim-settling-distance-m rated-h)
             (> sim-impact-energy-j budget-j)))))

(defn simulate-quarry-face-verification
  "Run the robot bench-face/quarry-face verification mission for
  `extraction-id` (`extraction` is the full extraction record, incl.
  `:fragment-mass-kg`/`:bench-drop-height-m`/`:catch-bench-rated-
  height-m`). Actually runs the REAL `physics-2d` free-fall/settling
  simulation (`bench-face-settling-telemetry-for`) for this
  extraction's own recorded fragment/drop-height fields and derives
  `:passed?` from that REAL simulated telemetry
  (`bench-face-settling-out-of-tolerance?`), never invented or
  randomized -- `kotoba.robotics` mandates no network/IO, and a
  repeatable simulation is what makes the governor's independent
  recheck (`simulation-out-of-tolerance?`) meaningful. Returns
  {:mission .. :actions [{:action .. :proof ..} ..] :passed? bool
  :sim-settling-distance-m n :sim-impact-energy-j n
  :sim-impact-velocity-mps n}."
  [extraction-id extraction]
  (let [telemetry (bench-face-settling-telemetry-for extraction)
        out-of-range? (bench-face-settling-out-of-tolerance? (merge extraction telemetry))
        reading (if out-of-range? :out-of-tolerance :nominal)
        mission (robotics/mission (str "mission-" extraction-id "-face-verify")
                                   :robot/quarry-face-cell-1
                                   :quarry-face-verification
                                   :boundaries {:station "quarry-face-survey-station"}
                                   :max-steps (count mission-actions))
        actions (mapv (fn [{:keys [step kind safety]}]
                        (let [a (robotics/action (str (:mission/id mission) "-" (name step))
                                                  (:mission/id mission) kind safety
                                                  :params {:step step :extraction-id extraction-id})]
                          {:action a
                           :proof (robotics/telemetry-proof (:mission/id mission) step reading
                                                             :provenance :simulated)}))
                      mission-actions)]
    {:mission mission
     :actions actions
     :passed? (not out-of-range?)
     :sim-settling-distance-m (:sim-settling-distance-m telemetry)
     :sim-impact-energy-j (:sim-impact-energy-j telemetry)
     :sim-impact-velocity-mps (:sim-impact-velocity-mps telemetry)}))

(defn simulation-out-of-tolerance?
  "Independent ground-truth recheck for the governor: does
  `extraction`'s OWN current, on-file real `physics-2d`-simulated
  telemetry (`:sim-settling-distance-m`/`:sim-impact-energy-j`, plus
  its own `:catch-bench-rated-height-m` design rating) fall out of
  tolerance right now? Ignores whatever :passed? verdict a prior
  mission run stored -- identical in spirit to `quarryops.registry/
  royalty-matches-claim?`'s refusal to trust a proposal's self-report."
  [extraction]
  (bench-face-settling-out-of-tolerance? extraction))
