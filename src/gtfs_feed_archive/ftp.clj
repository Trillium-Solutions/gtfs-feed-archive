(ns gtfs-feed-archive.ftp
  (:refer-clojure :exclude [format])   ;; I like cl-format better...
  (:import org.apache.commons.net.ftp.FTPClient
           org.apache.commons.net.ftp.FTPReply)
  (:require [clj-http.client :as http] ;; docs at https://github.com/dakrone/clj-http
            clojure.set
            [miner.ftp :as ftp])
  (:use gtfs-feed-archive.util 
        clojure.test
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

;;;; scratch file for testing FTP client.

(def *fc (org.apache.commons.net.ftp.FTPClient.))
(def +server+ "69.59.192.202") ;; Corvallis GTFS archive server.
(def +remote-file+ "pw/Transportation/GoogleTransitFeed/Google_Transit.zip")
(def +remote-dir+ "pw/Transportation/GoogleTransitFeed/")


(defn anonymous-login! []
  (.login *fc "anonymous" "spider@archive.oregon-gtfs.com"))

(defn connect! []
  (.connect *fc +server+))

(defn get-reply! []
  (.getReplyCode *fc))

(defn reply-ok? [reply]
  (FTPReply/isPositiveCompletion reply))

(defn list-file! []
  (first (.listFiles *fc +remote-file+)))

(defn lookup-modification-time! []
  (.getTimestamp (list-file!)))

(defn test-ftp []
  (connect!)
  (anonymous-login!)
  (println (.toFormattedString (list-file!)))
  (println (lookup-modification-time!)) )

;; example using clj-ftp.  doesn't quite work, the client does not
;; change from the root directory.  however the information appears to
;; be available. when we figure out extended listing we should send
;; pull request to miner-ftp.
(defn clj-ftp-list [url]
  (let [u (http/parse-url url)
        host (:server-name u)
        port (:server-port u)
        directory (dirname (:uri u))
        file-name (basename (:uri u))
        user-info (or (:user-info u)
                      "anonymous")]
    (ftp/with-ftp
      [client
       ;; file name Google_Transit.zip
       (str "ftp://" 
            user-info "@"
            host "/" directory)]
      (first (filter (fn [ftp-file]
                (= file-name (.getName ftp-file)))
                     (ftp/client-FTPFiles-all client))))))

(defn clj-ftp-example []
  (let [url "ftp://ftp.ci.corvallis.or.us/pw/Transportation/GoogleTransitFeed/Google_Transit.zip"
        ftp-file (clj-ftp-list url)
        modification-time (.getTimestamp ftp-file)]
    (println "file object" ftp-file "\nlast modified: " modification-time) ))
