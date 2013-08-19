(ns gtfs-feed-archive.util
  (:refer-clojure :exclude [format])   ;; I like cl-format better...
  (:require [clj-http.client :as http] ;; docs at https://github.com/dakrone/clj-http
            clojure.set
            [miner.ftp :as ftp]
            clj-time.coerce
            clj-time.format
            clj-time.core)
  (:use clojure.test
        clojure-csv.core
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

;; handy utility functions.

(def t true);; for cl-format.

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
        ;;(format true "nread: ~a~%" nread)
        (when (pos? nread)
          (.write out buffer 0 nread)
          (recur))))))

(defn copy-binary-file "Example code for binary file copy."
  [in-file out-file]
  (with-open [in (clojure.java.io/input-stream in-file)
              out (clojure.java.io/output-stream out-file)]
    (copy-binary-stream in out)))

(defn file->bytes [input-file-name]
  (clojure.java.io/input-stream input-file-name))

(defn inst->rfc3339-day*old
  "Convert inst into RFC 3339 format, then pull out the year, month, and day only."
  [inst]
  ;; funny that there's no function to do this already?
  (if (nil? inst)
    nil
    (.substring (pr-str inst) 7 26)))

(defn inst->rfc3339-day
  "Convert inst into RFC 3339 format, then pull out the year, month, and day only."
  [inst]
  ;; funny that there's no function to do this already?
  ()
  (if (nil? inst)
    nil
    (let [formatter (clj-time.format/formatters :date)
          time (clj-time.coerce/from-date inst)]
      (str (clj-time.format/unparse formatter time)
           "Z"))))

(defn inst->rfc3339-utc
  "Convert inst into RFC 3339 format."
  [inst]
  ;; funny that there's no function to do this already?
  ()
  (if (nil? inst)
    nil
    (let [formatter (clj-time.format/formatters :date-hour-minute-second)
          time (clj-time.coerce/from-date inst)]
      (str (clj-time.format/unparse formatter time)
           "Z"))))

(defn now 
  "We're looking at now, now. This is now."
  [] (java.util.Date.))

(defn dirname "directory component of path" [path]
  (.getParent (clojure.java.io/file path)))

(defn mkdir-p "make directory & create parent directories as needed" [path]
  (.mkdirs (clojure.java.io/file path)))

;; how can we pull out a :last-modified & :data from ftp connection??
;; fake the results to make them look like the HTTP api.
(defn http-or-ftp-get [url]
  (if (re-matches #"[Ff][Tt][Pp]://" url)
    nil ;; TODO: grab via FTP
    (try
      ;; http/get with the { :as :byte-array } option avoids text
      ;; conversion, which would corrupt our zip file.
      (http/get url
                {:as :byte-array
                 :force-redirects true})
      (catch Exception _ nil))))


(defn last-modified [response]
  (-> response
      (get-in [:headers "last-modified"])
      java.util.Date.))


;; TODO: make a version which works for FTP URLs.  May need to search
;; for pure Java FTP library that supports the LIST command better.
(defn page-last-modified [url]
  (or (try 
        ;; NOTE: HEAD with force-redirects doesn't return the
        ;; modification-time with all servers it seems. That's why we
        ;; fall back to a GET request.
        (last-modified (http/head url
                                  {:force-redirects true}))
        (catch Exception _ nil))
      (try 
        (last-modified (http/get url
                                 {;; Try to avoid downloading entire
                                  ;; file, since we only care about
                                  ;; the headers.
                                  :as :stream
                                  :force-redirects true}))
        (catch Exception _ nil))))

(defn page-data "http/get example"
  [url]
  (try 
    (-> (http/get url {:as :byte-array})
        (get :body))
    (catch Exception _ nil)))

(defn page-size "size of data at URL" [url]
  (count (page-data url)))

;; for instance: (feed-names (public-gtfs-feeds))
(defn feed-names [feeds]
  (map :feed-name feeds))

;; for instance:
;; (fresh-feeds (feed-last-updates) #inst "2013-01-01")
(defn fresh-feeds [feed-updates date] ;; date is java.lang.Date
  (filter (every-pred #(not (nil? (:last-update %)))
                      #(.after (:last-update %) date))
          feed-updates))



(defn csv->maps
  "Turn a CSV string (with headers) into a list of maps from header->data."
  [string]
  (let [csv (parse-csv string)
        header (map keyword-with-dashes (first csv))
        data (rest csv)]
    (map (partial zipmap header)
         data)))

