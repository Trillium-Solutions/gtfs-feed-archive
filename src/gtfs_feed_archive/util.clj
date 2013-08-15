(ns gtfs-feed-archive.util)

;; handy utility functions.

(def t true);; for cl-format.

(defn keyword-with-dashes
  "convert a string to a keyword replacing '_' with '-'"
  [string]
  (keyword (clojure.string/replace string "_" "-")))

(defn methods-of-java-object
  "For repl development. Finds methods of a java object."
  [obj]
  (-> obj (class) (.getDeclaredMethods) (seq)))

(defn string->bytes [s]
  "Convert a string into an array of bytes."
  (byte-array (map byte s)))

(defn power-of-two "nth power of 2" [n] (int (Math/pow 2 n)))

(defn copy-binary-stream [^java.io.InputStream in
                          ^java.io.OutputStream out]
  "Buffered copy of in-stream to out-stream."
  (let [^bytes buffer (byte-array (power-of-two 14))]
    (loop []
      (let [nread (.read in buffer)]
        (format true "nread: ~a~%" nread)
        (when (pos? nread)
          (.write out buffer 0 nread)
          (recur))))))

(defn copy-binary-file "Example code for binary file copy."
  [in-file out-file]
  (with-open [in (clojure.java.io/input-stream in-file)
              out (clojure.java.io/output-stream out-file)]
    (copy-binary-stream in out)))

(defn file->bytes [input-file-name]
  (clojure.java.io/input-stream input-file-name))

(defn inst->rfc3339-day
  "Convert inst into RFC 3339 format, then pull out the year, month, and day only."
  [inst]
  ;; funny that there's no function to do this already?
  (if (nil? inst)
    nil
    (.substring (pr-str inst) 7 26)))

(defn now 
  "We're looking at now, now. This is now."
  [] (java.util.Date.))

(defn dirname "directory component of path" [path]
  (.getParent (clojure.java.io/file path)))

(defn mkdir-p "make directory & create parent directories as needed" [path]
  (.mkdirs (clojure.java.io/file path)))


