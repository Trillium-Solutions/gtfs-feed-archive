(ns gtfs-feed-archive.archive-creator
  (:refer-clojure :exclude [format]) ;; I like cl-format better.
  (:require [clojure.edn :as edn])
  (:require [gtfs-feed-archive.download-agent :as download-agent]
            [gtfs-feed-archive.cache-persistance :as cache-persistance]
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal spy with-log-level)])
  (:use gtfs-feed-archive.util
        clojure.test
        [gtfs-feed-archive.config :as config]
        [gtfs-feed-archive.cache-manager :as cache-manager]
        [gtfs-feed-archive.download-agent :as download-agent]
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

;; Functions related to updating the cache and creating archives. This
;; is a bit of a catch-all, this module may benefit from being split
;; or refactored.


;; These functions might belong in the cache manager. On the other
;; hand they know about our CSV input files, and the freshness
;; setting, so we might consider them a higher-level abstraction.

(defn- unique-feeds []
  (into #{} (mapcat read-csv-file @config/*input-csv-files*)))

(defn update-cache! []
  (cache-manager/fetch-feeds-slow!
   (unique-feeds)))

(defn verify-cache-freshness! []
  (cache-manager/verify-feeds-are-fresh! (unique-feeds)
                                         (java.util.Date.
                                          (- (.getTime (now))
                                             (int (* 1000 60 60
                                                     @config/*freshness-hours*))))))
