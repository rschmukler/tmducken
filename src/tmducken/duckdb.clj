(ns tmducken.duckdb
  "DuckDB C-level bindings for tech.ml.dataset.

  Current datatype support:

  * boolean, all numeric types int8->int64, uint8->uint64, float32, float64.
  * string
  * LocalDate, Instant column types.


  Example:

  ```clojure

user> (require '[tech.v3.dataset :as ds])
nil
user> (require '[tmducken.duckdb :as duckdb])
nil
user> (duckdb/initialize!)
10:04:14.814 [nREPL-session-635e9bc8-2923-442b-9fad-da547210617b] INFO tmducken.duckdb - Attempting to load duckdb from \"/home/chrisn/dev/cnuernber/tmducken/binaries/libduckdb.so\"
true
user> (def stocks
        (-> (ds/->dataset \"https://github.com/techascent/tech.ml.dataset/raw/master/test/data/stocks.csv\" {:key-fn keyword})
            (vary-meta assoc :name :stocks)))
#'user/stocks
user> (def db (duckdb/open-db))
#'user/db
user> (def conn (duckdb/connect db))
#'user/conn
user> (duckdb/create-table! conn stocks)
\"stocks\"
user> (duckdb/append-dataset! conn stocks)
nil
user> (ds/head (duckdb/execute-query! conn \"select * from stocks\"))
10:05:28.356 [tech.resource.gc ref thread] INFO tech.v3.resource.gc - Reference thread starting
_unnamed [5 3]:

| symbol |       date | price |
|--------|------------|------:|
|   MSFT | 2000-01-01 | 39.81 |
|   MSFT | 2000-02-01 | 36.35 |
|   MSFT | 2000-03-01 | 43.22 |
|   MSFT | 2000-04-01 | 28.37 |
|   MSFT | 2000-05-01 | 25.45 |
```"
  (:require [tmducken.duckdb.ffi :as duckdb-ffi]
            [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype.struct :as dt-struct]
            [tech.v3.datatype.native-buffer :as native-buffer]
            [tech.v3.datatype.casting :as casting]
            [tech.v3.datatype :as dt]
            [tech.v3.datatype.datetime :as dt-datetime]
            [tech.v3.datatype.packing :as packing]
            [tech.v3.datatype.bitmap :as bitmap]
            [tech.v3.datatype.unary-pred :as unary-pred]
            [tech.v3.resource :as resource]
            [tech.v3.dataset :as ds]
            [tech.v3.dataset.sql :as sql]
            [ham-fisted.api :as hamf]
            [ham-fisted.lazy-noncaching :as lznc]
            [clojure.tools.logging :as log])
  (:import [java.nio.file Paths]
           [java.util Map]
           [java.time LocalDate LocalTime]
           [tech.v3.datatype.ffi Pointer]
           [org.roaringbitmap RoaringBitmap]))


(set! *warn-on-reflection* true)


(defonce ^:private initialize* (atom false))

(defn initialized?
  []
  @initialize*)


(defn initialize!
  "Initialize the duckdb ffi system.  This must be called first should be called only once.
  It is safe, however, to call this multiple times.

  Options:

  * `:duckdb-home` - Directory in which to find the duckdb shared library.  Users can pass
  this in.  If not passed in, then the environment variable `DUCKDB_HOME` is checked.  If
  neither is passed in then the library will be searched in the normal system library
  paths."
  ([{:keys [duckdb-home]}]
   (swap! initialize*
          (fn [is-init?]
            (when-not is-init?
              (let [duckdb-home (or duckdb-home
                                    (System/getenv "DUCKDB_HOME")
                                    "./binaries")
                    libpath (if-not (empty? duckdb-home)
                              (str (Paths/get duckdb-home
                                              (into-array String [(System/mapLibraryName "duckdb")])))
                              "duckdb")]
                (if libpath
                  (log/infof "Attempting to load duckdb from \"%s\"" libpath)
                  (log/infof "Attempting to load in-process duckdb" libpath))
                (duckdb-ffi/define-datatypes!)
                (dt-ffi/library-singleton-set! duckdb-ffi/lib libpath)))
            true)))
  ([] (initialize! nil)))


