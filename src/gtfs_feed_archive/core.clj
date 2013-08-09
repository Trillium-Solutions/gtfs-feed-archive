(ns gtfs-feed-archive.core
  (:refer-clojure :exclude [format]) ;; I like cl-format better...
  (:require [clj-http.client :as http]) ;; docs at https://github.com/dakrone/clj-http
  (:require clojure.set)
  (:require [miner.ftp :as ftp])
  (:use clojure.test
        clojure-csv.core
        [clj-http.client :rename {get http-get}]
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

;; for development -- set local documentation source for javadoc command.
(do 
  (use 'clojure.java.javadoc)
  (dosync (ref-set clojure.java.javadoc/*local-javadocs*
                   ["/usr/share/doc/openjdk-6-doc/api"])))

(def example-csv-config-text "feed_name,feed_description,gtfs_zip_url
sample,Google example feed,http://localhost/gtfs-examples/sample-feed/sample-feed.zip
broken,testing feed with intentionally broken data,http://localhost/gtfs-examples/broken-feed/gtfs.zip
error,broken link to test file parsing,http://localhost:1111
mendocino,Mendocino County CA,http://localhost/gtfs-examples/mendocino-transit-authority/mendocino-transit-authority_20121230_0426.zip")

;; kingcounty,King County Seattle Metro,http://localhost/gtfs-examples/kingcounty/kingcounty-archiver_20130206_0431.zip
;; trimet,Tri-Met: Portland Metro,http://developer.trimet.org/schedule/gtfs.zip
;; cherriots,Cherriots: Salem-Kaiser,http://www.cherriots.org/developer/gtfs.zip

(defn keyword-with-dashes
  "convert a string to a keyword replacing '_' with '-'"
  [string]
  (keyword (clojure.string/replace string "_" "-")))

(defn methods-of-java-object
  "For repl development. Finds methods of a java object."
  [obj]
  (-> obj (class) (.getDeclaredMethods) (seq)))

(defn string->bytes [s]
  "Convert a string into an array of bytes."
  (byte-array (map byte s)))

(defn power-of-two "nth power of 2" [n] (int (Math/pow 2 n)))

(defn copy-binary-stream [^java.io.InputStream in
                          ^java.io.OutputStream out]
  "Buffered copy of in-stream to out-stream."
  (let [^bytes buffer (byte-array (power-of-two 14))]
    (loop []
      (let [nread (.read in buffer)]
        (format true "nread: ~a~%" nread)
        (when (pos? nread)
          (.write out buffer 0 nread)
          (recur))))))

(defn copy-binary-file "Example code for binary file copy."
  [in-file out-file]
  (with-open [in (clojure.java.io/input-stream in-file)
              out (clojure.java.io/output-stream out-file)]
    (copy-binary-stream in out)))

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
        java.lang.String (.write z (string->bytes content))
        (Class/forName "[B") (.write z content)))))

(defn make-example-zip-file []
  (make-zip-file "/tmp/foo.zip"
                 [["foo/foo.txt" (string->bytes "foo\n")]
                  ["foo/bar.txt" (string->bytes "bar\n")]
                  ["foo/baz.txt" (string->bytes "baz\n")]]))

(defn file->bytes [input-file-name]
  (clojure.java.io/input-stream input-file-name))

(defn csv->maps
  "Turn a CSV string (with headers) into a list of maps from header->data."
  [string]
  (let [csv (parse-csv string)
        header (map keyword-with-dashes (first csv))
        data (rest csv)]
    (map (partial zipmap header)
         data)))

;; convenience function. long term we want to manage the input CSV
;; file(s) using a user setting for which URL to grab the CSV file from.
(defn public-gtfs-feeds [] 
  (let [public-feeds "./resources/oregon_public_gtfs_feeds.csv" ;; some download links are broken.
        public-feeds-working "./resources/oregon_public_gtfs_feeds.working.csv"]
    (with-open [r (clojure.java.io/reader public-feeds-working)]
      (doall (csv->maps r)))))

(defn example-csv-config []
  (csv->maps example-csv-config-text))

(defn last-modified [response]
  (-> response
      (get-in [:headers "last-modified"])
      java.util.Date.))

(defn page-last-modified [url]
  (try 
    (last-modified (http/head url))
    (catch Exception _ nil)))

(defn page-data "http/get example"
  [url]
  (try 
    (-> (http/get url {:as :byte-array})
        (get :body))
    (catch Exception _ nil)))

(defn page-size "size of data at URL" [url]
  (count (page-data url)))

(defn feed-last-updates
  ([]
     (feed-last-updates (example-csv-config)))
  ([csv-config]
     (map (fn [e]
            (assoc e :last-update
                   ((comp page-last-modified :gtfs-zip-url) e)))
          csv-config)))

;;; for instance:
;;; (error-feeds (feed-last-updates (example-csv-config))) 
;;;  => ({:last-update nil, :feed-name "error"})
(defn error-feeds [feed-updates]
  (filter #(nil? (:last-update %))
          feed-updates))

;; for instance: (feed-names (public-gtfs-feeds))
(defn feed-names [feeds]
  (map :feed-name feeds))

;; for instance:
;; (fresh-feeds (feed-last-updates) #inst "2013-01-01")
(defn fresh-feeds [feed-updates date] ;; date is java.lang.Date
  (filter (every-pred #(not (nil? (:last-update %)))
                      #(.after (:last-update %) date))
          feed-updates))

(defn -main [] (feed-last-updates))

(defn inst->rfc3339-day
  "Convert inst into RFC 3339 format, then pull out the year, month, and day only."
  [inst]
  ;; funny that there's no function to do this already?
  (if (nil? inst)
    nil
    (.substring (pr-str inst) 7 26)))

(defn feed->download-agent [feed]
  (agent {:url (:gtfs-zip-url feed)
          :download-attempt 0
          :feed-name (:feed-name feed)
          :feed-description (:feed-description feed) ; for debugging.
          :destination-dir "/tmp/gtfs-cache/"}))

(defn dirname "directory component of path" [path]
  (.getParent (clojure.java.io/file path)))

(defn mkdir-p "make directory & create parent directories as needed" [path]
  (.mkdirs (clojure.java.io/file path)))

(defn download-agent-success? [state]
  "Has the file been saved?"
  (:file-saved state))

(defn download-agent-failure? 
  "Was there a file save failure, or a download failure?
   In either case We're done, but the download didn't work." 
  [state]
  (or (:download-failed state)
      (:file-save-failed state)))

(defn download-agent-completed? [state]
  "Has the download agent completed, either with success or failure??"
  ;; An alternative would be to check if there's a (:completion-date state)
  (or (download-agent-success? state) 
      (download-agent-failure? state)))

(def download-agent-still-running?
  (complement download-agent-completed?))

(defn download-agent-completed-after?
  "Has a download agent completed on or after earliest-date?
   Could have completed with either a success or failure state."
  [earliest-date state]
  (and (download-agent-completed? state)
       (when-let [d (:completion-date state)]
         (not (.before d earliest-date)))))

(defn download-agent-has-feed-name? [feed-name state]
  (= (:feed-name state) feed-name))

(defn now 
  "We're looking at now, now. This is now."
  [] (java.util.Date.))

(defn download-agent-save-file [state]
  ;; todo -- we should *never* overwrite an existing file here.
  ;; that could lead to race conditions if we replace a file where
  ;; another process is trying to use it.
  ;; 
  ;; RESEARCH:
  ;; instead if the file exists it should be an error condition.
  ;; how can clojure's output-stream let us express this?
  (let [file-name (str (:destination-dir state) "/"
                       (:feed-name state) "/"
                       (inst->rfc3339-day (:last-modified state))
                       ".zip")
        data (:data state)]
    (try (mkdir-p (dirname file-name))
         (with-open [w 
                     (clojure.java.io/output-stream file-name)]
           (.write w data))             ; binary data
         (-> state
             (dissoc :data)
             (dissoc :destination-dir)
             (assoc :file-saved true)
             (assoc :file-name file-name)
             (assoc :completion-date (now)))
         (catch Exception _
           (-> state
               ;; hmm, what is the proper course of action should a
               ;; file save fail? retrying probably won't help since
               ;; odds are the disk is full or its permissions or
               ;; configuration problem. we should probably log an
               ;; error and give up.
               (dissoc :data)
               (assoc :file-save-failed true)
               (assoc :completion-date (now)))))))


;; how can we pull out a :last-modified & :data from ftp connection??
;; fake the results to make them look like the HTTP api.
(defn http-or-ftp-get [url]
  (if (re-matches #"[Ff][Tt][Pp]://" url)
    nil ;; TODO: grab via FTP
    (try
      (http/get url
                {:as :byte-array})
      (catch Exception _ nil))))

(defn download-agent-attempt-download [state]
  ;; TODO -- Only attempt a download if the cache doesn't already
  ;; contain a file created at exactly the same date.  One way to
  ;; check would be to see if the file already exists, but I think it
  ;; would be better to consult the cache, since otherwise a partially
  ;; downloaded invalid file would permanantly stay in the cache.
  (if (< (:download-attempt state) 5)
    (do
      ;; exponential back-off delay, 2**download-attempt seconds.
      (Thread/sleep (* 1000 (power-of-two (:download-attempt state))))
      ;; http/get with the { :as :byte-array } option avoids text
      ;; conversion, which would corrupt our zip file.
      (let [response (http-or-ftp-get (:url state))]
        (if (nil? response)
          (assoc state :download-attempt ;; ok, we'll try again later.
                 (inc (:download-attempt state)))
          (-> state 
              (dissoc :download-attempt)
              (assoc :last-modified (last-modified response))
              (assoc :data (:body response))))))
    (-> state ;; too many attempts -- give up.
        (dissoc :download-attempt)
        (assoc :download-failed true)
        (assoc :completion-date (now)))))


;;; TODO: ultimately we can verify a download succeeded by checking if
;;; the result is a zip file which represents a more-or-less valid
;;; GTFS feed.
;;;
;;; If not we should probably punt with an information message to the
;;; user, since we may simply be using the wrong URL or the file may be
;;; corrupt.

(defn download-agent-next-state [state]
  (cond
   ;; we're done, nothing more to do.
   (download-agent-completed? state) state

   ;; we have data? try and save it to a file.
   (:data state) (do (send-off *agent* download-agent-next-state)
                     (download-agent-save-file state))
   
   ;; we just started, try a download.
   (:download-attempt state) (do (send-off *agent* download-agent-next-state)
                                 (download-agent-attempt-download state))))

;; Store all download files in a cache data structure. The cache
;; is (TODO) populated on startup by reading a cache directory,
;; modified by clients sending actions, and can be observed by a
;; cache-notifier.
;;
;; --> Cache entries which already existed on startup will be given an
;; "already had it" state, the same as if we had started a download
;; agent for a file and it turned out we already had the latest
;; version in the cache.
;;
;;
;; Either files have already been downloaded, or they should be
;; downloaded in the future (in which case we start a download-agent).
;; Old files can be expired when new valid data is available.
;;
;; We can report errors if files we are trying to cache cannot
;; be downloaded, or if they are corrupt.
;;
;; Consumers will request files to be added to the cache (for
;; instance, files from a set of feeds which have been modified since
;; May 1st), by sending an action to the cache-manager.  Then we will
;; modify the cache structure such that agents are created for each
;; file, and start the agents.
;;
;; When the agents complete with success, complete with error, or
;; hang and we time them out (= error state), a cache-notifier can
;; we notify the client of the result using a promise.
;;
;; Each download agent should be responsible for updating its own
;; status. If an agent completes in error (or we cancel it), it is our
;; responsibility to reset all its data to a known-good state (for
;; instance, if there was a half-written file we should make sure to
;; erase it so it doesn't accidentally get used the next time we scan
;; for new cache files.
;;
;; Clients can wait on results by starting a cache-notifier for the
;; set of items the consumer needs.  The cache-notifier will deliver a
;; promise when either the cache is ready to use, or there was a
;; failure downloading some of the files which were requested. It does
;; this by registering a watch function on the download agent for each
;; file it is waiting for.
;;
;; TODO: enforce some constraints:
;;  (1) there can only be one running download-agent for any given URL,
;;      so that downloads don't compete and clobber each other.
;;      
;;  (2) similar idea, but multiple running cache managers shouldn't
;;      share the same cache directory. this would be most likely to
;;      happen if we accidentally started more than one copy of the
;;      application.

(defonce cache-manager
  (agent []))

;;; for debugging
(defn !reset-cache-manager! []
  (def cache-manager (agent [])))

;;; This could be called "refresh feed" or "fetch feed"? Since, the
;;; cache could already have an older copy of the feed; in fact the
;;; older copy may still be current.
;;;
;;; usage: (send-off cache-manager fetch-feed! feed)
(defn fetch-feed! [manager feed]
  (let [d (feed->download-agent feed)]
    (send-off d download-agent-next-state)
    (conj manager
          d)))

(defn !fetch-test-feeds! []
  (doseq [f (feed-last-updates)]
    (send-off cache-manager fetch-feed! f)))

;; For instance
;; (!fetch-fresh-feeds! #inst "2012")
(defn !fetch-fresh-feeds! [date]
  (doseq [f (fresh-feeds (feed-last-updates)
                         date)]
    (send-off cache-manager fetch-feed! f)))

(defn show-cache-manager-info []
  (doseq [a @cache-manager]
    (let [a (deref a)]
      (println "")
      (doseq [k (keys a)]
        (if (= k :data)
          (println "has data of size: " (count (:data a)))
          (println k (k a)))))))


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

(defn feed-succeeded-after-date?
  [feed-name refresh-date download-agents] 
  (some (every-pred (partial download-agent-has-feed-name? feed-name)
                    download-agent-success?
                    (partial download-agent-completed-after? refresh-date))
        (map deref download-agents)))

(defn all-feeds-succeeded-after-date?
  [feed-names refresh-date download-agents]
  (every? (fn [feed-name]
            (feed-succeeded-after-date? feed-name refresh-date download-agents) )
          feed-names))


;; Keep polling the cache-manager until all feeds have been freshly
;; downloaded on or after the freshness-date.  Returns a list of
;; download agents with completed downloads of all feeds, or throws an
;; error.
;;
;; Note that download-agents is a *copy* of the cache-manager at a given
;; point in time -- since new agents will not be added while we are checking,
;; it is easier to guarantee the process is determanistic and completes.
(defn wait-for-fresh-feeds! [feeds freshness-date cache]
  (let [names (into #{} (feed-names feeds))
        agent-we-care-about? (fn [download-agent] ;; on our list of feed-names?
                               (names (:feed-name @download-agent)))
        agents->names (fn [agents]
                        (into #{} (map (comp :feed-name deref)
                                       agents)))
        give-up-time (java.util.Date. (+ (* 1000 60) ;; 60 seconds.
                                         (.getTime (now))))]
    ;; should we track feed names, agents, or feeds? I think names.
    (loop [in-progress-feeds names
           ;; wait until we know the full list of failed feeds before returning.
           error-feeds nil 
           finished-download-agents nil]
      (let [download-agents (filter agent-we-care-about? @cache)
            any-agents-still-running (some (comp download-agent-still-running? deref)
                                           download-agents )
            fresh-completed-agents (filter (comp (partial download-agent-completed-after? freshness-date)
                                                 deref)
                                           download-agents)
            completed-feed-names (agents->names fresh-completed-agents)
            fresh-successful-agents (filter (comp download-agent-success? deref)
                                            fresh-completed-agents)
            successful-feed-names (agents->names fresh-successful-agents)
            not-successful-feed-names (clojure.set/difference names successful-feed-names)
            ;;successful-agents ** ;; find exactly one successful finished agent for each feed name
            ]
        (when (.after (now) give-up-time)
          (throw (IllegalStateException. (str "Time out."))))
        (when-not any-agents-still-running
          (if (empty? not-successful-feed-names)
            finished-download-agents
            (throw (IllegalStateException. (str "These feeds have not succeeded: "
                                                not-successful-feed-names)))))
        (Thread/sleep 1000)
        (println "any agents still running?")
        (pprint any-agents-still-running) 
        (println "successful agents:")
        (pprint successful-feed-names) 
        (println "not yet successful agents:")
        (pprint not-successful-feed-names) 
        (recur (clojure.set/difference names completed-feed-names) ;; in-progress-feeds
               error-feeds
               :finished-feeds
               )))))

;; returns a list of download agents with completed downloads of all feeds,
;; or throws an error.
(defn fetch-feeds! [feeds]
  (let [start-time (now)]
    (do 
      (doseq [f feeds]
        (send-off cache-manager fetch-feed! f))
      (wait-for-fresh-feeds! feeds start-time cache-manager))))

(defn build-public-feed-archive! []
  (io! "writes a zip file"
       (let [feeds (public-gtfs-feeds)
             names (feed-names feeds)
             archive-name "Oregon-GTFS-feeds"
             output-file-name (str "/tmp/gtfs-archive-output/" archive-name ".zip") ]
         (try (mkdir-p (dirname output-file-name))
              (println "gathering feed archives.")
              (fetch-feeds! feeds)
              (println "creating zip file.")
              (make-zip-file output-file-name [[(str archive-name "/hello.txt")  "hello, world!\n"]])
              (catch Exception e
                (doall (map println ["Error while building a feed archive:"
                                     e 
                                     "TODO: figure out the cause, then pass this"
                                     "error up to the User and ask them what to do."]) ))))))

(defn all-feeds-succeeded-example "example for testing. run (!fetch-all-feeds!) first."
  []
  (println "output should be: true, false")
  (pprint (all-feeds-succeeded-after-date? '("sample" "broken")
                                           #inst "2012"
                                           @cache-manager)) 
  (pprint (all-feeds-succeeded-after-date? '("sample" "broken" "error")
                                           #inst "2012"
                                           @cache-manager)))

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


