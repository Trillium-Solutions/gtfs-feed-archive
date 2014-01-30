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
             ["-u" "--update"
              "Fetch updated feeds from the Internet and store them in the cache."
              :default false :flag true]
             ["-f" "--freshness-date"
              "How fresh does the cache need to be?"
              :parse-fn parse-date] ;; default to one day before now?
             ["-s" "--since-date"
              "Create an archive of feeds modified after date, e.g. 2013-08-23."
              :parse-fn parse-date :assoc-fn set-merge]
             ["-a" "--all"
              "Make an archive of all feeds." :default false :flag true]])
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
      (when-not (or (:update options) (:since-date options) (:all options))
        (print-usage-and-die! "Please use one or more of the --update, --since-date, or --all options."))
      [options plain-args])))
