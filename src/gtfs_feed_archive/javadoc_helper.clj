(ns gtfs-feed-archive.javadoc-helper
  (:require clojure.java.javadoc))

;; for development -- set local documentation source
;; for javadoc command.
(defn set-local-documentation-source! []
  (dosync (ref-set clojure.java.javadoc/*local-javadocs*
                   ["/usr/share/doc/openjdk-6-doc/api"])))
