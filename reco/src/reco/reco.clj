(ns reco.reco
  (:gen-class
   :implements [clojure.lang.IDeref]
   :state state
   :methods [[add_event [String, String, String, boolean, long] void]
             [add_event [String, String, String, boolean] void]
             [pick_next [boolean] java.util.Map]
             [add_to_blacklist [java.util.Map] void]
             ^:static [parse_spotify_response [String] java.util.List]
             ^:static [parse_track [String] java.util.Map]]
   :init init
   :constructors {[java.util.Collection] []})
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(use '[clojure.set :only [intersection union difference]])
(use '[clojure.math.combinatorics :only [combinations]])
(use '[clojure.pprint :only [pprint]])
;(use '[clojure.java.jdbc :as jd])


(def debug false)

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

(defn dbg [desc arg]
  (when debug
    (print (str desc ": "))
    (pprint arg))
  arg)

(defn now []
  (quot (System/currentTimeMillis) 1000))

(defn -init [raw-songs]
  ; into turns the java hashmap into a normal hashmap or something. Otherwise,
  ; the get-in call in mk-candidate always returns 0.
  (let [songs (map #(into {} %) raw-songs)
        candidates (repeat (count songs) (Candidate. [] 1 0 0 0 1 1 1))]
    [[] (atom {:candidates (zipmap songs candidates)
               :sessions '()
               :last-time 0
               :model nil
               :blacklist []})]))

(defn -add_to_blacklist [this song]
  (println "adding to blacklist:" song)
  (swap! (.state this)
         (fn [state]
           (update state :blacklist #(conj % (into {} song))))))

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
  ; take out max?
  [k {:score (- (* 2 (/ (:num v) (max 1 (:den v))))
                1)
      :n (:den v)}])

(defn mk-model [sessions]
  (->> sessions (map mini-model) flatten
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

(defn update-cand [[candidate data] model mdata skipped]
  [candidate
   (let [sim (sim-score model mdata candidate skipped)
         content-sim (sim-score model (mdata "artist")
                                (candidate "artist") skipped)]
     (cond-> data
       sim (update-score :collaborative sim)
       content-sim (update-score :content content-sim)))])

(defn update-candidates
  ([state mdata skipped]
   (let [{candidates :candidates model :model} state]
     (assoc state :candidates
            (update-candidates candidates model mdata skipped))))
  ([candidates model mdata skipped]
   (into {} (map #(update-cand % model mdata skipped) candidates))))


(defn penalty [delta strength]
  ;(println delta strength)
  (let [ret (- 1 (/ 1 (Math/exp (/ delta strength))))]
    ;(assert (<= 0 ret 1))
    ret))

(defn predicted [deltas strength]
  (let [ret (reduce * (map #(penalty % strength) deltas))]
    ;(assert (<= 0 ret 1))
    ret))

(defn total-error [input-data strength]
  (reduce + (map #(Math/pow (- (predicted (:deltas %) strength)
                               (:observed %)) 2) input-data)))

;(def strength-set (map #(Math/pow (/ % 5) 2) (range 2 30)))
(def strength-set [0.2 0.5 1 1.5 2 3 5 8 13 21])
(defn calc-freshness [event-vec song]
  ;(pprint (map :day event-vec))
  (let [get-deltas (fn [i event] {:observed (if (:skipped event) 0 1)
                                  :deltas (map #(- (:day event) (:day %))
                                               (take i event-vec))})
        input-data (map-indexed get-deltas event-vec)
        strength (apply min-key #(total-error input-data %) strength-set)
        day (/ (now) 86400)
        deltas (map (fn [e]
                      ;(when (< day (:day e))
                      ;  (println "WARNING: now is " day " but then was " (:day e)))
                      (max (- day (:day e)) 0)) event-vec)]
    ;(println "strength for " song ": " strength)
    (predicted deltas strength)))

(defn reset-candidates [candidates]
  (into {} (map (fn [[song data]]
                  [song
                   (assoc data
                          :freshness (calc-freshness
                                       (sort-by :day (:event-vec data)) song)
                                       ;(:event-vec data) song)
                          :ratio 0
                          :n 0
                          :score 0
                          :content-ratio 1
                          :content-n 1
                          :content-score 1)])
                candidates)))


(defn internal-add-event
  [this, mdata, skipped, timestamp]
  (swap! (.state this)
         (fn [state]
           (let [time-delta (- timestamp (:last-time state))
                 new-session (> time-delta ses-threshold)
                 sessions (ses-append (:sessions state) mdata skipped new-session)
                 new-model (when (and new-session (:model state))
                             (mk-model (:sessions state)))]

             (when (= new-session (= (count sessions)
                                     (count (:sessions state))))
               (println "WARNING: didn't create new session"))
             (when (< timestamp (:last-time state))
               (printf "WARNING: timestamp=%d but last-time=%d\n"
                       timestamp (:last-time state)))

             (cond-> state
               true (assoc :last-time timestamp
                           :sessions sessions)
               true (update-in [:candidates mdata :event-vec]
                               #(conj % (Event. (/ timestamp 86400) skipped)))
               new-model (assoc :model new-model :candidates
                                (reset-candidates (:candidates state)))
               (:model state) (update-candidates mdata skipped))))))

(defn -add_event
  ([this, artist, album, title, skipped, timestamp]
   (internal-add-event this {"artist" artist "album" album "title" title}
               skipped timestamp))
  ([this, artist, album, title, skipped]
   (internal-add-event this {"artist" artist "album" album "title" title}
               skipped (now))))

(defn init-model [this]
  (println "init model")
  (swap! (.state this)
         (fn [state]
           (let [[old-sessions cur-session]
                 (if (> ses-threshold (- (now) (:last-time state)))
                   [(rest (:sessions state)) (first (:sessions state))]
                   [(:sessions state) nil])

                 new-model (mk-model old-sessions)
                 new-candidates
                 (loop [cands (reset-candidates (:candidates state))
                        session cur-session]
                   (if (empty? session)
                     cands
                     (let [[mdata skipped] (first session)]
                       (recur (update-candidates cands new-model mdata skipped)
                              (rest session)))))]
             (assoc state :model new-model :candidates new-candidates)))))

(defn calc-confidence [n]
  (- 1 (/ 1 (Math/pow 1.5 n))))
(def calc-confidence (memoize calc-confidence))

(defn assign [candidate]
  ;(if (< (rand) (calc-confidence (:n candidate)))
  (if (< 0 (:n candidate))
    :main
    :content))

(defn pick-next [{candidates :candidates sessions :sessions
                  blacklist :blacklist} local-only]
  (let [cur-session (set (keys (first sessions)))
        cand-list (as-> candidates x 
                    (apply dissoc x cur-session)
                    (apply dissoc x blacklist)
                    (map (fn [[k v]] (assoc v :mdata k)) x)
                    (remove #(and local-only
                                  (str/starts-with? (get-in % [:mdata "title"])
                                    "spotify:track:")) x)
                    (shuffle x))
        {main-choices :main content-choices :content} (group-by assign cand-list)
        choices (map #(if (empty? %2) nil (apply max-key %1 %2))
                     [:score :content-score] [main-choices content-choices])]
    (dbg "main-choices length" (count main-choices))
    (dbg "content-choices length" (count content-choices))
    (println "choices:" choices)
    (:mdata ((if (< (rand) 0.6) first last)
             (remove nil? choices)))))

(defn -pick_next [this local-only]
  (when (not (:model @@this))
    (init-model this))
  (pick-next @@this local-only))

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

    ;(.update_model rec)

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
    ;(.update_model rec)
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
    ;(.update_model rec)
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
;
;    (println "adding events")
;    (doseq [{timestamp :time title :title artist :artist album :album
;             skipped :skipped}
;            (jd/query db "select time, title, artist, album, skipped from songs s
;                         join events e on s._id = e.song_id order by time asc")]
;      (let [seconds (quot (.getTime (.parse parser timestamp)) 1000)]
;        (.add_event rec artist album title
;                    (if (= skipped 1) true false) seconds)))
;
;
;    (dbg "sessions length" (count (:sessions @@rec)))
;
;    ;(println "updating model")
;    ;(.update_model rec)
;    (dbg "model length" (count (:model @@rec)))
;
;    (println "making recommendations")
;    ;(wait)
;    (loop [next-song (.pick_next rec)
;           actions [true true false true false false true true]]
;           ;actions (repeat 500 true)]
;      (let [{title "title" album "album" artist "artist"} next-song]
;        (when (not (empty? actions))
;          (pprint next-song)
;          (println (if (first actions) "skip" "listen"))
;          (println)
;          (.add_event rec artist album title (first actions))
;          (recur (.pick_next rec)
;                 (rest actions)))))))

;(defn -spotify_thang [token]
;  (let [;token "BQBgnAlhZiGA4TXPdKeFAr9iRq5bQ5vEPq3NPirb4bV01hIXh_FWeXrN1Kf3_JJWrLNZ1kwgfrDvmQBKIKmI3qfXv1sBCqqA5E833aCotbEl148SirYSUV-xFKypG9z5fhCHn2JqMkj2Ov-VOPgkW0opRBZH7UKP21Quef0qkowP5Lc"
;        response (client/get "https://api.spotify.com/v1/me/top/tracks?limit=50"
;                             {:headers {:Authorization (str "Bearer " token)}})
;        data (json/read-str (:body response))]
;    (map (fn [item] {"uri" (get item "uri")
;                             "artist" (get-in item ["artists" 0 "name"])})
;                 (data "items"))))

(defn -parse_spotify_response [response]
  (let [data (json/read-str response)]
    (map (fn [item] {"uri" (get item "uri")
                     "artist" (get-in item ["artists" 0 "name"])})
         (data "items"))))

(defn -parse_track [response]
  (let [data (json/read-str response)]
    {"title" (get data "name")
     "artist" (get-in data ["artists" 0 "name"])
     "album" (get-in data ["album" "name"])
     "duration" (get data "duration_ms")}))

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
  ;(spotify-thang)
  ;(reco.reco/spotify_thang "BQBgnAlhZiGA4TXPdKeFAr9iRq5bQ5vEPq3NPirb4bV01hIXh_FWeXrN1Kf3_JJWrLNZ1kwgfrDvmQBKIKmI3qfXv1sBCqqA5E833aCotbEl148SirYSUV-xFKypG9z5fhCHn2JqMkj2Ov-VOPgkW0opRBZH7UKP21Quef0qkowP5Lc")
  (println "all tests pass"))
