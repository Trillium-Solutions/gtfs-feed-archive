(ns gtfs-feed-archive.core
  (:refer-clojure :exclude [format]) ;; I like cl-format better...
  (:require [clj-http.client :as http])
  (:use clojure.test
        clojure-csv.core
        [clj-http.client :rename {get http-get}]
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format} ]))

(def example-csv-config-text "feed_name,feed_description,feed_url
sample,Google example feed,http://localhost/gtfs-examples/sample-feed/sample-feed.zip
broken,testing feed with intentionally broken data,http://localhost/gtfs-examples/broken-feed/gtfs.zip
kingcounty,King County Seattle Metro,http://localhost/gtfs-examples/kingcounty/kingcounty-archiver_20130206_0431.zip
error,broken link to test file parsing,http://
mendocino,Mendocino County CA,http://localhost/gtfs-examples/mendocino-transit-authority/mendocino-transit-authority_20121230_0426.zip
trimet,Tri-Met: Portland Metro,http://developer.trimet.org/schedule/gtfs.zip
cherriots,Cherriots: Salem-Kaiser,http://www.cherriots.org/developer/gtfs.zip")

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

(defn page-last-modified [url]
  (try 
    (-> (http/head url)
        (get-in [:headers "last-modified"])
        java.util.Date.)
    (catch Exception _ nil)))

(defn test-parse-config []
  (map (juxt :feed-name
             (comp page-last-modified :feed-url)) 
       (example-csv-config)))

(defn -main [] (test-parse-config))
