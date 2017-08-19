(ns reco.reco
  (:gen-class
   :implements [clojure.lang.IDeref java.io.Serializable]
   :state state
   :methods [[add_event [long boolean long boolean] void]
             [add_event [long boolean long] void]
             [add_event [long boolean] void]
             [pick_next [boolean] java.util.Map]
             [pick_random [boolean] java.util.Map]
             [add_to_blacklist [long] void]
             [get_state [] java.util.Map]
             [set_state [java.util.Map] void]
             [get_last_event_id [] long]
             [set_last_event_id [long] void]
             ^:static [parse_top_tracks [String] java.util.List]
             ^:static [parse_track [String] java.util.Map]
             ^:static [parse_features [String] java.util.List]
             ^:static [parse_search [String] String]]
   :init init
   :constructors {[java.util.Collection] []})
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(use '[clojure.set :only [intersection union difference]])
(use '[clojure.math.combinatorics :only [combinations]])
(use '[clojure.pprint :only [pprint]])
;(use '[clojure.java.jdbc :as jd])

(def debug true)

(def ses-threshold (* 20 60))

(def k 5)
(def a {"artist" "a" "album" "a" "title" "a"})
(def b {"artist" "b" "album" "b" "title" "b"})
(def c {"artist" "c" "album" "c" "title" "c"})
(def d {"artist" "d" "album" "d" "title" "d"})
(def e {"artist" "e" "album" "e" "title" "e"})
(def f {"artist" "f" "album" "f" "title" "f"})
(def g {"artist" "g" "album" "g" "title" "g"})
(def h {"artist" "h" "album" "h" "title" "h"})

(defrecord Event [day skipped])

(defrecord Candidate [event-vec freshness
                      ratio n score
                      content-ratio content-n content-score])

(defrecord Song [artist album title source spotify_id duration])
                 ;danceability
                 ;energy
                 ;mode
                 ;speechiness
                 ;acousticness
                 ;instrumentalness
                 ;liveness
                 ;valence])

(defn dbg [desc arg]
  (when debug
    (print (str desc ": "))
    (pprint arg))
  arg)

(defn now []
  (quot (System/currentTimeMillis) 1000))

(defn -init [raw-songs]
  ; raw-songs - list of map
  ; into turns the java hashmap into a normal hashmap or something. Otherwise,
  ; the get-in call in mk-candidate always returns 0.
  (let [library (->> raw-songs
                     (map #(into {} %))
                     (map walk/keywordize-keys)
                     (map (fn [item] [(item :_id)
                                      (map->Song (dissoc item :_id))]))
                     (into {}))
        candidates (repeat (count library) (Candidate. [] 1 0 0 0 1 1 1))]
    [[] (atom {:library library
               :candidates (zipmap (keys library) candidates)
               :session {}
               :last-time 0
               :model nil
               :blacklist #{}
               :event-id -1})]))

(defn -add_to_blacklist [this song-id]
  (println "adding to blacklist:" song-id)
  (swap! (.state this)
         (fn [state]
           (update state :blacklist #(conj % song-id)))))

(defrecord Frac [num den])
(defn mini-model [library session]
  (->> (combinations (set (keys session)) 2)
       (remove (fn [[a b]] (= (session a) (session b) true)))
       (map (fn [[a b]]
              (let [score (if (= (session a) (session b) false) 1 0)]
                [{(set [a b]) (Frac. score 1)}
                 {(set (map #(get-in library [% :artist]) [a b]))
                  (Frac. score 1)}])))))

(defn merge-models [models]
  (apply merge-with #(merge-with + %1 %2) models))

(defrecord Cell [score n])
(defn convert-cell [[song-pair frac]]
  ; take out max?
  [song-pair (Cell.
               (- (* 2 (/ (:num frac) (max 1 (:den frac)))) 1)
               (:den frac))])

(defn mk-model [session library]
  (->> session (mini-model library) flatten
       merge-models (map convert-cell) (into {})))

(defn calc-margin [n]
  (/ 1 (Math/pow 1.1 n)))
(def calc-margin (memoize calc-margin))

(defn update-score [candidate score-type sim]
  (let [[n-key ratio-key score-key]
        (map #(keyword (str (when (= score-type :content) "content-") %))
             ["n", "ratio", "score"])
        
        old-n (n-key candidate)
        old-ratio (ratio-key candidate)
        new-n (inc old-n)
        new-ratio (/ (+ (* old-ratio old-n) sim) new-n)
        margin (if (= score-type :content) 0 (calc-margin new-n))
        new-score (* (+ new-ratio margin) (:freshness candidate))]
    (assoc candidate score-key new-score ratio-key new-ratio n-key new-n)))

(defn sim-score [model a b skipped]
  (let [sim (if (= a b) 1 (get-in model [(set [a b]) :score]))]
    (if (or (nil? sim) (and skipped (< sim 0)))
      nil
      (* sim (if skipped -1 1)))))


;(defn dot-product [vectors]
;  (reduce + (apply map * vectors)))
;
;(defn norm [v]
;  (Math/sqrt (reduce + (map #(* % %) v))))
;
;(defn cosine [vectors]
;  (/ (dot-product vectors)
;     (apply * (map norm vectors))))
;
;(defn feature-sim [library a b skipped]
;  (let [features [:danceability
;                  :energy
;                  :mode
;                  :speechiness
;                  :acousticness
;                  :instrumentalness
;                  :liveness
;                  :valence]
;        vectors (map (fn [song-id]
;                       (map (fn [feature] (get-in library [song-id feature]))
;                            features))
;                     [a b])
;        distance (cosine vectors)
;        sim (- (* distance 2) 1)]
;    ;(println "feature distance between" (get-in library [a :title]) "and"
;    ;         (get-in library [b :title]) "is" distance)
;    (if (and skipped (< sim 0))
;      nil
;      (* sim (if skipped -1 1)))))

(defn update-cand [[cand-id data] library model other-id skipped]
  [cand-id
   (let [sim (sim-score model other-id cand-id skipped)
         cand-artist (get-in library [cand-id :artist])
         other-artist (get-in library [other-id :artist])
         content-sim (sim-score model other-artist cand-artist skipped)]
                     ;(if (every? some? (map #(get-in library [% :mode])
                     ;                       [cand-id other-id]))
                     ;  (feature-sim library cand-id other-id skipped)
                     ;  (let [s (sim-score model other-artist cand-artist skipped)]
                     ;    (if s
                     ;      (min s (if (= other-artist cand-artist) 1 0.8))
                     ;      nil)))]
     (cond-> data
       sim (update-score :collaborative sim)
       content-sim (update-score :content content-sim)))])

(defn update-candidates
  ([state song-id skipped]
   (let [{candidates :candidates model :model library :library} state]
     (assoc state :candidates
            (update-candidates candidates library model song-id skipped))))
  ([candidates library model song-id skipped]
   (into {} (map #(update-cand % library model song-id skipped) candidates))))

(defn penalty [delta strength]
  (- 1 (/ 1 (Math/exp (/ delta strength)))))

(defn predicted [deltas strength]
  (reduce * (map #(penalty % strength) deltas)))

(defn total-error [input-data strength]
  (reduce + (map #(Math/pow (- (predicted (:deltas %) strength)
                               (:observed %)) 2) input-data)))

;(def strength-set [0.2 0.5 1 1.5 2 3 5 8 13 21])
(def strength-set [0.5 3 14])
(defn calc-freshness [event-vec]
  (let [get-deltas (fn [i event] {:observed (if (:skipped event) 0 1)
                                  :deltas (map #(- (:day event) (:day %))
                                               (take i event-vec))})
        input-data (map-indexed get-deltas event-vec)
        strength (apply min-key #(total-error input-data %) strength-set)
        day (/ (now) 86400)
        deltas (map (fn [e] (max (- day (:day e)) 0)) event-vec)]
    (predicted deltas strength)))

(defn reset-candidates [candidates]
  (into {} (map (fn [[song-id data]]
                  [song-id
                   (assoc data
                          :freshness (calc-freshness
                                       (sort-by :day (:event-vec data)))
                          :ratio 0
                          :n 0
                          :score 0
                          :content-ratio 1
                          :content-n 1
                          :content-score 1)])
                candidates)))

(defn update-model [model session library]
  (let [partial-model (mk-model session library)]
    (merge-with (fn [{score-a :score n-a :n} {score-b :score n-b :n}]
                  (Cell. (/ (+ (* score-a n-a) (* score-b n-b))
                            (+ n-a n-b))
                         (+ n-a n-b)))
                model partial-model)))

(defn -add_event
  ([this song-id skipped timestamp do-cand-update]
   (swap! (.state this)
          (fn [state]
            (let [time-delta (- timestamp (:last-time state))
                  new-session (> time-delta ses-threshold)
                  ; song id -1 represents the empty session. It gives the model
                  ; something to work with when a session is just getting
                  ; started.
                  session (if new-session
                            {-1 false song-id skipped}
                            (assoc (:session state) song-id skipped))
                  new-model (when new-session
                              (update-model (:model state) (:session state)
                                            (:library state)))]

              (when (< timestamp (:last-time state))
                (printf "WARNING: timestamp=%d but last-time=%d\n"
                        timestamp (:last-time state)))

              (cond-> state
                true (assoc :last-time timestamp
                            :session session)
                true (update-in [:candidates song-id :event-vec]
                                #(conj % (Event. (/ timestamp 86400) skipped)))
                new-model (assoc :model new-model :candidates
                                 (reset-candidates (:candidates state)))
                do-cand-update (update-candidates song-id skipped))))))
  ([this song-id skipped timestamp]
   (.add_event this song-id skipped timestamp true))
  ([this song-id skipped]
   (.add_event this song-id skipped (now))))

;(defn init-model [this]
;  (println "init model")
;  (swap! (.state this)
;         (fn [state]
;           (let [[old-sessions cur-session]
;                 (if (> ses-threshold (- (now) (:last-time state)))
;                   [(rest (:sessions state)) (first (:sessions state))]
;                   [(:sessions state) nil])
;                 new-candidates
;                 (loop [cands (reset-candidates (:candidates state))
;                        session cur-session]
;                   (if (empty? session)
;                     cands
;                     (let [[song-id skipped] (first session)]
;                       (recur (update-candidates cands (:library state) new-model
;                                                 song-id skipped)
;                              (rest session)))))]
;             (assoc state :model new-model :candidates new-candidates)))))

(defn calc-confidence [n]
  (- 1 (/ 1 (Math/pow 1.5 n))))
(def calc-confidence (memoize calc-confidence))

(defn assign [candidate]
  (if (< 0 (:n candidate))
    :main
    :content))

(defn pick-with-algorithm [cand-list]
  (let [{main-choices :main content-choices :content} (group-by assign cand-list)
        top-choices (map (fn [score-key choices]
                           (if (empty? choices)
                             nil
                             (apply max-key score-key choices)))
                         [:score :content-score] [main-choices content-choices])
        song-id (:song-id ((if (< (rand) 0.6) first last)
                           (remove nil? top-choices)))]
    (println "choices:" (map :song-id top-choices))
    song-id))

(defn pick-randomly [cand-list]
  (if (empty? cand-list)
    nil
    (:song-id (rand-nth cand-list))))

(defn pick [{candidates :candidates session :session
             blacklist :blacklist library :library} local-only song-picker]
  (let [cur-session (set (keys session))
        cand-list (as-> candidates x 
                    (apply dissoc x (union cur-session blacklist))
                    (remove #(and local-only
                                  (= (get-in library [% :source]) "spotify")) x)
                    (map (fn [[k v]] (assoc v :song-id k)) x)
                    (shuffle x))
        song-id (song-picker cand-list)]
    (if (nil? song-id)
      nil
      (walk/stringify-keys
        (assoc (library song-id) :_id song-id)))))

(defn -pick_next [this local-only]
  ;(when (not (:model @@this))
  ;  (init-model this))
  (pick @@this local-only pick-with-algorithm))

(defn -pick_random [this local-only]
  (dbg "pick" (pick @@this local-only pick-randomly)))

(defn -deref [this]
  (.state this))

(defn -set_state [this new-state]
  (swap! (.state this)
         (fn [old-state]
           (assoc new-state :library (:library old-state)
                  :candidates (merge (:candidates old-state)
                                     (:candidates new-state))))))

(defn -get_state [this]
  (dissoc @@this :library :blacklist))

(defn -get_last_event_id [this]
  (:event-id @@this))

(defn -set_last_event_id [this event-id]
  (swap! (.state this)
         (fn [state]
           (assoc state :event-id event-id))))

(defn -parse_top_tracks [response]
  (let [data (json/read-str response)]
    (map (fn [item] {"spotify_id" (get item "uri")
                     "duration" (get item "duration_ms")
                     "title" (get item "name")
                     "album" (get-in item ["album" "name"])
                     "artist" (get-in item ["artists" 0 "name"])})
         (data "items"))))

(defn -parse_track [response]
  (let [data (json/read-str response)]
    {"title" (get data "name")
     "artist" (get-in data ["artists" 0 "name"])
     "album" (get-in data ["album" "name"])
     "duration" (get data "duration_ms")}))

(defn -parse_features [response]
  (let [data (json/read-str response)
        features ["danceability"
                  "energy"
                  "mode"
                  "speechiness"
                  "acousticness"
                  "instrumentalness"
                  "liveness"
                  "valence"]]
    (map (fn [item] (into {} (map (fn [k] [k (get item k)])
                                  features)))
         (data "audio_features"))))

(defn -parse_search [response]
  (let [data (json/read-str response)]
    (get-in data ["tracks" "items" 0 "uri"])))


;(defn demo-real-data []
;  (println "Press Enter to start demo")
;  (read-line)
;
;  (println "adding library")
;  (let [db {:classname   "org.sqlite.JDBC"
;            :subprotocol "sqlite"
;            :subname     "moody.db"}
;        library (map walk/stringify-keys (jd/query db "select * from songs"))
;        rec (new reco.reco library)
;        parser (new java.text.SimpleDateFormat "yyyy-MM-dd HH:mm:ss")]
;
;    (println "adding events")
;    (doseq [{song-id :song_id timestamp :time skipped :skipped}
;            (jd/query db "select song_id, skipped, time from events
;                         order by time asc")]
;      (let [seconds (quot (.getTime (.parse parser timestamp)) 1000)]
;        (.add_event rec song-id (if (= skipped 1) true false) seconds)))
;
;    ;(dbg "sessions length" (count (:sessions @@rec)))
;    (dbg "model length" (count (:model @@rec)))
;
;    (println "making recommendations")
;    (loop [next-song (.pick_next rec false)
;           actions [true true false true false false true true]]
;      ;actions (repeat 500 true)]
;      (when (not (empty? actions))
;        (pprint next-song)
;        (println (if (first actions) "skip" "listen"))
;        (println)
;        (.add_event rec (next-song "_id") (first actions))
;        (recur (.pick_next rec false) (rest actions))))
;    (.pick_random rec false)))

(defn test-serial []
  (let [fout (new java.io.FileOutputStream "/tmp/foobar")
        oos (new java.io.ObjectOutputStream fout)
        rec (new reco.reco [a])]
    (.writeObject oos @@rec)
    (.close oos)
    (.close fout)
    (let [fin (new java.io.FileInputStream "/tmp/foobar")
          ois (new java.io.ObjectInputStream fin)
          state (.readObject ois)]
      (.close ois)
      (.close fin)
      (assert (= state @@rec)))))

(defn -main [& args]
  (println "starting up")
  (test-serial)
  ;(demo-real-data)
  (println "all tests pass"))
