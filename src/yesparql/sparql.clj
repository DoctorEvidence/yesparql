(ns yesparql.sparql
  (:refer-clojure :exclude [update])
  (:require [cheshire.core :as json])
  (:import
   [clojure.lang.Reflector]
   [java.net URL URI]
   [org.apache.jena.graph Node]
   [org.apache.jena.update
    Update UpdateAction
    UpdateFactory UpdateProcessor
    UpdateRequest UpdateExecutionFactory]
   [org.apache.jena.rdf.model Model
    StmtIterator Statement Resource Property
    RDFNode Resource Literal]
   [org.apache.jena.query Dataset]
   [org.apache.jena.sparql.core DatasetGraph]
   [org.apache.jena.sparql.resultset RDFOutput]
   [ org.apache.jena.graph Node Node_Literal Node_Blank Node_NULL Node_URI]
   [org.apache.jena.query
    Query QuerySolution QueryExecution
    QueryExecutionFactory QueryFactory QuerySolutionMap
    ParameterizedSparqlString
    ResultSetFactory ResultSet ResultSetFormatter]))

;; Util
(defn ^java.io.OutputStream output-stream []
  (java.io.ByteArrayOutputStream.))

(defn reset-if-rewindable!
  [^ResultSet result]
  (when (instance? org.apache.jena.query.ResultSetRewindable result)
    (.reset result)))


;; Serialize model
(defn serialize-model
  [^Model model ^String format]
  (with-open [w (java.io.StringWriter.)]
    (.write model w format)
    (str w)))

(defn model->rdf+xml [^Model model] (serialize-model model "RDF/XML"))
(defn model->ttl [^Model model] (serialize-model model "TTL"))
(defn model->json-ld [^Model model] (serialize-model model "JSONLD"))
(defn model->clj [^Model model] (json/decode (model->json-ld model) true))