(defn get-config-options
  "Returns a sequence of maps of {:name :desc} describing valid valid configuration
  options to the open-db function."
  []
  (resource/stack-resource-context
   (->> (range (duckdb-ffi/duckdb_config_count))
        (mapv (fn [^long idx]
                (let [msg-ptr (dt-ffi/make-ptr :pointer 0)
                      desc-ptr (dt-ffi/make-ptr :pointer 0)]
                  (duckdb-ffi/duckdb_get_config_flag idx msg-ptr desc-ptr)
                  {:name (dt-ffi/c->string (Pointer. (msg-ptr 0)))
                   :desc (dt-ffi/c->string (Pointer. (desc-ptr 0)))}))))))


(defn open-db
  "Open a database.  `path` may be nil in which case database is opened in-memory.
  For valid config options call [[get-config-options]].  Options must be
  passed as a map of string->string.  As duckdb is dynamically linked configuration options
  may change but with `linux-amd64-0.3.1` current options are:

```clojure
tmducken.duckdb> (get-config-options)
[{:name \"access_mode\",
  :desc \"Access mode of the database ([AUTOMATIC], READ_ONLY or READ_WRITE)\"}
 {:name \"default_order\",
  :desc \"The order type used when none is specified ([ASC] or DESC)\"}
 {:name \"default_null_order\",
  :desc \"Null ordering used when none is specified ([NULLS_FIRST] or NULLS_LAST)\"}
 {:name \"enable_external_access\",
  :desc
  \"Allow the database to access external state (through e.g. COPY TO/FROM, CSV readers, pandas replacement scans, etc)\"}
 {:name \"enable_object_cache\",
  :desc \"Whether or not object cache is used to cache e.g. Parquet metadata\"}
 {:name \"max_memory\", :desc \"The maximum memory of the system (e.g. 1GB)\"}
 {:name \"threads\", :desc \"The number of total threads used by the system\"}]
```"
  (^Pointer [^String path config-options]
   (resource/stack-resource-context
    (let [path (or path "")
          config-ptr (when-not (empty? config-options)
                       (let [config-ptr (dt-ffi/make-ptr :pointer 0)
                             _ (duckdb-ffi/duckdb_create_config config-ptr)
                             cfg (Pointer. (config-ptr 0))]
                         (doseq [[k v] config-options]
                           (duckdb-ffi/duckdb_set_config cfg (str k) (str v)))
                         config-ptr))
          config (when config-ptr
                   (Pointer. (config-ptr 0)))
          db-ptr (dt-ffi/make-ptr :pointer 0)
          err (dt-ffi/make-ptr :pointer 0)
          open-retval (duckdb-ffi/duckdb_open_ext path db-ptr config err)]
      (when config-ptr
        (duckdb-ffi/duckdb_destroy_config config-ptr))
      (when-not (= open-retval duckdb-ffi/DuckDBSuccess)
        (let [err-ptr (Pointer. (err 0))
              err-str (dt-ffi/c->string err-ptr)]
          (duckdb-ffi/duckdb_free err-ptr)
          (throw (Exception. (format "Error opening database: %s" err-str)))))
      (Pointer. (db-ptr 0)))))
  (^Pointer [^String path]
   (open-db path nil))
  (^Pointer []
   (open-db "")))


(defn close-db
  "Close the database."
  [^Pointer db]
  (resource/stack-resource-context
   (let [db-ptr (dt-ffi/make-ptr :pointer (.address db))]
     (duckdb-ffi/duckdb_close db-ptr))))


(defn connect
  "Create a new database connection from an opened database.
  Users should call disconnect to close this connection."
  ^Pointer [^Pointer db]
  (resource/stack-resource-context
   (let [ctx-ptr (dt-ffi/make-ptr :pointer 0)]
     (duckdb-ffi/duckdb_connect db ctx-ptr)
     (Pointer. (ctx-ptr 0)))))


