(ns dag_unify.core

;; TODO: define a dissoc that works with special values 
;; which are not maps but keywords, like :fail; 
;; e.g.:
;; (dissoc :fail :anykey) => :fail
;; (dissoc :top :anykey) => :top
;; Another approach would be to not modify dissoc to handle non-maps, and instead
;; use special values that *are* maps.
;; e.g. {:fail :fail} rather than simply :fail,
;; and {:top :top} rather than simply :top.
  (:refer-clojure :exclude [exists? get-in merge resolve])
  (:require
   [clojure.set :refer [intersection subset? union]]
   [clojure.string :as string]))

(defn exception [error-string]
  #?(:clj
     (throw (Exception. error-string)))
  #?(:cljs
     (throw (js/Error. error-string))))

(defn ref? [val]
  #?(:clj
     (= (type val) clojure.lang.Atom))
  #?(:cljs
     (= (type val) cljs.core.Atom)))

(defn resolve [arg]
  "if arg is not a ref, return arg. if is a ref, return (resolve @arg)"
  (if (ref? arg)
    (resolve @arg)
    arg))

;; TODO: need tests: many tests use (get-in), but need more dedicated tests for it alone.
(defn get-in [in-map path & [not-found]]
  "same as clojure.core (get-in), but it resolves references if need be."
  (cond (seq? in-map)
        (map (fn [each]
               (get-in each path not-found))
             in-map)
        true
        (let [result
              (if (first path)
                (let [result (get in-map (first path) not-found)]
                  (if (= result not-found) not-found
                      (get-in (resolve result) (rest path) not-found)))
                in-map)]
          (if (ref? val)
            @result
            result))))

(defn exists? [the-map path]
  (not (= :does-not-exist
          (get-in the-map path :does-not-exist))))

;; TODO: use multi-methods.
;; TODO: keep list of already-seen references to avoid
;; cost of traversing substructures more than once.
(defn fail? [fs]
  "(fail? fs) <=> true if at least one of fs's path's value is :fail."
  (cond (= :fail fs) true
        (seq? fs) false ;; a sequence is never fail.
        (= fs :fail) true ;; :fail is always fail.
 
        (and (map? fs) (fail? (get-in fs [:fail] :top))) true
        (and (map? fs) (= true (get-in fs [:fail] :top))) true ;; note: :top != true, and (fail? {:fail :top}) => false.

       (fn? fs) false ;; a function is never fail.

        (ref? fs)
        (do
          (fail? @fs))
        (not (map? fs)) false

        :else
        ;; otherwise, check recursively.
        (do
          (defn failr? [fs keys]
            (and (not (empty? keys))
                 (or (fail? (get fs (first keys)))
                     (failr? fs (rest keys)))))
          (cond
            (= fs :fail) true
            (map? fs)
            (failr? fs (keys fs))
            :else false))))

(defn nonfail [maps]
  (filter (fn [each-map]
            (not (fail? each-map)))
          maps))

(defn fail-path [fs & [ fs-keys ] ]
  "find the first failing path in a fs."
  (if (map? fs)
    (let [fs-keys (if fs-keys fs-keys (keys fs))]
      (if (> (count fs-keys) 0)
        (if (fail? (get-in fs (list (first fs-keys))))
          (cons (first fs-keys) (fail-path (get-in fs (list (first fs-keys)))))
          (fail-path fs (rest fs-keys)))))))

(defn any? [fn members]
  (if (not (empty? members))
    (or (fn (first members))
        (any? fn (rest members)))

    ;; members is empty.
    false))

(declare expand-disj) ;; needed by unify.
(declare copy)
(declare unifyc)

