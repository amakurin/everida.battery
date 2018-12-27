(ns everida.battery.transport
  "Sente based websocket/ajax transport
  NOTES:
  * Sente sends over UIDs. This transport uses 3-component UIDs (user,session,client) produced by ring-request->uid function. Each component could be overridden by transport component config.
  * In most common case we have such an hierarchy (-> means has zero or many):
  USER (business/logic user) -> Sessions (http sessions with devices) -> Clients (e.g. browser-tabs).
  * Sente caches ring request which was provided during handshake, so you have to reconnect client on session change (e.g. authentication). This is true for in-memory sessions, as well as cookie and others."
  (:require
    [everida.core.api :as api]
    [taoensso.sente :as sente]
    [clojure.core.async :as async
     :refer (<! <!! >! >!! put! chan go go-loop)])
  (:gen-class))

(def web-server-adapter-key :sente/web-server-adapter)
(def ring-ajax-post-key :ajax-post-fn)
(def ring-ajax-get-or-ws-handshake-key :ajax-get-or-ws-handshake-fn)
(def ch-chsk-key :ch-recv)
(def chsk-send-key :send-fn)
(def connected-uids-key :connected-uids)
(def user-id-fn-key :transport/user-id-fn)
(def session-id-fn-key :transport/session-key-fn)
(def client-id-fn-key :transport/client-id-fn)
(def sente-http-route-key :sente/http-route)

(defn ring-request->uid
  [transport ring-request]
  [((user-id-fn-key transport) ring-request)
   ((session-id-fn-key transport) ring-request)
   ((client-id-fn-key transport) ring-request)])

(defn uid->components [uid]
  (zipmap [:user-id :session-id :client-id] uid))

(defn handler-post [transport req]
  (if-let [ring-ajax-post (ring-ajax-post-key transport)]
    (ring-ajax-post req)
    (throw (ex-info "Can't find sente's ring-ajax-post"
                    {:transport transport}))))

(defn handler-get [transport req]
  (if-let [ring-ajax-get-or-ws-handshake
           (ring-ajax-get-or-ws-handshake-key transport)]
    (ring-ajax-get-or-ws-handshake req)
    (throw (ex-info "Can't find sente's ring-ajax-get-or-ws-handshake"
                    {:transport transport}))))


(defn add-message-meta [m msg & [as-local]]
  (let [{:keys [ring-req uid]} msg]
    (with-meta
      m
      (assoc (meta m)
        :remote? (not as-local)
        :remote-address uid
        :remote-address-components (uid->components uid)
        :ring-req ring-req))))

(defn form-message [msg]
  (add-message-meta (second (:event msg)) msg))

(defn handle-ping
  [{:keys [core]} msg]
  (api/pub-event
    core
    (-> msg :uid
        (uid->components)
        (assoc :qn :transport/ping)
        (add-message-meta msg :as-local!))))

