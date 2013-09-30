(defproject gtfs-feed-archive "0.1.2"
  :description "GTFS Feed Archive Tool"
  :url "http://github.com/ed-g/gtfs-feed-archive"
  :license {:name "GNU GPL version 3 or newer"
            :url "http://www.gnu.org"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [clj-http "0.7.6"]  ;; HTTP client
                 [com.velisco/clj-ftp "0.3.0"] ;; FTP client
                 [clojure-csv/clojure-csv "2.0.1"] ;; CSV read/write
                 [clj-time "0.6.0"] ;; Sane date and time library.
                 [org.clojure/tools.cli "0.2.4"] ;; Command-line parsing.
                 [com.taoensso/timbre "2.6.2"] ;; logging
                 [compojure "1.1.5"]
                 [hiccup "1.0.4"]
                 [ring/ring-core "1.2.0"]
                 [ring/ring-jetty-adapter "1.2.0"]]
  :main gtfs-feed-archive.core
  :aot [gtfs-feed-archive.core])