(defn disconnect
  "Disconnect a connection."
  [^Pointer conn]
  (resource/stack-resource-context
   (let [conn-ptr (dt-ffi/make-ptr :pointer (.address conn))]
     (duckdb-ffi/duckdb_disconnect conn-ptr))))


(defn- run-query!
  ([conn sql options]
   (let [query-res (dt-struct/new-struct :duckdb-result {:container-type :native-heap
                                                         :resource-type nil})
         success? (= (duckdb-ffi/duckdb_query conn (str sql) query-res)
                     duckdb-ffi/DuckDBSuccess)
         query-ptr (dt-ffi/->pointer query-res)
         ;;destructor must only be called once and cannot reference query-res as that will
         ;;create a circular references.
         destructor! (fn []
                       (duckdb-ffi/duckdb_destroy_result query-ptr)
                       (native-buffer/free (.address query-ptr)))]
     (if-not success?
       (let [error-msg (dt-ffi/c->string (Pointer. (query-res :error-message)))]
         (destructor!)
         (throw (Exception. error-msg)))
       (resource/track query-res {:track-type (get options :resource-type :auto)
                                  :dispose-fn destructor!}))))
  ([conn sql]
   (run-query! conn sql nil)))


(defn create-table!
  "Create an sql table based off of the column datatypes of the dataset.  Note that users
  can also call [[execute-query!]] with their own sql create-table string.  Note that the
  fastest way to get data into the system is [[append-dataset!]].

  Options:

  * `:table-name` - Name of the table to create.  If not supplied the dataset name will
     be used.
  * `:primary-key` - sequence of column names to be used as the primary key."
  ([conn dataset options]
   (let [sql (sql/create-sql "duckdb" dataset)]
     (resource/stack-resource-context
      (run-query! conn sql))
     (sql/table-name dataset options)))
  ([conn dataset]
   (create-table! conn dataset nil)))


(sql/set-datatype-mapping! "duckdb" :boolean "bool" -7
                           sql/generic-sql->column sql/generic-column->sql)
(sql/set-datatype-mapping! "duckdb" :string "varchar" 12
                           sql/generic-sql->column sql/generic-column->sql)


(defn drop-table!
  [conn dataset]
  (let [ds-name (sql/table-name dataset)]
    (resource/stack-resource-context
     (run-query! conn (format "drop table %s" ds-name))
     ds-name)))


(defn- local-time->microseconds
  ^long [^LocalTime lt]
  (if lt
    (-> (.toNanoOfDay lt)
        (/ 1000))
    0))


