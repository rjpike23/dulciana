;  Copyright 2017, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.net
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.nodejs :as nodejs]
            [cljs.core.async :as async]
            [ajax.core :as ajax]
            [taoensso.timbre :as log :include-macros true]
            [dulciana.service.events :as events]
            [dulciana.service.parser :as parser]
            [os :as os]
            [dgram :as dgram]
            [http :as http]
            [net :as net]
            [url :as url]))

(def *event-server-port* 3200)

(defn get-ifaces
  "Returns a list of active external network interfaces. See nodejs os.networkInterfaces()."
  []
  (let [ifaces (js->clj (os/networkInterfaces) :keywordize-keys true)]
    (into {}
          (map (fn [[name addrs]]
                 [name (some (fn [addr] (and (not (:internal addr)) addr))
                             addrs)])
               ifaces))))

(defn create-udp-socket [prot-family]
  (let [socket (dgram/createSocket prot-family)
        channels (events/listen* socket [:listening :error :message :close])]
    {:socket socket
     :channels channels}))

(defn bind-udp-socket [socket port addr]
  (if (= "Windows_NT" (os/type))
    (.bind (:socket socket) port addr)
    (.bind (:socket socket) port))
  socket)

(defn add-membership [socket group-addr local-addr]
  (.addMembership (:socket socket) group-addr local-addr)
  socket)

(defn close-udp-socket [socket]
  (.close (:socket socket))
  socket)

(defn send-http-request
  "Retuns a channel."
  ([method url-string headers opts]
   (send-http-request method url-string headers nil opts))
  ([method url-string headers body opts]
   (let [url (url/URL. url-string)]
     (send-http-request method
                        (.-hostname url) (.-port url) (str (.-pathname url) (.-search url))
                        headers body opts)))
  ([method host port path headers body opts]
   (let [options {:hostname host
                  :port port
                  :path path
                  :method method
                  :headers (clj->js headers)}
         result-chan (async/chan 1)
         req (.request http (clj->js options))
         req-channels (events/listen* req [:response :error])]
     (async/take! (:error req-channels)
                  (fn [[this err]]
                    (async/go
                      (async/>! result-chan err)
                      (async/close! result-chan))))
     (async/take! (:response req-channels)
                  (fn [[this res]]
                    (let [body-chan (async/chan)]
                      (events/slurp body-chan res)
                      (async/go
                        (async/>! result-chan {:message {:status-code (.-statusCode res)
                                                         :status-message (.-statusMessage res)
                                                         :body (async/<! body-chan)
                                                         :headers (js->clj (.-headers res) :keywordize-keys true)}
                                               :opts options
                                               :rcvd (js/Date.)})))))
     (.end req body)
     result-chan)))

(defn send-subscribe-message
  "Returns a channel."
  [announcement service]
  (log/debug "Subscribe" announcement service)
  (let [req-url (url/URL. (url/resolve (-> announcement :message :headers :location)
                                       (:eventSubURL service)))
        return-url (str "http://" (-> announcement :local :address) ":" *event-server-port* "/events")]
     (send-http-request "SUBSCRIBE"
                       (.-hostname req-url)
                       (.-port req-url)
                       (str (.-pathname req-url) (.-search req-url))
                       {:CALLBACK (str "<" return-url ">")
                        :NT "upnp:event"
                        :HOST (.-host req-url)
                        :USER-AGENT "FreeBSD/11.1 UPnP/1.1 dulciana/0.0"}
                       ""
                       nil)))

(defn send-unsubscribe-message
  "Returns a channel."
  [sid announcement service]
  (let [req-url (url/URL. (url/resolve (-> announcement :message :headers :location)
                                       (:eventSubURL service)))]
    (send-http-request "UNSUBSCRIBE"
                       (.-hostname req-url)
                       (.-port req-url)
                       (str (.-pathname req-url) (.-search req-url))
                       {:SID sid
                        :HOST (str (.-host req-url))}
                       ""
                       nil)))

(defn send-resubscribe-message
  "Returns a channel."
  [subscription announcement service]
  (let [req-url (url/URL. (url/resolve (-> announcement :message :headers :location)
                                       (:eventSubURL service)))]
    (send-http-request "SUBSCRIBE"
                       (.-hostname req-url)
                       (.-port req-url)
                       (str (.-pathname req-url) (.-search req-url))
                       {:SID (:sid subscription)
                        :HOST (str (.-host req-url))}
                       ""
                       nil)))
