(defproject gtfs-feed-archive "0.0.1"
  :description "GTFS Feed Archive Tool"
  :url "http://github.com/ed-g"
  :license {:name "GNU GPL version 3 or newer"
            :url "http://www.gnu.org"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [clj-http "0.7.6"]  ;; HTTP client
                 [com.velisco/clj-ftp "0.3.0"] ;; FTP client
                 [clojure-csv/clojure-csv "2.0.0-alpha1"] ;; CSV read/write
                 ]
  :main gtfs-feed-archive.core)