;; Serialize ResultSet
(defmacro serialize-result
  [method result]
  `(let [output# (output-stream)]
     (try
       (do
         (reset-if-rewindable! ~result)
         (~method ^java.io.OutputStream output# ^ResultSet ~result)
         (.toString output# "UTF-8"))
       (finally (.close output#)))))

(defn result->json [^ResultSet result] (serialize-result ResultSetFormatter/outputAsJSON result))
(defn result->text [^ResultSet result] (ResultSetFormatter/asText result))

(defn result->xml [^ResultSet result] (serialize-result ResultSetFormatter/outputAsXML result))
(defn result->csv [^ResultSet result] (serialize-result ResultSetFormatter/outputAsCSV result))
(defn result->tsv [^ResultSet result] (serialize-result ResultSetFormatter/outputAsTSV result))

(defn result->clj
  [^ResultSet result]
  (json/decode (result->json result) true))

(defn result->model
  [^ResultSet result]
  (let [^RDFOutput rdf (RDFOutput.)]
    (reset-if-rewindable! result)
    (.asModel rdf ^ResultSet result)))

(defn copy-result-set
  "Returns a copy of a `ResultSet` allowing it to be re-used.

  Make sure to apply this function if you intend to re-use the
  `ResultSet` after initial traversal.

  See also: `reset-if-rewindable!`"
  [^ResultSet result]
  (ResultSetFactory/copyResults result))

(defn ^ParameterizedSparqlString parameterized-query
  [^String statement]
  (ParameterizedSparqlString. statement))

(defn ^ParameterizedSparqlString query-with-bindings
  "The `query` string will be formatted as a `ParameterizedSparqlString`
   and can be provided with a map of `bindings`.
   Each binding is a String->URL, String->URI, String->Node or String->RDFNode.
   Any other type (e.g. strings, float) will be set as literal.
   Does not warn if setting a binding that does not exist. "
  [^ParameterizedSparqlString pq bindings]
  (doall
   (map
    (fn [[name resource]]
      (condp instance? resource
        URL (.setIri pq ^String name ^URL resource)
        URI (.setIri pq ^String name ^String (str resource))
        Node (.setParam pq ^String name ^Node resource)
        RDFNode (.setParam pq ^String name ^RDFNode resource)
        (.setLiteral pq name resource)))
    bindings))
  pq)

(defn- with-type
  [f ^Node_Literal literal]
  (if-let [lang (if (empty? (.getLiteralLanguage literal)) false (.getLiteralLanguage literal))]
    {:type (.getLiteralDatatypeURI literal)
     :value (f literal)
     :lang (str "@" lang)}
    {:type (.getLiteralDatatypeURI literal)
     :value (f literal)}))

(defmulti node->clj (fn [^Node_Literal literal] (.getLiteralDatatypeURI literal)))

(defmethod node->clj nil [^Node_Literal literal]
  {:value (.getLiteralValue literal)})

(defmethod node->clj :default [^Node_Literal literal]
  (try
    (with-type #(.getLiteralValue %) literal)
    (catch org.apache.jena.shared.JenaException e
      {:value (.getLiteralLexicalForm literal)
       :type (.getLiteralDatatypeURI literal)})))

(defprotocol INodeConvertible
  (convert [^Node this]))

(extend-protocol INodeConvertible
  org.apache.jena.graph.Node_Blank
  (convert [this] (.getBlankNodeId this))
  org.apache.jena.graph.Node_Literal
  (convert [this] (node->clj this))
  org.apache.jena.graph.Node_NULL
  (convert [this] nil)
  org.apache.jena.graph.Node_URI
  (convert [this] (.getURI this)))

(defn- result-binding->clj
  [^org.apache.jena.sparql.core.ResultBinding result-binding]
  (let [binding (.getBinding result-binding)]
    (into {} (map (fn [v] [(.getVarName v) (convert (.get binding v))])
                  (iterator-seq (.vars binding))))))

(deftype CloseableResultSet [^QueryExecution qe ^ResultSet rs]
  clojure.lang.Seqable
  (seq [_] (map result-binding->clj (iterator-seq rs)))
  java.lang.AutoCloseable
  (close [_] (.close qe)))

(defn ->query-execution [t] (.qe t))
(defn ->result [^ResultSet r] (.rs r))

(defrecord Quad [g s p o])
(defrecord Triple [s p o])

(defn statements->clj
  [^Statement s]
  (let [^org.apache.jena.graph.Triple t (.asTriple s)]
    (apply ->Triple (map convert [(.getSubject t) (.getPredicate t) (.getObject t)]))))

(deftype CloseableModel [^QueryExecution qe ^Model m]
  clojure.lang.Seqable
  (seq [this] (map statements->clj (iterator-seq (.listStatements m))))
  java.lang.AutoCloseable
  (close [this] (.close qe)))

(defn ->model [^CloseableResultSet m] (.m m))

(defmulti query-exec (fn [connection _] (class connection)))
(defmethod query-exec String [connection query]
  (QueryExecutionFactory/sparqlService
   ^String connection
   ^Query query))

(defn- query-exec*
  [connection query]
  (QueryExecutionFactory/create
   ^Query query
   connection))

(defmethod query-exec Dataset [connection query] (query-exec* connection query))
(defmethod query-exec DatasetGraph [connection query] (query-exec* connection query))
(defmethod query-exec Model [connection query] (query-exec* connection query))

(defn- query-type
  [^Query q]
  (cond
    (.isSelectType q) "execSelect"
    (.isConstructType q) "execConstruct"
    (.isAskType q) "execAsk"
    (.isDescribeType q) "execDescribe"))

(defn query*
  [^QueryExecution q-exec]
  (clojure.lang.Reflector/invokeInstanceMethod
   q-exec
   (query-type (.getQuery q-exec)) (object-array 0)))

(defn- ->execution
  [connection ^ParameterizedSparqlString pq {:keys [bindings timeout]}]
  (let [^Query q (.asQuery pq)
        ^QueryExecution query-execution (query-exec connection q)]
    (when timeout (.setTimeout query-execution timeout))
    query-execution))

(defn query
  [connection ^ParameterizedSparqlString pq {:keys [bindings timeout] :as call-options}]
  (let [query-execution (->execution connection (query-with-bindings pq bindings) call-options)
        query-type (query-type (.getQuery ^QueryExecution query-execution))]
    (cond
      (= query-type "execSelect")
      (->CloseableResultSet query-execution (query* query-execution))
      (or (= query-type "execDescribe") (= query-type "execConstruct"))
      (->CloseableModel query-execution (query* query-execution))
      :else
      (try (query* query-execution)
           (finally (.close query-execution))))))

(defmulti update-exec (fn [connection _] (class connection)))
(defmethod update-exec String [connection update]
  (UpdateExecutionFactory/createRemote update ^String connection))
(defmethod update-exec Dataset [connection update]
  (UpdateExecutionFactory/create update ^Dataset connection))
(defmethod update-exec DatasetGraph [connection update]
  (UpdateExecutionFactory/create update ^DatasetGraph connection))

(defn update!
  "Execute a SPARQL UPDATE `query` against the `connection`,
  returning nil if success, throw an exception otherwise. `bindings`
  will be substituted when possible, can be empty.
  `connection` can be a String for a SPARQL endpoint URL or `Dataset`"
  [connection ^ParameterizedSparqlString pq {:keys [bindings]}]
  (let [q (.toString (.asUpdate (query-with-bindings pq bindings)))
        ^UpdateRequest update-request (UpdateFactory/create q)
        ^UpdateProcessor processor (update-exec connection update-request)]
    (.execute processor)))
