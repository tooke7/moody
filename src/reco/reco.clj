(ns reco.reco
  (:gen-class
   :implements [clojure.lang.IDeref]
   :state state
   :methods [[add_event [String, String, String, boolean, long] void]
             [add_event [String, String, String, boolean] void]
             [add_event [java.util.Map, boolean, long] void]
             [pick_next [] java.util.Map]
             [testing [] String]]
   :init init
   :constructors {[java.util.Collection] []}))

(use '[clojure.set :only [intersection union]])

(def ses-threshold (* 30 60))
(def k 5)

(defn now []
  (quot (System/currentTimeMillis) 1000))

(defn -init [songs]
  [[] (atom {:songs songs
             :sessions nil
             :last-time 0})])

(defn ses-append [sessions mdata skipped create-session]
  (if create-session
    (cons {mdata skipped} sessions)
    (if (contains? (first sessions) mdata)
      sessions
      (cons (assoc (first sessions) mdata skipped)
            (rest sessions)))))

(defn -add_event
  ([this, mdata, skipped, timestamp]
     (swap! (.state this)
            (fn [state]
              (assoc state :last-time timestamp
                     :sessions (ses-append (:sessions state)
                                           mdata
                                           skipped
                                           (> timestamp (+ (:last-time state) ses-threshold)))))))
  ([this, artist, album, title, skipped, timestamp]
   (.add_event this {:artist artist :album album :title title} skipped timestamp))
  ([this, artist, album, title, skipped]
   (.add_event this artist album title skipped (now))))

(defn dbg [desc arg]
  (println (str desc ": " arg))
  arg)

(defn dist [sesa sesb]
  (let [universe (intersection (set (keys sesa)) (set (keys sesb)))
        hits (filter #(not (and (sesa %1) (sesb %1))) universe)
        matches (map #(if (= (sesa %1) (sesb %1) false) 1 0) universe)]
    (/ (reduce + matches) (count hits))))

(defn aggregate [sessions]
  (let [universe (apply union (map #(set (:recs %1)) sessions))]
    (map (fn [mdata]
           {:mdata mdata
            :score (reduce + (map (fn [ses]
                                    (if (contains? (:recs ses) mdata)
                                      (:score ses)
                                      0))
                                  sessions))})
         universe)))

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

(defn create-neighbor [cur-session ses]
  {:score (dist cur-session ses)
   :recs (set (filter #(and (not (ses %1))
                            (not (contains? cur-session %1)))
                      (keys ses)))})

(defn -pick_next [this]
  (let [sessions (:sessions @(.state this))
        neighbors (filter #(:recs %1)
                          (map #(create-neighbor (first sessions) %1)
                               (rest sessions)))
        nearest (take k (sort-by :score neighbors))
        candidates (aggregate nearest)]
    (if (empty? candidates)
      (rand-nth (:songs @(.state this)))
      (:mdata (nth candidates (wrand (vec (map :score candidates))))))))

(defn -testing [this] "hello there")

(defn -deref [this]
  (.state this))

(defn -main [& args]
  (println "starting")
  (let [rec (new reco.reco [{:artist "rise against" :album "myalbum" :title "the dirt whispered"}
                            {:artist "evanescence" :album "foobar" :title "lithium"}])]
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

    (.add_event rec "a" "a" "a" false 6000)
    (.add_event rec "b" "b" "b" false 6000)
    (.add_event rec "c" "c" "c" false 6000)
    (.add_event rec "d" "d" "d" false 6000)
    (.add_event rec "e" "e" "e" false 6000)
    (.add_event rec "f" "f" "f" false 6000)
    (.add_event rec "g" "g" "g" false 6000)

    (let [sessions (:sessions @@rec)]
      (println (.pick_next rec))
      (println (.pick_next rec))
      (println (.pick_next rec))
      (println (.pick_next rec))
      (println (.pick_next rec))
      (println (.pick_next rec))
      (println (.pick_next rec)))))
