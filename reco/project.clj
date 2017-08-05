(defproject reco "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/math.combinatorics "0.1.4"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/java.jdbc "RELEASE"]
                 [org.xerial/sqlite-jdbc "3.7.2"]
                 ]
  :main reco.reco
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  ;:jvm-opts ["-Dcom.sun.management.jmxremote"
  ;         "-Dcom.sun.management.jmxremote.ssl=false"
  ;         "-Dcom.sun.management.jmxremote.authenticate=false"
  ;         "-Dcom.sun.management.jmxremote.port=43210"]
  :javac-options ["-Dclojure.compiler.direct-linking=true"])
