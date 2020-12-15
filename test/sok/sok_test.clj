(ns sok.sok-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async :refer [chan go thread >! <! >!! <!! alt! timeout]]
            [hato.websocket :as hws]
            [sok.api :as sok]
            [clojure.tools.logging :as log]))

(defonce server (atom nil))
(defonce client (atom nil))
(def port 8124)

(defn with-server [f]
  (reset! server (sok/server! port))
  (f)
  ((:close @server)))

(defn with-client [f]
  (reset! client (sok/client! (str "ws://localhost:" port)))
  (f)
  (hws/close! (@client :ws)))

(use-fixtures :once with-server with-client)


(defn round-trip [msg client server]
  (log/info "about to roundtrip" (count msg) "characters")
  (<!! (go (if (>! (client :out) msg)
             (let [[id msg] (<! (server :in))]
               (>! (server :out) [id msg])
               (alt! (timeout 1000) ::timeout
                     (client :in) ([val _] val)))
             (log/warn "already closed")))))

(deftest messages
  (let [{:keys [clients port path close evict] :as server} @server
        client @client
        client-id (-> @clients keys first)
        short-message "hello"
        ; Can't actually get very near (* 1024 1024); presumably protocol overhead.
        ; Hangs IDE when trying, annoyingly. TODO debug
        long-message (apply str (repeatedly (* 512 1024) #(char (rand-int 255))))]
    (is (contains? @clients client-id)) ; hard to imagine this failing, just for symmetry
    (is (= short-message (round-trip short-message client server)))
    (is (= long-message (round-trip long-message client server)))
    (is (nil? (-> @clients keys first evict deref)))
    (is (not (contains? @clients client-id)))))

#_ (@client :ws)
#_ (hws/close! (@client :ws))
#_ ((:close @server))