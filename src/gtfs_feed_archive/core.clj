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

(comment 
  (def ^:dynamic *archive-output-directory*)
  (def ^:dynamic *archive-filename-prefix*)
  ;; Remembering CSV files, instead of the feeds they represent, has the
  ;; feature of allwing the user to update CSV files between runs.
  (def ^:dynamic *input-csv-files*)  ;; (def ^:dynamic *feeds*)
  (def ^:dynamic *cache-directory*) 
  ;; TODO -- change cache-manager.clj to use this instead of global definition.
  (def ^:dynamic *cache-manager*)
  (def ^:dynamic *freshness-hours*)
  (def ^:dynamic *nrepl-server* (atom nil)))

(defn run-command-line [& args]
  ;; TODO: split out all these option handlers into their own
  ;; functions, so we can call them as easily from the web interface
  ;; as from the command line.
  (let [[options plain-args] (apply command-line/parse-args-or-die! args)]
    (reset! config/*archive-output-directory* (:output-directory options))
    (reset! config/*archive-filename-prefix* (:archive-name-prefix options))
    (reset! config/*input-csv-files*  (:input-csv options))
    (reset! config/*cache-directory* (:cache-directory options))
    (reset! config/*freshness-hours* (:freshness-hours options))
    (reset! config/*web-server-port* (:server-port options))
    (reset! config/*nrepl-port* (:nrepl-port options))

    (info "Setting cache directory:" @config/*cache-directory*)
    (info "nrepl-port: " @config/*nrepl-port*)
    (when-let [p @config/*nrepl-port*]
      (reset! config/*nrepl-server* 
              (nrepl-server/start-server :bind "127.0.0.1" :port p)))
    (info "*nrepl-server*: " config/*nrepl-server* )
    
    (cache-manager/load-cache-manager!)
    (let [finished-agents 
          (try 
            (if (:update options)
              (archive-creator/update-cache!)
              (archive-creator/verify-cache-freshness!))
            (catch Exception e nil))]

      (info "Looked at " (count (into #{} (mapcat read-csv-file @config/*input-csv-files*))) "feeds.")

      (when-not finished-agents
        ;; provide a more descriptive error message. we should really re-think how to handle & show
        ;; errors, and provide guidance to the user on how errors might be resolved.
        (error "Error updating feeds, sorry!")
        (System/exit 1))

      (do 
        (cache-manager/save-cache-manager!) ;; save cache status for next time.
        (info "Cache saved."))
      (when (:all options)
        (archive-creator/build-archive-of-all-feeds!)
        ;;(build-feed-archive! (str @config/*archive-filename-prefix* "-feeds-" (inst->rfc3339-day (now))) 
        ;;                     @config/*archive-output-directory*
        ;;                     finished-agents))
      (doseq [s (:since-date options)]
        (archive-creator/build-archive-of-feeds-modified-since! s))
        ;(let [new-enough-agents (filter (fn [a] (download-agent/modified-after? s @a))
        ;                                finished-agents)]
        ;    (build-feed-archive!
        ;     (str @config/*archive-filename-prefix* "-updated-from-" (inst->rfc3339-day s)
        ;          "-to-" (inst->rfc3339-day (now))) @config/*archive-output-directory*
        ;          new-enough-agents)))

      (when (:run-server options)
        (web/start-web-server!  #_(*web-server-port*))
        (loop []
          (Thread/sleep 1000)
          ;;(info "Web server still running")
          (recur)))))))

(defn -main [& args]
  ;;(timbre/set-level! :warn)
  (apply run-command-line args)
  (shutdown-agents))

