(ns everida.battery.spa-state-builder
  "Client state builder for Single Page Applications"
  (:require [everida.core.api :as api]))

(defn register-state-providers [builder units]
  (->>
    units
    (mapv (fn [unit] {:id       (or (api/client-state-unit-id-key unit) (api/id-key unit))
                       :provider unit}))
    (assoc builder :providers)))

(defrecord ClientStateBuilder []
  api/IGenericBuilder
  (build-result
    [builder request]
    (->> builder :providers
         (map (fn [provider-spec]
                (let [{:keys [id provider]} provider-spec]
                  [id (assoc
                        (api/provide-client-state provider request)
                        api/stand-mode-key (api/component-mode provider))])))
         (into {})))

  api/IComponent
  (start [builder]
    builder)

  (stop [builder]
    builder))

(defn new-client-state-builder [& [m]]
  (map->ClientStateBuilder
    (merge
      {api/id-key :client-state-builder
       api/post-start-pred-key
                  (partial satisfies? api/IClientStateProvider)
       api/post-start-action-key
                  register-state-providers}
      m)))