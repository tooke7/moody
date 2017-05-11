(ns reco.reco
  (:gen-class
   :implements [clojure.lang.IDeref]
   :state state
   :methods [[add_event [String, String, String, boolean, long] void]
             [add_event [String, String, String, boolean] void]
             [add_event [java.util.Map, boolean, long] void]
             [pick_next [] java.util.Map]
             [new_session [] void]
             [update_model [] void]
             [pprint_model [] void]
             [testing [] String]]
   :init init
   :constructors {[java.util.Collection] []}
   :require [clojure.java.jdbc :as jd]))

(use '[clojure.set :only [intersection union difference]])
(use '[clojure.math.combinatorics :only [combinations]])
(use '[clojure.pprint :only [pprint]])

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

(def db
  {:classname   "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname     "moody.db"
   })

(defn now []
  (quot (System/currentTimeMillis) 1000))

(defn -init [songs]
  ; into turns the java hashmap into a normal hashmap or something. Otherwise,
  ; the get-in call in mk-candidate always returns 0.
  [[] (atom {:songs (map #(into {} %) songs)
             :sessions nil
             :last-time 0
             :model nil
             :freshness {}})])

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
  (apply merge (map (fn [[a b]]
                      {(set [a b])
                       {:num (if (= (session a) (session b) false) 1 0)
                        :den (if (= (session a) (session b) true) 0 1)}})
                    (combinations (set (keys session)) 2))))

(defn merge-models [models]
  (apply merge-with #(merge-with + %1 %2) models))

(defn convert-cell [[k v]]
  [k {:score (- (* 2 (/ (:num v) (max 1 (:den v))))
                1)
      :n (:den v)}])

(defn mk-model [sessions]
  (->> sessions (map mini-model) merge-models (map convert-cell) (into {})))

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
                     :freshness (assoc (:freshness state)
                                       mdata timestamp))))))
  ([this, artist, album, title, skipped, timestamp]
   (.add_event this {"artist" artist "album" album "title" title} skipped timestamp))
  ([this, artist, album, title, skipped]
   (.add_event this artist album title skipped (now))))

(defn dbg [desc arg]
  (print (str desc ": "))
  (pprint arg)
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

(defn mk-candidate [candidate model cur]
  {:mdata candidate
   :score (->> (set (keys cur))
               (map #(get-in model [(set [%1 candidate]) :score] 0))
               (reduce +))})

(defn -pick_next [this]
  (let [model (:model @@this)
        cur (first (:sessions @@this))
        universe (difference (set (:songs @@this)) (set (keys cur)))
        candidates (->> universe
                        (map #(mk-candidate % model cur))
                        (filter #(< 0 (:score %)))
                        (sort-by :score) reverse)]

    (dbg "current session" cur)
    (dbg "count candidates" (count candidates))
    (dbg "first 10 candidates" (take 10 candidates))
    (if (empty? candidates)
      (rand-nth (:songs @@this))
      (:mdata (->> candidates (take 10) (map :score)
                   vec wrand (nth candidates))))))


(defn -deref [this]
  (.state this))

(defn -testing [this] "hello there")

(defn mk-comparator [model]
  (fn [key1 key2]
    (compare [(get-in model [key2 :n]) (get-in model [key2 :score])]
             [(get-in model [key1 :n]) (get-in model [key1 :score])])))

(defn -pprint_model [this]
  (let [model (:model @@this)
        freshness (:freshness @@this)
        sorted (into (sorted-map-by (mk-comparator model)) model)
        sessions (:sessions @@this)]
    (println (count model) " items in model")
    ;(println "candidate:")
    ;(pprint (mk-candidate {"artist" "Lindsey Stirling"
    ;                       "album" "Lindsey Stirling"
    ;                       "title" "Crystallize"}
    ;                      model
    ;                      {{"artist" "Breaking Benjamin",
    ;                        "album" "Dear Agony",
    ;                        "title" "Give Me A Sign"}
    ;                       false}))
    (dbg "pick_next" (.pick_next this))
    (println "top 10 least fresh items:")
    (pprint (take 10 (reverse (sort-by last (vec freshness)))))

    ;(pprint (filter #(contains? (first %) {"artist" "Breaking Benjamin",
    ;                                       "album" "Dear Agony",
    ;                                       "title" "Give Me A Sign"})
    ;                model))))
    ))

(defn demo []
  (let [rec (new reco.reco [a b c d e f g h])]
    (.add_event rec "a" "a" "a" true 2000)
    (.add_event rec "b" "b" "b" false 2000)
    (.add_event rec "c" "c" "c" false 2000)
    (.add_event rec "d" "d" "d" false 2000)
    (.add_event rec "e" "e" "e" false 2000)
    (.add_event rec "f" "f" "f" false 2000)

    (.add_event rec "a" "a" "a" false 4000)
    (.add_event rec "b" "b" "b" false 4000)
    (.add_event rec "c" "c" "c" false 4000)
    (.add_event rec "d" "d" "d" false 4000)
    (.add_event rec "e" "e" "e" false 4000)
    (.add_event rec "g" "g" "g" false 4000)
    (.add_event rec "h" "h" "h" true 4000)

    (.add_event rec "a" "a" "a" false 6000)
    (.add_event rec "b" "b" "b" false 6000)
    (.add_event rec "c" "c" "c" false 6000)
    (.add_event rec "d" "d" "d" false 6000)

    (.update_model rec)

    (pprint (mk-candidate a (:model @@rec) {b false}))
    (println (.pick_next rec))

    ;(.pprint_model rec)

    ;(let [sessions (:sessions @@rec)]
    ;  (println (sim sessions a b))
    ;  (println (sim sessions b c))
    ;  (println (sim sessions f g)))

    ; these recommendations will be either e, f or g.
    ; e has the highest probability of being picked because it's in both
    ; previous sessions.
    ; g is next because sessions 1 and 2 are more similar than sessions 0 and 2.
    ; f is the least likely prediction.
    ;(println (.pick_next rec))
    ;(println (.pick_next rec))
    ;(println (.pick_next rec))
    ;(println (.pick_next rec))
    ;(println (.pick_next rec))
    ;(println (.pick_next rec))
    ;(println (.pick_next rec))
    ;(println (.pick_next rec))
    ;(println (.pick_next rec))

    ))

(defn -main [& args]
  (demo))
  
