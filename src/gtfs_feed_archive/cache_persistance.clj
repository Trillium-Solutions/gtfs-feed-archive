(ns gtfs-feed-archive.cache-persistance
  (:refer-clojure :exclude [format]) ;; I like cl-format better...
  (require [clojure.edn :as edn])
  (:use gtfs-feed-archive.util
        clojure.test
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

(def t true) ;; for format.

(defn test-edn []
  (edn/read-string "(1)"))