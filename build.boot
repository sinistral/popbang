
(set-env!
 :dependencies
 '[[org.clojure/clojure         "1.8.0"]

   ;; http://dev.clojure.org/jira/browse/CLJS-1862
   [org.clojure/clojurescript   "1.9.342"]

   [org.clojure/core.async      "0.2.395"]
   [com.cognitect/transit-cljs  "0.8.239"]
   [funcool/cats                "2.0.0"]
   [swiss-arrows                "1.0.0"]

   ;; CLJS-build and REPL dependencies.
   [adzerk/boot-cljs            "1.7.228-1"      :scope "test"]
   [com.cemerick/piggieback     "0.2.1"          :scope "test"]
   [crisptrutski/boot-cljs-test "0.2.2-SNAPSHOT" :scope "test"]
   [degree9/boot-npm            "0.2.0"          :scope "test"]
   [org.clojure/tools.nrepl     "0.2.12"         :scope "test"]
   [stencil                     "0.5.0"          :scope "test"]])

(require
 '[clojure.java.io             :as io]
 '[clojure.set                 :refer [difference subset?]]
 '[clojure.string              :as str]
 '[adzerk.boot-cljs            :refer [cljs]]
 '[boot.pod                    :as    pod]
 '[boot.repl]
 '[cljs.repl.node]
 '[cemerick.piggieback         :as    piggieback]
 '[crisptrutski.boot-cljs-test :refer [exit! test-cljs]]
 '[degree9.boot-npm]
 '[stencil.core                :refer [render-file]])

(swap! boot.repl/*default-middleware* conj 'cemerick.piggieback/wrap-cljs-repl)

(set-env! :source-paths
          #{"source" "test"})
(set-env! :resource-paths
          #{"build-resources/cljs-config" "build-resources/templates"})

(deftask export-handler
  "Injects the into main `.js` file the NodeJS `exports` assignment that
  exposes the handler function to AWS Lambda."
  [i ids SET #{str} "The builds for which handlers are to be defined."]
  (let [tmpdir       (tmp-dir!)
        handler-name "handler"]
    (with-pre-wrap fileset
      (empty-dir! tmpdir)
      (letfn [(cljfn->jsfn [name]
                (str/replace name #"/" "."))
              (render-insert [handler-data]
                (render-file "handler-nodejs-export" handler-data))
              (emit-handler [fs id]
                (let [edn-file (first (by-name [(str id ".cljs.edn")] (input-files fs)))
                      edn      (read-string (slurp (tmp-file edn-file)))
                      fn-name  (cljfn->jsfn (str (:aws-lambda/handler edn)))
                      tmpl-dat {:export-as handler-name :export-fn fn-name}
                      insert   (render-insert tmpl-dat)
                      js-file  (first (by-name [(str id ".js")] (input-files fs)))
                      out-file (io/file tmpdir (tmp-path js-file))]
                  (info "Defining handler \"%s\" using spec: %s%n"
                        (str/join "." [id handler-name])
                        tmpl-dat)
                  (spit out-file (str (slurp (tmp-file js-file)) insert))
                  (-> fs
                      (rm [edn-file js-file])   ; remove defunct .js
                      (add-resource tmpdir))))] ; add updated .js
        (commit! (reduce emit-handler fileset ids))))))

(deftask npm
  []
  (degree9.boot-npm/npm :install {:aws-sdk "2.6.9"}))

(def +build-ids+
  #{"popbang-tx-put"})

(deftask dev
  []
  (comp
   (npm)
   (cljs :ids +build-ids+ :optimizations :none)
   (target :dir #{"out"})))

(defn start-cljs-repl
  "Entry point for REPL-based development."
  []
  @(future (boot (dev)))
  (piggieback/cljs-repl (cljs.repl.node/repl-env :path ["out/node_modules"])))

(deftask +build
  [i ids SET #{str} "The IDs of the lambda functions to build; if unspecified, all known lambda functions will be built.  (See also: `+build-ids+`)"]
  (when (not (subset? ids +build-ids+))
    (throw (let [invalid-ids (difference ids +build-ids+)]
             (ex-info
              (format "Invalid lambda function IDs: %s" invalid-ids)
              {:valid-ids   +build-ids+
               :invalid-ids invalid-ids}))))
  (let [build-ids (if (empty? ids) +build-ids+ ids)]
    (comp
     (cljs :ids build-ids :optimizations :none)
     (export-handler :ids build-ids)
     ;; TODO: make zip archive for upload.
     (target :dir #{"out"}))))
