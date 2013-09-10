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

(defn copy-data-to-stream
  "copy src (which can be an input stream, byte array, or a string) to
  the output-stream dst."
  [src dst]
  (condp isa? (type src)
    org.apache.commons.net.io.SocketInputStream  (copy-binary-stream src dst)
    java.io.InputStream (copy-binary-stream src dst)
    java.io.BufferedInputStream (copy-binary-stream src dst)
    java.lang.String (.write dst (string->bytes src))
    (Class/forName "[B") (.write dst src)))

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

(defn basename "file component of path" [path]
  (.getName (clojure.java.io/file path)))

(defn mkdir-p "make directory & create parent directories as needed" [path]
  (.mkdirs (clojure.java.io/file path)))

;; example using clj-ftp.  
(defn- clj-ftp-list [url]
  (let [u (http/parse-url url)
        host (:server-name u)
        port (:server-port u)
        directory (dirname (:uri u))
        file-name (basename (:uri u))
        user-info (or (:user-info u)
                      "anonymous")]
    (ftp/with-ftp
      [client (str "ftp://" 
                   user-info "@"
                   host "/" directory)]
      (first (filter (fn [ftp-file]
                       (= file-name (.getName ftp-file)))
                     (ftp/client-FTPFiles-all client))))))

(defn- clj-ftp-file-data [url]
  (let [u (http/parse-url url)
        host (:server-name u)
        port (:server-port u)
        directory (dirname (:uri u))
        file-name (basename (:uri u))
        user-info (or (:user-info u)
                      "anonymous")]
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
  (try 
    (let [ftp-file-listing (clj-ftp-list url)]
      ;; .getTime converts from GregorianCalendar object to a Date.
      ;; Since, of course, Java has both and they're incompatible.
        (.getTime (.getTimestamp ftp-file-listing)))
    (catch Exception e nil)))

(defn ftp-url-data [url]
  ;; simple wrapper which ignores errors -> nil
  (try 
    (let [ftp-file-listing (clj-ftp-list url)]
        (.getTimestamp ftp-file-listing))
    (catch Exception e nil)))

(defn http-last-modified-header [response]
  (-> response
      (get-in [:headers "last-modified"])
      java.util.Date.))

;; how can we pull out a :last-modified & :data from ftp connection??
;; fake the results to make them look like the HTTP api.
;; use the get-as-stream function in clj-ftp
(defn http-or-ftp-get [url]
  (let [url-parts (try (http/parse-url url)
                       (catch Exception e nil))
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
                :last-modified (try (http-last-modified-header response)
                                    (catch Exception _ nil))})
             (catch Exception _ nil)))))

(defn http-page-last-modified [url]
  (or (try 
        ;; NOTE: HEAD with force-redirects doesn't return the
        ;; modification-time with all servers it seems. That's why we
        ;; fall back to a GET request.
        (http-last-modified-header (http/head url
                                  {:force-redirects true}))
        (catch Exception _ nil))
      (try 
        (http-last-modified-header (http/get url
                                 {;; Try to avoid downloading entire
                                  ;; file, since we only care about
                                  ;; the headers.
                                  :as :stream
                                  :force-redirects true}))
        (catch Exception _ nil))))

;; Works for HTTP or FTP URLs.
(defn page-last-modified [url]
  (let [scheme (:scheme (http/parse-url url))]
    (condp = scheme
      :ftp (ftp-url-last-modifed url)
      :http (http-page-last-modified url))))

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

