(defproject gtfs-feed-archive "0.2.0.2"
  :description "GTFS Feed Archive Tool"
  :url "http://github.com/ed-g/gtfs-feed-archive"
  :license {:name "GNU GPL version 3 or newer"
            :url "http://www.gnu.org"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [environ "1.0.0"] ;; for environment variables
                 [clj-http "1.0.0"]  ;; HTTP client
                 [com.velisco/clj-ftp "0.3.1"] ;; FTP client
                 [clojure-csv/clojure-csv "2.0.1"] ;; CSV read/write
                 [clj-time "0.8.0"] ;; Sane date and time library.
                 [org.clojure/tools.cli "0.3.1"] ;; Command-line parsing.
                 [com.taoensso/timbre "3.2.1"] ;; logging
                 [compojure "1.4.0"]
                 [hiccup "1.0.5"]
                 [cheshire "5.5.0"]
                 [ring/ring-core "1.4.0"]
                 [ring/ring-jetty-adapter "1.4.0"]
                 [org.clojure/tools.nrepl "0.2.12"]]
  :main gtfs-feed-archive.core
  :aot [gtfs-feed-archive.core])
