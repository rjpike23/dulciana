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
            [dulciana.service.messages :as messages]
            [os :as os]
            [dgram :as dgram]
            [http :as http]
            [net :as net]
            [url :as url]))

;;; Define SSDP protocol networking constants:
(def ssdp-mcast-addresses {"IPv4" "239.255.255.250"
                           "IPv6" "ff0e::c"})
(def ssdp-protocols {"IPv4" "udp4"
                     "IPv6" "udp6"})
(def ssdp-port 1900)
(def event-server-port 3200)

;;; Networking module state vars:
;;; Map of open sockets:
(defonce sockets (atom {}))

;;; HTTP server socket for eventing:
(defonce event-server (atom {}))

(defn get-ifaces
  "Returns a list of active external network interfaces. See nodejs os.networkInterfaces()."
  []
  (filter #(not (% :internal))
          (flatten (vals (js->clj (os/networkInterfaces) :keywordize-keys true)))))

;;; Multicast message sender:
(defn send-ssdp-message
  "Sends a multicast message from the supplied interface. A socket for the interface should have previously
  been opened with net/start-listener. See nodejs dgram.send()."
  [iface message]
  (log/trace "Sending SSDP message" message)
  (when-let [socket (@sockets (iface :address))]
    (.send socket message ssdp-port (ssdp-mcast-addresses (iface :family)))))

(defn send-ssdp-search-message
  "Sends a M-SEARCH SSDP Discovery message on all enabled interfaces."
  [iface]
  (send-ssdp-message iface (messages/emit-m-search-msg)))

(defn send-http-request
  "Retuns a channel."
  [method host port path headers body]
  (let [options {:hostname host
                 :port port
                 :path path
                 :method method
                 :headers headers}
        result-chan (async/chan)
        req (.request http (clj->js options) (partial events/slurp result-chan))]
    (.end req body)
    result-chan))

;;; Handler for SSDP pub-sub events
(defn respond [response code message]
  (set! (.-statusCode response) code)
  (set! (.-statusMessage response) message)
  (.end response))

(defn handle-pub-server-request
  "Callback for when a pub-sub data event is received from an active socket."
  [request response]
  (log/debug "UPnP server received" (.-method request) "request")
  (case (.-method request)
    "NOTIFY" (let [c (async/chan 1 (map (fn [msg]
                                          {:message {:type :NOTIFY
                                                     :body msg
                                                     :headers (js->clj (.-headers request)
                                                                       :keywordize-keys true)}
                                           :ok #(respond response 200 "OK")
                                           :error (partial respond response)})))]
               (events/slurp c request)
               (async/pipe c @parser/ssdp-event-channel false))
    (do
      (log/warn "UPnP server ignoring" (.-method request) "request")
      (.writeHead response 405 "Method Not Allowed" (clj->js {"Allow" "NOTIFY"}))
      (.end response "Method not allowed."))))

;;; Datagram event handlers follow:
(defn handle-ssdp-error
  "Callback for socket errors returned from NodeJS datagram API."
  [iface socket exception]
  (log/error exception "Socket error!")
  (.close socket))

(defn handle-ssdp-message [iface socket msg remote-js]
  "Callback for messages received on the SSDP multicast socket.
  pushes messages onto the ssdp-message-channel, with metadata."
  (let [remote (js->clj remote-js :keywordize-keys true)]
    (log/trace "Dgram rcvd" (:address remote) (.toString msg))
    (go (async/>! @parser/ssdp-message-channel {:remote remote
                                          :interface iface
                                          :timestamp (js/Date.)
                                          :message (.toString msg)}))))

(defn handle-ssdp-socket-close
  "Callback for socket close events generated from NodeJS datagram API."
  [iface socket]
  (log/debug "Socket closed" (:address iface))
  (swap! sockets dissoc (:address iface)))

