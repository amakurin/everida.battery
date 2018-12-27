(ns everida.battery.in-memory-session-manager
  (:require
    [ring.middleware.session.store :as session-store]
    [clojure.core.async :as async]
    [everida.utils :as utils]
    [everida.core.api :as api])
  (:import java.util.UUID
           (java.util Date)))

(def state-key :session-manager/state)
(def watcher-control-chan-key :session-manager/watcher-control-chan)

(defn -read-session
  [manager korks]
  (let [state (state-key manager)
        path (utils/korks-path korks)]
    (when (seq path)
      (let [v (get-in @state path)]
        ((:logger manager) :debug "Read session: " korks " ::: v = " v)
        v))))

(defn -write-session
  [manager k data]
  (let [state (state-key manager)
        k (or k (str (UUID/randomUUID)))]
    (swap! state assoc k (assoc data :session/timestamp (Date.)))
    k))

(defn -update-session
  [manager korks v]
  (let [state (state-key manager)
        k (first korks)
        path (utils/korks-path k (rest korks))]
    (assert (and k (get @state k))
            (str "Session key not found for update: " k))
    (-> state
        (swap! assoc-in path v)
        (get-in path))))

(defn -delete-session
  [manager k]
  (when-let [session (-read-session manager k)]
    (let [state (state-key manager)]
      (api/pub-event
        (:core manager)
        {:qn      :session/dropped
         :session (assoc session :session-id k)})
      ((:logger manager) :debug "Delete session key" k)
      (swap! state dissoc k)
      nil)))

(defn -get-sessions
  [manager]
  ((:logger manager) :debug "get-sessions " @(state-key manager))
  (mapv (fn [[k v]] (assoc v :session-id k)) @(state-key manager)))

(defn -prolong-session
  [manager sk]
  ((:logger manager) :trace "Prolong session key" sk)
  (-update-session manager [sk :session/timestamp] (Date.)))

(defn handle-transport-ping [manager event]
  (let [session-id (:session-id event)]
    (if (-read-session manager session-id)
      (-prolong-session manager session-id)
      (api/pub-event
        (:core manager)
        {:qn         :session/ping-for-missing-session
         :ping-event event}))))

(defn handle-transport-session-empty [manager event]
  (async/go
    (let [session-id (:session-id event)
          session (api/read-val manager session-id)]
      (when session
        (async/<! (async/timeout (api/get-conf manager :empty-drop-timeout-ms)))
        (when-let [updated-session (api/read-val manager session-id)]
          (when (= (:session/timestamp session)
                   (:session/timestamp updated-session))
            (api/delete-val manager session-id)))))))

(defn start-session-watcher
  [manager]
  (when-let [{:keys [session-watch-interval-ms
                     session-idle-expire-min]}
             (api/get-conf manager :expiration-params)]
    (let [logger (:logger manager)
          control-chan (async/chan)]
      (logger :debug "Session watcher started"
              " session-watch-interval-ms= "
              session-watch-interval-ms
              " session-idle-expire-min= "
              session-idle-expire-min)
      (async/thread
        (loop []
          (let [[_ port]
                (async/alts!! [control-chan (async/timeout session-watch-interval-ms)])]
            (when (not= port control-chan)
              (logger :debug "Session watcher timeout")
              (let [now (.getTime (Date.))
                    expired
                    (->> (api/read-vals manager)
                         (filter
                           (fn [session]
                             (-> (:session/timestamp session)
                                 (.getTime)
                                 (+ (* session-idle-expire-min 60000))
                                 (<= now))))
                         (map :session-id))]
                (doseq [k expired]
                  (logger :debug "Session expired:" k)
                  (api/delete-val manager k))
                (recur)))))
        (logger :debug "Session watcher stopped"))
      control-chan)))

(defrecord InMemorySessionManager []
  api/IGenericKvStoreReader
  (read-val [manager korks]
    (-read-session manager korks))
  (read-vals [manager]
    (-get-sessions manager))
  api/IGenericKvStoreManager
  (write-val [manager k data]
    (-write-session manager k data))
  (delete-val [manager k]
    (-delete-session manager k))
  (update-val [manager korks v]
    (-update-session manager korks v))
  session-store/SessionStore
  (read-session [manager k]
    (api/read-val manager k))
  (write-session [manager k data]
    (api/write-val manager k data))
  (delete-session [manager k]
    (api/delete-val manager k))
  api/IModule
  api/IComponent
  (start [manager]
    (let [event-subs
          (filterv
            some?
            [(when-let [drop-event-filter (api/get-conf manager :drop-event-filter)]
               {:msg-filter drop-event-filter
                :handler    handle-transport-session-empty})
             (when-let [prolongation-event-filter (api/get-conf manager :prolongation-event-filter)]
               {:msg-filter prolongation-event-filter
                :handler    handle-transport-ping})])
          watcher-control-chan (start-session-watcher manager)]
      (->
        manager
        (utils/->if watcher-control-chan assoc watcher-control-chan-key watcher-control-chan)
        (utils/->if (seq event-subs) assoc api/event-subs-key event-subs))))

  (stop [manager]
    (when-let [watcher-control-chan (watcher-control-chan-key manager)]
      (async/close! watcher-control-chan))
    (dissoc manager watcher-control-chan-key)))

(defn new-in-memory-session-manager
  "* :expiration-params enables session expiration, map with keys:
     - :session-watch-interval-ms - frequency of watching
     - :session-idle-expire-min - idle time to expiration in minutes
   * :prolongation-event-filter - msg-filter to watch for prolong sessions
   * :drop-event-filter - msg-filter to watch for drop sessions
   * :empty-drop-timeout-ms - delay before delete empty session (will check if session wasn't prolonged since event)"
  [& [m]]
  (map->InMemorySessionManager
    (merge
      {api/id-key            :session-manager
       api/static-deps-key   [:core]
       api/configuration-key {:expiration-params         {:session-watch-interval-ms 30000
                                                          :session-idle-expire-min   1}
                              :prolongation-event-filter :transport/ping
                              :drop-event-filter         :transport/session-empty
                              :empty-drop-timeout-ms        30000}
       state-key             (atom {})}
      m)))
