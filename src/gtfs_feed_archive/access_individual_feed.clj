(ns gtfs-feed-archive.access-individual-feed
  (:use gtfs-feed-archive.util
        [gtfs-feed-archive.config :as config]
        [gtfs-feed-archive.cache-manager :as cache-manager]))

(defn chosen-feeds [feed-names]
  (into #{} (concat (filter (or (map #(= :feed-name %) feed-names)) 
                            (map read-csv-file @config/*input-csv-files*)))))

(defn verify-freshness-of-chosen-feeds! [feed-names]
  (cache-manager/verify-feeds-are-fresh! (chosen-feeds feed-names)
                                         (java.util.Date.  
                                           (- (.getTime (now))
                                              (int (* 1000 60 60
                                                      @config/*freshness-hours*))))))

(defn get-chosen-feeds-csv! [feed-names]
  (try
      (download-agents->concated-csv (verify-freshness-of-chosen-feeds! 
                                       feed-names))
  (catch Exception e
    (error "The cache does not contain new enough copies of the GTFS feeds requested.")
    (error "This is usually due to a download problem or a typo in the download URL."))))
