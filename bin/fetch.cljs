#!/usr/bin/env nbb
;; Preserves the e-Gov 法令API v2 corpus verbatim into raw/.
;;
;;   raw/law-list.json        — every page of GET /api/2/laws, concatenated
;;   raw/laws/<law_id>.json   — GET /api/2/law_data/<law_id> (contains
;;                              law_full_text: the complete statutory text)
;;   raw/source-catalog.edn   — written by bin/catalog.cljs, not here
;;
;; Responses are stored EXACTLY as returned. No normalisation, no pruning,
;; no re-serialisation -- the sha256 in source-catalog.edn is the sha256 of
;; what e-Gov actually sent, so a downstream consumer can verify custody
;; without trusting this script.
;;
;; Resumable: an already-present raw/laws/<id>.json is skipped, so an
;; interrupted run costs only the requests it had not yet made.
;;
;; Usage: nbb --classpath bin bin/fetch.cljs [--pool N] [--limit N]
(ns fetch
  (:require [lib :refer [fetch-buffer log mkdirp! exists? write-file! pooled
                         sleep file-size]]
            [clojure.string :as str]))

(def api "https://laws.e-gov.go.jp/api/2")
(def raw "raw")
(def laws-dir (str raw "/laws"))

(def args (vec *command-line-args*))
(defn arg [flag default]
  (if-let [i (first (keep-indexed #(when (= %2 flag) %1) args))]
    (js/parseInt (nth args (inc i)))
    default))

(def pool-size (arg "--pool" 4))
(def hard-limit (arg "--limit" 0))

(defn fetch-law-list
  "Pages GET /api/2/laws until next_offset stops advancing. Returns a promise
   of the merged {:total n :laws [...]}"
  []
  (letfn [(page [offset acc total]
            (-> (fetch-buffer (str api "/laws?limit=500&offset=" offset))
                (.then (fn [{:keys [ok buf status error]}]
                         (if-not ok
                           (throw (js/Error. (str "law list offset=" offset " failed: " (or status error))))
                           (let [j (js/JSON.parse (.toString buf))
                                 laws (js->clj (.-laws j) :keywordize-keys false)
                                 total (or total (.-total_count j))
                                 acc (into acc laws)
                                 next-offset (.-next_offset j)]
                             (log "law-list" (count acc) "/" total)
                             (if (and next-offset (< (count acc) total) (pos? (count laws)))
                               (-> (sleep 250) (.then (fn [_] (page next-offset acc total))))
                               {:total total :laws acc})))))))]
    (page 0 [] nil)))

(defn law-path [law-id] (str laws-dir "/" law-id ".json"))

(defn fetch-one [law-id]
  (let [p (law-path law-id)]
    (if (and (exists? p) (pos? (file-size p)))
      (js/Promise.resolve {:law-id law-id :skipped true})
      (-> (fetch-buffer (str api "/law_data/" law-id))
          (.then (fn [{:keys [ok buf status error]}]
                   (if ok
                     (do (write-file! p buf)
                         {:law-id law-id :bytes (.-length buf)})
                     {:law-id law-id :failed (or status error)})))))))

(defn -main []
  (mkdirp! laws-dir)
  (-> (fetch-law-list)
      (.then (fn [{:keys [total laws]}]
               (write-file! (str raw "/law-list.json")
                            (js/Buffer.from (js/JSON.stringify (clj->js {"total_count" total "laws" laws}) nil 1)))
               (log "law-list saved:" (count laws) "entries, total_count" total)
               (let [ids (distinct (keep #(get-in % ["law_info" "law_id"]) laws))
                     ids (if (pos? hard-limit) (take hard-limit ids) ids)]
                 (log "fetching" (count ids) "law texts with pool" pool-size)
                 (-> (pooled pool-size ids
                             (fn [id i]
                               (-> (fetch-one id)
                                   (.then (fn [r]
                                            (when (zero? (mod (inc i) 200))
                                              (log "progress" (inc i) "/" (count ids)))
                                            r)))))
                     (.then (fn [results]
                              (let [failed (filter :failed results)
                                    fetched (remove #(or (:failed %) (:skipped %)) results)]
                                (log "done. fetched" (count fetched)
                                     "skipped" (count (filter :skipped results))
                                     "failed" (count failed))
                                (when (seq failed)
                                  (log "FAILED IDS:" (str/join "," (map :law-id (take 40 failed))))))))))))
      (.catch (fn [e] (log "FATAL" (str e)) (set! (.-exitCode js/process) 1)))))

(-main)
