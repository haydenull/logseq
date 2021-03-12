(ns frontend.handler.dnd
  (:require [frontend.handler.notification :as notification]
            [frontend.handler.repo :as repo-handler]
            [frontend.handler.block :as block-handler]
            [frontend.config :as config]
            [frontend.state :as state]
            [frontend.util :as util :refer-macros [profile]]
            [frontend.db :as db]
            [clojure.walk :as walk]
            [clojure.string :as string]
            [frontend.utf8 :as utf8]
            [cljs-time.coerce :as tc]
            [cljs-time.core :as t]))

(defn- remove-block-child!
  [target-block parent-block]
  (let [child-ids (set (block-handler/get-block-ids target-block))]
    (block-handler/get-block-content-rec
     parent-block
     (fn [{:block/keys [uuid level content]}]
       (if (contains? child-ids uuid)
         ""
         content)))))

(defn- recompute-block-level
  [to-block nested?]
  (+ (:block/level to-block)
     (if nested? 1 0)))

(defn- recompute-block-content-and-changes
  [target-block to-block nested? same-repo? same-file?]
  (let [new-level (recompute-block-level to-block nested?)
        target-level (:block/level target-block)
        format (:block/format target-block)
        block-changes (atom [])
        all-content (block-handler/get-block-content-rec
                     target-block
                     (fn [{:block/keys [uuid level content]
                           :as block}]
                       (let [new-level (+ new-level (- level target-level))
                             ;; TODO: verify whether it works for unordered list
                             new-content (string/replace-first content
                                                               (config/repeat-block-pattern format level)
                                                               (config/repeat-block-pattern format new-level))
                             block (cond->
                                    {:block/uuid uuid
                                     :block/level new-level
                                     :block/content new-content
                                     :block/page (:block/page to-block)}

                                     (not same-repo?)
                                     (merge (dissoc block [:block/level :block/content]))

                                     (not same-file?)
                                     (merge {:block/page (:block/page to-block)
                                             :block/file (:block/file to-block)}))]
                         (swap! block-changes conj block)
                         new-content)))]
    [all-content @block-changes]))

(defn- move-parent-to-child?
  [target-block to-block]
  (let [to-block-id (:block/uuid to-block)
        result (atom false)
        _ (walk/postwalk
           (fn [form]
             (when (map? form)
               (when-let [id (:block/uuid form)]
                 (when (= id to-block-id)
                   (reset! result true))))
             form)
           target-block)]
    @result))

(defn- compute-target-child?
  [target-block to-block]
  (let [target-block-id (:block/uuid target-block)
        result (atom false)
        _ (walk/postwalk
           (fn [form]
             (when (map? form)
               (when-let [id (:block/uuid form)]
                 (when (= id target-block-id)
                   (reset! result true))))
             form)
           to-block)]
    @result))

(defn rebuild-dnd-blocks
  [repo file target-child? start-pos target-blocks offset-block-uuid {:keys [delete? same-file?]
                                                                      :or {delete? false
                                                                           same-file? true}}]
  (when (seq target-blocks)
    (let [file-id (:db/id file)
          target-block-ids (set (map :block/uuid target-blocks))
          after-blocks (->> (db/get-file-after-blocks repo file-id start-pos)
                            (remove (fn [h] (contains? target-block-ids (:block/uuid h)))))

          after-blocks (cond
                         delete?
                         after-blocks

                         (and offset-block-uuid
                              (not (contains? (set (map :block/uuid after-blocks)) offset-block-uuid)))
                         (concat target-blocks after-blocks)

                         offset-block-uuid
                         (let [[before after] (split-with (fn [h] (not= (:block/uuid h)
                                                                        offset-block-uuid)) after-blocks)]
                           (concat (conj (vec before) (first after))
                                   target-blocks
                                   (rest after)))
                         :else
                         (concat target-blocks after-blocks))
          after-blocks (remove nil? after-blocks)
          ;; _ (prn {:start-pos start-pos
          ;;         :target-blocks target-blocks
          ;;         :after-blocks (map (fn [block]
          ;;                                (:block/content block))
          ;;                           after-blocks)})
          last-start-pos (atom start-pos)
          result (mapv
                  (fn [{:block/keys [uuid meta content level page] :as block}]
                    (let [content (str (util/trim-safe content) "\n")
                          target-block? (contains? target-block-ids uuid)
                          content-length (if target-block?
                                           (utf8/length (utf8/encode content))
                                           (- (:end-pos meta) (:start-pos meta)))
                          new-end-pos (+ @last-start-pos content-length)
                          new-meta {:start-pos @last-start-pos
                                    :end-pos new-end-pos}]
                      (reset! last-start-pos new-end-pos)
                      (let [data {:block/uuid uuid
                                  :block/meta new-meta}]
                        (cond
                          (and target-block? (not same-file?))
                          (merge
                           (dissoc block :block/idx :block/dummy?)
                           data)

                          target-block?
                          (merge
                           data
                           {:block/level level
                            :block/content content
                            :block/page page})

                          :else
                          data))))
                  after-blocks)]
      result)))

