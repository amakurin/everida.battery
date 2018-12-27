(ns everida.battery.immutant-webserver
  (:require [immutant.web :as web]
            [everida.core.api :as api]))

(defrecord ImmutantWebServer []
  api/IComponent
  (start [server-component]
    (let [ring-handler (api/build-result (:ring-handler-builder server-component))
          conf {:port (api/get-conf server-component :port)
                :host (api/get-conf server-component :host)}
          server (if (api/dev-mode? server-component)
                   (web/run-dmc ring-handler conf)
                   (web/run ring-handler conf))]
      (assoc server-component :server server)))
  (stop [{:keys [server] :as server-component}]
    (when server (web/stop server))
    (dissoc server-component :server)))

(defn new-immutant-web-server [& [m]]
  (map->ImmutantWebServer
    (merge
      {api/id-key            :immutant-web-server
       api/static-deps-key   [:ring-handler-builder]
       api/configuration-key {:port 8080 :host "localhost"}
       api/order-dependency-pred-key
                             (fn [_] true)
       #_(partial api/has-key? api/routes-key)}
      m)))