(defn find-clients [connected-uids find-by-key value]
  {:pre [(#{:user-id :session-id :client-id} find-by-key)]}
  (filterv
    (fn [uid] (= value (find-by-key (uid->components uid))))
    connected-uids))

(defn get-addresses [connected-uids {:keys [address user-id session-id client-id]}]
  {:pre [(or (some? address) (some? user-id) (some? session-id) (some? client-id))]}
  (cond
    address (filterv #{address} connected-uids)
    client-id (find-clients connected-uids :client-id client-id)
    session-id (find-clients connected-uids :session-id session-id)
    user-id (find-clients connected-uids :user-id user-id)))

(defn msg-type [msg] (first (:event msg)))

(defn get-connected [transport]
  (:any @(connected-uids-key transport)))

(defmulti handle-client-event (fn [_ msg] (msg-type msg)))

(defmethod handle-client-event :chsk/uidport-open
  [transport msg]
  (handle-ping transport msg)
  (api/pub-event
    (:core transport)
    (-> msg :uid
        (uid->components)
        (assoc :qn :transport/connection-opened)
        (add-message-meta msg :as-local!))))

(defmethod handle-client-event :chsk/uidport-close
  [transport msg]
  (let [core (:core transport)
        addr (uid->components (:uid msg))]
    (api/pub-event
      core
      (-> addr
          (assoc :qn :transport/connection-closed)
          (add-message-meta msg :as-local!)))
    (let [session-id (:session-id addr)
          addrs (seq (get-addresses
                       (get-connected transport)
                       {:session-id session-id}))]
      (when (empty? addrs)
        (api/pub-event
          core
          (-> addr
              (assoc :qn :transport/session-empty)
              (add-message-meta msg :as-local!)))))))

(defmethod handle-client-event :chsk/ws-ping
  [transport msg]
  (handle-ping transport msg))

(defmethod handle-client-event :core/event
  [{:keys [core logger] :as transport} {:keys [event ?reply-fn] :as msg}]
  (logger :debug "SENTE event msg:" event)
  (handle-ping transport msg)
  (api/pub-event core (form-message msg))
  (when ?reply-fn (?reply-fn (api/success-response core))))

(defmethod handle-client-event :core/request
  [{:keys [core logger request-handling-expiry-timeout] :as transport}
   {:keys [event ?reply-fn] :as msg}]
  (logger :debug "SENTE request msg:" event)
  (handle-ping transport msg)
  (let [request (form-message msg)
        response-chan (api/call-async core request)]
    (go
      (let [[v p] (async/alts! [response-chan (async/timeout request-handling-expiry-timeout)])
            response
            (if (= p response-chan)
              v
              (api/error-response
                core
                request {:code :timeout-expired-during-handling-request}))]
        (logger :debug "Transport request handler got response: " response)
        (when ?reply-fn (?reply-fn response))))))

(defmethod handle-client-event :core/response
  [{:keys [logger]} {:keys [event]}]
  (logger :debug "SENTE response msg:" event))

(defmethod handle-client-event :default
  [{:keys [logger] :as transport} {:keys [event] :as msg}]
  (logger :debug "SENTE default msg:" event)
  (handle-ping transport msg))

(defn serve-send-to! [transport request]
  (let [{:keys [msg-type-id data ]
         :or {msg-type-id :core/event}} request
        {:keys [core send-split-timeout]} transport
        connected (get-connected transport)
        addresses (get-addresses connected request)
        chsk-send! (chsk-send-key transport)]
    (if (empty? addresses)
      (api/respond-error
        core request
        {:code :disconnected-addressee}
        [:address :user-id :session-id :client-id])
      (go
        (doseq [address addresses]
          (chsk-send! address [msg-type-id data])
          (<! (async/timeout send-split-timeout)))
        ;; guaranteed by Sente
        (api/respond-success core request)))))

(defn send-session-drop [transport addr]
  (let [chsk-send! (chsk-send-key transport)]
    (go
      (chsk-send! addr [:core/event {:qn :session/dropped}])
      (<! (async/timeout (:send-split-timeout transport)))
      (chsk-send! addr [:chsk/close]))))

(defn handle-session-dropped
  [transport event]
  (let [session-id (get-in event [:session :session-id])
        addresses (get-addresses (get-connected transport) {:session-id session-id})]
    ((:logger transport) :debug "Closing conns of droppped session-id "
      session-id "addresses: " addresses)
    (doseq [addr addresses] (send-session-drop transport addr))))

(defn start-client-channel-loop [transport]
  (let [session-reader (:session-reader transport)
        sess-id-fn (session-id-fn-key transport)
        ch-chsk (ch-chsk-key transport)
        parallelism (:client-loop-parallelism transport)]
    (dotimes [_ parallelism]
      (go-loop []
        (when-let [msg (<! ch-chsk)]
          (if (api/read-val session-reader (sess-id-fn (:ring-req msg)))
            (handle-client-event transport msg)
            (when-not (= (msg-type msg) :chsk/uidport-close)
              (send-session-drop transport (:uid msg))))
          (recur))))))

(defrecord ClientServerTransport []
  api/IClientStateProvider
  (provide-client-state [transport _]
    (select-keys transport [sente-http-route-key]))
  api/IModule
  api/IComponent
  (start [transport]
    {:pre [(some? (web-server-adapter-key transport))]}
    (let [transport
          (merge
            transport
            (api/get-conf transport)
            (sente/make-channel-socket!
              (web-server-adapter-key transport)
              {:user-id-fn (partial ring-request->uid transport)}))]
      (start-client-channel-loop transport)
      transport))
  (stop [transport]
    (when-let [ch-chsk (ch-chsk-key transport)]
      (async/close! ch-chsk))
    (dissoc transport
            ring-ajax-post-key
            ring-ajax-get-or-ws-handshake-key
            ch-chsk-key
            chsk-send-key
            connected-uids-key)))

(defn new-client-server-transport [sente-web-server-adapter
                                   & [sente-http-route m]]
  (map->ClientServerTransport
    (let [sente-http-route (or sente-http-route "/chsk")]
      (merge
        {api/id-key              :transport
         api/static-deps-key     [:session-reader]
         web-server-adapter-key  sente-web-server-adapter
         api/request-servers-key [{:msg-filter :transport/send-to!
                                   :handler    #'serve-send-to!}]
         api/event-subs-key      [{:msg-filter :session/dropped
                                   :handler    #'handle-session-dropped}]
         api/routes-key          [[sente-http-route :get {:handler handler-get}]
                                  [sente-http-route :post {:handler handler-post}]]
         sente-http-route-key    sente-http-route
         session-id-fn-key       (fn [ring-request] (:session/key ring-request))
         client-id-fn-key        (fn [ring-request] (get-in ring-request [:params :client-id]))
         user-id-fn-key          (fn [ring-request] (get-in ring-request [:session :user-id]))
         :client-loop-parallelism 8
         :send-split-timeout 100
         :request-handling-expiry-timeout 10000
         }
        m))))