(defn- get-start-pos
  [block]
  (get-in block [:block/meta :start-pos]))

(defn- get-end-pos
  [block]
  (get-in block [:block/meta :end-pos]))

(defn- compute-direction
  [target-block top-block nested? top? target-child?]
  (cond
    (= top-block target-block)
    :down

    (and target-child? nested?)
    :up

    (and target-child? (not top?))
    :down

    :else
    :up))

(defn- compute-after-blocks-in-same-file
  [repo target-block to-block direction top? nested? target-child? target-file original-top-block-start-pos block-changes]
  (cond
    top?
    (rebuild-dnd-blocks repo target-file target-child?
                        original-top-block-start-pos
                        block-changes
                        nil
                        {})

    (= direction :up)
    (let [offset-block-id (if nested?
                            (:block/uuid to-block)
                            (last (block-handler/get-block-ids to-block)))
          offset-end-pos (get-end-pos
                          (db/entity repo [:block/uuid offset-block-id]))]
      (rebuild-dnd-blocks repo target-file target-child?
                          offset-end-pos
                          block-changes
                          nil
                          {}))

    (= direction :down)
    (let [offset-block-id (if nested?
                            (:block/uuid to-block)
                            (last (block-handler/get-block-ids to-block)))
          target-start-pos (get-start-pos target-block)]
      (rebuild-dnd-blocks repo target-file target-child?
                          target-start-pos
                          block-changes
                          offset-block-id
                          {}))))

;; TODO: still could be different pages, e.g. move a block from one journal to another journal
(defn- move-block-in-same-file
  [repo target-block to-block top-block bottom-block nested? top? target-child? direction target-content target-file original-top-block-start-pos block-changes]
  (if (move-parent-to-child? target-block to-block)
    nil
    (let [old-file-content (db/get-file (:file/path (db/entity (:db/id (:block/file target-block)))))
          old-file-content (utf8/encode old-file-content)
          subs (fn [start-pos end-pos] (utf8/substring old-file-content start-pos end-pos))
          bottom-content (block-handler/get-block-content-rec bottom-block)
          top-content (remove-block-child! bottom-block top-block)
          top-area (subs 0 (get-start-pos top-block))
          bottom-area (subs
                       (cond
                         (and nested? (= direction :down))
                         (get-end-pos bottom-block)
                         target-child?
                         (block-handler/get-block-end-pos-rec repo top-block)
                         :else
                         (block-handler/get-block-end-pos-rec repo bottom-block))
                       nil)
          between-area (if (= direction :down)
                         (subs (block-handler/get-block-end-pos-rec repo target-block) (get-start-pos to-block))
                         (subs (block-handler/get-block-end-pos-rec repo to-block) (get-start-pos target-block)))
          up-content (when (= direction :up)
                       (cond
                         nested?
                         (util/join-newline (:block/content top-block)
                                            target-content
                                            (if target-child?
                                              (remove-block-child! target-block (:block/children to-block))
                                              (block-handler/get-block-content-rec (:block/children top-block))))
                         (and top? target-child?)
                         (util/join-newline target-content (remove-block-child! target-block to-block))

                         top?
                         (util/join-newline target-content top-content)

                         :else
                         (let [top-content (if target-child?
                                             (remove-block-child! target-block to-block)
                                             top-content)]
                           (util/join-newline top-content target-content))))
          down-content (when (= direction :down)
                         (cond
                           nested?
                           (util/join-newline (:block/content bottom-block)
                                              target-content)
                           target-child?
                           (util/join-newline top-content target-content)

                           :else
                           (util/join-newline bottom-content target-content)))
          ;; _ (prn {:direction direction
          ;;         :nested? nested?
          ;;         :top? top?
          ;;         :target-child? target-child?
          ;;         :top-area top-area
          ;;         :up-content up-content
          ;;         :between-area between-area
          ;;         :down-content down-content
          ;;         :bottom-area bottom-area
          ;;         })
          new-file-content (string/trim
                            (util/join-newline
                             top-area
                             up-content
                             between-area
                             down-content
                             bottom-area))
          after-blocks (->> (compute-after-blocks-in-same-file repo target-block to-block direction top? nested? target-child? target-file original-top-block-start-pos block-changes)
                            (remove nil?))
          path (:file/path (db/entity repo (:db/id (:block/file to-block))))]
      (profile
       "Move block in the same file: "
       (repo-handler/transact-react-and-alter-file!
        repo
        (concat
         after-blocks)
        {:key :block/change
         :data block-changes}
        [[path new-file-content]]))
      ;; (alter-file repo
      ;;             path
      ;;             new-file-content
      ;;             {:re-render-root? true})
)))

