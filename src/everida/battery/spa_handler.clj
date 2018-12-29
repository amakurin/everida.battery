(ns everida.battery.spa-handler
  (:require [everida.core.api :as api]
            [everida.utils :as utils]
            [hiccup.page :as hiccup])
  (:import (clojure.lang IFn)))

(def app-info-reader-key :spa-handler/app-info-reader)
(def cache-app-info-to-key :spa-handler/cache-app-info-to)

(defn add-ver [ver statement]
  (str statement "?ver=" ver))

(defn read-app-info [spa-handler]
  (let [app-info-reader (app-info-reader-key spa-handler)
        {:keys [app-id
                app-name
                logo-hiccup
                build-number
                main-ns
                root-http-route
                prod-css
                dev-css
                prod-js
                dev-js]
         :or   {app-name        ""
                logo-hiccup     ""
                main-ns         ""
                build-number    "0.1.0-SNAPSHOT"
                root-http-route "/"
                prod-css        ["/css/prod.css"]
                dev-css         ["/css/base.css"
                                 "/css/layout.css"
                                 "/css/promo.css"
                                 "/css/skeleton.css"
                                 "/css/font-awesome.css"
                                 "/css/helpdesk.css"]
                prod-js         ["/js/prod.js"]
                dev-js          ["/js/cljs-dev-out/goog/base.js"
                                 "/js/dev.js"]}
         :as   app-info}
        (api/read-resource app-info-reader)]
    (assert (some? app-id) "App id should be set for spa handler")
    (merge app-info {:app-name     app-name
                     :logo-hiccup  logo-hiccup
                     :build-number build-number
                     :main-ns      main-ns
                     :prod-css     prod-css
                     :dev-css      dev-css
                     :prod-js      prod-js
                     :dev-js       dev-js})))

(defn read-app [spa-handler request]
  (let [base-info
        (if-let [app-info-path (cache-app-info-to-key spa-handler)]
          (get-in spa-handler app-info-path)
          (read-app-info spa-handler))]
    (let [client-state-builder (:client-state-builder spa-handler)]
      (->
        base-info
        (assoc :spa/state
                 (merge
                   {(api/id-key spa-handler)
                    (->
                      base-info
                      (select-keys [:logo-hiccup :app-id :app-name])
                      (assoc :react-target-element-id "app"))}
                   (api/build-result client-state-builder request)))
        (utils/deep-merge (:app request {}))))
      ))

(defrecord SpaHandler []
  api/IComponent
  (start [spa-handler]
    (if-let [app-info-path (cache-app-info-to-key spa-handler)]
      (assoc-in spa-handler app-info-path
                (read-app-info spa-handler))
      spa-handler))
  (stop [spa-handler] spa-handler)
  IFn
  (invoke [spa-handler request]
    (let [dev? (api/dev-mode? spa-handler)
          {:keys [app-name
                  build-number
                  main-ns
                  prod-css
                  dev-css
                  prod-js
                  dev-js] :as app}
          (read-app spa-handler request)]
      {:headers
       {"Content-Type" "text/html; charset=UTF-8"}
       :status
       200
       :body
       (hiccup/html5
         {}
         [:head
          [:meta {:charset "UTF-8"}]
          [:meta {:name    "viewport"
                  :content "width=device-width, initial-scale=1, maximum-scale=1"}]
          [:title app-name]
          (if dev?
            (apply hiccup/include-css dev-css)
            (->> prod-css
                 (map add-ver (repeat build-number))
                 (apply hiccup/include-css)))]
         [:body
          [:div#app.container "Javasript is required!  Se requiere Javascript!  Нам нужен Ваш Javascript!"]
          (if dev?
            (apply hiccup/include-js dev-js)
            (->> prod-js
                 (map add-ver (repeat build-number))
                 (apply hiccup/include-js)))
          [:script "document.getElementById(\"app\").innerHTML = \"Loading... Cargar la aplicación... Загружаем приложение...\";"]
          (when dev?
            [:script (str "goog.require(\"" main-ns "\");")])
          [:script (str main-ns ".main('"
                        (clojure.string/escape
                          (pr-str (:spa/state app))
                          {\" "\\\"" \\ "\\\\"}) "');")]])})))

(defn root-http-handler [spa-handler request]
  (spa-handler request))

(defn new-spa-handler [app-info-reader
                       & [cache-app-info-on-start-to root-http-route m]]
  (map->SpaHandler
    (merge
      {api/id-key            :spa
       api/static-deps-key   [:client-state-builder]
       app-info-reader-key   app-info-reader
       cache-app-info-to-key cache-app-info-on-start-to}
      (when root-http-route
        {api/routes-key [[root-http-route :get {:handler root-http-handler}]]})
      m)))