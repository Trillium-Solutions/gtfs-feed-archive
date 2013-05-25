(ns gtfs-feed-archive.core
  (:refer-clojure :exclude [format]) ;; I like cl-format better...
  (:require [clj-http.client :as http]) ;; docs at https://github.com/dakrone/clj-http
  (:use clojure.test
        clojure-csv.core
        [clj-http.client :rename {get http-get}]
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

;; for development -- set local documentation source for javadoc command.
(dosync (ref-set clojure.java.javadoc/*local-javadocs*
                 ["/usr/share/doc/openjdk-6-doc/api"]))

(def example-csv-config-text "feed_name,feed_description,feed_url
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

(defn methods-of-java-object [obj]
  (-> obj (class) (.getDeclaredMethods) (seq)))

(defn byte-example []
  (seq (byte-array (map byte "foo\n"))))

(defn make-example-zip-file []
  (let [z (java.util.zip.ZipOutputStream.
           (clojure.java.io/output-stream "/tmp/foo.zip"))]
    (doto z
      (.putNextEntry (java.util.zip.ZipEntry. "foo/foo.txt"))
      (.write (byte-array (map byte "foo\n")))
      (.putNextEntry (java.util.zip.ZipEntry. "foo/bar.txt"))
      (.write (byte-array (map byte "bar\n")))
      (.close))))

(defn csv->maps
  "Turn a CSV string (with headers) into a list of maps from header->data."
  [string]
  (let [csv (parse-csv string)
        header (map keyword-with-dashes (first csv))
        data (rest csv)]
    (map (partial zipmap header)
         data)))

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
                   ((comp page-last-modified :feed-url) e)))
          csv-config)))

;;; for instance:
;;; (error-feeds (feed-last-updates (example-csv-config))) 
;;;  => ({:last-update nil, :feed-name "error"})
(defn error-feeds [feed-updates]
  (filter #(nil? (:last-update %))
          feed-updates))

;; for instance:
;; (fresh-feeds (feed-last-updates) #inst "2013-01-01")
(defn fresh-feeds [feed-updates date] ;; date is java.lang.Date
  (filter (every-pred #(not (nil? (:last-update %)))
                      #(.after (:last-update %) date))
          feed-updates))

;;(def feed-data)

(defn -main [] (feed-last-updates))

(defn inst->rfc3339 [inst]
  ;; funny that there's no function to do this already?
  (if (nil? inst)
    nil
    (.substring (pr-str inst) 7 26)))

(defn feed->download-agent [feed]
  (agent {:url (:feed-url feed) :download-attempt 0
          :destination-file (str "/tmp/gtfs-cache/"
                                 (:feed-name feed) "/"
                                 (inst->rfc3339 (:last-update feed))
                                 ".zip")}))

(defn dirname [path]
  (.getParent (clojure.java.io/file path)))

(defn mkdir-p [path]
  (.mkdirs (clojure.java.io/file path)))


(defn download-agent-save-file [state]
  (let [file (:destination-file state)
        data (:data state)]
    (try (mkdir-p (dirname file))
         (println "type of data is " (type data))
         ;; the Java output-stream is for writing binary data.
         (with-open [w (clojure.java.io/output-stream file)]
           (.write w data))
         (-> state
             (dissoc :data)
             (assoc :file-saved true))
         (catch Exception _
           (-> state
               ;; hmm, what is the proper course of action should a
               ;; file save fail? retrying probably won't help since
               ;; odds are the disk is full or its permissions or
               ;; configuration problem. we should probably log an
               ;; error and give up.
               (dissoc :data)
               (assoc :file-save-failed true))))))

(defn download-agent-attempt-download [state]
  (if (< (:download-attempt state) 5)
    (do
      ;; exponential back-off delay, 2**download-attempt seconds.
      (Thread/sleep (* 1000 (Math/pow 2 (:download-attempt state))))
      ;; http/get with the { :as :byte-array } option avoids text
      ;; conversion, which would corrupt our zip file.
      (let [response (try
                       (http/get (:url state)
                                 {:as :byte-array})
                       (catch Exception _ nil))]
        (if (nil? response)
          (assoc state :download-attempt ;; ok, we'll try again later.
                 (inc (:download-attempt state)))
          (-> state 
              (dissoc :download-attempt)
              (assoc :last-modified (last-modified response))
              (assoc :data (:body response))))))
    (-> state ;; too many attempts -- give up.
        (dissoc :download-attempt)
        (assoc :download-failed true))))

;; for testing
(defn make-agents! []
  (def *agents (map feed->download-agent 
                    (feed-last-updates) )))
;;(fresh-feeds (feed-last-updates) #inst "2012")


(defn show-agent-info []
  (doseq [a *agents]
    (let [a (deref a)]
      (println "")
      (doseq [k (keys a)]
        (if (= k :data)
          (println "has data of size: " (count (:data a)))
          (println k (k a)))))))

(defn run-agents! []
  (doseq [a *agents]
    (send-off a download-agent-next-state)))

(defn download-agent-next-state [state]
  (cond
   ;; file has been saved?
   ;; we're done, and the download succeeded.
   (:file-saved state) state
   ;; file save failure, or download failure?
   ;; we're done, but the download didn't work.
   (or (:download-failed state)
       (:file-save-failed state))  state
      (:file-save-failed state) state
   ;; we have data? try and save it to a file.
   (:data state) (do (println 'next-state)
                     (send-off *agent* download-agent-next-state)
                     (println 'save)
                     (download-agent-save-file state)) 
   ;; we just started, OK try a download.
   (:download-attempt state) (do (println 'next-state)
                                 (send-off *agent* download-agent-next-state)
                                 (println 'download)
                                 (download-agent-attempt-download state))))