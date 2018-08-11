;  Copyright 2018, Radcliffe J. Pike. All rights reserved.
;
;  This Source Code Form is subject to the terms of the Mozilla Public
;  License, v. 2.0. If a copy of the MPL was not distributed with this
;  file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns dulciana.service.xml
  (:require [clojure.string :as str]
            [tubax.core :as xml]
            [tubax.helpers :as xml-util]))

;; Hack city, next 2 functions. data.xml does not support node-js and
;; tubax does not support xml namespaces, so gotta do this mess:
(defn ns-map [attrs]
  (into {}
        (map (fn [[k v]]
               [(subs (name k) (count "xmlns:")) v])
             (filter (fn [[k v]] (str/starts-with? (name k) "xmlns"))
                          attrs))))
(defn munge-namespaces [xml ns-ctx]
  (if (and (map? xml) (:tag xml))
    (let [ns-cur (merge ns-ctx (ns-map (:attributes xml)))
          tag-split (str/split (name (:tag xml)) ":")
          result (assoc xml :content (map (fn [x] (munge-namespaces x ns-cur))
                                          (:content xml)))]
      (if (> (count tag-split) 1)
        (let [ns-uri (ns-cur (first tag-split))]
          (if ns-uri
            (assoc result :tag [(second tag-split) ns-uri])
            result))
        result))
    xml))

(defn xml-map
  "Returns a function that converts an xml->clj data structure into a map {:<tag-name> <content>},
  according to the supplied spec. spec is a map from a tag keyword to a function of a single argument.
  When a child node with a tag name appearing in the spec map is found, the corresponding function is
  called with the node as argument. The return value is used as the <content> in the resulting map."
  [spec & {:keys [include-unspec-elt] :or {:include-unspec-elt false}}]
  (fn [node]
    (into {} (reduce (fn [out child]
                       (if-let [spec-fun (spec (:tag child))]
                         (cons [(:tag child) (spec-fun child)] out)
                         (if include-unspec-elt
                           (cons [(:tag child) (xml-util/text child)] out)
                           out)))
                     '()
                     (node :content)))))

(defn xml-list
  "Returns a function that converts an xml-clj data structure into a list, based
  on the supplied 'spec'. The spec is a map of keywords to functions. If the
  tag of the child element is a member of the spec map, the associated function
  is called on the child element."
  [spec]
  (fn [node]
    (reduce (fn [out child]
              (if-let [spec-fun (spec (child :tag))]
                (cons (spec-fun child) out)
                out))
            '()
            (node :content))))

