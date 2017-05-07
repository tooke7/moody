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
             [testing [] String]]
   :init init
   :constructors {[java.util.Collection] []}))

(use '[clojure.set :only [intersection union difference]])
(use '[clojure.math.combinatorics :only [combinations]])
(use '[clojure.pprint :only [pprint]])

(def ses-threshold (* 30 60))
(def k 5)

(defn now []
  (quot (System/currentTimeMillis) 1000))

(defn -init [songs]
  [[] (atom {:songs songs
             :sessions nil
             :last-time 0
             :model nil})])

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
                  :den (if (not (or (nil? (session a))
                                    (nil? (session b)))) 1 0)}})
              (combinations (set (keys session)) 2))))

(defn merge-models [models]
  (apply merge-with #(merge-with + %1 %2) models))

(defn convert-cell [[k v]]
  [k {:score (- (* 2 (/ (:num v) (max 1 (:den v))))
                1)
      :n (:den v)}])

(defn mk-model [sessions]
  (into {} (map convert-cell (merge-models (map mini-model sessions)))))

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
                              (:model state)))))))
  ([this, artist, album, title, skipped, timestamp]
   (.add_event this {"artist" artist "album" album "title" title} skipped timestamp))
  ([this, artist, album, title, skipped]
   (.add_event this artist album title skipped (now))))

(defn dbg [desc arg]
  ;(print (str desc ": "))
  ;(prn arg)
  arg)

(defn wrand 
  "given a vector of slice sizes, returns the index of a slice given a
  random spin of a roulette wheel with compartments proportional to
  slices."
  [slices]
  (dbg "slices" slices)
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
   :score (reduce + (map #(get-in model [(set [candidate %1]) :score] 0)
                         (set (keys cur))))})

(defn -pick_next [this]
  (let [model (:model @(.state this))
        cur (first (:sessions @(.state this)))
        universe (difference (set (:songs @(.state this))) (set (keys cur)))
        candidates (filter #(> (:score %1) 0)
                           (map #(mk-candidate %1 model cur) universe))]
    (if (empty? candidates)
      (do
        (println "ohno")
        (rand-nth (:songs @(.state this))))
      (:mdata (nth candidates (wrand (vec (map :score candidates))))))))


(defn -deref [this]
  (.state this))

(defn -testing [this] "hello there")

(defn -main [& args]
  (println "starting")

  ; this initializes the user's library with the given songs. This information
  ; actually isn't used very much right now because the recommender works with
  ; songs given by calls to add_event.
  (let [a {"artist" "a" "album" "a" "title" "a"}
        b {"artist" "b" "album" "b" "title" "b"}
        c {"artist" "c" "album" "c" "title" "c"}
        d {"artist" "d" "album" "d" "title" "d"}
        e {"artist" "e" "album" "e" "title" "e"}
        f {"artist" "f" "album" "f" "title" "f"}
        g {"artist" "g" "album" "g" "title" "g"}
        h {"artist" "h" "album" "h" "title" "h"}
        rec (new reco.reco [a b c d e f g h])]

    (println (str "count" (count (:songs @@rec))))
    
    ; explanation of the first line in session 0:
    ; artist: a
    ; album: a
    ; title: a
    ; skipped: true (i.e. the user skipped the song instead of listening to it).
    ; time: 2000 (i.e. this song ended 2000 seconds after the unix epoch). If
    ; there's at least 1800 seconds (half an hour) between songs, then we count
    ; that as a divider between sessions.

    ; session 0
    (.add_event rec "a" "a" "a" true 2000)
    (.add_event rec "b" "b" "b" false 2000)
    (.add_event rec "c" "c" "c" false 2000)
    (.add_event rec "d" "d" "d" false 2000)
    (.add_event rec "e" "e" "e" false 2000)
    (.add_event rec "f" "f" "f" false 2000)

    ; session 1
    (.add_event rec "a" "a" "a" false 4000)
    (.add_event rec "b" "b" "b" false 4000)
    (.add_event rec "c" "c" "c" false 4000)
    (.add_event rec "d" "d" "d" false 4000)
    (.add_event rec "e" "e" "e" false 4000)
    (.add_event rec "g" "g" "g" false 4000)
    (.add_event rec "h" "h" "h" true 4000)

    ; this will choose "f" because it's the only song in the first session that
    ; isn't in the current session
    (.update_model rec)
    (println (str "next song: " (.pick_next rec)))

    ; session 2 (current session)
    (.add_event rec "a" "a" "a" false 6000)
    (.add_event rec "b" "b" "b" false 6000)
    (.add_event rec "c" "c" "c" false 6000)
    (.add_event rec "d" "d" "d" false 6000)

    (.update_model rec)

    ;(let [sessions (:sessions @@rec)]
    ;  (println (sim sessions a b))
    ;  (println (sim sessions b c))
    ;  (println (sim sessions f g)))

    ; these recommendations will be either e, f or g.
    ; e has the highest probability of being picked because it's in both
    ; previous sessions.
    ; g is next because sessions 1 and 2 are more similar than sessions 0 and 2.
    ; f is the least likely prediction.
    (println (.pick_next rec))
    (println (.pick_next rec))
    (println (.pick_next rec))
    (println (.pick_next rec))
    (println (.pick_next rec))
    (println (.pick_next rec))
    (println (.pick_next rec))
    (println (.pick_next rec))
    (println (.pick_next rec))

    ;(println "model:")
    ;(pprint (:model @@rec))

    ; Now the system won't be able to generate any recommendations because all the songs it
    ; knows about are already in the current session. Instead, it'll choose a random song from
    ; the library (i.e. either "the dirt whispered" or "lithium").
    (.add_event rec "e" "e" "e" false 6000)
    (.add_event rec "f" "f" "f" false 6000)
    (.add_event rec "g" "g" "g" false 6000)
    (println (.pick_next rec))
    (println (.pick_next rec))

    ;(println "See src/reco/reco.clj for comments about what these things mean.")))
    ))
