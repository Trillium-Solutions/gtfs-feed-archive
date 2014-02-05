(ns gtfs-feed-archive.core
  (:gen-class)
  (:refer-clojure :exclude [format])   ;; I like cl-format better...
  (:require [clj-http.client :as http] ;; docs at https://github.com/dakrone/clj-http
            clojure.set
            [miner.ftp :as ftp]
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal spy with-log-level)]
            [gtfs-feed-archive.javadoc-helper :as javadoc-helper]
            [gtfs-feed-archive.cache-persistance :as cache-persistance]
            [gtfs-feed-archive.cache-manager :as cache-manager]
            [gtfs-feed-archive.download-agent :as download-agent]
            [gtfs-feed-archive.command-line :as command-line]
            [gtfs-feed-archive.web :as web])
  (:use gtfs-feed-archive.util 
        clojure.test
        clojure-csv.core
        [clojure.tools.cli :only [cli]] ;; Command-line parsing.
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

(javadoc-helper/set-local-documentation-source!)

;; contents can be a java.io.InputStream, in which case we'll loop and
;; copy everything from the stream into the zip file.
(defn make-zip-file
  [output-file-name names-contents]
  (with-open [z (java.util.zip.ZipOutputStream.
           (clojure.java.io/output-stream output-file-name))]
    (doseq [[name content] names-contents]
      (.putNextEntry z (java.util.zip.ZipEntry. name))
      (copy-data-to-stream content z))))

(defn read-csv-file [filename]
  (with-open [r (clojure.java.io/reader filename)]
      (doall (csv->maps r))))

;; convenience function. long term we want to manage the input CSV
;; file(s) using a user setting for which URL to grab the CSV file from.
(defn public-gtfs-feeds [] 
  (let [public-feeds "./resources/oregon_public_gtfs_feeds.csv"]
    (read-csv-file public-feeds)))


(defn download-agents->last-updates-csv [download-agents]
  ;; TODO: use the CSV file writer to ensure proper quoting so strange
  ;; names and URLs don't have a chance to break the CSV file.
  (let [header-str "zip_file_name,most_recent_update,feed_name,historical_download_url\r\n"]
    (reduce str header-str
            (for [a (map deref download-agents) ]
              (str (str "feeds/"(:feed-name a) ".zip,")
                   (inst->rfc3339-utc (:last-modified a)) ","
                   (:feed-name a) ","
                   (:url a) "\r\n")))))

(defn download-agents->last-updates-csv-entry [download-agents]
  ["last_updates.csv" (download-agents->last-updates-csv download-agents)])

(defn download-agents->zip-file-list
  "Build a list of file names and contents from successful download agents.
   May throw an exception if agents do not have files or their files are not readable."
  [download-agents]
  (for [a (map deref download-agents) ]
    [(str "feeds/"(:feed-name a) ".zip")
     (clojure.java.io/input-stream (:file-name a))]))

(defn prepend-path-to-file-list [path zip-file-list]
  (for [[name data] zip-file-list] 
    [(str path "/" name) data]))

(defn build-feed-archive! 
  "Given an archive name, and a successful download agents, create an
  archive in the output directory."
  [archive-name output-directory finished-agents]
  (let [output-file-name (str output-directory "/" archive-name ".zip")]
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

(defn run-command-line [& args]
  ;; TODO: split out all these option handlers into their own
  ;; functions, so we can call them as easily from the web interface
  ;; as from the command line.
  (let [[options plain-args] (apply command-line/parse-args-or-die! args)]
    (let [output-directory (:output-directory options)
          archive-prefix (:archive-name-prefix options) ;; "Oregon-GTFS"
          feeds (into #{} (mapcat read-csv-file (:input-csv options)))]
      (when-let [dir (:cache-directory options)]
        (info "Setting cache directory:" dir)
        (cache-manager/set-cache-directory! dir))
      (cache-manager/load-cache-manager!)
      (info "Looking at " (count feeds ) "feeds.")
      (let [finished-agents 
            (cond (:update options) (cache-manager/fetch-feeds-slow! feeds)
                  (:freshness-hours options) (cache-manager/verify-feeds-are-fresh! feeds
                                                                              (java.util.Date.
                                                                               (- (.getTime (now))
                                                                                  (int (* 1000 60 60
                                                                                          (:freshness-hours options)))))))]
        (when-not finished-agents
          (error "Error updating feeds, sorry!")
          (System/exit 1))
        (do 
          (cache-manager/save-cache-manager!) ;; save cache status for next time.
          (info "Cache saved."))
        (when (:all options)
          (build-feed-archive! (str archive-prefix "-feeds-" (inst->rfc3339-day (now))) 
                               output-directory
                               finished-agents))
        (doseq [s (:since-date options)]
          (let [new-enough-agents (filter (fn [a] (download-agent/modified-after? s @a))
                                          finished-agents)]
            (build-feed-archive!
             (str archive-prefix "-updated-from-" (inst->rfc3339-day s)
                  "-to-" (inst->rfc3339-day (now))) output-directory
                  new-enough-agents)))
        (when (:run-server options)
          (web/start-web-server! (:server-port options))
          (loop []
            (Thread/sleep 1000)
            ;;(info "Web server still running")
            (recur)))))))

(defn -main [& args]
  ;;(timbre/set-level! :warn)
  (apply run-command-line args)
  (shutdown-agents))

