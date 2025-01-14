(ns more-speech.nostr.elliptic-signature
  (:require [more-speech.logger.default :refer [log-pr]])
  (:import (schnorr Schnorr)))

(defn do-sign [message private-key aux-rand]
  (Schnorr/sign message private-key aux-rand))

(defn do-verify [message public-key signature]
  (try
    (Schnorr/verify message public-key signature)
    (catch Exception e
      (log-pr 1 'do-verify 'exception e)
      false)))

(defn get-pub-key
  "private-key is byte array.  Returns byte array."
  [private-key]
  (Schnorr/genPubKey private-key))



