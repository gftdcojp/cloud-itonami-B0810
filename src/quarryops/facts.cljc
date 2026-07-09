(ns quarryops.facts
  "Per-jurisdiction mine-safety AND explosives/blast-safety regulatory
  catalog -- the G2-style spec-basis table the Quarry Governor checks
  every `:jurisdiction/assess` proposal against ('did the advisor cite
  an OFFICIAL public source for this jurisdiction's requirements, or
  did it invent one?').

  This blueprint's own text (docs/business-model.md's Offer: 'blast
  safety gating') names a real, distinct regulatory concern beyond
  general mine-safety law: most jurisdictions have a SEPARATE
  statutory regime specifically for explosives/blast-safety in
  quarrying, independent of the general mine-safety framework (mine
  safety law covers ventilation, ground support, machinery guarding
  etc.; explosives law covers storage, handling, shot-firing
  competency and blast-clearance procedures specifically). Each
  jurisdiction entry below therefore cites BOTH the general mine-
  safety law AND a SEPARATE explosives/blast-safety law.

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries. As with
  `leathergoods`/9523's own brand-authenticity sub-citation,
  `ictrepair`/9511's own media-sanitization sub-citation,
  `retailops`/4711's own unit-pricing sub-citation and `freightops`/
  4920's own cargo-liability-disclosure sub-citation, ALL FOUR seeded
  jurisdictions actually have a real explosives/blast-safety regime
  here, reported honestly rather than forcing an artificial gap.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  extraction-permit/survey-record/haul-record evidence set (PLUS a
  blast-clearance record for every seeded jurisdiction);
  `:legal-basis` / `:owner-authority` / `:provenance` are the G2
  citation the governor requires before any `:jurisdiction/assess`
  proposal can commit. `:blast-owner-authority` / `:blast-legal-
  basis` / `:blast-provenance` are the SEPARATE explosives/blast-
  safety citation the governor's `blast-safety-clearance-
  unconfirmed?` check is grounded in."
  {"JPN" {:name "Japan"
          :owner-authority "経済産業省 産業保安グループ (METI Industrial Safety Group) / 産業保安監督部"
          :legal-basis "鉱山保安法 (Mine Safety Act)"
          :national-spec "採石場における保安に関する一般基準"
          :provenance "https://www.meti.go.jp/policy/safety_security/industrial_safety/sangyo/mine/"
          :required-evidence ["採掘許可記録 (extraction-permit record)"
                              "測量記録 (survey record)"
                              "運搬記録 (haul record)"
                              "発破安全確認記録 (blast-clearance record)"]
          :blast-owner-authority "経済産業省 (METI) / 都道府県 (prefectural governments)"
          :blast-legal-basis "火薬類取締法 (Explosives Control Act)"
          :blast-provenance "https://www.meti.go.jp/policy/safety_security/industrial_safety/sangyo/kayaku/"}
   "USA" {:name "United States"
          :owner-authority "Mine Safety and Health Administration (MSHA)"
          :legal-basis "Federal Mine Safety and Health Act (Mine Act, 30 U.S.C. §801 et seq.)"
          :national-spec "MSHA safety standards for surface metal/nonmetal mines (30 C.F.R. Part 56)"
          :provenance "https://www.law.cornell.edu/uscode/text/30/801"
          :required-evidence ["Extraction-permit record"
                              "Survey record"
                              "Haul record"
                              "Blast-clearance record"]
          :blast-owner-authority "Mine Safety and Health Administration (MSHA)"
          :blast-legal-basis "30 C.F.R. Part 56 Subpart E (Explosives)"
          :blast-provenance "https://www.ecfr.gov/current/title-30/chapter-I/subchapter-B/part-56/subpart-E"}
   "GBR" {:name "United Kingdom"
          :owner-authority "Health and Safety Executive (HSE)"
          :legal-basis "Quarries Regulations 1999"
          :national-spec "HSE quarry-safety enforcement standards"
          :provenance "https://www.hse.gov.uk/quarries/"
          :required-evidence ["Extraction-permit record"
                              "Survey record"
                              "Haul record"
                              "Blast-clearance record"]
          :blast-owner-authority "Health and Safety Executive (HSE)"
          :blast-legal-basis "Quarries Regulations 1999 (shot-firing provisions); Explosives Regulations 2014"
          :blast-provenance "https://www.hse.gov.uk/explosives/"}
   "DEU" {:name "Germany"
          :owner-authority "Landesbergämter (state mining authorities)"
          :legal-basis "Bundesberggesetz (BBergG, Federal Mining Act)"
          :national-spec "BBergG Betriebsplanpflicht und Sicherheitsanforderungen für Steinbrüche"
          :provenance "https://www.gesetze-im-internet.de/bbergg/"
          :required-evidence ["Abbaugenehmigungsnachweis (extraction-permit record)"
                              "Vermessungsnachweis (survey record)"
                              "Transportnachweis (haul record)"
                              "Sprengfreigabenachweis (blast-clearance record)"]
          :blast-owner-authority "Landesbehörden / Bundesanstalt für Materialforschung und -prüfung (BAM)"
          :blast-legal-basis "Sprengstoffgesetz (SprengG, Explosives Act)"
          :blast-provenance "https://www.gesetze-im-internet.de/sprengg_1976/"}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to extract
  material or ship a consignment on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-0810 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `quarryops.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))

(defn blast-spec-basis
  "The jurisdiction's explosives/blast-safety requirement map, or nil
  -- nil means this jurisdiction has NO formal statutory explosives/
  blast-safety regime this catalog is aware of. In this R0 catalog
  all four seeded jurisdictions actually have one (unlike some prior
  siblings' own honest single-jurisdiction gap), reported honestly."
  [iso3]
  (when-let [sb (spec-basis iso3)]
    (when (:blast-owner-authority sb)
      (select-keys sb [:blast-owner-authority :blast-legal-basis :blast-provenance]))))
