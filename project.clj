;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(defproject dulciana "0.0.0-SNAPSHOT"
  :description "DLNA server built with Clojurescript, targeted at NodeJS."
  :url "http://www.dulciana.net"
  :license {:name "I dunno"
            :url "http://www.license.net/"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.946"]
                 [org.clojure/core.async "0.3.443"]
                 [org.clojure/data.xml "0.2.0-alpha2"]
                 [binaryage/devtools "0.9.7"]
                 [lein-cljsbuild "1.1.7"]
                 [com.cemerick/piggieback "0.2.1"]
                 [com.cemerick/url "0.1.1"]
                 [lein-figwheel "0.5.14"]
                 [figwheel-sidecar "0.5.14"]
                 [instaparse "1.4.8"]
                 [reagent "0.7.0"]
                 [re-frame "0.10.2"]
                 [secretary "1.2.3"]
                 [macchiato/hiccups "0.4.1"]
                 [cljs-ajax "0.6.0"]
                 [funcool/tubax "0.2.0"]
                 [com.taoensso/timbre "4.10.0"]]
  :plugins [[lein-cljsbuild "1.1.7"]
            [org.clojure/clojurescript "1.9.946"]
            [lein-figwheel "0.5.14"]]
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
                                               :source-map-support "0.5.0"
                                               :ws "3.2.0"
                                               :sax "1.2.2"
                                               "@pupeno/xmlhttprequest" "1.7.0"}}}
                       {:id "fig-client"
                        :source-paths ["src/client/main/cljs" "src/client/test/cljs"]
                        :figwheel true
                        :compiler {:main dulciana.client.core
                                   :output-dir "target/fig-client"
                                   :output-to "target/fig-client/dulciana_figwheel.js"
                                   :asset-path "/resources/fig-client"
                                   :optimizations :none
                                   :source-map true
                                   :install-deps true
                                   :npm-deps {:react "15.6.0"
                                              :react-dom "15.6.0"}}}]}
  :figwheel {}
  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]})
