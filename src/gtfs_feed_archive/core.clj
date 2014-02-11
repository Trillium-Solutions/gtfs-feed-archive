(ns gtfs-feed-archive.core
  (:gen-class)
  (:refer-clojure :exclude [format])   ;; I like cl-format better...
  (:require [clj-http.client :as http] ;; docs at https://github.com/dakrone/clj-http
            clojure.set
            [miner.ftp :as ftp]
            [clojure.tools.nrepl.server :as nrepl-server]
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal spy with-log-level)]
            [gtfs-feed-archive.javadoc-helper :as javadoc-helper]
            [gtfs-feed-archive.cache-persistance :as cache-persistance]
            [gtfs-feed-archive.cache-manager :as cache-manager]
            [gtfs-feed-archive.download-agent :as download-agent]
            [gtfs-feed-archive.command-line :as command-line]
            [gtfs-feed-archive.archive-creator :as archive-creator]
            [gtfs-feed-archive.config :as config]
            [gtfs-feed-archive.web :as web]
            )
  (:use gtfs-feed-archive.util 
        clojure.test
        clojure-csv.core
        [clojure.tools.cli :only [cli]] ;; Command-line parsing.
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

(javadoc-helper/set-local-documentation-source!)

(defn run-command-line [& args]
  (let [[options plain-args] (apply command-line/parse-args-or-die! args)]
    (reset! config/*archive-output-directory* (:output-directory options))
    (reset! config/*archive-filename-prefix* (:archive-name-prefix options))
    (reset! config/*input-csv-files*  (:input-csv options))
    (reset! config/*cache-directory* (:cache-directory options))
    (reset! config/*freshness-hours* (:freshness-hours options))
    (reset! config/*web-server-port* (:server-port options))
    (reset! config/*archive-output-url* (:download-url options))
    (reset! config/*nrepl-port* (:nrepl-port options))

    (info "Cache directory:" @config/*cache-directory*)
    (info "Output directory:" @config/*archive-output-directory*)
    (when-let [p @config/*nrepl-port*]
      (info "nREPL port: " @config/*nrepl-port*)
      (reset! config/*nrepl-server* 
              (nrepl-server/start-server :bind "127.0.0.1" :port p))
      (info "*nrepl-server*: " config/*nrepl-server* ))
    
    (cache-manager/load-cache-manager!)
    (archive-creator/load-archive-list!)
    (info "Looking at " (count (into #{} (mapcat read-csv-file @config/*input-csv-files*))) "feeds.")

    (when (:update options)
      ;; fetch new copies of GTFS feeds, if possible.
      (archive-creator/update-cache!)
      (info "Cache updated.")
      ;; save cache status for next time.
      (cache-manager/save-cache-manager!) 
      (info "Cache saved."))
      
    ;; TODO: how do we inform the exit code of the program when we run it
    ;; as a script?  I think the best way is to perform as many operations
    ;; as possible, but return an error code, and print a message such as
    ;; "Not all operations succeeded, please see log for details."
    
    (when (:all options)
      (archive-creator/build-archive-of-all-feeds!))
    
    (doseq [s (:since-date options)]
      (archive-creator/build-archive-of-feeds-modified-since! s))
    
    (when (:run-server options)
      (web/start-web-server!)
      ;; If the web server is running in the background, shouldn't we just let it keep going?
      ;; We can have commands that access its nREPL port which can print out status, 
      ;; shutdown/restart the server etc. Trick would be to do all of the Unix daemon things like
      ;; change to the root directory, close open fd's etc. Without messing up the I/O to things like
      ;; the cache directory which might be specified as a relative path.
      (loop []
        (Thread/sleep 1000)
        ;;(info "Web server still running")
        (recur)))))

(defn -main [& args]
  ;;(timbre/set-level! :warn)
  (apply run-command-line args)
  ;; Hmm, are we sure we want to shutdown agents? I think only if we're not running a web server!
  ;; Since download agents trigger their own successor send-off commands, by shutting agents down,
  ;; we essentially kill any running downloads, and of course prevent any new ones from starting.
  (shutdown-agents))
