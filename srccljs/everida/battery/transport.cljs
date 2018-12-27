(ns everida.battery.transport
  (:require-macros
    [cljs.core.async.macros :refer [go go-loop]])
  (:require
    [everida.core.api :as api]
    [cljs.core.async :as async :refer [<! put! >! alts!]]
    [taoensso.sente :as sente :refer (cb-success?)]
    [everida.core.api :as api]))

(def sente-http-route-key :sente/http-route)
(def chsk-key :chsk)
(def ch-chsk-key :ch-recv)
(def chsk-send-key :send-fn)
(def chsk-state-key :state)

(defmulti handle-server-sent-event (fn [_ msg] (first (:event msg))))

(defmethod handle-server-sent-event :chsk/handshake
  [{:keys [logger]} {:keys [event]}]
  (logger :debug "SENTE handshake msg:" event))

(defmethod handle-server-sent-event :chsk/state
  [transport {:keys [event]}]
  (let [{:keys [core logger]} transport
        {:keys [type open? destroyed? first-open?
                requested-reconnect?
                requested-reconnect-pending?] :as ev} (second event)]
    (logger :debug "SENTE state msg:" event)
    (cond
      open?
      (api/pub-event
        core
        {:qn                   :transport/connection-opened
         :first-open?          first-open?
         :requested-reconnect? requested-reconnect?
         :connection-type      type})
      requested-reconnect-pending?
      (api/pub-event
        core
        {:qn              :transport/connection-reconnect-requested
         :connection-type type})
      destroyed?
      (api/pub-event
        core
        {:qn              :transport/connection-closed
         :connection-type type})
      :else
      (throw (ex-info "Unknownw connection event" ev)))))

(defmethod handle-server-sent-event :chsk/recv
           [transport {:keys [event]}]
           (let [{:keys [core logger]} transport
        [_ data] (second event)]
                (logger :debug "SENTE event msg:\n" event)
                (api/pub-event core data)))

(defmethod handle-server-sent-event :default
  [{:keys [logger]} {:keys [event]}]
  (logger :debug "SENTE default msg:" event))

(defn reply-callback
  [transport request reply]
  (if (sente/cb-success? reply)
    reply
    (api/error-response
      (:core transport)
      request
      {:code :transport-error :target :client :reason reply})))

(defn reply-response-chan
  [transport msg reply]
  (api/respond (:core transport)
               msg (reply-callback transport msg reply)))

(defn serve-remote-call [transport request]
  (let [logger (:logger transport)
        chsk-state (chsk-state-key transport)
        chsk-send! (chsk-send-key transport)]
    (logger :debug "remote-call: " (:qn request))
    (go
      (when-not (:open? @chsk-state)
        (loop [attempts 0]
          (logger :warn "Waiting for transport to establish connection...")
          (<! (async/timeout 1000))
          (when (and (not (:open? @chsk-state)) (< attempts 10))
            (recur (inc attempts)))))
      (chsk-send! [:core/request request] 10000
                  (partial reply-response-chan transport request)))))

(defn handle-target-server-event [transport event]
  (let [logger (:logger transport)
        chsk-state (chsk-state-key transport)
        chsk-send! (chsk-send-key transport)]
    (logger :debug "target-server-event: " event)
    (go
      (when-not (:open? @chsk-state)
        (loop [attempts 0]
          (logger :warn "Waiting for transport to establish connection...")
          (<! (async/timeout 1000))
          (when (and (not (:open? @chsk-state)) (< attempts 10))
            (recur (inc attempts)))))
      (chsk-send! [:core/event event] 10000
                  (partial reply-callback transport event)))))

(defn handle-reconnect [transport _]
  (sente/chsk-reconnect! (chsk-key transport)))

(defn start-channel-loop [transport]
  (let [ch-chsk (ch-chsk-key transport)]
    (go
      (loop [msg (<! ch-chsk)]
        (when msg
          (handle-server-sent-event transport msg)
          (recur (<! ch-chsk)))))))

(defrecord ClientServerTransport []
           api/IModule
           api/IComponent
  (start [transport]
    (let [sente-route (api/get-conf transport sente-http-route-key)
          core (:core transport)
          transport
          (merge
            transport
            (sente/make-channel-socket!
              sente-route {:client-id (api/get-instance-id core)
                           :type      :auto}))]
      (start-channel-loop transport)
      transport))
  (stop [transport]
    (when-let [ch-chsk (ch-chsk-key transport)]
      (async/close! ch-chsk))
    (when-let [chsk (chsk-key transport)]
      (async/close! chsk))
    (dissoc transport
            chsk-key
            ch-chsk-key
            chsk-send-key
            chsk-state-key)))

(defn new-client-server-transport [& [m]]
  (map->ClientServerTransport
    (merge
      {api/id-key              :transport
       api/event-subs-key      [{:msg-filter {:target #{:server}}
                                 :handler    handle-target-server-event}
                                {:msg-filter :transport/reconnect
                                 :handler    handle-reconnect}]
       api/request-servers-key [{:msg-filter {:target #{:server}}
                                 :handler    serve-remote-call}]}
      m)))
