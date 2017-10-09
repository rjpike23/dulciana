(ns dulciana.service.core
  (:require-macros [hiccups.core :as hiccups :refer [html5]])
  (:require [cljs.nodejs :as nodejs]
            [hiccups.runtime :as hiccupsrt]
            [dulciana.service.net :as net]
            [dulciana.service.parser :as parser]
            [dulciana.service.state :as state]
            [dulciana.service.messages :as msg]))

(nodejs/enable-util-print!)


(. (nodejs/require "source-map-support") install)
(defonce express (nodejs/require "express"))
(defonce http (nodejs/require "http"))

(defonce http-server (atom nil))

(defn template []
  (hiccups/html5 [:html
                  [:head
                   [:meta {:charset "utf-8"}]
                   [:meta {:name "viewport"
                           :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
                   [:link {:rel "stylesheet"
                           :href "https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0-beta/css/bootstrap.min.css"
                           :integrity "sha384-/Y6pD6FV/Vv2HJnA6t+vslU6fwYXjCFtcEpHbNJ0lyAFsXTsjBbfaDjzALeQsN6M"
                           :crossorigin "anonymous"}]]
                  [:body
                   [:div#app.container-fluid]
                   [:script {:src "https://code.jquery.com/jquery-3.2.1.slim.min.js"}]
                   [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/popper.js/1.11.0/umd/popper.min.js"}]
                   [:script {:src "https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0-beta/js/bootstrap.min.js"}]
                   [:script {:src "resources/fig-client/dulciana_figwheel.js"
                             :type "text/javascript"}]]]))

(def app (express))

(. app use "/resources" (. express (static "target")))
(. app (get "/devices"
            (fn [req res]
              (. res (set "Content-Type" "application/edn"))
              (. res (send (pr-str @state/remote-devices))))))
(. app (get "/services"
            (fn [req res]
              (. res (set "Content-Type" "application/edn"))
              (. res (send (pr-str @state/remote-services))))))
(. app (get "/services/:svcid"
            (fn [req res]
              (. res (set "Content-Type" "application/edn"))
              (. res (send (pr-str (@state/remote-services (.-svcid (.-params req)))))))))
(. app (get "/"
            (fn [req res]
              (. res (set "Access-Control-Allow-Origin" "*"))
              (. res (send (template))))))

;;; Initializes network connections / routes etc. Called from both
;;; -main and the figwheel reload hook.
(defn setup []
  (parser/start-ssdp-parser)
  (state/start-subscribers)
  (net/start-listeners)
  (reset! http-server
          (doto (.createServer http #(app %1 %2))
            (.listen 3000))))

(defn teardown []
  (net/stop-listeners)
  (state/stop-subscribers)
  (parser/stop-ssdp-parser)
  (.close @http-server))

(defn fig-reload-hook []
  (teardown)
  (setup))

(defn -main [& args]
  (setup)
  (.on nodejs/process "beforeExit" teardown))

(set! *main-cli-fn* -main)
