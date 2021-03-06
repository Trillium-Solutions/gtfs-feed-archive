(ns gtfs-feed-archive.command-line
  (:refer-clojure :exclude [format])   ;; I like cl-format better...
  (:require clojure.set
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal spy with-log-level)])
  (:use gtfs-feed-archive.util 
        clojure.test
        [clojure.tools.cli :only [cli]] ;; Command-line parsing.
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

(defn parse-args-or-die! [& args]
  (declare parse-date)
  (letfn [(set-merge [previous key val]
            (assoc previous key
                   (if-let [oldval (get previous key)]
                     (merge oldval val)
                     (hash-set val))))
          (cli-format []
            ["Create a GTFS feed archive. http://github.com/ed-g/gtfs-feed-archive"
             ["-o" "--output-directory" "Directory to place output zip files into."]
             ["-i" "--input-csv" "CSV feed list file." :assoc-fn set-merge]
             ["-c" "--cache-directory" 
              "Cache directory for GTFS feed downloads."
              :default "/tmp/gtfs-cache/"]
             ["-n" "--archive-name-prefix" "File name prefix for archives"
              :default "Oregon-GTFS"]
             ["-u" "--update"
              "Fetch updated feeds from the Internet and store them in the cache."
              :default false :flag true]
             ["-r" "--run-server"
              "Run the built-in web server on http://localhost:server-port"
              :default false :flag true]
             ["-p" "--server-port"
              "Which port should the built-in web server listen on?"
              :default 8081
              :parse-fn #(Integer/parseInt %)
              :validate [#(< 1 % 65535)
                         "Please use a port between 1 and 65535."]]
             ["-D" "--download-url"
              "What is the public URL for downloading archives?"
              ;; if none, we default to the URL of the built-in web server.
              :default nil]
             ["-f" "--freshness-hours"
              "How many hours old can a cache item be, and still be considered fresh?"
              :default 24.0 ;; default to within the last day.
              :parse-fn #(Float/parseFloat %)
              :validate [#(< 0 %)
                         "Please use a freshness greater than zero (hours)."]]
             ["-s" "--since-date"
              "Create an archive of feeds modified after date, e.g. 2013-08-23."
              :parse-fn parse-date :assoc-fn set-merge]
             ["-a" "--all"
              "Make an archive of all feeds." :default false :flag true]
             ["-N" "--nrepl-port"
              "If defined, start a network REPL listening on this port."
              :default nil
              :parse-fn #(Integer/parseInt %)
              :validate [#(< 1 % 65535)
                         "Please use a port between 1 and 65535."]]
             ])
          (print-usage-and-die! [malfunction]
            (let [[_ _ usage-text] (apply cli nil (cli-format))]
              (format t "~a~%~a~%" usage-text malfunction)
              (System/exit 1)))
          (parse-date [arg] (try
                              (let [d (clj-time.format/parse-local (clj-time.format/formatters :date) arg)]
                                (.toDate d))
                              (catch Exception e (print-usage-and-die!
                                                  "Please format dates as RFC 3339: YYYY-MM-DD."))))]
    (let [[options plain-args _]
          (try (apply cli args (cli-format))
               (catch Exception e (do (error "Error parsing command line: " e)
                                      (print-usage-and-die! "Cannot parse command line."))))] 
      ;; See if we have all the information we need to create an archive.
      ;; If not, print usage information and bail.
      (when-not (pos? (count (:input-csv options)))
        (print-usage-and-die! "Please supply at least one input CSV feed list file."))
      (when-not (pos? (count (:output-directory options)))
        (print-usage-and-die! "Please indicate an output directory in which to create zip files."))
      (when-not (or (:update options) (:since-date options) (:all options) (:run-server options))
        (print-usage-and-die!
         "Please use one or more of the --run-server, --update, --since-date, or --all options."))
      [options plain-args])))