(defn- move-block-in-different-files
  [repo target-block to-block top-block bottom-block nested? top? target-child? direction target-content target-file original-top-block-start-pos block-changes]
  (let [target-file (db/entity repo (:db/id (:block/file target-block)))
        target-file-path (:file/path target-file)
        target-file-content (db/get-file repo target-file-path)
        to-file (db/entity repo (:db/id (:block/file to-block)))
        to-block (assoc to-block :block/meta (:block/meta (db/entity (:db/id to-block))))
        to-file-path (:file/path to-file)
        target-block-end-pos (block-handler/get-block-end-pos-rec repo target-block)
        to-block-start-pos (get-start-pos to-block)
        to-block-end-pos (block-handler/get-block-end-pos-rec repo to-block)
        new-target-file-content (utf8/delete! target-file-content
                                              (get-start-pos target-block)
                                              target-block-end-pos)
        to-file-content (utf8/encode (db/get-file repo to-file-path))
        new-to-file-content (let [separate-pos (cond nested?
                                                     (get-end-pos to-block)
                                                     top?
                                                     to-block-start-pos
                                                     :else
                                                     to-block-end-pos)]
                              (string/trim
                               (util/join-newline
                                (utf8/substring to-file-content 0 separate-pos)
                                target-content
                                (utf8/substring to-file-content separate-pos))))
        target-after-blocks (rebuild-dnd-blocks repo target-file target-child?
                                                (get-start-pos target-block)
                                                block-changes nil {:delete? true})
        to-after-blocks (cond
                          top?
                          (rebuild-dnd-blocks repo to-file target-child?
                                              (get-start-pos to-block)
                                              block-changes
                                              nil
                                              {:same-file? false})

                          :else
                          (let [offset-block-id (if nested?
                                                  (:block/uuid to-block)
                                                  (last (block-handler/get-block-ids to-block)))
                                offset-end-pos (get-end-pos
                                                (db/entity repo [:block/uuid offset-block-id]))]
                            (rebuild-dnd-blocks repo to-file target-child?
                                                offset-end-pos
                                                block-changes
                                                nil
                                                {:same-file? false})))]
    (profile
     "Move block between different files: "
     (repo-handler/transact-react-and-alter-file!
      repo
      (concat
       target-after-blocks
       to-after-blocks)
      {:key :block/change
       :data (conj block-changes target-block)}
      [[target-file-path new-target-file-content]
       [to-file-path new-to-file-content]]))))

(defn move-block
  "There can be at least 3 possible situations:
  1. Move a block in the same file (either top-to-bottom or bottom-to-top).
  2. Move a block between two different files.
  3. Move a block between two files in different repos.

  Notes:
  1. Those two blocks might have different formats, e.g. one is `org` and another is `markdown`,
     we don't handle this now. TODO: transform between different formats in mldoc.
  2. Sometimes we might need to move a parent block to it's own child.
  "
  [target-block to-block target-dom-id top? nested?]
  (when (and
         target-block
         to-block
         (:block/format target-block)
         (:block/format to-block)
         (not (:block/dummy? to-block))
         (not (:block/dummy? target-block)))
    (cond
      (not= (:block/format target-block)
            (:block/format to-block))
      (notification/show!
       (util/format "Sorry, you can't move a block of format %s to another file of format %s."
                    (:block/format target-block)
                    (:block/format to-block))
       :error)

      (= (:block/uuid target-block) (:block/uuid to-block))
      nil

      :else
      (let [target-block-repo (:block/repo target-block)
            to-block-repo (:block/repo to-block)
            target-block (assoc target-block
                                :block/meta
                                (:block/meta (db/entity target-block-repo [:block/uuid (:block/uuid target-block)])))
            to-block (assoc to-block
                            :block/meta
                            (:block/meta (db/entity [:block/uuid (:block/uuid to-block)])))
            same-repo? (= target-block-repo to-block-repo)
            target-file (:block/file target-block)
            same-file? (and
                        same-repo?
                        (= (:db/id target-file)
                           (:db/id (:block/file to-block))))
            [top-block bottom-block] (if same-file?
                                       (if (< (get-start-pos target-block)
                                              (get-start-pos to-block))
                                         [target-block to-block]
                                         [to-block target-block])
                                       [nil nil])
            target-child? (compute-target-child? target-block to-block)
            direction (compute-direction target-block top-block nested? top? target-child?)
            original-top-block-start-pos (get-start-pos top-block)
            [target-content block-changes] (recompute-block-content-and-changes target-block to-block nested? same-repo? same-file?)]
        (cond
          same-file?
          (move-block-in-same-file target-block-repo target-block to-block top-block bottom-block nested? top? target-child? direction target-content target-file original-top-block-start-pos block-changes)

          ;; same repo but different files
          same-repo?
          (move-block-in-different-files target-block-repo target-block to-block top-block bottom-block nested? top? target-child? direction target-content target-file original-top-block-start-pos block-changes)

          ;; different repos
          :else
          nil)

        (when (state/get-git-auto-push?)
          (doseq [repo (->> #{target-block-repo to-block-repo}
                            (remove nil?))]
            (repo-handler/push repo nil)))))))
