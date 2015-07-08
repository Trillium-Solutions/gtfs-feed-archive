(ns gtfs-feed-archive.cache-persistance
  (:refer-clojure :exclude [format]) ;; I like cl-format better.
  (:require [clojure.edn :as edn]
            [clojure.pprint :only [pprint]]
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal spy with-log-level)])
  (:require [gtfs-feed-archive.download-agent :as download-agent])
  (:use gtfs-feed-archive.util
        clojure.test
        [gtfs-feed-archive.config :as config]

        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

(defn cache->list
  "List of all completed, successful download agents."
  [cache]
  ;; group agents by their last-modified time and feed-name, remove
  ;; :im-just-a-copy, and keep only those which have the most recent completion-date.
  (let [agents (map deref @cache)
        completed (for [a agents :when (download-agent/success? a)] a)
        by-name-modified (map second (group-by (juxt :last-modified :feed-name)
                                               completed))
        most-recent (map #(last (sort-by :completion-date %)) by-name-modified)]
    most-recent))

(defn list->cache [lst]
  ;;(agent
   (into []
         (for [a lst]
           (agent a)))) ;;)

(defn cache->edn [cache]
  (prn-str (cache->list cache)))

(defn edn->cache [str]
  (list->cache (edn/read-string str)))

(defn write-cache!
  "Save all completed agents to an EDN file.
   Returns true if we could save the cache, false if there was a problem."
  [cache file-name]
  (try
    (with-open [f (clojure.java.io/writer file-name)]
      (binding [*out* f]
        (pprint (cache->list cache))
        (.flush *out*)))
    true
    (catch Exception e
      false)))

(defn read-cache
  "Return a cache, or nil if there was a problem."
  [file-name]
  (try 
    (with-open [f (clojure.java.io/reader file-name)]
      (binding [*in* (java.io.PushbackReader. f)]
        (list->cache (edn/read))))
    (catch Exception e
      ;; TODO: log an error here.
      (println "Exception in load-cache!" e)
      nil)))


