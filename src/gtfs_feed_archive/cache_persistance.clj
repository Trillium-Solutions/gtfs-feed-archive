(ns gtfs-feed-archive.cache-persistance
  (:refer-clojure :exclude [format]) ;; I like cl-format better.
  (:require [clojure.edn :as edn]
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal spy with-log-level)])
  (:require [gtfs-feed-archive.download-agent :as download-agent])
  (:use gtfs-feed-archive.util
        clojure.test
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

(defn cache->list
  "List of all completed download agents."
  [cache]
  ;; group agents by their last-modified time and feed-name, remove
  ;; :im-just-a-copy, and keep only those which have the most recent completion-date.
  (let [completed (filter download-agent/completed?
                          (for [a @cache] @a))
        without-copy-flags (map #(dissoc % :im-just-a-copy) completed)
        by-name-modified (group-by (juxt :last-modified :feed-name)
                                   without-copy-flags)
        most-recent (map last (sort-by :completion-date
                              (map second by-name-modified)))]
    most-recent))

(defn list->cache [lst]
  (agent
   (into []
         (for [a lst]
           (agent a)))))

(defn cache->edn [cache]
  (prn-str (cache->list cache)))

(defn edn->cache [str]
  (list->cache (edn/read-string str)))

(defn save-cache!
  "Save all completed agents to an EDN file.
   Returns true if we could save the cache, false if there was a problem."
  [cache file-name]
  (try
    (with-open [f (clojure.java.io/writer file-name)]
      (binding [*out* f]
        (prn (cache->list cache))
        (.flush *out*)))
    true
    (catch Exception e
      false)))

(defn load-cache!
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


