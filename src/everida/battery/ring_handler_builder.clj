(ns everida.battery.ring-handler-builder
  (:require [everida.core.api :as api]
            [bidi.ring :as bdr]
            [ring.middleware.session.memory :as session-memory]
            [ring.middleware
             [params :refer [wrap-params]]
             [keyword-params :refer [wrap-keyword-params]]
             [nested-params :refer [wrap-nested-params]]
             [multipart-params :refer [wrap-multipart-params]]
             [session :refer [wrap-session]]
             [anti-forgery :refer [wrap-anti-forgery]]]))

(def session-id-path-key :ring-request-session-path)
(def session-cookie-name-key :session-cookie-name)
(def session-store-key :session-store)
(def ping-event-qn-key :ping-event-qn)

(defn wrap-middlewares
  [builder route-handler-spec]
  (let [{:keys [handler exclude-mws include-mws]} route-handler-spec
        ex (set exclude-mws)
        mws (->>
              (or include-mws
                  [wrap-keyword-params
                   wrap-params
                   wrap-anti-forgery
                   [wrap-session
                    {:store
                     (session-store-key builder (session-memory/memory-store))
                     :cookie-name
                     (session-cookie-name-key builder "uid")}]])
              (remove (fn [mw] (ex (if (vector? mw) (first mw) mw))))
              reverse
              (map (fn [mw]
                     (if (vector? mw)
                       (fn [handler] (apply (first mw) handler (rest mw)))
                       mw)))
              (apply comp))]
    (mws handler)))

(defn resolve-routes [builder routes]
  (mapv
    (fn [path]
      (first
        (reduce
          (fn [res x]
            (cond
              (empty? res) [::first (if (:handler x) (wrap-middlewares builder x) x)]
              (= ::first (first res)) [[x (last res)]]
              :else [[x res]]))
          []
          (reverse path))))
    routes))

(defn prepare-routes [builder routes]
  ["" (resolve-routes builder routes)])

(defn make-handler [builder routes]
  (bdr/make-handler
    (prepare-routes
      builder
      (into
        routes
        [["/" (bdr/->ResourcesMaybe {:prefix "public/"})]
         [#".*" (fn [_] {:status 404})]]))))

(defn mw-ping-event [builder handler]
  (fn [component request]
    (when-let [core (:core component)]
      (when-let [sk (get-in request (session-id-path-key builder))]
        (api/pub-event
          core
          {:qn         (ping-event-qn-key builder :transport/ping)
           :session-id sk})))
    ;; NOTE::snoop: this very strange "into" is a workaround of immutant's lazy map, which i just don't get, it doesn't actually work and has no docs
    (handler component (into {} request))))

(defn update-route-handler [route f]
  (if-let [handler-index
           (->>
             route
             (map-indexed (fn [i route-element] [i route-element]))
             (some (fn [[i route-element]]
                     (when (and (map? route-element)
                                (fn? (:handler route-element)))
                       i))))]
    (update-in route [handler-index :handler] f)
    route))

(defn prebuild-route-handler [builder route-spec]
  (let [{:keys [route component]} route-spec]
    (update-route-handler
      route
      (fn [handler]
        (partial (mw-ping-event builder handler) component)))))

(defrecord RingHandlerBuilder []
  api/IGenericBuilder
  (build-result
    [builder]
    (->> builder :registered-routes
         (mapv (partial prebuild-route-handler builder))
         (make-handler builder)))
  api/IComponent
  (start [builder] builder)
  (stop [builder] builder))

(defn new-ring-handler-builder [& [m]]
  (map->RingHandlerBuilder
    (merge
      {api/id-key          :ring-handler-builder
       api/post-start-pred-key
                           (partial api/has-key? api/routes-key)
       api/post-start-action-key
                           (fn [builder units]
                             (assoc builder
                               :registered-routes
                               (->>
                                 units
                                 (mapcat
                                   (fn [unit]
                                     (map (fn [route] {:route route :component unit})
                                          (api/routes-key unit))))
                                 vec)))
       session-id-path-key [:session/key]}
      m)))

