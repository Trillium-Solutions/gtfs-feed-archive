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
  (let [set-merge (fn [previous key val]
                    (assoc previous key
                           (if-let [oldval (get previous key)]
                             (merge oldval val)
                             (hash-set val))))
        parse-date (fn [arg] (when-let [d (clj-time.format/parse-local (clj-time.format/formatters :date)
                                                                       arg)] (.toDate d)))
        cli-format ["Create a GTFS feed archive. http://github.com/ed-g/gtfs-feed-archive"
                    ["-o" "--output-directory" "Directory to place output zip files into."]
                    ["-i" "--input-csv"
                     "Input CSV feed list file." :assoc-fn set-merge]
                    ["-c" "--cache-directory" 
                     "Cache directory for GTFS feed downloads."
                     :default "/tmp/gtfs-cache/"]
                    ["-s" "--since-date"
                     "Create an archive of feeds modified after date, e.g. 2013-08-23."
                     :parse-fn parse-date
                     :assoc-fn set-merge]
                    ["-a" "--all"
                     "Make an archive of all feeds." :default false :flag true]]
        [_ _ usage-text] (apply cli nil cli-format)
        print-usage-and-die! (fn [malfunction]
                               (format t "~a~%~a~%" usage-text malfunction)
                               (System/exit 1))
        [options plain-args _]
        (try (apply cli args cli-format)
             (catch Exception e (do (error "Error parsing command line: " e)
                                    (print-usage-and-die!))))]
    ;; See if we have all the information we need to create an archive.
    ;; If not, print usage information and bail.
    (when-not (every? identity (:since options))
      (print-usage-and-die! "Please format dates as RFC 3339: YYYY-MM-DD."))
    (when-not (pos? (count (:input-csv options)))
      (print-usage-and-die! "Please supply at least one input CSV feed list file."))
    (when-not (pos? (count (:output-directory options)))
      (print-usage-and-die! "Please indicate an output directory in which to create zip files."))
    [options plain-args]))
