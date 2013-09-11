(ns gtfs-feed-archive.cache-manager
  (:refer-clojure :exclude [format]) ;; I like cl-format better.
  (:require [clojure.edn :as edn])
  (:require [gtfs-feed-archive.download-agent :as download-agent]
            [gtfs-feed-archive.cache-persistance :as cache-persistance])
  (:use gtfs-feed-archive.util
        clojure.test
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

;; todo: split all cache code off into its own namespace.
(declare cache-has-a-fresh-enough-copy?)

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

(def +cache-file+ "/tmp/gtfs-cache/cache.edn")
(def +cache-file-backup+ (str +cache-file+ ".1"))

(defonce cache-manager
  ;; TODO: verify that all files referenced in the cache exist.
  (or (cache-persistance/load-cache! +cache-file+)
      (agent [])))

(defn reload-cache-manager! "for debugging only."
  []
  (def cache-manager
    (or (cache-persistance/load-cache! +cache-file+)
        (agent []))))

(defn save-cache-manager! []
  (.renameTo (clojure.java.io/file +cache-file+) 
             (clojure.java.io/file +cache-file-backup+))
  (cache-persistance/save-cache! cache-manager +cache-file+))

;;; for debugging
(defn !reset-cache-manager! []
  (def cache-manager (agent [])))

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
    (first (filter (every-pred download-agent/success?
                               (comp fresh-enough? :last-modified)
                               (partial download-agent/has-feed-name? feed-name))
                   (map deref download-agents)))))

(defn feed-already-has-running-download-agent? [feed-name manager]
  (some (every-pred download-agent/still-running?
                    (partial download-agent/has-feed-name? feed-name))
        (map deref manager)))

;;; This could be called "refresh feed" or "fetch feed"? Since, the
;;; cache could already have an older copy of the feed; in fact the
;;; older copy may still be current.
;;;
;;; usage: (send-off cache-manager fetch-feed! feed)
(defn fetch-feed! [manager feed]
  (let [d (download-agent/feed->download-agent feed)
        feed-name (:feed-name feed)]
    ;; potential race condition here. I think its okay since the agent should always
    ;; move monotonically from running -> not running, and a false positive only
    ;; means we won't run another download immediately.
    (if (feed-already-has-running-download-agent? feed-name
                                                  manager)
      (do (format t "Download agent already running for ~A, not starting a new one.~%"
                  feed-name)
          manager)
      (do 
        (binding [download-agent/close-enough-cache-hit?
                  cache-has-a-fresh-enough-copy?]
          (send-off d download-agent/next-state)) 
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
              (remove (comp (partial download-agent/has-feed-name? "trimet-portland-or-us")
                            deref)
                      manager)))) 

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
            any-agents-still-running (some (comp download-agent/still-running? deref)
                                           download-agents )
            fresh-completed-agents (filter (comp (partial download-agent/completed-after? freshness-date)
                                                 deref)
                                           download-agents)
            completed-feed-names (agents->names fresh-completed-agents)
            fresh-successful-agents (filter (comp download-agent/success? deref)
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
      ;; FIXME: sometimes the wait-for-fresh-feeds! will return since
      ;; the initial check happens before any agents have begun
      ;; running.  is there a way to delay until at least the first
      ;; state has been reached?  or is there a better way to handle
      ;; this?
      ;; 
      ;; maybe we could first wait until any agents are running,
      ;; *then* wait until the feeds begin. actually we'd want to wait
      ;; until all the agents we care about are running, since it
      ;; would theoretically be possible for one agent to fail before
      ;; the others had even started. perhaps we should wait until we
      ;; can find at least one failed agent for each feed before
      ;; giving up.
      (Thread/sleep 5000) ;; HACK HACK HACK HACK HACK HACK HACK HACK
      (wait-for-fresh-feeds! feeds start-time cache-manager))))


