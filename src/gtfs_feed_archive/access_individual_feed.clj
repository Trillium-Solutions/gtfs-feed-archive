(ns gtfs-feed-archive.access-individual-feed
  (:require [taoensso.timbre :as timbre :refer (trace debug info warn error fatal spy with-log-level)])
  (:use gtfs-feed-archive.util
        [gtfs-feed-archive.config :as config]
        [gtfs-feed-archive.cache-manager :as cache-manager]))

(defn chosen-feeds [feed-names]
  ;(into #{} (concat (filter (or (map #(= :feed-name %) feed-names))
  ;(into #{} (mapcat read-csv-file @config/*input-csv-files*))
  (into #{} (concat (filter (apply some-fn (map (fn [arg] #(= (:feed-name %) arg)) feed-names)) 
                            (mapcat read-csv-file @config/*input-csv-files*)))))

(defn verify-freshness-of-chosen-feeds! [feed-names]
  (cache-manager/verify-feeds-are-fresh! (chosen-feeds feed-names)
                                         (java.util.Date.  
                                           (- (.getTime (now))
                                              (int (* 1000 60 60
                                                      @config/*freshness-hours*))))))

(defn feed-name->individual-download-link [feed-name]
  (str @config/*archive-output-url* "/" feed-name "-" (inst->rfc3339-day (now))
       ".zip"))

(defn get-chosen-feeds-zip! [feed-names]
  (try
      (info "Verifying freshness of " feed-names "agent: " 
            (pr-str (verify-freshness-of-chosen-feeds! feed-names))
            "Feed found? " (pr-str (chosen-feeds feed-names))
            "More testing: " (pr-str (first (mapcat read-csv-file @config/*input-csv-files*))))
      (download-agent->zip-file (verify-freshness-of-chosen-feeds! feed-names)
                                (apply feed-name->individual-download-link 
                                  feed-names))
  #_(catch Exception e
    (error "The cache does not contain new enough copies of the GTFS feeds requested.")
    (error "This is usually due to a download problem or a typo in the download URL."))))
