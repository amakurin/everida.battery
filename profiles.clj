{:dev
 {:dependencies [[cider/piggieback "0.3.10"]
                 [nrepl/nrepl "0.4.5"]
                 [ring/ring-devel "1.7.1"]]
  :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}
  :env {:dev-debug true
        :stand-mode :test}}
 :uberjar
 {:env {:dev-debug false}}}
