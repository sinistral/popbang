
(ns pop.tx.put-test
  (:require [cljs.test :refer-macros [deftest testing is]]))

(def +kinesis-event+
  (clj->js
   {"Records" [{"kinesis"           {"partitionKey"         "partitionKey-3"
                                     "kinesisSchemaVersion" "1.0"
                                     "data"                 "ewogICAgInRpbWVzdGFtcCI6ICIyMDE2MTEyOFQxNTo1MzowMFoiLAogICAgImNhdGVnb3J5IjogIk92ZXJoZWFkIiwKICAgICJ2ZW5kb3IiOiAiVGhlIFNhdm95IGF0IERheXRvbiBTdGF0aW9uIiwKICAgICJub3RlcyI6ICJSZW50Igp9"
                                     "sequenceNumber"       "49545115243490985018280067714973144582180062593244200961"}
                "eventSource"       "aws:kinesis"
                "eventID"           "shardId-000000000000:49545115243490985018280067714973144582180062593244200961"
                "invokeIdentityArn" "arn:aws:iam::account-id:role/testLEBRole"
                "eventVersion"      "1.0"
                "eventName"         "aws:kinesis:record"
                "eventSourceARN"    "arn:aws:kinesis:us-west-2:35667example:stream/examplestream"
                "awsRegion"         "us-west-2"}]}))

