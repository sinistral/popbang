
(ns pop.ddb
  "Convenience wrapper for the AWS Javascript SDK DynamoDB module."
  (:require-macros
   [cljs.core.async.macros :refer [go]]
   [swiss.arrows           :refer [-<>]])
  (:require
   [cljs.core.async        :refer [>! chan close!]]
   [cljs.nodejs            :as    nodejs]
   [cljs.pprint            :refer [cl-format]]
   [clojure.string         :as    s]
   [clojure.walk           :refer [postwalk]]
   [cats.core              :as    cat]
   [cats.labs.channel      :as    chn]
   [cats.monad.exception   :as    end]
   [goog.date              :as    dt]))

(def ^{:private true} AWS (nodejs/require "aws-sdk"))

(def ^{:private true} schema
  [{:name "tx-log"
    :attributes [;; The key type order is important; HASH must precede RANGE.
                 {:name "capture-ts" :type "S" :key-type "HASH"}
                 {:name "tx-ts"      :type "S" :key-type "RANGE"}
                 ;; Non-key attributes
                 {:name "category"   :type "S"}
                 {:name "vendor"     :type "S"}
                 {:name "notes"      :type "S"}]
    :provisioned-throughput {:read-capacity-units 1 :write-capacity-units 1}}])

(def ^{:private true} ddb (AWS.DynamoDB.
                           (clj->js {:apiVersion       "2012-08-10"
                                     :region           "eu-west-1"
                                     :signatureVersion "v4"})))

(defn- jsify
  [m]
  (postwalk #(if (keyword? %)
               (-<> %
                    (name)
                    (s/split #"-")
                    (map s/capitalize <>)
                    (s/join))
               %)
            m))

(defn tx-log-tablename-for-ts
  "Returns the name of the table for the transaction timetamp, an
  ISO-8601-formatted string."
  [ts]
  (let [datetime (dt/fromIsoString ts)]
    (str "tx-log-" (.getFullYear datetime) (inc (.getMonth datetime)))))

(defn- make-attr-def
  [spec]
  {:AttributeName (:name spec)
   :AttributeType (:type spec)})

(defn- make-key-def
  [spec]
  {:AttributeName (:name spec)
   :KeyType (:key-type spec)})

(defn- make-provisioned-throughput
  [spec]
  (jsify (select-keys spec [:provisioned-throughput])))

(defn- make-request:create-table
  [spec & {:keys [name]}]
  (let [[as ks] ((juxt #(map make-attr-def %) #(map make-key-def %))
                 (filter :key-type (:attributes spec)))
        tn      (or name (:name spec))]
    (clj->js
     (merge
      {"TableName"            tn
       "AttributeDefinitions" as
       "KeySchema"            ks}
      (make-provisioned-throughput spec)))))

(defn- async:make-table-from-schema
  [schema name & {:keys [instance-name]}]
  (let [c (chan)]
    (if-let [table-schema (first (filter #(= name (:name %)) schema))]
      (.createTable ddb
                    (make-request:create-table table-schema :name (or instance-name name))
                    (fn [err, rsp]
                      (go
                        (>! c (if rsp (end/success rsp) (end/failure err)))
                        (close! c))))
      (go
        (>! c (end/failure {:table-name name}
                           (cl-format nil "No schema definition for table ~a" name)))))
    c))

(def async:make-table
  (partial async:make-table-from-schema schema))

(defn async:table-exists?
  [t]
  (let [c       (chan)
        rnfe?   #(when % (= (.-name %) "ResourceNotFoundException"))
        active? #(= "ACTIVE" (get-in % [:Table :TableStatus]))]
    (.describeTable ddb
                    #js{"TableName" t}
                    (fn [err, rsp]
                      (go
                        (>! c (if-let [spec (when rsp (js->clj rsp :keywordize-keys true))]
                                ;; A table description of some kind was returned.
                                (do
                                  (if (active? spec)
                                    (end/success {:tablename t :exists? true})
                                    (end/success {:tablename t :exists? false})))
                                ;; An error occurred.
                                (if (rnfe? err)
                                  (end/success {:tablename t :exists? false})
                                  (end/failure err))))
                        (close! c))))
    c))
