(defproject gtfs-feed-archive "0.1.9"
  :description "GTFS Feed Archive Tool"
  :url "http://github.com/ed-g/gtfs-feed-archive"
  :license {:name "GNU GPL version 3 or newer"
            :url "http://www.gnu.org"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [clj-http "1.0.0"]  ;; HTTP client
                 [com.velisco/clj-ftp "0.3.1"] ;; FTP client
                 [clojure-csv/clojure-csv "2.0.1"] ;; CSV read/write
                 [clj-time "0.8.0"] ;; Sane date and time library.
                 [org.clojure/tools.cli "0.3.1"] ;; Command-line parsing.
                 [com.taoensso/timbre "3.2.1"] ;; logging
                 [compojure "1.1.8"]
                 [hiccup "1.0.5"]
                 [ring/ring-core "1.3.1"]
                 [ring/ring-jetty-adapter "1.3.1"]
                 [org.clojure/tools.nrepl "0.2.4"]]
  :main gtfs-feed-archive.core
  :aot [gtfs-feed-archive.core])
