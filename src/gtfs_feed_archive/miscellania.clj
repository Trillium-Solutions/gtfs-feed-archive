(ns gtfs-feed-archive.miscellania
  (:require gtfs-feed-archive.core))

(def example-csv-config-text "feed_name,feed_description,gtfs_zip_url
sample,Google example feed,http://localhost/gtfs-examples/sample-feed/sample-feed.zip
broken,testing feed with intentionally broken data,http://localhost/gtfs-examples/broken-feed/gtfs.zip
error,broken link to test file parsing,http://localhost:1111
mendocino,Mendocino County CA,http://localhost/gtfs-examples/mendocino-transit-authority/mendocino-transit-authority_20121230_0426.zip")

;; kingcounty,King County Seattle Metro,http://localhost/gtfs-examples/kingcounty/kingcounty-archiver_20130206_0431.zip
;; trimet,Tri-Met: Portland Metro,http://developer.trimet.org/schedule/gtfs.zip
;; cherriots,Cherriots: Salem-Kaiser,http://www.cherriots.org/developer/gtfs.zip


(defn example-csv-config []
  (gtfs-feed-archive.core/csv->maps example-csv-config-text))

(defn make-example-zip-file []
  (gtfs-feed-archive.core/make-zip-file "/tmp/foo.zip"
                 [["foo/foo.txt" (string->bytes "foo\n")]
                  ["foo/bar.txt" (string->bytes "bar\n")]
                  ["foo/baz.txt" (string->bytes "baz\n")]]))

(defn all-feeds-succeeded-example "example for testing. run (!fetch-all-feeds!) first."
  []
  (println "output should be: true, false")
  (pprint (gtfs-feed-archive.core/all-feeds-succeeded-after-date? '("sample" "broken")
                                           #inst "2012"
                                           @cache-manager)) 
  (pprint (gtfs-feed-archive.core/all-feeds-succeeded-after-date? '("sample" "broken" "error")
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

(defn feed-last-updates
  ([]
     (feed-last-updates (example-csv-config)))
  ([csv-config]
     (map (fn [e]
            (assoc e :last-update
                   ((comp page-last-modified :gtfs-zip-url) e)))
          csv-config)))

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


(defn cache-search-example
  "Find cache entires which have feed-name, and also the subset
  which have completed after refresh-date."
  [feed-name refresh-date cache]
  (let [finished-agents (filter (comp (every-pred (partial download-agent-has-feed-name? feed-name)
                                                  (partial download-agent-completed-after? refresh-date))
                                      deref)
                                cache)
        running-agents (filter (comp (every-pred (partial download-agent-has-feed-name? feed-name)
                                                 (partial download-agent-still-running?))
                                     deref)
                               cache)
        running-and-finished (filter (comp (every-pred (partial download-agent-has-feed-name? feed-name)
                                                       (some-fn (partial download-agent-completed-after?
                                                                         refresh-date)
                                                                download-agent-still-running?) )
                                           deref)
                                     cache)]
    ;; then I suppose we can reduce finished-entries to find the one which has newest data?
    [finished-agents
     running-agents
     running-and-finished]))
