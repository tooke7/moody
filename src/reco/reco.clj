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
    [[] (atom {:songs converted-songs
               :artist-frequency (get-frequency converted-songs)
               :sessions '()
               :last-time 0
               :model {}
               :freshness {}})]))

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
                   :den 1}}])))
       flatten))

(defn merge-models [models]
  (apply merge-with #(merge-with + %1 %2) models))

(defn convert-cell [[k v]]
  [k {:score (- (* 2 (/ (:num v) (max 1 (:den v))))
                1)
      :n (:den v)}])

(defn mk-model [sessions]
  (->> sessions (map mini-model) flatten
       merge-models (map convert-cell) (into {})))

(defn -add_event
  ([this, mdata, skipped, timestamp]
   (swap! (.state this)
          (fn [state]
            (let [new-session (> timestamp (+ (:last-time state) ses-threshold))]
              (assoc state :last-time timestamp
                     :sessions (ses-append (:sessions state)
                                           mdata
                                           skipped
                                           new-session)
                     :model (if (and new-session (:model state))
                              (mk-model (:sessions state))
                              (:model state))
                     :freshness (if-not skipped
                                  (assoc (:freshness state)
                                         mdata timestamp)
                                  (:freshness state)))))))
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

(defn wrand 
  "given a vector of slice sizes, returns the index of a slice given a
  random spin of a roulette wheel with compartments proportional to
  slices."
  [slices]
  (let [total (reduce + slices)
        r (rand total)]
    (loop [i 0 sum 0]
      (if (< r (+ (slices i) sum))
        i 
        (recur (inc i) (+ (slices i) sum))))))

(defn -update_model [this]
  (swap! (.state this)
         (fn [state]
           (assoc state :model (mk-model (:sessions state))))))

(defn calc-freshness [mdata freshness cur]
  (let [count-artist? (fn [[key-mdata skipped]]
                        (and (not skipped)
                             (= (mdata "artist")
                                (key-mdata "artist"))))
        artist-occurrences (count (filter count-artist? cur))]
    ; Penalize the song if we've heard it recently. Also penalize if we've
    ; already played at least two songs from the same artist in the current
    ; session.
    (* (- 1 (Math/exp (/ (- (get freshness mdata 0) (now)) 86400)))
       (/ 1 (Math/pow 2 (max 0 (- artist-occurrences 1)))))))

(defn margin-of-error [score n]
  (* 1.28 (Math/sqrt (/ (* score (- 1 score)) (max n 1)))))

(defn get-sim-score [sim skipped]
  (if (or (nil? sim) (and skipped (< sim 0)))
    [0 0]
    [(* sim (if skipped -1 1)) 1]))

(defn get-sim [model a b]
  (if (= a b)
    1
    (get-in model [(set [a b]) :score])))

(defn get-content-mean [{cand-artist "artist"} model cur]
  (let [[mean n] (as-> cur result
                   (map (fn [[song skipped]]
                          (get-sim-score (get-sim model (song "artist") cand-artist)
                                         skipped))
                        result)
                   (apply map + result))]
    ; the +1's give each artist pair a default similarity of 1.
    (/ (+ 1 mean) (+ 1 n))))

(defn get-raw-mean [model candidate cur]
  (as-> cur result
    (map (fn [[song skipped]]
           (get-sim-score (get-sim model song candidate)
                          skipped))
         result)
    (apply map + result)
    (apply vector result)
    (update result 0 #(/ % (max 1 (last result))))))

(defn mk-candidate [candidate model freshness cur]
  (if (= (count cur) 0)
    {:mdata candidate
     :score 1}
    (let [[raw-mean n] (get-raw-mean model candidate cur)
          confidence (- 1 (/ 1 (Math/pow 1.5 n)))
          content-mean (get-content-mean candidate model cur)
          hybrid-mean (+ (* confidence raw-mean)
                         (* (- 1 confidence) content-mean))
          margin (/ 1 (Math/pow 1.1 n))
          fresh-score (calc-freshness candidate freshness cur)
          final-score (* (+ hybrid-mean margin) fresh-score)]
      (dbg "candidate" candidate)
      (dbg "raw-mean" raw-mean)
      (dbg "n" n)
      (dbg "confidence" confidence)
      (dbg "content-mean" content-mean)
      (dbg "hybrid-mean" hybrid-mean)
      (dbg "margin" margin)
      (dbg "fresh-score" fresh-score)
      (dbg "final-score" final-score)
      (when debug (println))
      {:mdata candidate
       :score final-score})))

(defn candidate-key [candidate frequency]
  [(:score candidate)
   (frequency (get-in candidate [:mdata "artist"]))])

(defn pick-next [model freshness cur songs frequency]
  (let [candidates (->> (difference (set songs) (set (keys cur)))
                        (map #(mk-candidate % model freshness cur))
                        (filter #(< 0 (:score %)))
                        (sort-by #(candidate-key % frequency))
                        reverse)]
    ;(dbg "current session" cur)
    ;(dbg "count candidates" (count candidates))
    ;(dbg "first 10 candidates" (take 10 candidates))
    (if (empty? candidates)
      (rand-nth songs)
      (do
        (dbg "score" (:score (first candidates)))
        (:mdata (first candidates))))))
      ;(:mdata (->> candidates (take 10) (map :score)
      ;             vec wrand (nth candidates))))))

(defn -pick_next [this]
  (pick-next (:model @@this)
             (:freshness @@this)
             (first (:sessions @@this))
             (:songs @@this)
             (:artist-frequency @@this)))

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

(defn test-get-sim-score []
  (for [[input expected]
        [[[0.3 false] 0.3]
         [[-0.3 true] 0]
         [[0.3 true] -0.3]]]
    (assert (= (apply get-sim-score input) expected))))

(defn test-get-content-mean []
  (test=
    (get-content-mean a
                      {#{"a" "b"} {:score 0.777 :n 1}
                       #{"a" "c"} {:score 0.111 :n 1}}
                      {b false
                       c true})
    0.5553333333333333))

(defn test-mk-candidate []
  (test= (mk-candidate
           a
           {#{a b} {:score 0.777 :n 1}
            #{a c} {:score 0.111 :n 1}
            #{"a" "b"} {:score 0.5 :n 1}
            #{"a" "c"} {:score 0.8 :n 1}}
           {a (- (now) (* 60 60 24))}
           {b false
            c true})
    {:mdata {"artist" "a", "album" "a", "title" "a"},
     :score 0.7049092315033385}))

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
    (doseq [action skip-sequence]
      (.add_event rec (.pick_next rec) action (now)))
    (test=
      ((.pick_next rec) "artist")
      next-artist)))

;(defn demo-real-data []
;;(:require [clojure.java.jdbc :as jd])
;  (let [db {:classname   "org.sqlite.JDBC"
;            :subprotocol "sqlite"
;            :subname     "moody.db"}
;        library (map #(->> %
;                           (filter (fn [[k v]] (not= k :_id)))
;                           (map (fn [[k v]] [(name k) v]))
;                           (into {}))
;                     (jd/query db "select * from songs"))
;        rec (new reco.reco library)]
;    (.update_model rec)
;    (loop [next-song (.pick_next rec)
;           actions [false false true true false false]]
;      (when (not (empty? actions))
;        (pprint next-song)
;        (println (if (first actions) "skip" "listen"))
;        (println)
;        (.add_event rec next-song (first actions) (now))
;        (recur (.pick_next rec)
;               (rest actions))))))

(defn -main [& args]
  (test-mini-model)
  (test-get-sim-score)
  (test-get-content-mean)
  (test-mk-candidate)
  (test-model)
  (test-pick-next [["a" 5] ["b" 10] ["c" 7]] nil "b")
  (test-pick-next [["a" 2] ["b" 1]] [true] "b")
  (test-pick-next [["a" 3] ["b" 1]] [false false] "b")

  ;(demo-real-data)
  (println "all tests pass"))
