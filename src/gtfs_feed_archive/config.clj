(ns gtfs-feed-archive.config ;; global and per-server-instance configuration.
  (:refer-clojure :exclude [format])   ;; I like cl-format better...
  (:require clojure.set
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal spy with-log-level)])
  (:use gtfs-feed-archive.util 
        clojure.test
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

(comment *archive-list* format is
           [["archive-name1.zip" :complete]
            ["archive-name2.zip" :running ]
            ["archive-name3.zip" :error]])
(defonce ^:dynamic *archive-list* (agent {}))
(defonce ^:dynamic *archive-output-directory* (atom nil))
(defonce ^:dynamic *archive-filename-prefix* (atom nil))
;; Remembering CSV files, instead of the feeds they represent, has the
;; feature of allowing the user to update CSV files between runs.
 ;; (def ^:dynamic *feeds*)
(defonce ^:dynamic *input-csv-files* (atom nil)) 
(defonce ^:dynamic *cache-directory* (atom nil)) 
(defonce ^:dynamic *cache-manager* (agent []))
(defonce ^:dynamic *freshness-hours* (atom nil))
(defonce ^:dynamic *web-server-port* (atom nil))
(defonce ^:dynamic *nrepl-server* (atom nil))
(defonce ^:dynamic *nrepl-port* (atom nil))


