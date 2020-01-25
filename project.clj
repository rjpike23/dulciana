;  Copyright 2017-2020, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(require 'cemerick.pomegranate.aether)
(cemerick.pomegranate.aether/register-wagon-factory!
 "http" #(org.apache.maven.wagon.providers.http.HttpWagon.))

(defproject dulciana "0.0.0-SNAPSHOT"
  :description "DLNA server built with Clojurescript, targeted at NodeJS."
  :url "http://www.dulciana.net"
  :license {:name "Mozilla Public License, v 2.0"
            :url "http://mozilla.org/MPL/2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/clojurescript "1.10.597"]
                 [org.clojure/core.async "0.7.559"]
                 [org.clojure/test.check "0.10.0"]
                 [org.clojure/data.xml "0.2.0-alpha6"]
                 [binaryage/devtools "0.9.11"]
                 [lein-cljsbuild "1.1.7"]
                 [com.cemerick/url "0.1.1"]
                 [lein-figwheel "0.5.19"]
                 [figwheel-sidecar "0.5.19"]
                 [instaparse "1.4.10"]
                 [reagent "0.9.1"]
                 [re-frame "0.11.0"]
                 [day8.re-frame/http-fx "v0.2.0"]
                 [day8.re-frame/re-frame-10x "0.4.5"]
                 [clj-commons/cljss "1.6.4"]
                 [clj-commons/secretary "1.2.4"]
                 [macchiato/hiccups "0.4.1"]
                 [cljs-ajax "0.8.0"]
                 [funcool/tubax "0.2.0"]
                 [com.taoensso/sente "1.15.0"]
                 [com.taoensso/timbre "4.10.0"]]
  :plugins [[lein-cljsbuild "1.1.7"]
            [org.clojure/clojurescript "1.10.597"]
            [lein-figwheel "0.5.19"]]
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
                                    :npm-deps {:express "4.17.1"
                                               :express-ws "4.0.0"
                                               :body-parser "1.19.0"
                                               :cookie-parser "1.4.4"
                                               :express-session "1.17.0"
                                               :csurf "1.11.0"
                                               :source-map-support "0.5.16"
                                               :ws "7.2.1"
                                               :sax "1.2.4"
                                               :xmldom "0.2.1"
                                               :xmlhttprequest "1.8.0"}}}
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
                                   :npm-deps {:websocket "1.0.31"}}}]}
  :figwheel {}
  :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]})