(defn insert-dataset!
  "Append this dataset using the higher performance append api of duckdb.  This is recommended
  as opposed to using sql statements or prepared statements."
  ([conn dataset options]
   (resource/stack-resource-context
    (let [table-name (sql/table-name dataset options)
          app-ptr (dt-ffi/make-ptr :pointer 0)
          app-status (duckdb-ffi/duckdb_appender_create conn "" table-name app-ptr)
          _ (resource/track app-ptr {:track-type :stack
                                     :dispose-fn #(duckdb-ffi/duckdb_appender_destroy
                                                   app-ptr)})
          appender (Pointer. (app-ptr 0))
          check-error (fn [status]
                        (when-not (= status duckdb-ffi/DuckDBSuccess)
                          (let [err (duckdb-ffi/duckdb_appender_error appender)]
                            (throw (Exception. (str err))))))
          _ (check-error app-status)
          n-rows (ds/row-count dataset)
          n-cols (ds/column-count dataset)
          colvec (ds/columns dataset)
          dtypes (mapv (comp packing/unpack-datatype dt/elemwise-datatype) colvec)
          missing (mapv ds/missing colvec)
          rdr-vec (mapv dt/->reader colvec)]
      (dotimes [row n-rows]
        (dotimes [col n-cols]
          (let [coldata (rdr-vec col)]
            (->
             (if (.contains ^RoaringBitmap (missing col) row)
               (duckdb-ffi/duckdb_append_null appender)
               (case (dtypes col)
                 :boolean (duckdb-ffi/duckdb_append_bool appender (unchecked-byte (if (coldata row) 1 0)))
                 :int8  (duckdb-ffi/duckdb_append_int8 appender (unchecked-byte (coldata row)))
                 :uint8 (duckdb-ffi/duckdb_append_uint8 appender (unchecked-byte (coldata row)))
                 :int16 (duckdb-ffi/duckdb_append_int16 appender (unchecked-short (coldata row)))
                 :uint16 (duckdb-ffi/duckdb_append_uint16 appender (unchecked-short (coldata row)))
                 :int32 (duckdb-ffi/duckdb_append_int32 appender (unchecked-int (coldata row)))
                 :uint32 (duckdb-ffi/duckdb_append_uint32 appender (unchecked-int (coldata row)))
                 :int64 (duckdb-ffi/duckdb_append_int64 appender (unchecked-long (coldata row)))
                 :uint64 (duckdb-ffi/duckdb_append_uint64 appender (unchecked-long (coldata row)))
                 :float32 (duckdb-ffi/duckdb_append_float appender (float (coldata row)))
                 :float64 (duckdb-ffi/duckdb_append_double appender (double (coldata row)))
                 :local-date (duckdb-ffi/duckdb_append_date appender (-> (coldata row)
                                                                         (dt-datetime/local-date->days-since-epoch)))
                 :local-time (duckdb-ffi/duckdb_append_time appender
                                                            (-> (coldata row)
                                                                local-time->microseconds))
                 :instant (duckdb-ffi/duckdb_append_timestamp appender (-> (coldata row)
                                                                           (dt-datetime/instant->microseconds-since-epoch)))
                 :string (duckdb-ffi/duckdb_append_varchar appender (str (coldata row)))))
             (check-error))))
        (check-error (duckdb-ffi/duckdb_appender_end_row appender))))))
  ([conn dataset] (insert-dataset! conn dataset nil)))


(defn- validity->missing
  "Validity is 64 bit."
  ^RoaringBitmap [^long n-rows ^Pointer nmask]
  (if (or (nil? nmask) (== 0 (.address nmask)))
    (bitmap/->bitmap)
    (let [nvals (quot (+ n-rows 63) 64)
          dvec (-> (native-buffer/wrap-address (.address nmask) (* nvals 8) nil)
                   (native-buffer/set-native-datatype :int64)
                   (dt/->buffer))
          rval (bitmap/->bitmap)]
      (dotimes [idx nvals]
        (let [lval (.readLong dvec idx)]
          (when-not (== lval Long/MAX_VALUE)
            (let [logical-idx (* idx 64)]
              (loop [bit-idx 0]
                (when (< bit-idx 64)
                  (when (== 0 (bit-and lval (bit-shift-left 1 bit-idx)))
                    (.add rval (unchecked-int (+ bit-idx logical-idx))))
                  (recur (unchecked-inc bit-idx))))))))
      rval)))


