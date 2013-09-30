(ns gtfs-feed-archive.web
  (:gen-class)
  (:refer-clojure :exclude [format]) ;; I like cl-format better...
  (:require [clj-http.client :as http] ;; docs at https://github.com/dakrone/clj-http
            clojure.set
            [ring.adapter.jetty :as jetty] 
            [compojure.route :as route]
            [compojure.handler :as handler]
            [taoensso.timbre :as timbre :refer (trace debug info warn error fatal spy with-log-level)]
            [gtfs-feed-archive.javadoc-helper :as javadoc-helper]
            [gtfs-feed-archive.cache-persistance :as cache-persistance]
            [gtfs-feed-archive.cache-manager :as cache-manager]
            [gtfs-feed-archive.download-agent :as download-agent]
            [gtfs-feed-archive.command-line :as command-line])
  (:use compojure.core
        [hiccup.core :only [h html]]
        gtfs-feed-archive.util 
        clojure.test
        clojure-csv.core
        [clojure.tools.cli :only [cli]] ;; Command-line parsing.
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

(javadoc-helper/set-local-documentation-source!)

(defroutes app
  (GET "/" [] "<h1>Hello There!</h1>")
  (GET "/h" [] (h/html
                [:h1 "Hello from Hiccup!"]))
  (GET "/g/:a" [a b :as r] (html
                            [:head [:title "Parameter demonstration."]]
                            [:body
                             [:h1 "Parameter demonstration."]
                             [:p "The path after /g/ is " (h a) ]
                             [:p "Query-string variable b is " (h b) ]
                             [:p "Full request map is " (h r) ]]))
  (route/not-found (html [:h1 "Page not found"])))

(def app-site (handler/site app))

(defonce server
  (jetty/run-jetty #'app-site {:port 8081 :join? false}))
