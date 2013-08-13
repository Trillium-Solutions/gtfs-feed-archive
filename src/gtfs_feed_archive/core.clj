(ns gtfs-feed-archive.core
  (:refer-clojure :exclude [format]) ;; I like cl-format better...
  (:require [clj-http.client :as http] ;; docs at https://github.com/dakrone/clj-http
            clojure.set
            [miner.ftp :as ftp]
            [gtfs-feed-archive.javadoc-helper :as javadoc-helper]) 
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
        java.lang.String (.write z (string->bytes content))
        (Class/forName "[B") (.write z content)))))

;; convenience function. long term we want to manage the input CSV
;; file(s) using a user setting for which URL to grab the CSV file from.
(defn public-gtfs-feeds [] 
  (let [public-feeds "./resources/oregon_public_gtfs_feeds.csv" ;; some download links are broken.
        public-feeds-working "./resources/oregon_public_gtfs_feeds.working.csv"]
    (with-open [r (clojure.java.io/reader public-feeds-working)]
      (doall (csv->maps r)))))

(defn last-modified [response]
  (-> response
      (get-in [:headers "last-modified"])
      java.util.Date.))

;; TODO: make a version which works for FTP URLs
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

(defn -main [] 'run-command-line-application)

(declare cache-has-a-fresh-enough-copy?)

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
      ;; http/get with the { :as :byte-array } option avoids text
      ;; conversion, which would corrupt our zip file.
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

      ;; poll the server to find the modification-time of the gtfs-zip-url
      ;; TODO: integrate this into our decision-making.
      (let [modification-time (page-last-modified (:url state))]
        (if (nil? modification-time) 
          ;; TODO: Increment download-attempt & return. What to do if
          ;; server simply doesn't support asking for the
          ;; modification-time?  Currently we would just bail here.
          (do (format t "I was not able to find the modification-time of ~A~%" (:url state))
              (assoc state :download-attempt ;; ok, we'll try again later.
                     (inc (:download-attempt state))))
          (if-let [fresh-copy (cache-has-a-fresh-enough-copy? (:feed-name state)
                                                              modification-time)] 
            (do (format t "Cache already contains a fresh-enough copy of ~A~%" (:feed-name state))
                (:file-name fresh-copy) 
                (-> state 
                    (dissoc :download-attempt)
                    (dissoc :destination-dir) 
                    ;; for debugging, so we know which are original download agents:
                    (assoc :im-just-a-copy true) 
                    (assoc :file-name (:file-name fresh-copy)) ;; copy the file name
                    (assoc :last-modified (:last-modified fresh-copy)) ;; and modification time
                    (assoc :completion-date (now))
                    (assoc :file-saved true)))
            (let [response (http-or-ftp-get (:url state))]
              (format t "Cache does not contain a fresh-enough copy of ~A, downloading.~%" (:feed-name state))
              (if (nil? response)
                (assoc state :download-attempt ;; ok, we'll try again later.
                       (inc (:download-attempt state)))
                (-> state 
                    (dissoc :download-attempt)
                    (assoc :last-modified (last-modified response))
                    (assoc :data (:body response)))))))))
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


(defn feed-already-has-running-download-agent? [feed-name manager]
  (some (every-pred download-agent-still-running?
                    (partial download-agent-has-feed-name? feed-name))
        (map deref manager)))

;;; This could be called "refresh feed" or "fetch feed"? Since, the
;;; cache could already have an older copy of the feed; in fact the
;;; older copy may still be current.
;;;
;;; usage: (send-off cache-manager fetch-feed! feed)
(defn fetch-feed! [manager feed]
  (let [d (feed->download-agent feed)
        feed-name (:feed-name feed)]
    ;; potential race condition here. I think its okay since the agent should always
    ;; move monotonically from running -> not running, and a false positive only
    ;; means we won't run another download immediately.
    (if (feed-already-has-running-download-agent? feed-name
                                                  manager)
      (do (format t "Download agent already running for ~A, not starting a new one.~%"
                  feed-name)
          manager)
      (do (send-off d download-agent-next-state)
          (conj manager d)))))

(defn show-cache-manager-info []
  (doseq [a @cache-manager]
    (let [a (deref a)]
      (println "")
      (doseq [k (keys a)]
        (if (= k :data)
          (println "has data of size: " (count (:data a)))
          (println k (k a)))))))


(defn clean-cache-example! "example cache cleaning code"
  [] 
  (send-off cache-manager
            (fn [manager]
              (remove (comp (partial download-agent-has-feed-name? "trimet-portland-or-us")
                            deref)
                      manager)))) 

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

(defn cache-has-a-fresh-enough-copy?  [feed-name modified-date]
  ;; If the modified-date is newer than the file in the cache, but by less
  ;; than refresh-interval, the file in the cache is fresh enough.
  ;;
  ;; If the modified-date is newer than the file in the cache, by more than
  ;; the refresh-interval, we should use the new copy.
  (let [download-agents @cache-manager
        refresh-interval (* 1000 60 60) ;; one hour
        cutoff (java.util.Date. (- (.getTime modified-date)
                                   refresh-interval))
        fresh-enough? (fn [date]
                        ;;(println "date:" date "modified-date" modified-date)
                        (.after date cutoff))]
    (first (filter (every-pred download-agent-success?
                               (comp fresh-enough? :last-modified)
                               (partial download-agent-has-feed-name? feed-name))
                   (map deref download-agents)))))


;; Keep polling the cache-manager until all feeds have been freshly
;; downloaded on or after the freshness-date.  Returns a list of
;; download agents with completed downloads of all feeds, or throws an
;; error.
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
    (loop []
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
            ;; These are feeds which have not succeeded YET.  As long
            ;; as agents are still running they may suceed in the
            ;; future!
            unsuccessful-feed-names (clojure.set/difference names successful-feed-names)
            some-broken-feeds (and (not any-agents-still-running)
                                   (seq unsuccessful-feed-names)) 
            all-feeds-ok (and (not any-agents-still-running)
                              (empty? unsuccessful-feed-names))
            too-late (.after (now) give-up-time)
            ;;successful-agents ** ;; find exactly one successful finished agent for each feed name
            ]
        (cond too-late (throw (IllegalStateException. (str "Time out."))) 
              some-broken-feeds (throw (IllegalStateException.
                                        (str "These feeds have not succeeded: "
                                             unsuccessful-feed-names)))        
              ;; TODO: filter so we return at most one agent per feed-name!!
              all-feeds-ok fresh-successful-agents
              :else (do (Thread/sleep 1000)
                        (println "all feeds OK:" all-feeds-ok)
                        (println "any agents still running?")
                        (pprint any-agents-still-running) 
                        (println "successful agents:")
                        (pprint successful-feed-names) 
                        (println "not yet successful agents:")
                        (pprint unsuccessful-feed-names) 
                        (recur)))))))

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





