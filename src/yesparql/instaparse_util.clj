(ns yesparql.instaparse-util
  (:require [instaparse.core :as instaparse]
            [instaparse.failure :as failure])
  (:import [java.io StringWriter]))

(defn process-instaparse-result
  [parse-results context]
  (if-let [failure (instaparse/get-failure parse-results)]
    (binding [*out* (StringWriter.)]
      (failure/pprint-failure failure)
      (throw (ex-info (str *out*)
                      failure)))
    (if (second parse-results)
      (throw (ex-info "Ambiguous parse - please report this as a bug at https://github.com/joelkuiper/yesparql/issues"
                      {:variations (count parse-results)}))
      (first parse-results))))
