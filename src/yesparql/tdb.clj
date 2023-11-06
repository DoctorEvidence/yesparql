(ns yesparql.tdb
  (:import [org.apache.jena.query Dataset DatasetFactory]
           [org.apache.jena.tdb TDBFactory]))

(defn create-file-based
  "Creates a new TDB-backed `Dataset` in the `directory` (absolute path)"
  ^Dataset [^String directory]
  (TDBFactory/createDataset directory))

(defn create-in-memory
  "Create an in-memory, modifiable TDB `Dataset`"
  ^Dataset []
  (TDBFactory/createDataset))

(defn ^Dataset create-bare
  "Creates a bare `DataSet` (without TDB)
   Recommended for testing purposes only"
  []
  (DatasetFactory/createGeneral))
