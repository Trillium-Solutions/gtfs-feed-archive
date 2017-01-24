(defproject gtfs-feed-archive "0.2.0.4"
  :description "GTFS Feed Archive Tool"
  :url "http://github.com/ed-g/gtfs-feed-archive"
  :jvm-opts ["-Djsse.enableSNIExtension=false"] ;; fix ssl handshake alert
  :license {:name "GNU GPL version 3 or newer"
            :url "http://www.gnu.org"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [environ "1.1.0"] ;; for environment variables
                 [clj-http "3.4.1"]  ;; HTTP client
                 [com.velisco/clj-ftp "0.3.8"] ;; FTP client
                 [clojure-csv/clojure-csv "2.0.2"] ;; CSV read/write
                 [clj-time "0.13.0"] ;; Sane date and time library.
                 [org.clojure/tools.cli "0.3.5"] ;; Command-line parsing.
                 [com.taoensso/timbre "4.8.0"] ;; logging
                 [compojure "1.5.2"]
                 [hiccup "1.0.5"]
                 [cheshire "5.7.0"]
                 [ring/ring-core "1.5.1"]
                 [ring/ring-jetty-adapter "1.5.1"]
                 [org.clojure/tools.nrepl "0.2.12"]]
  :main gtfs-feed-archive.core
  :aot [gtfs-feed-archive.core])
