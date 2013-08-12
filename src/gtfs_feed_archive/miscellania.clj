(ns gtfs-feed-archive.miscellania
  (:refer-clojure :exclude [format]) ;; I like cl-format better...
  (:require [clj-http.client :as http]) ;; docs at https://github.com/dakrone/clj-http
  (:require clojure.set)
  (:use gtfs-feed-archive.core
        clojure.test
        clojure-csv.core
        [clj-http.client :rename {get http-get}]
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

(def example-csv-config-text "feed_name,feed_description,gtfs_zip_url
sample,Google example feed,http://localhost/gtfs-examples/sample-feed/sample-feed.zip
broken,testing feed with intentionally broken data,http://localhost/gtfs-examples/broken-feed/gtfs.zip
error,broken link to test file parsing,http://localhost:1111
mendocino,Mendocino County CA,http://localhost/gtfs-examples/mendocino-transit-authority/mendocino-transit-authority_20121230_0426.zip")

;; kingcounty,King County Seattle Metro,http://localhost/gtfs-examples/kingcounty/kingcounty-archiver_20130206_0431.zip
;; trimet,Tri-Met: Portland Metro,http://developer.trimet.org/schedule/gtfs.zip
;; cherriots,Cherriots: Salem-Kaiser,http://www.cherriots.org/developer/gtfs.zip


(defn example-csv-config []
  (csv->maps example-csv-config-text))

(defn make-example-zip-file []
  (make-zip-file "/tmp/foo.zip"
                 [["foo/foo.txt" (string->bytes "foo\n")]
                  ["foo/bar.txt" (string->bytes "bar\n")]
                  ["foo/baz.txt" (string->bytes "baz\n")]]))

(defn all-feeds-succeeded-example "example for testing. run (!fetch-all-feeds!) first."
  []
  (println "output should be: true, false")
  (pprint (all-feeds-succeeded-after-date? '("sample" "broken")
                                           #inst "2012"
                                           @cache-manager)) 
  (pprint (all-feeds-succeeded-after-date? '("sample" "broken" "error")
                                           #inst "2012"
                                           @cache-manager)))

(defn cache-search-example-2 
  "For each feed name, find all download agents for those feeds, which
   are either still running, or which have completed after refresh-date."
  [feed-names refresh-date cache]
  (let [feed-name-set (into #{} feed-names)
        feed-name-in-set? (fn [state] (feed-name-set (:feed-name state)))]
    (filter (comp (every-pred feed-name-in-set?
                              (some-fn (every-pred (partial download-agent-completed-after?
                                                            refresh-date)
                                                   download-agent-success?) 
                                       download-agent-still-running?) )
                  deref)
            cache)))


(defn test-cache-search-example-2-public
  []
  (cache-search-example-2 (map :feed-name (public-gtfs-feeds))
                          #inst "2013-08-01"
                          @cache-manager))

(defn test-cache-search-example-2
  []
  (cache-search-example-2 ["sample" "broken" "mendocino"] #inst "2012" @cache-manager))


(defn !fetch-test-feeds! []
  (doseq [f (feed-last-updates)]
    (send-off cache-manager fetch-feed! f)))

;; For instance
;; (!fetch-fresh-feeds! #inst "2012")
(defn !fetch-fresh-feeds! [date]
  (doseq [f (fresh-feeds (feed-last-updates)
                         date)]
    (send-off cache-manager fetch-feed! f)))

(defn feed-last-updates
  ([]
     (feed-last-updates (example-csv-config)))
  ([csv-config]
     (map (fn [e]
            (assoc e :last-update
                   ((comp page-last-modified :gtfs-zip-url) e)))
          csv-config)))

