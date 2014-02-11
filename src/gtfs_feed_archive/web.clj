(ns gtfs-feed-archive.web
  (:gen-class)
  (:refer-clojure :exclude [format]) ;; I like cl-format.
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
            [gtfs-feed-archive.config :as config]
            [gtfs-feed-archive.archive-creator :as archive-creator]
            [gtfs-feed-archive.command-line :as command-line])
  (:use compojure.core
        [hiccup.core :only [h html]]
        hiccup.page ;; html5.
        hiccup.form ;; form-to, check-box, ...
        hiccup.def
        hiccup.element
        gtfs-feed-archive.util 
        clojure.test
        clojure-csv.core
        [clojure.tools.cli :only [cli]] ;; Command-line parsing.
        [clojure.pprint :only [pprint]] 
        [clojure.pprint :rename {cl-format format}]))

(javadoc-helper/set-local-documentation-source!)

(defhtml date-selector [year month day]
  (html (text-field {:size 4} :year year) "-"
        (text-field {:size 2} :month month) "-"
        (text-field {:size 2} :day day)))

(defn with-status [status response]
  ;; starting with either a body string, or a response map, generate a
  ;; response with a given HTTP status.
  (if (map? response)
    (assoc response :status status)
    {:status status :body response}))

(defn status-demo []
  ;; to test: curl -v -v http://127.0.0.1:8081/status
  ;; most browsers won't show the message, they disconnect as soon as they recieve the header.
  (with-status 204 "Try back later.")) 

(defn parse-date [arg]
  (let [d (clj-time.format/parse-local (clj-time.format/formatters :date) arg)]
    (.toDate d)))

(defhtml archive-generator-page [submit which-feeds year month day]
  [:head [:title "GTFS Feed Archive"]]
  [:body
   [:h2 "What kind of archive would you like to create?"]
   "<!-- "
   [:p "Button Value: " (h submit) ]
   [:p "Which Feeds: " (h (pr-str which-feeds))] 
   [:p "Date: " (map (comp h str) [year "-" month "-" day])]
   "-->"
   (when (= which-feeds "all")
     (archive-creator/build-archive-of-all-feeds!))
   (when (= which-feeds "since-date")
     (try (let [date (parse-date (str year "-" month "-" day))]
            (info "I was told to build an archive of feeds modified since")
            (info "date is " date)
            (archive-creator/build-archive-of-feeds-modified-since! date))
          (catch Exception e nil)))
   (let [all-feeds? (= which-feeds "all")
         year (or year "2013")
         month (or month "01")
         day (or day "15")]
     (form-to [:post ""] ;; current URL.
              [:p [:label "All Feeds" (radio-button "which-feeds" all-feeds? "all")]]
              [:p [:label "Feeds Modified Since" (radio-button "which-feeds" (not all-feeds?) "since-date")]
               "Date: " (date-selector year month day)]
              (submit-button {:name "submit"} "Create Archive")))
   [:p "Archives created here may be found on the "
    (link-to "http://archive.oregon-gtfs.com/archive-download-public/" "download page")]])

(defhtml forms-page [a b year month day]
  [:head [:title "Forms demonstration."]]
  [:body
   [:h1 "Forms Demonstration"]
   (let [year (or year "2013")
         month (or month "01")
         day (or day "15")]
     (form-to [:post ""] ;; current URL.
              [:p [:label "A" (check-box "a" a)] "value: " (h (pr-str a))]
              [:p [:label "B" (check-box "b" b)] "value: " (h (pr-str b))]
              [:p "Date: " (date-selector year month day)
               "value: " (map (comp h str) [year "-" month "-" day])]
              (submit-button {:name "submit"} "Submit!")))])

(defhtml variables-demo []
  [:head [:title "Variables Demonstration."]]
  [:body
   [:h1 "Variables Demonstration"]
   [:p "*out*: " (h *out*)]
   [:p "input csv " (h @config/*input-csv-files*)]
   [:p "cache dir " (h @config/*cache-directory*)]
   (let [al (sort @config/*archive-list*)]
     [:p "archive list holds "  (count al) " entries:"
      [:ol 
      (for [a al]
        [:li (h a) ] )
      ]])
   (let [cm @config/*cache-manager*]
     [:p "cache manager holds "  (count cm) " entries:"
      [:ol 
      (for [a cm]
        [:li (h @a) ] )
      ]])])

(defroutes app
  (GET "/" [] (html
               [:h1 "Hello There!"]
               [:p (link-to "/f" "Forms demo." )]
               [:p (link-to "/g/path-text" "Parameter demo." )]))
  (POST "/a" [submit which-feeds year month day]
    (archive-generator-page submit which-feeds year month day))
  (GET "/a" [submit which-feeds year month day]
    (archive-generator-page submit which-feeds year month day))
  (POST "/f" [a b year month day]
    (forms-page a b year month day))
  (GET "/f" [a b year month day]
    (forms-page a b year month day))
  (GET "/status" [] (status-demo))
  (GET "/g/:a" [a b :as r]
    (html [:head [:title "Parameter demonstration."]]
          [:body
           [:h1 "Parameter demonstration."]
           [:p "The path after /g/ is " (h a)]
           [:p "Query-string variable b is " (h b)]
           [:p "Full request map is " (h r) ]]))
  (GET "/v" []
    (variables-demo))
  (route/not-found (html [:h1 "Page not found"])))

(def app-site (handler/site app))


;; rebound in start/stop-web-server.
(defonce ^:dynamic *web-server* nil)

(defn stop-web-server! []
  (try 
    (when *web-server*
      (info "attempting to stop web server...")
      (.stop *web-server*)
      (def ^:dynamic *web-server* nil))
    (catch Exception e nil)))

;; actually this is a restart operation.
(defn start-web-server!
  ([]
     (start-web-server! @config/*web-server-port*))
  ([port]
     (stop-web-server!)
     (def ^:dynamic *web-server*
       (jetty/run-jetty #'app-site {:port port :join? false}))))

