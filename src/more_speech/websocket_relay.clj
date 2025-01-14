(ns more-speech.websocket-relay
  (:require [more-speech.logger.default :refer [log-pr log]]
            [more-speech
             [relay :as relay]
             [mem :refer :all]]
            [clojure.data.json :as json]
            [more-speech.nostr.util :as util])
  (:import (java.net.http HttpClient WebSocket$Listener WebSocket)
           (java.net URI)
           (java.util Timer TimerTask)
           (java.nio ByteBuffer)))

(defn to-json [o]
  (json/write-str o :escape-slash false :escape-unicode false))

;callbacks :recv :close
(defn make [url callbacks]
  {::relay/type ::websocket
   ::url url
   ::callbacks callbacks
   ::socket nil})

(defn handle-text [{:keys [buffer relay]} data last]
  (let [{::keys [url callbacks]} relay]
    (.append buffer (.toString data))
    (when last
      (try
        (let [envelope (json/read-str (.toString buffer))]
          ((:recv callbacks) relay envelope))
        (catch Exception e
          (log-pr 1 'onText url (.getMessage e))
          (log-pr 1 (.toString buffer))))
      (.delete buffer 0 (.length buffer)))))

(defrecord listener [buffer relay]
  WebSocket$Listener
  (onOpen [_this webSocket]
    (log-pr 2 'open (::url relay))
    (.request webSocket 1))
  (onText [this webSocket data last]
    (handle-text this data last)
    (.request webSocket 1))
  (onBinary [_this _webSocket _data _last]
    (log-pr 2 'binary))
  (onPing [_this webSocket message]
    (set-mem [:deadman (::url relay)] (util/get-now))
    (.sendPong webSocket message)
    (.request webSocket 1))
  (onPong [_this webSocket _message]
    (set-mem [:deadman (::url relay)] (util/get-now))
    (.request webSocket 1))
  (onClose [_this _webSocket _statusCode _reason]
    (log-pr 2 'websocket-closed (::url relay))
    ((:close (::callbacks relay)) relay))
  (onError [_this _webSocket error]
    (log-pr 2 'websocket-listener-error (::url relay) (:cause error))
    ((:close (::callbacks relay)) relay))
  )

(defmethod relay/close ::websocket [relay]
  (let [{::keys [url socket timer]} relay]
    (when (some? socket)
      (try
        (when-not (and (.isOutputClosed socket) (.isInputClosed socket))
          (.get (.sendClose socket WebSocket/NORMAL_CLOSURE "done")))
        (catch Exception e
          (log-pr 1 'close-error url (:message e)))))
    (when timer (.cancel timer))
    (set-mem [:deadman url] (util/get-now))
    (assoc relay ::socket nil ::timer nil)))

(defn send-ping [relay]
  (let [{::keys [socket]} relay]
    (.sendPing socket (ByteBuffer/allocate 4))))

(defn start-timer [relay]
  (let [timer (Timer. (format "Ping timer for %s" (::url relay)))
        ping-task (proxy [TimerTask] []
                    (run [] (send-ping relay)))
        s30 (long 30000)]
    (.schedule timer ping-task s30 s30)
    timer))

(defmethod relay/open ::websocket [relay]
  (let [{::keys [url]} relay]
    (try
      (let [client (HttpClient/newHttpClient)
            cl (.newWebSocketBuilder client)
            cl (.header cl "origin" "more-speech")
            cws (.buildAsync cl (URI/create url) (->listener (StringBuffer.) relay))
            wsf (future (.get cws))
            ws (deref wsf 5000 :time-out)]
        (if (= ws :time-out)
          (do
            (log-pr 2 'connection-time-out url)
            (future ((:close (::callbacks relay)) relay))
            relay)
          (let [open-relay (assoc relay ::socket ws)]
            (send-ping open-relay)
            (assoc open-relay ::timer (start-timer open-relay)))))
      (catch Exception e
        (log-pr 2 'connect-to-relay-failed url (:reason e))
        (future ((:close (::callbacks relay)) relay))
        relay))))

(defmethod relay/send ::websocket [relay message]
  (let [{::keys [socket url]} relay]
    (when (and socket (not (.isOutputClosed socket)))
      (try
        (let [json (to-json message)]
          (log 2 (str "sending to:" url " " json))
          (.sendText socket json true)
          (.request socket 1))
        (catch Exception e
          (log-pr 1 'send-to (.getMessage e)))))))

