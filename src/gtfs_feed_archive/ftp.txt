;;testing code for FTP. all the working code has been moved to util.clj.
(ns gtfs-feed-archive.ftp
  (:refer-clojure :exclude [format]) ;; I like cl-format better...
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

(comment 
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
    (println (lookup-modification-time!))))


(defn- clj-ftp-example []
  (let [url "ftp://ftp.ci.corvallis.or.us/pw/Transportation/GoogleTransitFeed/Google_Transit.zip"
        ftp-file (clj-ftp-list url)
        modification-time (.getTimestamp ftp-file)]
    (println "file object" ftp-file "\nlast modified: " modification-time)))

;; example using clj-ftp.  
(defn- clj-ftp-list [url]
  (let [u (http/parse-url url)
        host (:server-name u)
        port (:server-port u)
        directory (dirname (:uri u))
        file-name (basename (:uri u))
        user-info (or (:user-info u)
                      "anonymous")]
    (ftp/with-ftp
      [client (str "ftp://" 
                   user-info "@"
                   host "/" directory)]
      (first (filter (fn [ftp-file]
                       (= file-name (.getName ftp-file)))
                     (ftp/client-FTPFiles-all client))))))

(defn- clj-ftp-file-data [url]
  (let [u (http/parse-url url)
        host (:server-name u)
        port (:server-port u)
        directory (dirname (:uri u))
        file-name (basename (:uri u))
        user-info (or (:user-info u)
                      "anonymous")]
    (ftp/with-ftp
      [client (str "ftp://" 
                   user-info "@"
                   host "/" directory)]
      ;; Supposedly we should run (ftp/client-complete-pending-command
      ;; client) after using this stream. Maybe we can make a macro
      ;; similar to with-open which accomplishes this? Or copy the
      ;; whole darn thing into an InputStream representing a tempory file,  
      ;; cleanup the ftp connection, and return the copied stream.
      ;;
      ;; See http://alvinalexander.com/java/java-temporary-files-create-delete
      (ftp/client-set-file-type :binary) ;; no ASCII conversion.
      (ftp/client-get-stream client file-name))))

(defn ftp-url-last-modifed [url]
  (try 
    (let [ftp-file-listing (clj-ftp-list url)]
        (.getTimestamp ftp-file-listing))
    (catch Exception e nil)))

(defn ftp-url-data [url]
  (try
    (let [data (clj-ftp-file-data url)]
        data)
    (catch Exception e nil)))
