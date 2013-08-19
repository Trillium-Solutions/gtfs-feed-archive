(ns gtfs-feed-archive.download-agent
  (:refer-clojure :exclude [format]) ;; I like cl-format better.
  (:require [clojure.edn :as edn]
            [clj-http.client :as http] ;; docs at https://github.com/dakrone/clj-http
            )
  (:use gtfs-feed-archive.util
        clojure.test
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

;; Function of (feed-name modified-date), which tries to pull "good
;; enough" copy of the feed, from a cache.  Our caller binds this
;; dynamic variable for us. Yes it's a little ugly but it appears to
;; be the Clojure Way.
(def ^:dynamic close-enough-cache-hit? nil)

(defn feed->download-agent [feed]
  (agent {
          :url (:gtfs-zip-url feed)
          :download-attempt 0
          :feed-name (:feed-name feed)
          :feed-description (:feed-description feed) ; for debugging.
          :destination-dir "/tmp/gtfs-cache/"}))

(defn success? [state]
  "Has the file been saved?"
  (:file-saved state))

(defn failure? 
  "Was there a file save failure, or a download failure?
   In either case We're done, but the download didn't work." 
  [state]
  (or (:download-failed state)
      (:file-save-failed state)))

(defn completed? [state]
  "Has the download agent completed, either with success or failure??"
  ;; An alternative would be to check if there's a (:completion-date state)
  (or (success? state) 
      (failure? state)))

(def still-running?
  (complement completed?))

(defn completed-after?
  "Has a download agent completed on or after earliest-date?
   Could have completed with either a success or failure state."
  [earliest-date state]
  (and (completed? state)
       (when-let [d (:completion-date state)]
         (not (.before d earliest-date)))))

(defn has-feed-name? [feed-name state]
  (= (:feed-name state) feed-name))

(defn save-file [state]
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


(defn attempt-download [state]
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
          ;; Increment download-attempt & return. What to do if
          ;; server simply doesn't support asking for the
          ;; modification-time?  Currently we would just bail here.
          (do (format t "I was not able to find the modification-time of ~A~%" (:url state))
              (assoc state :download-attempt ;; OK, we'll try again later.
                     (inc (:download-attempt state))))
          (if-let [fresh-copy (close-enough-cache-hit?
                               (:feed-name state)         
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

(defn next-state [state]
  (cond
   ;; we're done, nothing more to do.
   (completed? state) state

   ;; we have data? try and save it to a file.
   (:data state) (do (send-off *agent* next-state)
                     (save-file state))
   
   ;; we just started, try a download.
   (:download-attempt state) (do (send-off *agent* next-state)
                                 (attempt-download state))))

