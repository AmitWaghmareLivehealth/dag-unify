(defproject dag_unify "1.0.0"
  :description "Unification of Directed Acyclic Graphs"
  :url "https://github.com/ekoontz/dag-unify"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/clojurescript "1.7.170"]]
  :plugins [[cider/cider-nrepl "0.10.0-SNAPSHOT"]
            [lein-doo "0.1.6"]
            [lein-cljsbuild "1.1.2"]]

  ;; run clojure tests with "lein test"
  ;; run clojurescript tests with "lein doo phantom test once"
  :cljsbuild {:builds [{:id "test"
                        :source-paths ["src" "test"]
                        :compiler {:output-to "out/testable.js"
                                   ;; you must have {:optimizations :whitespace}
                                   ;; to avoid "ReferenceError: Can't find variable: goog"
                                   :optimizations :whitespace}}]}
  :doo {:paths {:phantom "phantomjs --web-security=false"
                :slimer "slimerjs --ignore-ssl-errors=true"
                :karma "karma --port=9881 --no-colors"
                :rhino "rhino -strict"
                :node "node --trace-gc --trace-gc-verbose"}}
  ;; doesn't seem to work as expected: 'No such namespace: doo.runner'
  ;;  :hooks [leiningen.cljsbuild]
  )
