(ns gtfs-feed-archive.core
  (:refer-clojure :exclude [format])   ;; I like cl-format better...
  (:require [clj-http.client :as http] ;; docs at https://github.com/dakrone/clj-http
            clojure.set
            [miner.ftp :as ftp]
            [gtfs-feed-archive.javadoc-helper :as javadoc-helper]
            [gtfs-feed-archive.cache-persistance :as cache-persistance]
            [gtfs-feed-archive.cache-manager :as cache-manager]
            [gtfs-feed-archive.download-agent :as download-agent])
  (:use gtfs-feed-archive.util 
        clojure.test
        clojure-csv.core
        [clj-http.client :rename {get http-get}]
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

(javadoc-helper/set-local-documentation-source)

;; contents can be a java.io.InputStream, in which case we'll loop and
;; copy everything from the stream into the zip file.
(defn make-zip-file
  [output-file-name names-contents]
  (with-open [z (java.util.zip.ZipOutputStream.
           (clojure.java.io/output-stream output-file-name))]
    (doseq [[name content] names-contents]
      (.putNextEntry z (java.util.zip.ZipEntry. name))
      (condp isa? (type content)
        java.io.InputStream (copy-binary-stream content z)
        java.io.BufferedInputStream (copy-binary-stream content z)
        java.lang.String (.write z (string->bytes content))
        (Class/forName "[B") (.write z content)))))


;; example zip creation from download agent.
(defn create-eugene-zip-file []
  (make-zip-file "/tmp/testing-eugene.zip"
               (download-agents->zip-file-list [ (last @cache-manager/cache-manager)] )))

(defn make-example-zip-file []
  (make-zip-file "/tmp/foo.zip"
                 [["foo/foo.txt" (string->bytes "foo\n")]
                  ["foo/bar.txt" (string->bytes "bar\n")]
                  ["foo/baz.txt" (string->bytes "baz\n")]]))

;; convenience function. long term we want to manage the input CSV
;; file(s) using a user setting for which URL to grab the CSV file from.
(defn public-gtfs-feeds [] 
  (let [public-feeds "./resources/oregon_public_gtfs_feeds.14-Aug-2013.csv"
        public-feeds-working "./resources/oregon_public_gtfs_feeds.working.csv" ;; all working feeds.
        public-feeds-smaller "./resources/oregon_public_gtfs_feeds.smaller.csv" ;; smaller feeds only.
        ]
    (with-open [r (clojure.java.io/reader public-feeds)]
      (doall (csv->maps r)))))

;;; for instance:
;;; (error-feeds (feed-last-updates (example-csv-config))) 
;;;  => ({:last-update nil, :feed-name "error"})
(defn error-feeds [feed-updates]
  (filter #(nil? (:last-update %))
          feed-updates))

(defn -main [] 'run-command-line-application)

#_(defn feed->download-agent [feed]
  (download-agent/make-from-feed feed
                                 #'cache-manager/cache-has-a-fresh-enough-copy?))

(defn download-agents->zip-file-list
  "Build a list of file names and contents from successful download agents.
   May throw an exception if agents do not have files or their files are not readable."
  [download-agents]
  (for [a (map deref download-agents) ]
    [(str "feeds/"(:feed-name a) ".zip")
     (clojure.java.io/input-stream (:file-name a))]))

(defn build-public-feed-archive! []
  (io! "writes a zip file"
       (let [feeds (public-gtfs-feeds)
             names (feed-names feeds)
             archive-name "Oregon-GTFS-feeds"
             output-file-name (str "/tmp/gtfs-archive-output/" archive-name ".zip") ]
         (try (mkdir-p (dirname output-file-name))
              (println "gathering feed archives.")
              (when-let [finished-agents (cache-manager/fetch-feeds! feeds)]
                (println "creating zip file.")
                (make-zip-file output-file-name 
                             (download-agents->zip-file-list finished-agents)))
              ;;(comment (make-zip-file output-file-name [[(str archive-name "/hello.txt")  "hello, world!\n"]])) 
              (catch Exception e
                (doall (map println ["Error while building a feed archive:"
                                     e 
                                     "TODO: figure out the cause, then pass this"
                                     "error up to the User and ask them what to do."])))))))

