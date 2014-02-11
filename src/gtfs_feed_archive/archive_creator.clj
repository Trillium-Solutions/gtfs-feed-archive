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
  ;; return a list of agents, or if the cache could not be updated,
  ;; throw an error.
  (cache-manager/fetch-feeds-slow!
   (unique-feeds)))


(defn verify-cache-freshness! []
  ;; return a list of agents, or if the cache is not fresh enough,
  ;; throw an error.
  (cache-manager/verify-feeds-are-fresh! (unique-feeds)
                                         (java.util.Date.
                                          (- (.getTime (now))
                                             (int (* 1000 60 60
                                                     @config/*freshness-hours*))))))


(defonce ^:dynamic *force-rebuild?* false)

;; convenience function. long term we want to manage the input CSV
;; file(s) using a user setting for which URL to grab the CSV file from.
(defn public-gtfs-feeds [] 
  (let [public-feeds "./resources/oregon_public_gtfs_feeds.csv"]
    (read-csv-file public-feeds)))

(defn build-feed-archive! 
  "Given an archive name, and a successful download agents, create an
  archive in the output directory."
  [archive-name output-directory finished-agents]
  (let [output-file-name (str output-directory "/" archive-name )]
    (try (mkdir-p (dirname output-file-name))
         (info "Creating zip file:" output-file-name)
         (let [file-list (cons (download-agents->last-updates-csv-entry finished-agents)
                               (download-agents->zip-file-list finished-agents))
               file-list-with-prefix (prepend-path-to-file-list archive-name
                                                                file-list)]
           (make-zip-file output-file-name file-list-with-prefix))
         (catch Exception e
           ;; TODO: log and/or show error to user.
           (error "Error while building a feed archive:" (str e))))))

(defn build-public-feed-archive!
  "Write a zip file with the most recent data for Oregon public GTFS feeds."
  []
  (io! "Creates a file."
       (let [feeds (public-gtfs-feeds)
             ;;names (feed-names feeds)
             archive-name (str "Oregon-GTFS-feeds-" (inst->rfc3339-day (now)))
             output-directory "/tmp/gtfs-archive-output"]
         (let [finished-agents (cache-manager/fetch-feeds-slow! feeds)]
  ;;       (let [finished-agents (cache-manager/fetch-feeds! feeds)]
           ;; TODO: if there's an error, provide a log and notify the user somehow.
           (if finished-agents
             (build-feed-archive! archive-name output-directory finished-agents)
             (error "Error fetching public GTFS feeds."))))))

;; how can the web interface determine if archive generation succeeded or failed?

(defn update-archive-list! [filename worker-thunk] 
  (send-off config/*archive-list*
            (fn [archives]
              (let [status (get archives filename)]
                (if (or *force-rebuild?*
                        (not= status :complete))
                  (do (info "Building archive " filename ".")
                      (if (worker-thunk)
                        (assoc archives filename :complete)
                        (assoc archives filename :error)))
                  (do (info "No work to be done," filename "already created successfully.")
                      archives))))))

;; for debugging. clear the list so we can test adding items to it.
(defn !empty-archive-list! []
  (send-off config/*archive-list* (fn [a] {})))

(defn load-archive-list! []
  (let [archive-list-from-disk
        (->> (try (file-seq (clojure.java.io/file @config/*archive-output-directory* ))
                  (catch Exception e nil))
             (filter #(.isFile %)) ;; TODO -- verify the zip is properly formatted!!
             (map #(.getName %))
             (filter #(.endsWith (.toLowerCase %) ".zip"))
             (map (fn [name] [name :complete]))
             (into {}))]
    (send config/*archive-list*
          (fn [existing-archive-list]
            (merge existing-archive-list archive-list-from-disk)))
    ;; TODO is this await needed?
    (await config/*archive-list*)))

(defn all-feeds-filename []
  (str @config/*archive-filename-prefix* "-feeds-"
       (inst->rfc3339-day (now)) ".zip" ))

(defn build-archive-of-all-feeds-worker!
  [filename]
  (try
    (let [finished-agents (verify-cache-freshness!)]
      (build-feed-archive! filename 
                           @config/*archive-output-directory*
                           finished-agents)
      true)
    (catch Exception e
      (error "The cache does not contain new enough copies of the GTFS feeds requested.")
      (error "This is usually due to a download problem or a typo in the download URL.")
      (error "Sorry, I am unable to build an archive.")
      false)))


;; to use:  (send-off config/*archive-list* build-archive archive-builder-function args...)
(defn build-archive-of-all-feeds!
  ([]
     (build-archive-of-all-feeds! (all-feeds-filename)))
  ([filename] 
     (update-archive-list! filename
                           (fn []
                             (build-archive-of-all-feeds-worker! filename)))))


(defn modified-since-filename [since-date]
  (str @config/*archive-filename-prefix* "-updated-from-" (inst->rfc3339-day since-date)
            "-to-" (inst->rfc3339-day (now)) ".zip"))

(defn build-archive-of-feeds-modified-since-worker!
  [since-date filename]
  (try
    (let [finished-agents (verify-cache-freshness!)
          new-enough-agents (filter (fn [a] (download-agent/modified-after? since-date @a))
                                    finished-agents)]
      (build-feed-archive! filename
                           @config/*archive-output-directory*
                           new-enough-agents)
      true)
    (catch Exception e
      (error "The cache does not contain new enough copies of the GTFS feeds requested.")
      (error "This is usually due to a download problem or a typo in the download URL.")
      (error "Sorry, I am unable to build an archive.")
      false)))

(defn build-archive-of-feeds-modified-since!
  ([since-date]
     (build-archive-of-feeds-modified-since! since-date (modified-since-filename since-date)))
  ([since-date filename] 
     (update-archive-list! filename
                           (fn []
                             (build-archive-of-feeds-modified-since-worker! since-date filename)))))
