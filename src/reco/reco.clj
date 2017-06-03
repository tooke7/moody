(ns reco.reco
  (:gen-class
   :implements [clojure.lang.IDeref]
   :state state
   :methods [[add_event [String, String, String, boolean, long] void]
             [add_event [String, String, String, boolean] void]
             [add_event [java.util.Map, boolean, long] void]
             [pick_next [] java.util.Map]
             [new_session [] void]
             [update_model [] void]]
   :init init
   :constructors {[java.util.Collection] []}))

(use '[clojure.set :only [intersection union difference]])
(use '[clojure.math.combinatorics :only [combinations]])
(use '[clojure.pprint :only [pprint]])
;(use '[clojure.java.jdbc :as jd])

(def debug false)

(def ses-threshold (* 30 60))
(def k 5)
(def a {"artist" "a" "album" "a" "title" "a"})
(def b {"artist" "b" "album" "b" "title" "b"})
(def c {"artist" "c" "album" "c" "title" "c"})
(def d {"artist" "d" "album" "d" "title" "d"})
(def e {"artist" "e" "album" "e" "title" "e"})
(def f {"artist" "f" "album" "f" "title" "f"})
(def g {"artist" "g" "album" "g" "title" "g"})
(def h {"artist" "h" "album" "h" "title" "h"})

(defn now []
  (quot (System/currentTimeMillis) 1000))

(defn get-frequency [songs]
  (loop [freq {}
         songs-left songs]
    (if (empty? songs-left)
      (assoc freq "<unknown>" 1 nil 1)
      (let [artist ((first songs-left) "artist")]
        (recur (update freq artist #(if (nil? %) 1 (inc %)))
               (rest songs-left))))))

(defn -init [songs]
  ; into turns the java hashmap into a normal hashmap or something. Otherwise,
  ; the get-in call in mk-candidate always returns 0.
  (let [converted-songs (map #(into {} %) songs)]
    [[] (atom {:songs (vec converted-songs)
               :artist-frequency (get-frequency converted-songs)
               :sessions '()
               :last-time 0
               :model nil
               :freshness {}
               :candidates nil})]))

(defn -new_session [this]
  (swap! (.state this)
         (fn [state]
           (assoc state
                  :sessions (cons nil (:sessions state))))))

(defn ses-append [sessions mdata skipped create-session]
  (if create-session
    (cons {mdata skipped} sessions)
    (if (contains? (first sessions) mdata)
      sessions
      (cons (assoc (first sessions) mdata skipped)
            (rest sessions)))))

(defn mini-model [session]
  (->> (combinations (set (keys session)) 2)
       (filter (fn [[a b]] (not= (session a) (session b) true)))
       (map (fn [[a b]]
              (let [score (if (= (session a) (session b) false) 1 0)]
                [{(set [a b])
                  {:num score
                   :den 1}}
                 {(set [(a "artist") (b "artist")])
                  {:num score
                   :den 1}}])))))

(defn merge-models [models]
  (apply merge-with #(merge-with + %1 %2) models))

(defn convert-cell [[k v]]
  [k {:score (- (* 2 (/ (:num v) (max 1 (:den v))))
                1)
      :n (:den v)}])

(defn mk-model [sessions]
  (->> sessions (map mini-model) flatten
       merge-models (map convert-cell) (into {})))

(defn update-score [old-score old-n sim]
  (let [new-n (inc old-n)
        new-score (/ (+ (* old-score old-n) sim) new-n)]
    [new-score new-n]))

(defn calc-margin [n]
  (/ 1 (Math/pow 1.1 n)))
(def calc-margin (memoize calc-margin))

(defn calc-confidence [n]
  (- 1 (/ 1 (Math/pow 1.5 n))))
(def calc-confidence (memoize calc-confidence))

(defn calc-final-score [score n content-score freshness]
  (let [confidence (calc-confidence n)
        hybrid-mean (+ (* confidence score)
                       (* (- 1 confidence) content-score))
        margin (calc-margin n)
        final-score (* (+ hybrid-mean margin) freshness)]
    final-score))

(defn update-final-score [data]
  (let [{score :score n :n content-score :content-score freshness :freshness} data]
    (assoc data :final-score (calc-final-score score n content-score freshness))))

(defn sim-score [model a b skipped]
  (let [sim (if (= a b) 1 (get-in model [(set [a b]) :score]))]
    (if (or (nil? sim) (and skipped (< sim 0)))
      nil
      (* sim (if skipped -1 1)))))

(defn update-candidates
  [{candidates :candidates model :model
    artist-frequency :artist-frequency}
   mdata skipped]
  (map (fn [data]
         (let [sim (sim-score model mdata (:mdata data) skipped)
               content-sim (sim-score model (mdata "artist")
                                      (get-in data [:mdata "artist"])
                                      skipped)
               assoc-score (fn [result score-key n-key sim]
                             (let [[new-score new-n]
                                   (update-score (score-key data) (n-key data) sim)]
                               (assoc result score-key new-score n-key new-n)))]
           (cond-> data
             sim (assoc-score :score :n sim)
             content-sim (assoc-score :content-score :content-n content-sim)
             (or sim content-sim) update-final-score)))
       (remove #(= (:mdata %) mdata) candidates)))

(defn -add_event
  ([this, mdata, skipped, timestamp]
   (swap! (.state this)
          (fn [state]
            (let [new-session (> timestamp (+ (:last-time state) ses-threshold))]
              (cond-> state
                  true (assoc :last-time timestamp)
                  true (update :sessions ses-append mdata skipped new-session)

                  (and new-session (:model state))
                  (assoc :model (mk-model (:sessions state)))

                  (not skipped) (assoc-in [:freshness mdata] timestamp)
                  (:model state) (assoc :candidates (update-candidates
                                                      state mdata skipped)))))))
  ([this, artist, album, title, skipped, timestamp]
   (.add_event this {"artist" artist "album" album "title" title}
               skipped timestamp))
  ([this, artist, album, title, skipped]
   (.add_event this artist album title skipped (now))))

(defn dbg [desc arg]
  (when debug
    (print (str desc ": "))
    (pprint arg))
  arg)

(defn init-candidates [songs freshness]
  (map (fn [song]
         (let [freshness (- 1 (Math/exp (/ (- (get freshness song 0)
                                              (now)) 86400)))]
           {:mdata song
            :freshness freshness
            :score 1/2
            :n 1
            :content-score 1/2
            :content-n 1
            :final-score (calc-final-score 1/2 1 1/2 freshness)}))
       songs))

(defn -update_model [this]
  (swap! (.state this)
         (fn [state]
           (assoc state :model (mk-model (:sessions state))
                  :candidates (doall (init-candidates (:songs state)
                                                      (:freshness state)))))))

(defn candidate-key [candidate frequency]
  [(:final-score candidate)
   (frequency (get-in candidate [:mdata "artist"]))])

(defn max-by
  ([k x] x)
  ([k x y] (if (> (compare (k x) (k y)) 0) x y))
  ([k x y & more]
   (reduce #(max-by k %1 %2) (max-by k x y) more)))

(defn pick-next
  [{candidates :candidates frequency :artist-frequency}]
  (:mdata (apply max-by #(candidate-key % frequency) candidates)))

(defn -pick_next [this]
  (pick-next @@this))

(defn -deref [this]
  (.state this))

(defn test= [actual expected]
  (when (not= actual expected)
    (print "expected: ")
    (pprint expected)
    (print "got: ")
    (pprint actual)
    (throw (new AssertionError "Test failed"))))

(defn test-mini-model []
  (assert (= (mini-model {a false
                          b false
                          c false
                          d true})
             '({#{{"artist" "c", "album" "c", "title" "c"}
                  {"artist" "a", "album" "a", "title" "a"}}
                {:num 1, :den 1}}
               {#{"a" "c"} {:num 1, :den 1}}
               {#{{"artist" "c", "album" "c", "title" "c"}
                  {"artist" "d", "album" "d", "title" "d"}}
                {:num 0, :den 1}}
               {#{"d" "c"} {:num 0, :den 1}}
               {#{{"artist" "c", "album" "c", "title" "c"}
                  {"artist" "b", "album" "b", "title" "b"}}
                {:num 1, :den 1}}
               {#{"b" "c"} {:num 1, :den 1}}
               {#{{"artist" "a", "album" "a", "title" "a"}
                  {"artist" "d", "album" "d", "title" "d"}}
                {:num 0, :den 1}}
               {#{"d" "a"} {:num 0, :den 1}}
               {#{{"artist" "a", "album" "a", "title" "a"}
                  {"artist" "b", "album" "b", "title" "b"}}
                {:num 1, :den 1}}
               {#{"a" "b"} {:num 1, :den 1}}
               {#{{"artist" "d", "album" "d", "title" "d"}
                  {"artist" "b", "album" "b", "title" "b"}}
                {:num 0, :den 1}}
               {#{"d" "b"} {:num 0, :den 1}}))))

(defn test-model []
  (let [rec (new reco.reco [a b c d e f g h])]
    (.add_event rec "a" "a" "a" true 2000)
    (.add_event rec "b" "b" "b" false 2000)
    (.add_event rec "c" "c" "c" false 2000)

    (.add_event rec "a" "a" "a" false 4000)
    (.add_event rec "b" "b" "b" false 4000)

    (.update_model rec)

    (assert (= (:sessions @@rec)
               '({{"artist" "a", "album" "a", "title" "a"} false,
                  {"artist" "b", "album" "b", "title" "b"} false}
                 {{"artist" "a", "album" "a", "title" "a"} true,
                  {"artist" "b", "album" "b", "title" "b"} false,
                  {"artist" "c", "album" "c", "title" "c"} false})))
    (assert (= (:model @@rec)
               {#{{"artist" "a", "album" "a", "title" "a"}
                  {"artist" "b", "album" "b", "title" "b"}}
                {:score 0N, :n 2},
                #{"a" "b"} {:score 0N, :n 2},
                #{{"artist" "c", "album" "c", "title" "c"}
                  {"artist" "a", "album" "a", "title" "a"}}
                {:score -1, :n 1},
                #{"a" "c"} {:score -1, :n 1},
                #{{"artist" "c", "album" "c", "title" "c"}
                  {"artist" "b", "album" "b", "title" "b"}}
                {:score 1, :n 1},
                #{"b" "c"} {:score 1, :n 1}}))
    (assert (= (:freshness @@rec)
               {{"artist" "b", "album" "b", "title" "b"} 4000,
                {"artist" "c", "album" "c", "title" "c"} 2000,
                {"artist" "a", "album" "a", "title" "a"} 4000}))))

(defn test-pick-next [artist-frequency skip-sequence next-artist]
  (let [library (for [[artist n] artist-frequency
                      title (range n)]
                  {"artist" artist "album" artist "title" title})
        rec (new reco.reco library)]
    (.update_model rec)
    (doseq [action skip-sequence]
      (.add_event rec (.pick_next rec) action (now)))
    (test=
      ((.pick_next rec) "artist")
      next-artist)))

(defn demo-pick-next [artist-frequency skip-sequence]
  (let [library (for [[artist n] artist-frequency
                      title (range n)]
                  {"artist" artist "album" artist "title" title})
        rec (new reco.reco library)]
    (.update_model rec)
    (doseq [action skip-sequence]
      (let [n (.pick_next rec)]
        (.add_event rec n action (now))))
    (pprint (:candidates @@rec))))

(defn wait []
  (print "Press Enter to continue")
  (flush)
  (read-line))

;(defn demo-real-data []
;  (println "adding library")
;  (let [db {:classname   "org.sqlite.JDBC"
;            :subprotocol "sqlite"
;            :subname     "moody.db"}
;        library (map #(->> %
;                           (filter (fn [[k v]] (not= k :_id)))
;                           (map (fn [[k v]] [(name k) v]))
;                           (into {}))
;                     (jd/query db "select * from songs"))
;        rec (new reco.reco library)
;        parser (new java.text.SimpleDateFormat "yyyy-MM-dd HH:mm:ss")]
;    (println "adding events")
;    (doseq [{timestamp :time title :title artist :artist album :album
;             skipped :skipped}
;            (jd/query db "select time, title, artist, album, skipped from songs s
;                         join events e on s._id = e.song_id order by time asc")]
;      (let [seconds (.getTime (.parse parser timestamp))]
;        (.add_event rec artist album title
;                    (if (= skipped 1) true false) seconds)))
;    (println "updating model")
;    (.update_model rec)
;    (println "making recommendations")
;    ;(wait)
;    (loop [next-song (.pick_next rec)
;           ;actions [true true false true false false true true]]
;           actions (repeat 500 true)]
;      (when (not (empty? actions))
;        ;(pprint next-song)
;        ;(println (if (first actions) "skip" "listen"))
;        ;(println)
;        (.add_event rec next-song (first actions) (now))
;        (recur (.pick_next rec)
;               (rest actions))))))

(defn -main [& args]
  (println "starting up")
  ;(test-mini-model)
  ;(test-get-sim-score)
  ;(test-get-content-mean)
  ;(test-mk-candidate)
  ;(test-model)
  ;(test-pick-next [["a" 5] ["b" 10] ["c" 7]] nil "b")
  ;(test-pick-next [["a" 2] ["b" 1]] [true] "b")
  ;(test-pick-next [["a" 3] ["b" 1]] [false false] "b")

  ;(demo-pick-next [["a" 2] ["b" 1]] [true])
  ;(demo-pick-next [["a" 2] ["b" 1]] [false])
  ;(demo-real-data)
  (println "all tests pass"))
