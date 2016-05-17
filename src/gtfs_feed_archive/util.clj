(ns gtfs-feed-archive.util
  (:refer-clojure :exclude [format])   ;; I like cl-format better...
  (:require [clj-http.client :as http] ;; docs at https://github.com/dakrone/clj-http
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal spy with-log-level)]
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


(defmacro try-catch-nil
  "Evaluate form, or return nil if there was an exception."
  [form]
  `(try ~form
        (catch Exception ignored-exception# nil)))

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

(defn copy-binary-stream "Buffered copy of in-stream to out-stream."
  [^java.io.InputStream in ^java.io.OutputStream out]
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

(defn copy-data-to-stream
  "copy src (which can be an input stream, byte array, or a string) to
  the output-stream dst."
  [src ^java.io.OutputStream dst]
  (condp #(isa? %2 %1) (type src)
    java.io.InputStream (copy-binary-stream src dst)
    java.lang.String (.write dst (string->bytes src))
    (Class/forName "[B") (.write dst src)))

(defn inst->rfc3339-day "RFC 3339 format, with year, month, and day only."
  [^java.util.Date inst]
  (if (nil? inst)
    nil
    (let [formatter (clj-time.format/formatters :date)
          time (clj-time.coerce/from-date inst)]
      (str (clj-time.format/unparse formatter time) "Z"))))

(defn inst->rfc3339-utc "Convert inst into RFC 3339 format."
  [inst]
  (if (nil? inst)
    nil
    (let [formatter (clj-time.format/formatters :date-hour-minute-second)
          time (clj-time.coerce/from-date inst)]
      (str (clj-time.format/unparse formatter time) "Z"))))

(defn now "We're looking at now, now. This is now."
  [] (java.util.Date.))

(defn dirname "directory component of path"
  [path] (.getParent (clojure.java.io/file path)))

(defn basename "file component of path"
  [path] (.getName (clojure.java.io/file path)))

(defn mkdir-p "make directory & create parent directories as needed"
  [path] (.mkdirs (clojure.java.io/file path)))

(defn- clj-ftp-list "find the FTP list object for url."
  [url]
  (let [u (http/parse-url url)
        host (:server-name u)
        port (:server-port u)
        directory (dirname (:uri u))
        file-name (basename (:uri u))
        user-info (or (:user-info u) "anonymous")]
    (ftp/with-ftp
      [client (str "ftp://" 
                   user-info "@"
                   host "/" directory)]
      (first (filter (fn [ftp-file]
                       (= file-name (.getName ftp-file)))
                     (ftp/client-FTPFiles-all client))))))

(defn- clj-ftp-file-data
  "Download an FTP url, and return its data as an InputStream."
  [url]
  (let [u (http/parse-url url)
        host (:server-name u)
        port (:server-port u)
        directory (dirname (:uri u))
        file-name (basename (:uri u))
        user-info (or (:user-info u) "anonymous")]
    (ftp/with-ftp
      [client (str "ftp://" 
                   user-info "@"
                   host "/" directory)]
      ;; Supposedly we should run (ftp/client-complete-pending-command
      ;; client) after using this stream. Maybe we can make a macro
      ;; similar to with-open which accomplishes this? Or copy the
      ;; whole darn thing into an InputStream representing a tempory file,  
      ;; cleanup the ftp connection, and return the copied stream.
      ;;
      ;; See http://alvinalexander.com/java/java-temporary-files-create-delete
      (ftp/client-set-file-type client :binary) ;; no ASCII conversion.
      (ftp/client-get-stream client file-name))))

(defn ftp-url-last-modifed [url]
  (try-catch-nil
    (let [ftp-file-listing (clj-ftp-list url)]
      ;; .getTime converts from GregorianCalendar object to a Date.
      ;; Since, of course, Java has both and they're incompatible.
        (.getTime (.getTimestamp ftp-file-listing)))))

(defn ftp-url-data "Download an InputStream for the data at url, or nil if there is an error."
  [url]
  (try-catch-nil
    (let [ftp-file-listing (clj-ftp-list url)]
        (.getTimestamp ftp-file-listing))))

(defn http-last-modified-header
  "Return the last-modified HTTP header as a java.util.Date, or throw an exception."
  [response]
  (java.util.Date. (get-in response [:headers "last-modified"])))

(defn http-or-ftp-get
  "Find the data and last-modified time of an FTP or HTTP url."
  [url] 
  (let [url-parts (try-catch-nil (http/parse-url url))
        scheme (:scheme url-parts)]
    (condp = scheme
     :ftp (try
            ;; grab via FTP, and return a similar hash-map to http/get.
            (let [last-modified (ftp-url-last-modifed url)
                  data (clj-ftp-file-data url)]
              (if (and last-modified data)
                {:body data
                 :last-modified last-modified}
                nil))
            (catch Exception _ nil))
     :http (try
             ;; http/get with the { :as :byte-array } option avoids text
             ;; conversion, which would corrupt our zip file.
             (let [response (http/get url
                                      {:as :byte-array
                                       :force-redirects true})]
               {:body (:body response)
                :last-modified (try-catch-nil (http-last-modified-header response))})
             (catch Exception _ nil)))))

(defn http-page-last-modified [url]
  (or (try-catch-nil 
        ;; NOTE: HEAD with force-redirects doesn't return the
        ;; modification-time with all servers it seems. That's why we
        ;; fall back to a GET request.
        (http-last-modified-header (http/head url
                                  {:force-redirects true})))
      (try-catch-nil
        (http-last-modified-header (http/get url
                                 {;; Try to avoid downloading entire
                                  ;; file, since we only care about
                                  ;; the headers.
                                  :as :stream
                                  :force-redirects true})))))

;; Works for HTTP or FTP URLs.
(defn page-last-modified [url]
  (let [scheme (:scheme (http/parse-url url))]
    (condp = scheme
      :ftp (ftp-url-last-modifed url)
      :http (http-page-last-modified url))))

(defn page-data "http/get example"
  [url]
  (try-catch-nil
    (-> (http/get url {:as :byte-array})
        (get :body))))

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

(defn- remove-invalid-csv-entries 
  [csv-maps]
  ;; remove blank urls, for starters
  (remove #(clojure.string/blank? (:gtfs-zip-url %)) csv-maps))

(defn read-csv-file [filename]
  (with-open [r (clojure.java.io/reader filename)]
      (doall (-> (csv->maps r)
                 (remove-invalid-csv-entries)))))

;; contents can be a java.io.InputStream, in which case we'll loop and
;; copy everything from the stream into the zip file.
(defn make-zip-file
  [output-file-name names-contents]
  (with-open [z (java.util.zip.ZipOutputStream.
           (clojure.java.io/output-stream output-file-name))]
    (doseq [[name content] names-contents]
      (.putNextEntry z (java.util.zip.ZipEntry. name))
      (copy-data-to-stream content z))))


(defn download-agents->last-updates-csv [download-agents]
  ;; TODO: use the CSV file writer to ensure proper quoting so strange
  ;; names and URLs don't have a chance to break the CSV file.
  (let [header-str "zip_file_name,most_recent_update,feed_name,historical_download_url\r\n"]
    (reduce str header-str
            (for [a (map deref download-agents) ]
              (str (str "feeds/" (:feed-name a) "/" (:feed-name a) ".zip,")
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
    [(str "feeds/" (:feed-name a) "/" (:feed-name a) ".zip")
     (clojure.java.io/input-stream (:file-name a))]))

(defn download-agents->concated-csv
  [download-agents]
  (concat (for [a (map deref download-agents) ]
            (clojure.java.io/input-stream (:file-name a)))))

(defn prepend-path-to-file-list [path zip-file-list]
  (for [[name data] zip-file-list] 
    [(str path "/" name) data]))

