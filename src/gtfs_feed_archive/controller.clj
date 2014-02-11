(require '[clojure.tools.nrepl :as repl])

(with-open [conn (repl/connect :port 7999)]
     (-> (repl/client conn 1000)    ; message receive timeout required
       (repl/message {:op "eval" :code "(+ 2 3)"})
       repl/response-values))