(defn- coldata->buffer
  [^long n-rows ^long duckdb-type ^long data-ptr]
  (case (get duckdb-ffi/duckdb-type-map duckdb-type)
    :DUCKDB_TYPE_BOOLEAN
    (-> (native-buffer/wrap-address data-ptr n-rows nil)
        (dt/elemwise-cast :boolean))

    :DUCKDB_TYPE_TINYINT
    (native-buffer/wrap-address data-ptr n-rows nil)

    :DUCKDB_TYPE_SMALLINT
    (-> (native-buffer/wrap-address data-ptr (* 2 n-rows) nil)
        (native-buffer/set-native-datatype :int16))

    :DUCKDB_TYPE_INTEGER
    (-> (native-buffer/wrap-address data-ptr (* 4 n-rows) nil)
        (native-buffer/set-native-datatype :int32))

    :DUCKDB_TYPE_BIGINT
    (-> (native-buffer/wrap-address data-ptr (* 8 n-rows) nil)
        (native-buffer/set-native-datatype :int64))

    :DUCKDB_TYPE_UTINYINT
    (-> (native-buffer/wrap-address data-ptr n-rows nil)
        (native-buffer/set-native-datatype :uint8))

    :DUCKDB_TYPE_USMALLINT
    (-> (native-buffer/wrap-address data-ptr (* 2 n-rows) nil)
        (native-buffer/set-native-datatype :uint16))

    :DUCKDB_TYPE_UINTEGER
    (-> (native-buffer/wrap-address data-ptr (* 4 n-rows) nil)
        (native-buffer/set-native-datatype :uint32))

    :DUCKDB_TYPE_UBIGINT
    (-> (native-buffer/wrap-address data-ptr (* 8 n-rows) nil)
        (native-buffer/set-native-datatype :uint64))

    :DUCKDB_TYPE_FLOAT
    (-> (native-buffer/wrap-address data-ptr (* 4 n-rows) nil)
        (native-buffer/set-native-datatype :float32))

    :DUCKDB_TYPE_DOUBLE
    (-> (native-buffer/wrap-address data-ptr (* 8 n-rows) nil)
        (native-buffer/set-native-datatype :float64))

    :DUCKDB_TYPE_DATE
    (-> (native-buffer/wrap-address data-ptr (* 4 n-rows) nil)
        (native-buffer/set-native-datatype :packed-local-date))

    :DUCKDB_TYPE_TIME
    (-> (native-buffer/wrap-address data-ptr (* 8 n-rows) nil)
        (native-buffer/set-native-datatype :packed-local-time))

    :DUCKDB_TYPE_TIMESTAMP
    (-> (native-buffer/wrap-address data-ptr (* 8 n-rows) nil)
        (native-buffer/set-native-datatype :packed-instant))

    :DUCKDB_TYPE_VARCHAR
;;     typedef struct {
;; 	union {
;; 		struct {
;; 			uint32_t length;
;; 			char prefix[4];
;; 			char *ptr;
;; 		} pointer;
;; 		struct {
;; 			uint32_t length;
;; 			char inlined[12];
;; 		} inlined;
;; 	} value;
;; } duckdb_string_t;
    (let [string-t-width 16
          inline-len 12
          nbuf (native-buffer/wrap-address data-ptr (* string-t-width n-rows) nil)]
      (dt/make-reader
       :string n-rows
       (let [len-off (* idx string-t-width)
             slen (native-buffer/read-int nbuf len-off)]
         #_(println "reading string at idx" idx len-off slen)
         (if (<= slen inline-len)
           (let [soff (+ len-off 4)]
             (String. (dt/->byte-array (dt/sub-buffer nbuf soff slen))))
           (let [ptr-off (+ len-off 8)
                 ptr-addr (native-buffer/read-long nbuf ptr-off)]
             (String. (dt/->byte-array (native-buffer/wrap-address ptr-addr slen))))))))
    (throw (RuntimeException. (format "Failed to get a valid column type for integer type %d" duckdb-type)))))


(defn- map->struct
  [dtype data track-type]
  (let [rv (dt-struct/new-struct dtype {:container-type :native-heap
                                        :resource-type track-type})]
    (reduce (fn [acc e]
              (.put ^Map rv (key e) (val e)))
            false
            data)
    rv))


