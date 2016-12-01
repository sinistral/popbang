
(ns pop.tx.put
  (:require
   [cljs.nodejs            :as    nodejs]
   [cats.builtin]
   [cats.core              :as    cat]
   [cats.context           :as    ctx]
   [cats.labs.channel      :as    chn]
   [cats.monad.exception   :as    end]
   [cognitect.transit      :as    transit]
   [goog.crypt.base64      :as    base64]
   [pop.ddb                :as    ddb]))

(defn put-tx
  [tx]
  (prn tx)
  tx)

(defn unpack-tx
  [rec]
  (transit/read
   (transit/reader :json)
   (base64/decodeString (-> rec .-kinesis .-data))))

(defn filter-not-exists
  [tablenames]
  (ctx/with-context chn/context
    (cat/mlet [res-channels (cat/traverse ddb/async:table-exists? tablenames)]
      (cat/return
       (ctx/with-context end/context
         (cat/mlet [res-statuses (cat/sequence res-channels)]
           (cat/return (filter #(not (:exists? %)) res-statuses))))))))

(defn create-tx-log-tables
  [tablenames]
  (ctx/with-context chn/context
    (letfn [(make-table [name]
              (ddb/async:make-table "tx-log" :instance-name name))]
      (cat/mlet [res-channels (cat/traverse make-table tablenames)]
        (cat/return
         (ctx/with-context end/context
           (cat/mlet [res-status (cat/sequence res-channels)]
             (cat/return res-status))))))))

(defn ^{:export true} main
  [event context callback]
  (let [txs (map unpack-tx (.-Records event))
        chn (->> txs
                 (map #(get % "timestamp"))
                 (map ddb/tx-log-tablename-for-ts)
                 (set)                  ; eliminate duplicates
                 (vec)                  ; sets are not Traversable
                 (filter-not-exists))]
    ;; All of tables to; which the transactions will be written must exist.  If
    ;; any do not, request the creation of the tables and fail the entire
    ;; batch.  AWS Lambda will keep processing these records until the
    ;; processing succeeds (which it should once the tables have been created),
    ;; or the records expire and are removed from the AWS Kinesis stream.
    (cat/mlet [dne chn
               ctd (create-tx-log-tables (map :tablename @dne))]
      (cat/return (end/success @ctd)))))

(set! *main-cli-fn* main)
