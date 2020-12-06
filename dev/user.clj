(ns user
  (:require [hato.websocket :as ws]))

#_ (let [w @(ws/websocket "ws://localhost:8123/"
              {:on-message (fn [ws msg last?] (println "client received" (if last? "(last)") msg))
               :on-close (fn [ws status reason] (println "client ws closed" status reason))})]
     (ws/send! w #_"hi there" (apply str (interpose \space (range 10000))))
     (Thread/sleep 500)
     (ws/close! w))