(defn- results->datasets
  [duckdb-result options]
  (let [metadata {:duckdb-result duckdb-result}
        n-cols (long (duckdb-ffi/duckdb_column_count duckdb-result))
        names (hamf/mapv #(dt-ffi/c->string (duckdb-ffi/duckdb_column_name duckdb-result %)) (hamf/range n-cols))
        types (hamf/mapv #(let [db-type (duckdb-ffi/duckdb_column_logical_type duckdb-result %)
                                ;;complex work-around so we have a pointer to release as the destroy fn takes
                                ;;a ptr and not the thing by copying.
                                type-ptr (map->struct :duckdb-logical-type db-type :auto)]
                            (resource/track db-type {:track-type :auto
                                                     :dispose-fn (fn [] (duckdb-ffi/duckdb_destroy_logical_type type-ptr))})
                            db-type)
                         (hamf/range n-cols))
        type-ids (lznc/map #(duckdb-ffi/duckdb_get_type_id %) types)
        realize-chunk (fn [data-chunk]
                        (try
                          (let [n-rows (duckdb-ffi/duckdb_data_chunk_get_size data-chunk)]
                            (->> (hamf/range n-cols)
                                 (hamf/mapv (fn [cidx]
                                              (let [vdata (duckdb-ffi/duckdb_data_chunk_get_vector data-chunk cidx)
                                                    ^Pointer data-ptr (duckdb-ffi/duckdb_vector_get_data vdata)
                                                    missing (duckdb-ffi/duckdb_vector_get_validity vdata)]
                                                #:tech.v3.dataset {:name (names cidx)
                                                                   :missing (validity->missing n-rows missing)
                                                                   :data (coldata->buffer n-rows
                                                                                          (type-ids cidx)
                                                                                          (.-address data-ptr))
                                                                   ;;skip any further scanning
                                                                   :force-datatype? true})))
                                 (ds/new-dataset options (assoc metadata :data-chunk data-chunk))))))]
    (if (== 0 (long (duckdb-ffi/duckdb_result_is_streaming duckdb-result)))
      (let [chunk-count (duckdb-ffi/duckdb_result_chunk_count duckdb-result)]
        (->> (hamf/range chunk-count)
             (lznc/map #(let [chunk (duckdb-ffi/duckdb_result_get_chunk duckdb-result %)
                              chunk-ptr (map->struct :duckdb-data-chunk chunk :auto)]
                          (realize-chunk (resource/track chunk {:track-type :auto
                                                                :dispose-fn (fn []
                                                                              (duckdb-ffi/duckdb_destroy_data_chunk chunk-ptr))}))))))
      (throw (RuntimeException. "Streaming results are not supported at this time")))))


(defn sql->datasets
  "Execute a query returning a dataset iterator.  Most data will be read in-place in the result
  set which will be link via metadata to the returned dataset.  If you wish to release
  the data immediately wrap call in `tech.v3.resource/stack-resource-context` and clone
  each result.

  Example:


```clojure

  ;; !!Recommended!! - Results copied into jvm and duckdb-result released immediately after query

tmducken.duckdb> (resource/stack-resource-context
                  (mapv dt/clone (sql->datasets conn \"select * from stocks\")))
_unnamed [560 3]:

| symbol |       date | price |
|--------|------------|------:|
|   MSFT | 2000-01-01 | 39.81 |
|   MSFT | 2000-02-01 | 36.35 |
|   MSFT | 2000-03-01 | 43.22 |
|   MSFT | 2000-04-01 | 28.37 |
|   MSFT | 2000-05-01 | 25.45 |
|   MSFT | 2000-06-01 | 32.54 |
|   MSFT | 2000-07-01 | 28.40 |



  ;; Results read in-place, duckdb-result released as some point after dataset falls
  ;; out of scope.  Be extremely careful with this one.

tmducken.duckdb> (ds/head (execute-query! conn \"select * from stocks\"))
_unnamed [5 3]:

| symbol |       date | price |
|--------|------------|------:|
|   MSFT | 2000-01-01 | 39.81 |
|   MSFT | 2000-02-01 | 36.35 |
|   MSFT | 2000-03-01 | 43.22 |
|   MSFT | 2000-04-01 | 28.37 |
|   MSFT | 2000-05-01 | 25.45 |
```"
  ([conn sql options]
   (-> (run-query! conn sql options)
       (results->datasets options)))
  ([conn sql]
   (sql->datasets conn sql nil)))


(defn sql->dataset
  "Execute a query returning a single dataset.  This runs the query in a context that releases the memory used
  for the result set before function returns returning a dataset that has no native bindings."
  ([conn sql options]
   (resource/stack-resource-context
    (apply ds/concat (sql->datasets conn sql options))))
  ([conn sql] (sql->dataset conn sql nil)))

(comment
  (def stocks
    (-> (ds/->dataset "https://github.com/techascent/tech.ml.dataset/raw/master/test/data/stocks.csv" {:key-fn keyword})
        (vary-meta assoc :name :stocks)))
  (initialize!)
  (def db (open-db))
  (def conn (connect db))

  (create-table! conn stocks)
  (insert-dataset! conn stocks)
  (def res (run-query! conn "select * from stocks"))
  )
