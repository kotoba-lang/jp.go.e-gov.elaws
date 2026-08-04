#!/usr/bin/env nbb
;; Derives the small, git-tracked index/ layer from the large, annexed raw/
;; layer.
;;
;;   index/laws.edn       — one row per law: identity, title, dates, force
;;                          status, and the ADDRESS of its full text
;;                          (path + sha256 + bytes) inside raw/
;;   index/relations.edn  — law-to-law dependency edges
;;   raw/source-catalog.edn — custody: sha256 of every preserved file
;;
;; Why the split: raw/ is 543 MB and lives in git-annex/B2, so a consumer that
;; only wants to QUERY the corpus must not be forced to `datalad get` it. The
;; index is a few MB of text in git; the text itself is fetched on demand by
;; sha256. global-legislation-datoms consumes index/ only.
;;
;; Usage: nbb --classpath bin bin/index.cljs
(ns index
  (:require [lib :refer [log write-edn! sha256-file file-size mkdirp!]]
            [clojure.string :as str]))

(def fs (js/require "fs"))
(def raw "raw")

;; js->clj, not the raw JS object: `get` with a string key does not read a
;; plain JS object in ClojureScript, so a `for` over (get obj "laws") silently
;; iterates nil and produces an empty index (measured: 9,536 laws -> 0 rows).
(defn read-json [p] (js->clj (js/JSON.parse (.toString (.readFileSync fs p)))))

(def law-type->kind
  {"Constitution" :law.kind/constitution
   "Act" :law.kind/statute
   "CabinetOrder" :law.kind/cabinet-order
   "ImperialOrder" :law.kind/imperial-order
   "MinisterialOrdinance" :law.kind/ministerial-ordinance
   "Rule" :law.kind/rule})

(defn kind-of [law-type]
  ;; A handful of rows carry a compound type ("Act,CabinetOrder") for
  ;; instruments enacted jointly. Take the first -- the leading element is
  ;; the governing instrument -- rather than inventing a compound keyword
  ;; that no consumer could have a rule for.
  (or (law-type->kind law-type)
      (law-type->kind (first (str/split law-type #",")))
      :law.kind/other))

(defn status-of [rev]
  (case (get rev "repeal_status")
    "None" (if (= "CurrentEnforced" (get rev "current_revision_status"))
             :law.status/in-force
             :law.status/superseded-revision)
    "Repeal" :law.status/repealed
    "Expire" :law.status/expired
    "LossOfEffectiveness" :law.status/lapsed
    :law.status/unknown))

(defn blank->nil [s] (when (and s (not= "" s)) s))

(defn -main []
  (let [laws (get (read-json (str raw "/law-list.json")) "laws")
        rows (vec
              (for [l laws
                    :let [info (get l "law_info")
                          rev (get l "revision_info")
                          id (get info "law_id")
                          p (str raw "/laws/" id ".json")
                          have? (.existsSync fs p)]]
                (cond-> {:law/key (str "jp-elaws:" id)
                         :law/jurisdiction "JPN"
                         :law/source-id "jp-egov-elaws"
                         :law/local-id id
                         :law/kind (kind-of (get info "law_type"))
                         :law/number (get info "law_num")
                         :law/title (get rev "law_title")
                         :law/lang "ja"
                         :law/status (status-of rev)
                         :law/url (str "https://laws.e-gov.go.jp/law/" id)}
                  (get info "promulgation_date") (assoc :law/promulgated-at (get info "promulgation_date"))
                  (get rev "amendment_enforcement_date") (assoc :law/effective-at (get rev "amendment_enforcement_date"))
                  (get rev "repeal_date") (assoc :law/repealed-at (get rev "repeal_date"))
                  (blank->nil (get rev "abbrev")) (assoc :law/abbrev (get rev "abbrev"))
                  (get rev "category") (assoc :law/category (get rev "category"))
                  (get rev "law_revision_id") (assoc :law/revision-id (get rev "law_revision_id"))
                  have? (assoc :text/path p
                               :text/sha256 (sha256-file p)
                               :text/bytes (file-size p)
                               :text/format "e-gov-law-data-json"))))
        ;; Dependency edges. e-Gov states, for the CURRENT revision of each
        ;; law, which law produced it -- that is an amendment edge from the
        ;; amending act to this one. 7,793 of 9,536 rows carry it.
        rels (vec
              (for [l laws
                    :let [rev (get l "revision_info")
                          amd (blank->nil (get rev "amendment_law_id"))
                          id (get-in l ["law_info" "law_id"])]
                    :when (and amd (not= amd id))]
                (cond-> {:rel/from (str "jp-elaws:" amd)
                         :rel/to (str "jp-elaws:" id)
                         :rel/kind :law.rel/amends
                         :rel/provenance :from-metadata
                         :rel/source-id "jp-egov-elaws"}
                  (get rev "amendment_promulgate_date") (assoc :rel/at (get rev "amendment_promulgate_date"))
                  (blank->nil (get rev "amendment_law_num")) (assoc :rel/evidence (get rev "amendment_law_num")))))
        with-text (filter :text/path rows)]
    (mkdirp! "index")
    (write-edn! "index/laws.edn"
                {:index/id "jp.go.e-gov.elaws"
                 :index/jurisdiction "JPN"
                 :index/source-id "jp-egov-elaws"
                 :laws rows})
    (write-edn! "index/relations.edn"
                {:index/id "jp.go.e-gov.elaws"
                 :index/source-id "jp-egov-elaws"
                 :relations rels})
    (write-edn! (str raw "/source-catalog.edn")
                {:catalog/id "jp.go.e-gov.elaws"
                 :catalog/source-domain "laws.e-gov.go.jp"
                 :catalog/api "e-Gov 法令API v2"
                 :catalog/license "政府標準利用規約(第2.0版) — CC BY 4.0 互換。出典表示のうえ複製・公衆送信・翻訳・変形等が可能"
                 :catalog/license-tier :tier/a
                 :catalog/scope "e-Gov 法令検索が提供する全法令(憲法・法律・政令・勅令・府省令・規則)の現行改正版全文"
                 :catalog/complete-as-a-class true
                 :catalog/files
                 (into [{:path (str raw "/law-list.json")
                         :sha256 (sha256-file (str raw "/law-list.json"))
                         :bytes (file-size (str raw "/law-list.json"))
                         :kind :law-list}]
                       (map (fn [r] {:path (:text/path r) :sha256 (:text/sha256 r)
                                     :bytes (:text/bytes r) :kind :law-text
                                     :law-key (:law/key r)}))
                       with-text)})
    (log "laws" (count rows) "with-text" (count with-text) "relations" (count rels)
         "bytes" (reduce + 0 (map :text/bytes with-text)))))

(-main)
