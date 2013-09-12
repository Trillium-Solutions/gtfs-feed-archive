(ns gtfs-feed-archive.core
  (:refer-clojure :exclude [format])   ;; I like cl-format better...
  (:require [clj-http.client :as http] ;; docs at https://github.com/dakrone/clj-http
            clojure.set
            [miner.ftp :as ftp]
            [gtfs-feed-archive.javadoc-helper :as javadoc-helper]
            [gtfs-feed-archive.cache-persistance :as cache-persistance]
            [gtfs-feed-archive.cache-manager :as cache-manager]
            [gtfs-feed-archive.download-agent :as download-agent]
            [gtfs-feed-archive.command-line :as command-line])
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

(defn run-command-line [& args]
  (let [[options plain-args] (apply command-line/parse-args-or-die! args)]
    ))

(defn -main [& args]
  (apply run-command-line args))

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

(defn build-public-feed-archive!
  "Write a zip file with the most recent data for Oregon public GTFS feeds."
  []
  (io! "Creates a zip file."
       (let [feeds (public-gtfs-feeds)
             names (feed-names feeds)
             archive-name (str "Oregon-GTFS-feeds-" (inst->rfc3339-day (now)))
             output-file-name (str "/tmp/gtfs-archive-output/" archive-name ".zip")]
         (try (mkdir-p (dirname output-file-name))
              (println "Gathering feed archives.")
              (when-let [finished-agents (cache-manager/fetch-feeds! feeds)]
                (println "Creating zip file.")
                (let [file-list (cons (download-agents->last-updates-csv-entry finished-agents)
                                      (download-agents->zip-file-list finished-agents))
                      file-list-with-prefix (prepend-path-to-file-list archive-name
                                                                       file-list)]
                  (make-zip-file output-file-name
                                 file-list-with-prefix)))
              (catch Exception e
                (doall (map println ["Error while building a feed archive:" (str e)
                                     "TODO: figure out the cause, then pass this"
                                     "error up to the User and ask them what to do."])))))))

