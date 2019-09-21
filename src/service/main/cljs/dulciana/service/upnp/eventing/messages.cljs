(ns dulciana.service.upnp.eventing.messages
  (:require [clojure.string :as str]
            [tubax.core :as xml]
            [dulciana.service.xml :as dulc-xml]))

(defn event-parse [msg]
  (try
    (assoc msg :message {:body (xml/xml->clj (-> msg :message :body))
                         :type :NOTIFY
                         :headers (-> msg :message :headers)})
    (catch :default e
      ((:error msg) 400 "Malformed message")
      (throw e))))

(defn event-analyze [msg]
  (let [m (dulc-xml/munge-namespaces (-> msg :message :body) {})]
    (assoc msg :message {:body (apply merge ((dulc-xml/xml-list
                                              {["property" "urn:schemas-upnp-org:event-1-0"] (dulc-xml/xml-map {} :include-unspec-elt true)})
                                             m))
                         :type :NOTIFY
                         :headers (-> msg :message :headers)})))

(defn emit-properties [properties]
  (apply str/join "\n"
         (map (fn [[k v]]
                (str "<e:property><" k ">" v "</" k "></e:property>"))
              properties)))

(defn emit-event [properties]
  (str/join "\n"
            ["<?xml version=\"1.0\"?>"
             "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">"
             (emit-properties properties)
             "</e:propertyset>"]))
