(defproject gtfs-feed-archive "0.1.0"
  :description "GTFS Feed Archive Tool"
  :url "http://github.com/ed-g/gtfs-feed-archive"
  :license {:name "GNU GPL version 3 or newer"
            :url "http://www.gnu.org"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [clj-http "0.7.6"]  ;; HTTP client
                 [com.velisco/clj-ftp "0.3.0"] ;; FTP client
                 [clojure-csv/clojure-csv "2.0.0-alpha1"] ;; CSV read/write
                 [clj-time "0.6.0"] ;; Sane date and time library.
                 [org.clojure/tools.cli "0.2.4"] ;; Command-line parsing.
                 ]
  :main gtfs-feed-archive.core
  :aot [gtfs-feed-archive.core])
