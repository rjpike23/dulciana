;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(defproject dulciana "0.0.0-SNAPSHOT"
  :description "DLNA server built with Clojurescript, targeted at NodeJS."
  :url "http://www.dulciana.net"
  :license {:name "Mozilla Public License, v 2.0"
            :url "http://mozilla.org/MPL/2.0/"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/clojurescript "1.10.339"]
                 [org.clojure/core.async "0.4.474"]
                 [org.clojure/test.check "0.9.0"]
                 [org.clojure/data.xml "0.2.0-alpha5"]
                 [binaryage/devtools "0.9.10"]
                 [lein-cljsbuild "1.1.7"]
                 [cider/piggieback "0.3.6"]
                 [com.cemerick/url "0.1.1"]
                 [lein-figwheel "0.5.16"]
                 [figwheel-sidecar "0.5.16"]
                 [instaparse "1.4.8"]
                 [reagent "0.8.1"]
                 [re-frame "0.10.6"]
                 [day8.re-frame/re-frame-10x "0.3.3"]
                 [org.roman01la/cljss "1.6.3"]
                 [secretary "1.2.3"]
                 [macchiato/hiccups "0.4.1"]
                 [cljs-ajax "0.6.0"]
                 [funcool/tubax "0.2.0"]
                 [com.taoensso/sente "1.13.1"]
                 [com.taoensso/timbre "4.10.0"]]
  :plugins [[lein-cljsbuild "1.1.7"]
            [org.clojure/clojurescript "1.10.339"]
            [lein-figwheel "0.5.16"]]
  :clean-targets ^{:protect false} ["target"]
  :cljsbuild {
              :builds [{:id "fig-service"
                         :source-paths ["src/service/main/cljs" "src/service/test/cljs"]
                         :figwheel {:on-jsload "dulciana.service.core/fig-reload-hook"}
                         :compiler {:main dulciana.service.core
                                    :output-dir "target/fig-service"
                                    :output-to "target/fig-service/dulciana_figwheel.js"
                                    :target :nodejs
                                    :optimizations :none
                                    :source-map true
                                    :install-deps true
                                    :npm-deps {:express "4.15.3"
                                               :express-ws "4.0.0"
                                               :body-parser "1.18.3"
                                               :cookie-parser "1.4.3"
                                               :express-session "1.15.6"
                                               :csurf "1.9.0"
                                               :source-map-support "0.5.0"
                                               :ws "3.2.0"
                                               :sax "1.2.2"
                                               :xmldom "0.1.27"
                                               "@pupeno/xmlhttprequest" "1.7.0"}}}
                       {:id "fig-client"
                        :source-paths ["src/client/main/cljs" "src/client/test/cljs"]
                        :figwheel {:on-jsload "dulciana.client.core/fig-reload-hook"
                                   :websocket-host :js-client-host}
                        :compiler {:main dulciana.client.core
                                   :output-dir "target/fig-client"
                                   :output-to "target/fig-client/dulciana_figwheel.js"
                                   :asset-path "/resources/fig-client"
                                   :closure-defines {"re_frame.trace.trace_enabled_QMARK_" true}
                                   :preloads [process.env day8.re-frame-10x.preload]
                                   :optimizations :none
                                   :source-map true
                                   :install-deps true
                                   :npm-deps {:websocket "1.0.28"}}}]}
  :figwheel {}
  :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]})