(defn get-refs-in [input]
  (cond
   (ref? input)
   (union
    (set (list input))
    (get-refs-in @input))
   (map? input)
   (union
    (set (filter #(ref? %) (vals input)))
    (set (mapcat (fn [each] (get-refs-in each))
                 (vals input))))
   :else
   nil))

;; TODO: remove this variable (strict) in favor of string-unifier-keys.
(def strict true) ;; strict means: don't try to get smart with "foo" {:italiano "foo"} => {:italiano "foo"}; instead just :fail.

;; TODO: many code paths below only look at val1 and val2, and ignore rest of args beyond that.
;; either consider all args, or change signature of (unify) to take only val1 val2.
;; see also lexiconfn/unify (probably will change signature, but make lexiconfn/unify handle
;; have signature [& args] and pass to unify/unify with appropriate translation.
;;
;; TODO: support lazy sequences and vectors
;;
;; TODO: use commute to allow faster concurrent access: Rathore, p. 133.

(def string-unifier-keys #{:english :italiano})

(declare merge)
(declare merge-with-keys)
(declare simple-unify)

(defn merge-with-keys-simple [arg1 arg2]
  (let [keys1 (keys arg1)
        key1 (first keys1)]
    (if (not (nil? key1))
      (cond
       (and (string? (key1 arg1))
            (contains? string-unifier-keys key1)
            (contains? (set (keys arg2)) key1)
            (map? (key1 arg2)))
       :TODO
       
       (contains? (set (keys arg2)) key1)
       (simple-unify {key1 (simple-unify (key1 arg1)
                           (key1 arg2))}
              (merge-with-keys-simple (dissoc arg1 key1)
                                      (dissoc arg2 key1)))

       true
       (simple-unify {key1 (key1 arg1)}
              (merge-with-keys-simple (dissoc arg1 key1)
                                      (dissoc arg2 key1)))))))

(defn simple-unify [& args]
  (let [val1 (first args)
        val2 (second args)
        result
        (cond (and (= val1 '())
                   (= val2 :top))
              val1

              (and (= val1 '())
                   (= val2 '()))
              val1

              (and (= val1 '()))
              :fail

              (and (= val1 nil)
                   (= val2 :top))
              val1

              (= val1 nil)
              :fail

              (and (map? val1)
                   (map? val2))
              (merge-with-keys-simple val1 val2)

              true :fail)]
    result))

(defn unify [& args]
  (cond (empty? (rest args))
        (first args)
        (and (seq? args)
             (empty? (rest args)))
        (first args)
        (and (seq? args)
             (not (empty? (rest (rest args)))))
        (unify (first args) (apply unify (rest args)))
        true
        (let [val1 (first args)
              val2 (second args)]
          (cond
           (set? val1)
           (set (filter (fn [each]
                          (not (fail? each)))
                        (reduce union
                                (map (fn [each]
                                       (let [result (unifyc each val2)]
                                         (cond (set? result)
                                               result
                                               (seq? result)
                                               (set result)
                                               true
                                               (set (list result)))))
                                     val1))))

           (set? val2)
           (set (filter (fn [each]
                          (not (fail? each)))
                        (reduce union
                                (map (fn [each]
                                       (let [result (unifyc each val1)]
                                         (cond (set? result)
                                               result
                                               (seq? result)
                                               (set result)
                                               true
                                               (set (list result)))))
                                     val2))))
           
           (and (= val1 '())
                (= val2 :top))
           val1

           (and (= val1 '())
                (= val2 '()))
           val1

           (and (= val1 '()))
           :fail

           (and (= val1 nil)
                (= val2 :top))
           val1

           (= val1 nil)
           :fail

           (and (set? val1)
                (set? val2))
           (intersection val1 val2)
           
           (nil? args) nil
           
           (= (count args) 1)
           (first args)
           
           (= :fail (first args))
           :fail
           
           (= :fail (second args))
           :fail
           
           (seq? val1)
           (map (fn [each]
                  (unify each val2))
                val1)

           ;; This is the canonical unification case: unifying two DAGs
           ;; (maps with possible references within them).
           ;;
           (and (map? val1)
                (map? val2))
           (let [result (merge-with-keys val1 val2)
                 use-merge-with-fail false
                 ]
             (if (fail? result)
               (if use-merge-with-fail
                 ;; this doesn't work yet: use-merge-with-fail will be enabled when it works.
                 (merge {:fail true}
                        result)
                 :fail)

               (if (empty? (rest (rest args)))
                 result
                 (unify result
                        (apply unify (rest (rest args)))))))

           ;; val1 is a ref, val2 is a map that contains val1: return fail.
           (and (ref? val1)
                (map? val2)
                (some #(= val1 %) (get-refs-in val2)))
           (do
             :fail)

           (and (ref? val2)
                (map? val1)
                (some #(= val2 %) (get-refs-in val1)))
           :fail
           
           ;; val1 is a ref, val2 is not a ref.
           (and
            (ref? val1)
            (not (ref? val2)))
           (do
             (swap! val1
                     (fn [x] (unify @val1 val2)))
             ;; alternative to the above (not tested yet):  (fn [x] (unify (copy @val1) val2))))
             ;; TODO: why is this false-disabled? (document and test) or remove
             (if (and false (fail? @val1)) :fail
                 val1))
           
           ;; val2 is a ref, val1 is not a ref.
           (and
            (ref? val2)
            (not (ref? val1)))
           (do
             (swap! val2
                    (fn [x] (unify val1 @val2)))
             ;; alternative to the above (not tested yet): (fn [x] (unify val1 (fs/copy @val2)))))
             ;; TODO: why is this false-disabled? (document and test) or remove.
             (if (and false (fail? @val2)) :fail
                 val2))

           (and
            (ref? val1)
            (ref? val2))
           (let [refs-in-val1 (get-refs-in @val1)
                 refs-in-val2 (get-refs-in @val2)]
             
             (cond
              (or (= val1 val2) ;; same reference.
                  (= val1 @val2)) ;; val1 <- val2
              val1

              (= @val1 val2) ;; val1 -> val2
              val2
              
              (some #(= val2 %) refs-in-val1)
              :fail

              (some #(= val1 %) refs-in-val2)
              :fail

              :else
              (do
                (swap! val1
                       (fn [x] (unify @val1 @val2)))
                (swap! val2
                       (fn [x] val1)) ;; note that now val2 is a ref to a ref.
                ;; TODO: remove, since it's disabled, or add a global setting to en/dis-able.
                (if (and false (fail? @val1)) :fail
                    val1))))

           ;; convoluted way of expressing: "if val1 has the form: {:not X}, then .."
           (not (= :notfound (:not val1 :notfound)))
           (if (= val2 :top)
                                        ;       :top ;; special case: (unify :top {:not X}) => :top
             val1
             ;; else
             (let [result (unify (:not val1) val2)]
               (if (= result :fail)
                 val2
                 :fail)))
           
           ;; convoluted way of expressing: "if val2 has the form: {:not X}, then .."
           (not (= :notfound (:not val2 :notfound)))
           (if (= val1 :top)
                                        ;       val1 ;; special case mentioned above in comments preceding this function.
             val2
             (let [result (unify val1 (:not val2))]
               (if (= result :fail)
                 val1
                 :fail)))
           
           (or (= val1 :fail)
               (= val2 :fail))
           :fail

           (= val1 :top) val2
           (= val2 :top) val1
           
           ;; TODO: verify that these keyword/string exceptions are necessary - otherwise remove them.
           (= val1 "top") val2
           (= val2 "top") val1

           ;; TODO: verify that these keyword/string exceptions are necessary - otherwise remove them.
           ;; :foo,"foo" => :foo
           (and (keyword? val1)
                (string? val2)
                (= (string/replace-first (str val1) ":" "") val2))
           val1

           ;; TODO: verify that these keyword/string exceptions are necessary - otherwise remove them.
           ;; "foo",:foo => :foo
           (and (keyword? val2)
                (string? val1)
                (= (string/replace-first (str val2) ":" "") val1))
           val2

           (= val1 val2) val1
           
           ;; The follow two 2 rules allow values of :english and :italian that
           ;; are strings to over-ride values that are maps (in which
           ;; case they are specs of how to compute a string: agreement
           ;; information such as gender and number.
           (and
            (not strict)
            (map? val1)
            (string? val2))
           (do
             val2)

           (and
            (not strict)
            (string? val1)
            (map? val2))
           (do
             val1)

           :else ;; fail.
           :fail))))

(defn merge-with-keys [arg1 arg2]
  (let [keys1 (keys arg1)
        key1 (first keys1)]
    (if key1
      (cond
       (and (string? (key1 arg1))
            (contains? string-unifier-keys key1)
            (contains? (set (keys arg2)) key1)
            (map? (key1 arg2)))
       (merge
        {key1 (unify {key1 (key1 arg1)}
                     (key1 arg2))}
        (merge-with-keys (dissoc arg1 key1)
                         (dissoc arg2 key1)))


       (and (string? (key1 arg2))
            (contains? string-unifier-keys key1)
            (contains? (set (keys arg1)) key1)
            (map? (key1 arg1)))
       (merge
        {key1 (unify {key1 (key1 arg2)}
                     (key1 arg1))}
        (merge-with-keys (dissoc arg1 key1)
                         (dissoc arg2 key1)))

       (contains? (set (keys arg2)) key1)
       (merge {key1 (unify (key1 arg1)
                           (key1 arg2))}
              (merge-with-keys (dissoc arg1 key1)
                               (dissoc arg2 key1)))

       true
       (merge {key1 (key1 arg1)}
              (merge-with-keys (dissoc arg1 key1)
                               (dissoc arg2 key1))))
      arg2)))


;; unify vs. merge:
;;
;; case 1:
;; (fs/unify {:a 42} {:a 42 :b 43})
;; => {:a 42 :b 43}
;;  and:
;; (fs/match {:a 42} {:a 42 :b 43})
;; => {:b 43, :a 42} ; ok: val2 specializes val1.
;;
;; case 2:
;; (fs/unify {:a 42 :b 43} {:a 42})
;; => {:a 42 :b 43}
;;  but:
;; (fs/match {:a 42 :b 43} {:a 42})
;; => :fail          ; fail: val2 does not specialize val1.

;; special cases:
;; (fs/match {:not X} :top)
;; => :top

(defn match [val1 val2]
  "match: like unify, but requires that every path in val1 must be in val2: in other words, val2 matches, or is a specialization, of val1."
  (let [args (list val1 val2)]
;    (println (str "match(" val1 "," val2 ")"))
    (cond

     (= (count args) 1)
     (first args)

     (= :fail (first args))
     :fail

     (= :fail (second args))
     :fail

     ;; if keys(val1) is not a subset of keys(val2), then fail.
     ;; same set is ok, since a set is a subset of itself.
     (and (map? val1)
          (map? val2)
          (not (subset? (set (keys val1)) (set (keys val2)))))
     (do
;       (println (str "SUBSET FAIL: " (keys val1) " is not a subset of:" (keys val2)))
     :fail)

     (and (map? val1)
          (map? val2))
     (let [tmp-result
           (reduce #(merge-with match %1 %2) args)]
       (if (not (nil? (some #{:fail} (vals tmp-result))))
         (do
;           (println (str "found fail amongst: " (vals tmp-result)))
            :fail)
         (do ;(println (str "no fail in: " vals))
             tmp-result)))
     (and
      (ref? val1)
      (not (ref? val2)))
     (do (swap! val1
                (fn [x] (match @val1 val2)))
         ;; TODO: remove or parameterize this false-disabled code.
         (if (and false (fail? @val1)) :fail
             val1))
     (and
      (ref? val2)
      (not (ref? val1)))
     (do (swap! val2
                (fn [x] (match val1 @val2)))
         (if (and false (fail? @val2)) :fail
             val2))
     (and
      (ref? val1)
      (ref? val2))
     (do
       (if (or (= val1 val2) ;; same reference.
               (= val1 @val2)) ;; val1 <- val2
         val1
         (if (= @val1 val2) ;; val1 -> val2
           val2
           (do
             (swap! val1
                    (fn [x] (match @val1 @val2)))
             (swap! val2
                    (fn [x] val1)) ;; note that now val2 is a ref to a ref.
             (if (and false (fail? @val1)) :fail
                 val1)))))

     ;; convoluted way of expressing: "if val1 has the form: {:not X}, then .."
     (not (= :notfound (:not val1 :notfound)))
     (if (= val2 :top)
       :top ;; special case mentioned above in comments preceding this function.
       ;; else, (if val2 is not :top)..
       (let [result (match (:not val1) val2)]
         (if (= result :fail)
           val2
           :fail)))

     ;; convoluted way of expressing: "if val2 has the form: {:not X}, then .."
     (not (= :notfound (:not val2 :notfound)))
     (if (= val1 :top) val1 ;; another special case: (match :top {:not X}) => :top
         (let [result (match val1 (:not val2))]
           (if (= result :fail)
             val1
             :fail)))

     (or (= val1 :fail)
         (= val2 :fail))
     :fail
     (= val1 :top) val2
     (= val2 :top) val1

     ;; TODO: remove if not needed.
     (= val1 "top") val2
     (= val2 "top") val1

     ;; :foo,"foo" => :foo
     (and (keyword? val1)
          (string? val2)
          (= (string/replace-first (str val1) ":" "") val2))
     val1

     ;; "foo",:foo => :foo
     (and (keyword? val2)
          (string? val1)
          (= (string/replace-first (str val2) ":" "") val1))
     val2

     (= val1 val2) val1

     :else :fail)))

(defn merge [& args]
  "warning: {} is the identity value, not nil; that is: (merge X {}) => X, but (merge X nil) => nil, (not X)."
  (if (empty? (rest args)) (first args))
  (let [val1 (first args)
        val2 (second args)]
    (cond

     (set? val1)
     (set (filter (fn [each]
                    (not (fail? each)))
                  (reduce union
                          (map (fn [each]
                                 (let [result (merge (copy each) (copy val2))]
                                   (cond (set? result)
                                         result
                                         (seq? result)
                                         (set result)
                                         true
                                         (set (list result)))))
                               val1))))

     (set? val2)
     (set (filter (fn [each]
                    (not (fail? each)))
                  (reduce union
                          (map (fn [each]
                                 (let [result (merge (copy each) (copy val1))]
                                   (cond (set? result)
                                         result
                                         (seq? result)
                                         (set result)
                                         true
                                         (set (list result)))))
                               val2))))

     (= (count args) 1)
     (first args)

     (and (map? val1)
          (map? val2))
     (reduce #(merge-with merge %1 %2) args)

     (and
      (ref? val1)
      (not (ref? val2)))
     (do (swap! val1
                (fn [x] (merge @val1 val2)))
         val1)

     (and
      (ref? val2)
      (not (ref? val1)))
     (do (swap! val2
                (fn [x] (merge val1 @val2)))
         val2)

     (and
      (ref? val1)
      (ref? val2))
     (do (swap! val1
                (fn [x] (merge @val1 @val2)))
         val1)

     (and (= val2 :top)
          (not (= :notfound (:not val1 :notfound))))
     val1

     (not (= :notfound (:not val1 :notfound)))
     (let [result (unify (:not val1) val2)]
       (if (= result :fail)
         val2
         :fail))

     (and (= val1 :top)
          (not (= :notfound (:not val2 :notfound))))
     val2

     (not (= :notfound (:not val2 :notfound)))
     (let [result (unify val1 (:not val2))]
        (if (= result :fail)
          val1
          :fail))

     (or (= val1 :fail)
         (= val2 :fail))
     :fail

     (= val1 :top) val2
     (= val2 :top) val1
     (= val1 nil) val2

     ;; note difference in behavior between nil and :nil!:
     ;; (nil is ignored, while :nil! is not).
     ;; (merge 42 nil) => 42
     ;; (merge 42 :nil!) => :nil!
     (= val2 nil) val1
     (= val2 :nil!) val2
     (= val2 "nil!") val2 ;; TODO: remove this if not needed.

     (= val1 val2) val1

     :else ;override with remainder of arguments, like core/merge.
     (apply merge (rest args)))))

(defn merge-debug [& args]
  (let [retval (apply merge args)]
    retval))

(defn deref-map [input]
  input)

;; TODO: remove *exclude-keys*,(pathify-r) and (pathify) in favor of fs's versions.
(def ^:dynamic *exclude-keys* (set #{:_id :ref :refmap}))

(defn pathify-r [fs & [prefix]]
"Transform a map into a map of paths/value pairs,
 where paths are lists of keywords, and values are atomic values.
 e.g.:
 {:foo {:bar 42, :baz 99}} =>  { { (:foo :bar) 42}, {(:foo :baz) 99} }
The idea is to map the key :foo to the (recursive) result of pathify on :foo's value."
  (mapcat (fn [kv]
            (let [key (first kv)
                  val (second kv)]
;              (println (str "K:" key))
              (if (not (contains? *exclude-keys* key))
                (if (map? val)
                  (do
;                    (println (str "PAM"))
                    (pathify-r val (concat prefix (list key))))
                  (if (and (ref? val)
                           (let [val @val]
                             (map? val)))
                    (pathify-r @val (concat prefix (list key)))
                  (do
;                    (println (str "not PAM" (type val)))
                    (list {(concat prefix (list key))
                           (if (ref? val) @val ;; simply resolve references rather than trying to search for graph isomorphism.
                               val)})))))))
          fs))

(defn pathify [fs]
  (pathify-r fs))


(def uniq-using-recur
  "remove duplicates by checking first and second: if equal, remove the first and keep the second. otherwise, keep both."
  (fn [sorted-vals]
    (loop [sv sorted-vals result nil]
      (let [first-val (first sv)]
        (if (nil? (first sv))
          result
          (let [second-val (second sv)]
            (if (= first-val second-val)
              (recur (rest sv)
                     result)
              (recur (rest sv)
                     (cons first-val result)))))))))

(defn uniq [sorted-vals]
  (reverse (uniq-using-recur sorted-vals)))

;; by comparison (causes stack overflow on large lists):
(defn uniq-recursive [sorted-vals]
  (let [first-val (first sorted-vals)]
    (if first-val
      (let [second-val (second sorted-vals)]
        (if second-val
          (if (= first-val second-val)
            (uniq-recursive (rest sorted-vals))
            (cons first-val
                  (uniq-recursive (rest sorted-vals))))
          (list first-val))))))

(defn paths-to-value [map value path]
  (if (= map value) (list path)
      (if (ref? map)
        (paths-to-value @map value path)
        (if (map? map)
          (mapcat (fn [key]
                    (paths-to-value (get map key) value (concat path (list key))))
                  (keys map))))))

(defn all-refs [input]
  (if input
    (do
      (if (ref? input)
        (cons
         (if (ref? @input)
           ;; dereference double-references (references to another reference) :
           @input
           ;; a simple reference: reference to a non-reference (e.g. a map, boolean, etc):
           input)
         (all-refs @input))
        (if (map? input)
          ;; TODO: fix bug here: vals resolves @'s
          (concat
           (mapcat (fn [key]
                     (let [val (get input key)]
                       (if (ref? input)
                         (if (ref? @val)
                           (list @val)
                           (list val)))))
                   input)
           (all-refs
            (map (fn [val]
                   ;; dereference double-references (references to another reference) :
                   (if (and (ref? val)
                            (ref? @val))
                     (do
;                       (println (str "double ref: " val " -> " @val " -> " @@val))
                       @val)
                     ;; a simple reference: reference to a non-reference (e.g. a map, boolean, etc):
                     val))
                 (vals input))))
          (if (and (seq? input)
                   (> (count input) 0))
            (concat
             (all-refs (first input))
             (all-refs (rest input)))))))))

(defn skeletize [input-val]
  (if (map? input-val)
    (zipmap (keys (dissoc input-val :serialized))
            (map (fn [val]
                   (if (ref? val)
                     :top
                     (if (map? val)
                       (skeletize val)
                       val)))
                 (vals (dissoc input-val :serialized))))
    input-val))

;; TODO s/map/input-map/
;; TODO: merge or distinguish from all-refs (above)
(defn get-refs [input-map]
  (seq (set (all-refs input-map))))

;; TODO s/map/input-map/
(defn skels [input-map refs]
  "create map from reference to their skeletons."
  (let [
        refs (get-refs input-map)
        ]
    (zipmap
     refs
     (map (fn [ref]
            (skeletize @ref))
          refs))))

(defn ref-skel-map [input-map]
  "associate each reference in _input-map_ with:
   1. its skeleton
   2. all paths to point to it."
  (let [refs (get-refs input-map)
        ;; skels returns a map from a reference to its skeleton.
        skels (skels input-map refs)]
    (zipmap
     ;; associate each ref with its skeleton.
     (map (fn [ref]
            {:ref ref
             :skel (get skels ref)})
          refs)

     ;; list of all paths that point to each ref in _input-map_.
     (map (fn [eachref]
            (paths-to-value input-map eachref nil))
          refs))))

;; only used for testing: move to test.fs.
(defn ser-db [input-map]
  (let [refs (get-refs input-map)
        skels (skels input-map refs)]
    (ref-skel-map input-map)))

;; (((:a :c) (:b :c) (:d))
;;  ((:a) (:b))
;;  nil)
;;     =>
;; {((:a :c) (:b :c) (:d)) => 2
;;  ((:a)    (:b))         => 1
;;  nil                    => 0
;; }
(defn max-lengths [serialization]
  ;; check type (TODO: use multimethods instead)
  (if (= (first (first serialization)) ())
    (exception "Serializing a map failed because one of the map's keys had a sequence as its value. For now, only maps and atoms are supported as values of keys."))
  (let [keys (keys serialization)]
    (zipmap
     keys
     (map (fn [paths]
            (cond (nil? paths) 0
                  (= 0 (count paths)) 0
                  true
                  (apply max (map (fn [path] (if (nil? path) 0 (count path))) paths))))
          keys))))

(defn sort-by-max-lengths [serialization]
  (let [max-lengths (max-lengths serialization)]
    (sort (fn [x y] (< (second x) (second y)))
          max-lengths)))

(defn sort-shortest-path-ascending-r [serialization path-length-pairs]
  (if (first path-length-pairs)
    (let [path-length-pair (first path-length-pairs)
          paths (first path-length-pair)
          max-length (second path-length-pair)]
      (cons
       (list paths
             (get serialization paths))
       (sort-shortest-path-ascending-r serialization (rest path-length-pairs))))))

(defn ser-intermed [input-map]
  (cond (set? input-map)
        (set (map (fn [each]
                    (ser-intermed each))
                  input-map))
        true
        (let [top-level (skeletize input-map)
              rsk (ref-skel-map input-map)
              sk (map (fn [ref-skel]
                        (:skel ref-skel))
                      (keys rsk))]
          (merge
           {nil top-level}
           (zipmap
            (vals rsk)
            sk)))))

(defn create-shared-values [serialized]
  (map (fn [paths-vals]
         (let [val (second paths-vals)]
           ;; TODO: why/why not do copy val rather than just val(?)
           (atom val)))
       serialized))

(defn create-path-in [path value]
  "create a path starting at map through all keys in map:
   (create-path-in '(a b c d e) value) => {:a {:b {:c {:d {:e value}}}}})"
  (if (first path)
    (if (rest path)
      (let [assigned (create-path-in (rest path) value)]
        {(keyword (first path)) assigned})
      {(first path) value})
    value))

;; Serialization format is a sequence:
;; (
;;  paths1 => map1 <= 'base'
;;  paths2 => map2
;;  ..
;; )
;; 'base' is the outermost map 'skeleton' (
;; a 'skeleton' is a map with the dummy placeholder
;; value :top).
;;
;; Note that (deserialize) should be able to cope with
;; both lists and arrays (i.e. just assume a sequence).
(defn deserialize [serialized]
  (cond (set? serialized)
        (set (map (fn [each]
                    (deserialize each))
                  serialized))
        true (let [base (second (first serialized))]
               (apply merge-debug
                      (let [all
                            (cons base
                                  (flatten
                                   (map (fn [paths-val]
                                          (let [paths (first paths-val)
                                                val (atom (second paths-val))]
                                            (map (fn [path]
                                                   (create-path-in path val))
                                                 paths)))
                                        (rest serialized))))]
                        all)))))

(defn serialize [input-map]
  (cond
   (set? input-map)
   (set (map (fn [each]
               (serialize each))
             input-map))
   true
   (let [memoized (get input-map :serialized :none)]
     (if (not (= memoized :none))
       memoized
       (let [ser (ser-intermed input-map)]
        ;; ser is a intermediate (but fully-serialized) representation
         ;; of the input map, as a map from pathsets to reference-free maps
         ;; (maps which have no references within them).

         ;; In place of the references in the original map, the reference-free
         ;; maps have simply a dummy value (the value :top) stored where the
         ;; the reference is in the input-map.
         ;;
         ;; ser:
         ;;
         ;;   pathset    |  value
         ;; -------------+---------
         ;;   pathset1   => value1
         ;;   pathset2   => value2
         ;;      ..         ..
         ;;   nil        => outermost_map
         ;;
         ;; Each pathset is a set of paths to a shared value, the value
         ;; shared by all paths in that pathset.
         ;;
         ;; The last row shown is for the outermost_map that represents
         ;; the entire input, which is why its pathset is nil.
         ;;
         ;; However, ser is not sorted by path length: it needs to be
         ;; sorted so that, when deserialization is done, assignment
         ;; will occur in the correct order: shortest path first.

         ;; Thefore, we now sort _ser_ in a shortest-path-first order, so that
         ;; during de-serialization, all assignments will happen in this
         ;; same correct order.

         (sort-shortest-path-ascending-r ser (sort-by-max-lengths ser)))))))

(defn optimized-ser [input-map]
  "generate a better serialized form that removes intermediate refs (refs to other refs)"
  (let [serialized (serialize input-map)]
    serialized))

(defn copy [input]
  (cond (seq? input)
        (map (fn [each]
               (copy each))
             input)
        true
        (deserialize (serialize input))))

(defn trunc [serialized]
  "create a new serialized map with all paths removed that are non-immediate."
  (map (fn [paths-skel-pair]
         (let [paths (first paths-skel-pair)
               skel (second paths-skel-pair)]
           (let [filtered-paths
                 (filter (fn [path]
                           (let [take-2 (take 2 path)]
                             (and (not (= take-2
                                          '(:head :head)))
                                  (not (= take-2
                                          '(:comp :comp)))
                                  (not (= take-2
                                          '(:head :comp)))
                                  (not (= take-2
                                          '(:comp :2)))
                                  (not (= take-2
                                          '(:head :1)))
                                  (not (= take-2
                                          '(:head :2)))
                                  (not (= take-2
                                          '(:1 :1)))
                                  (not (= take-2
                                          '(:1 :comp)))
                                  (not (= take-2
                                          '(:1 :head)))
                                  (not (= take-2
                                          '(:1 :2)))
                                  (not (= take-2
                                          '(:2 :2)))
                                  (not (= take-2
                                          '(:2 :1)))
                                  (not (= take-2
                                          '(:2 :head)))
                                  (not (= take-2
                                          '(:2 :comp))))))
                         paths)]
           (list filtered-paths skel))))
       serialized))

(defn copy-trunc [map]
  (deserialize (trunc (serialize map))))

(defn unifyc [& args]
  "like fs/unify, but fs/copy each argument before unifying."
  (apply unify
         (map (fn [arg]
                (copy arg))
              args)))

(defn has-path [path paths]
  (if (first paths)
    (if (= (first paths) path)
      true
      (has-path path (rest paths)))))

(defn path-to-ref-index [serialized path n]
  "given serialized form of a map, find the index for _path_. Start with 0."
  (if (first serialized)
    (let [paths (butlast (first serialized))
          has-path (has-path path (first paths))]
      (if (not (nil? has-path))
        n
        (path-to-ref-index (rest serialized) path (+ n 1))))))

(defn compare-bytewise [a b index]
  "compare two byte arrays by casting each byte to short."
  (if (> (alength a) index)
    (if (> (alength b) index)
      (if (= (nth a index)
             (nth b index))
        (compare-bytewise a b (+ 1 index))
        (< (nth a index)
           (nth b index)))
      true)
    false))

(defn sorted-paths-1 [paths]
  (sort (fn [x y]
          (let [size-x (count x)
                size-y (count y)]
            (cond (< (count x) (count y)) true
                  (> (count x) (count y)) false
                  true (compare-bytewise (.getBytes (str x)) (.getBytes (str y)) 0))))
          paths))

(defn sorted-paths [serialized path n index]
  (let [lookup (nth serialized index)
        allpaths (seq (first (butlast lookup)))]
    (sorted-paths-1 allpaths)))

(defn is-first-path [serialized path n index]
  (if (nil? index)
    (exception (str "Index was null in serialized feature structure: " serialized)))
  (let [lookup (nth serialized index)
          firstpath (seq (first (sorted-paths serialized path n index)))]
      true))

(defn first-path [serialized path n index]
  (let [lookup (nth serialized index)
        firstpath (seq (first (sorted-paths serialized path n index)))]
    firstpath))

(defn ref= [map path1 path2]
  "return true iff path1 and path2 point to the same object."
  ;; TODO: add error checking.
  (let [butlast-val1 (get-in map (butlast path1) :none)
        butlast-val2 (get-in map (butlast path2) :none)]
    (and
     (not (= butlast-val1 :none))
     (not (= butlast-val2 :none))
     (= (get butlast-val1 (last path1) :none1)
        (get butlast-val2 (last path2) :none2)))))

(defn strip-refs [map-with-refs]
  "return a map like map-with-refs, but without refs - (e.g. {:foo (atom 42)} => {:foo 42}) - used for printing maps in plain (i.e. non html) format"
  (cond
   (or (vector? map-with-refs)
       (seq? map-with-refs))
   (map strip-refs map-with-refs)
   (= map-with-refs {})
   {}
   (map? map-with-refs)
   (let [map-keys (sort (keys map-with-refs))]
     (let [first-key (first (keys map-with-refs))
           val (get map-with-refs first-key)]
       (conj
        {first-key (strip-refs val)}
        (strip-refs (dissoc map-with-refs first-key)))))
   (ref? map-with-refs)
   (strip-refs (deref map-with-refs))
   :else
   map-with-refs))

(defn remove-top-values [fs]
  "Use case is logging where we don't care about uninformative key->value pairs where value is simply :top. Also strips refs for readability."
  (cond

   (= fs {})
   {}

   (map? fs)
   (let [map-keys (sort (keys fs))]
     (let [first-key (first (keys fs))
           val (get fs first-key)]
       (cond
        (and (not (= first-key :1)) 
             (not (= first-key :2)) 
             (not (= first-key :3))
             (= val :top)) ;; remove-top-values: core action of this function.
        (remove-top-values (dissoc fs first-key))

        (= first-key :comp-filter-fn) ;; TODO: deprecate and remove comp-filter-fn.
        (remove-top-values (dissoc fs first-key))

         ;; else, KV is not :top, so keep it.
        true
        (conj
         {first-key (remove-top-values val)}
         (remove-top-values (dissoc fs first-key))))))

   (ref? fs)
   ;; strip refs for readability.
   (remove-top-values (deref fs))

   :else
   fs))

(defn remove-matching-values [fs pred]
  "Use case is same as remove-top-values, but more general by use of pred: pred is a function that takes a key and a value; if (pred k v) or (pred k @v) is true, remove the kv from the fs."
  (cond

   (= fs {})
   {}

   (map? fs)
   (let [map-keys (sort (keys fs))]
     (let [k (first (keys fs))
           v (get fs k)]
       (cond
        (and (ref? v)
             (pred k @v))
        (remove-matching-values (dissoc fs k) pred)

        (pred k v)
        ;; remove (k v) from the map.
        (remove-matching-values (dissoc fs k) pred)

         ;; else keep (k v), but if v is itself a map, recursively remove things that match pred within v.
        true
        (conj
         {k (remove-matching-values v pred)}
         (remove-matching-values (dissoc fs k) pred)))))

   (ref? fs)
   ;; strip refs for readability.
   (remove-matching-values (deref fs) pred)

   :else
   fs))

(defn remove-top-values-log [fs]
  (let [result (remove-top-values fs)]
    ;; TODO: should not need to re-call this: workaround for the fact that remove-top-values doesn't work correctly,
    ;; but does seem to work correctly if called again on its own output.
    (remove-top-values result)))

(defn refset2map [fs]
  "Turn every ref to a set into a map with two keys: :ref and :val."
  (cond

   (set? fs)
   (set (map (fn [each]
               (refset2map each))
             fs))

   (and (ref? fs)
        (set? @fs))
   (set (map (fn [each]
               {:val (refset2map each)
                :ref fs})
             @fs))

   (ref? fs)
   {:val (refset2map @fs)
    :ref fs}

   (and (map? fs)
        (not (empty? fs)))
   (let [key (first (first fs))
         val (key fs)]
     (conj
      {key (refset2map val)}
      (refset2map (dissoc fs key))))

   true
   fs))

(defn get-all-ref-tuples [fs & [path]]
  "returns list of ref:val:path tuples."
  (let [path (if path path nil)]
    (cond
     (and (map? fs)
          (not (empty? fs))
          (:ref fs))
     (list {:ref (:ref fs)
            :val (:val fs)
            :path path})
     (and (map? fs)
          (not (empty? fs)))
     (let [key (first (first fs))
           val (key fs)]
       (concat
        (get-all-ref-tuples val (concat path (list key)))
        (get-all-ref-tuples (dissoc fs key) path)))
     true nil)))

(defn get-all-refs-for [fs]
  "get the set of refs in a normalized fs"
  (apply union
         (map (fn [tuple]
                (set (list (:ref tuple))))
              (get-all-ref-tuples fs))))

(defn cartesian [set1 set2]
  "for x in set1, y in set2, conj each x and each y"
  (cond (empty? set1) set2
        (empty? set2) set1
        true
        (apply union
               (map (fn [each-map-in-set-1]
                      (set (map (fn [each-map-in-set-2]
                                  (conj each-map-in-set-1 each-map-in-set-2))
                                set2)))
                    set1))))
(defn get-unified-value-for [fs ref]
  "get all values to be unified for the given ref in the given normalized fs."
  (reduce unify
          (apply concat
                 (map (fn [tuple]
                        (let [tuple-ref (:ref tuple)]
                          (if (= tuple-ref ref)
                            (list (:val tuple)))))
                      (get-all-ref-tuples fs)))))

(defn copy-with-ref-substitute [fs old-ref new-ref]
  "create new fs, but with new-ref substituted for every occurance of {:ref ref,:val X}"
  (cond
   (and (map? fs)
        (not (empty? fs))
        (not (nil? (:ref fs)))
        (= (:ref fs) old-ref))
   new-ref

   (and (map? fs)
        (not (empty? fs)))
   (let [key (first (first fs))
         val (key fs)]
     (conj {key (copy-with-ref-substitute val old-ref new-ref)}
           (copy-with-ref-substitute (dissoc fs key) old-ref new-ref)))
   true
   fs))

(defn copy-with-assignments [fs assignments]
  (if (not (empty? assignments))
    (let [assignment (first assignments)
          old-ref (:ref assignment)
          new-ref (atom (:val assignment))]
      (copy-with-assignments
       (copy-with-ref-substitute fs old-ref new-ref)
       (rest assignments)))
    fs))

(defn step2 [fs]
  "step2.."
  (cond

   (and (set? fs)
        (not (empty? fs)))
   (union
    (step2 (first fs))
    (step2 (set (rest fs))))

   (and (map? fs)
        (not (empty? fs)))
   (let [key (first (first fs))
         val (step2 (key fs))]
     (cond

      (and (set? val)
           (empty? val))
      val

      (set? val)
      (cartesian
       (union
        #{{key (first val)}}
        (step2 {key (set (rest val))}))
       (step2 (dissoc fs key)))

      true
      (cartesian
       (set (list {key val}))
       (step2 (dissoc fs key)))))

   (and (seq? fs)
        (empty? fs))
   fs

   (and (set? fs)
        (empty? fs))
   fs

   true
   (set (list fs))))

(defn expand-disj [input]
  (let [step2-set (step2 (refset2map input))
        refs-per-fs
        (zipmap (seq step2-set)
                (map (fn [each-member]
                       (get-all-refs-for each-member))
                     step2-set))

        unified-values (map (fn [each-fs]
                              {:fs each-fs
                               :assignments
                               (map (fn [each-ref-in-fs]
                                      {:ref each-ref-in-fs
                                    :val (get-unified-value-for each-fs each-ref-in-fs)})
                                    (get refs-per-fs each-fs))})
                            step2-set)]
    (set (filter (fn [each]
                   (not (fail? each)))
                 (map (fn [each-tuple]
                        (let [fs (:fs each-tuple)
                              assignments (:assignments each-tuple)]
                          (copy-with-assignments fs assignments)))
                      unified-values)))))

(defn dissoc-paths [fs & [paths]]
  "dissoc a path from a map; e.g.: (dissoc-paths {:a {:b 42 :c 43}} '(:a :b)) => {:a {:c 43}}."
  (do
    (cond (empty? paths)
          fs

          (seq? fs)
          (map #(dissoc-paths % paths) fs)

          (ref? fs)
          (dissoc-paths @fs paths)

          (keyword? fs)
          fs

          (empty? fs)
          :top

          (seq? fs)
          (cons (dissoc-paths (first fs))
                (dissoc-paths (rest fs)))

          true
          (let [path (first paths)]
            (dissoc-paths
             (cond (keyword fs)
                   fs
                   (and (map? fs)
                        (not (empty? fs))
                        (not (empty? path)))
                   (let [feature (first path)]
                     (cond (ref? fs)
                           (dissoc-paths @fs (list path))
                           (map? fs)
                           (cond
                            (and
                             (empty? (rest path))
                             (empty? (dissoc fs feature)))
                            :top

                            (empty? (rest path))
                            (dissoc fs feature)

                            (not (= :notfound (get-in fs (list feature) :notfound)))
                            (conj
                             {feature (dissoc-paths (get-in fs (list feature)) (list (rest path)))}
                             (dissoc fs feature))

                            true
                            (dissoc-paths fs (rest paths)))))

                   true
                   (exception
                    (str "dissoc-paths: don't know what to do with this input argument (fs): "
                         fs)))
             (rest paths))))))

(def use-lazy-shuffle true)

;; thanks to Boris V. Schmid for lazy-shuffle:
;; https://groups.google.com/forum/#!topic/clojure/riyVxj1Qbbs
(defn lazy-shuffle [coll]
  (if (= false use-lazy-shuffle) (shuffle coll)
      (let [size (count coll)]
        (if (> size 0)
          (let [rand-pos (rand-int size)
                [prior remainder]
                (split-at rand-pos coll)
                elem (nth coll rand-pos)]
            (lazy-seq
             (cons elem
                   (lazy-shuffle (concat prior (rest remainder))))))))))

(defn remove-false [spec]
  (cond (map? spec)
        (into {}
              (map (fn [key]
                     (let [val (get-in spec (list key))]
                       (if (not (= val false))
                         [key (remove-false val)])))
                   (keys spec)))
        
        (seq? spec)
        (map (fn [each]
               (remove-false each))
             spec)
        (ref? spec)
        (remove-false @spec)

        true
        spec))

(defn show-spec [spec]
  (cond (seq? spec)
        (map show-spec spec)
        true
        (remove-top-values-log (dissoc-paths spec '((:english :initial)
                                                    (:italiano :initial)
                                                    (:synsem :essere)
                                                    (:synsem :agr)
                                                    (:synsem :pronoun)
                                                    (:synsem :sem :tense)
                                                    (:synsem :sem :obj :tense)
                                                    (:synsem :sem :mod)
                                                    (:synsem :infl))))))

(defn isomorphic? [a b]
  (cond (and (map? a)
             (map? b)
             (empty? a)
             (empty? b))
        true  ;; two empty maps are equal
        (and (map? a)
             (map? b)
             (or (empty? a)
                 (empty? b)))
        false ;; two maps whose key cardinality (different number of keys) is different are not equal.
        (and (map? a)
             (map? b))
        (and (isomorphic? (get a (first (keys a))) ;; two maps are isomorphic if their keys' values are isomorphic.
                          (get b (first (keys a))))
             (isomorphic? (dissoc a (first (keys a)))
                          (dissoc b (first (keys a)))))
        (and (ref? a)
             (ref? b))
        (isomorphic? @a @b)
        true
        (= a b)))

(defn label-of [parent]
  (if (:rule parent) (:rule parent) (:comment parent)))

(defn find-fail-in [fs1 fs2 paths]
  (if (not (empty? paths))
    (let [path (first paths)
          val1 (get-in fs1 path :top)
          val2 (get-in fs2 path :top)]
      (if (fail? (unify val1 val2))
        {:fail-path path
         :val1 val1
         :val2 val2}
        (find-fail-in fs1 fs2 (rest paths))))))

(defn fail-path-between [fs1 fs2]
  (let [paths-in-fs1 (map #(first (first %)) (pathify-r fs1))
        paths-in-fs2 (map #(first (first %)) (pathify-r fs2))]
    (find-fail-in fs1 fs2 (concat paths-in-fs1 paths-in-fs2))))

#?(:clj (require 'cljs.repl))
#?(:clj (require 'cljs.build.api))
#?(:clj (require 'cljs.repl.browser))

;#?(:clj (cljs.build.api/build "src"
;                              {:main 'hello-world.core
;                               :output-to "out/main.js"
;                               :verbose true}))

;#?(:clj (cljs.repl/repl (cljs.repl.browser/repl-env)
;                        :watch "src"
;                        :output-dir "out"))