(defn handle-ssdp-socket-listening
  "Callback for socket listening events generated from NodeJS datagram API.
  This implementation adds the socket to the SSDP multicast group, and
  updates the sockets var to include the new socket."
  [iface socket]
  (log/debug "Socket listening, adding iface to multicast group" (:address iface))
  (.addMembership socket
                  (ssdp-mcast-addresses (:family iface))
                  (:address iface))
  (swap! sockets assoc (:address iface) socket)
  (log/info "Listening for SSDP messages on" (:address iface))
  (send-ssdp-search-message iface))

(defn handle-descriptor-response
  "Result handler for both device and service descriptor responses. Pumps
  a result object into the supplied channel."
  [chan error-flag announcement service-info msg]
  (log/trace "Desc rcvd" msg)
  (let [result {:timestamp (js/Date.), :error error-flag,
                :announcement announcement, :message msg}]
    (go
      (>! chan
          (if service-info
            (assoc result :service-info service-info)
            result)))))

;;; HTTP methods for sending requests for descriptors / SOAP requests below:
(defn get-device-descriptor
  "Sends HTTP request to get the descriptor for the device specified in the
  supplied announcement. The response is pumped into the descriptor-channel."
  [device-announcement]
  (log/trace "Fetching dev desc" device-announcement)
  (ajax/GET (-> device-announcement :message :headers :location)
            {:handler (partial handle-descriptor-response
                               @parser/descriptor-channel false device-announcement nil)
             :error-handler (partial handle-descriptor-response
                                     @parser/descriptor-channel true device-announcement nil)}))

(defn get-service-descriptor
  "Sends HTTP request to get the descriptor for the service specified in the
  supplied device-announcement and service-info objects. The response is
  pumped into the descriptor channel."
  [device-announcement service-info]
  (log/trace "Fetching svc desc" service-info)
  (let [scpdurl (url/resolve (-> device-announcement :message :headers :location) (service-info :SCPDURL))]
    (ajax/GET scpdurl
              {:handler (partial handle-descriptor-response
                                 @parser/descriptor-channel false device-announcement service-info)
               :error-heandler (partial handle-descriptor-response
                                        @parser/descriptor-channel true device-announcement service-info)})))

(defn send-control-request [url service-type action-name params]
  (let [msg (messages/emit-control-soap-msg service-type action-name params)
        hdrs {"USER-AGENT" "Unix/5.0 UPnP/2.0 dulciana/1.0"
              "SOAPACTION" (str "\"" service-type "#" action-name "\"")}]
    (ajax/POST url {:body msg
                    :headers hdrs})))

;;; Fns to manage the HTTP server which supports eventing.
(defn start-event-server
  ""
  []
  (let [server (.createServer http)
        evt-channels (events/listen* server ["request" "close"])]
    (log/info "Starting event server.")
    (go-loop []
      (let [req (async/<! (evt-channels "request"))]
           (when req
             (apply handle-pub-server-request req)
             (recur))))
    (go (async/<! (evt-channels "close"))
        (map async/close! (vals evt-channels))
        (log/info "Event server closed."))
    (.listen server event-server-port)
    (reset! event-server server)))

(defn stop-event-server
  ""
  []
  (.close @event-server))

;;; Following functions are used to init/teardown the SSDP multicast listeners.
(defn start-listener
  "Creates a new multicast socket on the supplied interface. "
  [iface]
  (let [socket (dgram/createSocket (ssdp-protocols (:family iface)))]
    (.on socket "error" (partial handle-ssdp-error iface socket))
    (.on socket "message" (partial handle-ssdp-message iface socket))
    (.on socket "close" (partial handle-ssdp-socket-close iface socket))
    (.on socket "listening" (partial handle-ssdp-socket-listening iface socket))
    (if (= "Windows_NT" (os/type))
      (.bind socket ssdp-port (:address iface)) ; Windows
      (.bind socket ssdp-port)) ; UNIX/Linux
    socket))

(defn start-listeners []
  (doseq [iface (get-ifaces)]
    (start-listener iface)))

(defn stop-listeners [sockets]
  (doseq [[k socket] sockets]
    (.close socket)))
