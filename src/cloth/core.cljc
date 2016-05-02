(ns cloth.core
  (:require [cloth.tx :as tx]
            [promesa.core :as p]
            [cats.core :as m]
            [cloth.chain :as chain]
            [cloth.util :as util]
            [cloth.keys :as keys]))

#?(:cljs (enable-console-print!))

;; In a browser we will typically only use one keypair
(def ^:dynamic bound-keypair nil)
(defonce global-keypair (atom nil))

(defn keypair []
  (or bound-keypair @global-keypair))


(defn maybe-create-keypair []
  (when-not (keypair)
    (reset! global-keypair (keys/create-keypair))))


(defn when-mined
  "Given a transaction hash it polls every 5 seconds to see if it's mined"
  [tx-hash]
  (->> (chain/get-transaction-by-hash tx-hash)
       (p/mapcat (fn [tx]
                   (if (:block-hash tx)
                     (p/resolved tx)
                     #(when-mined tx-hash))))))

(defn faucet!
  "Donate some eth from the local rpc coinbase to current account. Intended for testing purposes only."
  ([amount]
   (if (keypair)
      (faucet! (:address (keypair)) amount)))
  ([address amount]
   (->> (chain/coinbase)
        (p/mapcat #(chain/send-transaction {:value amount
                                            :from %
                                            :to address}))
        (p/mapcat when-mined))))

(defn balance []
  (chain/get-balance (:address (keypair))))

(defn fetch-nonce
  ([]
   (fetch-nonce (keypair)))
  ([kp-or-address]
   (p/then (chain/get-transaction-count (:address kp-or-address kp-or-address))
           (fn [tx-count] {:nonce tx-count}))))

(defn fetch-gas-price []
  (p/then (chain/gas-price)
          (fn [price] {:gas-price price})))

(defn fetch-defaults
  ([] (fetch-defaults (keypair)))
  ([kp]
   (p/then (p/all [(fetch-nonce kp)
                   (fetch-gas-price)])
           #(apply merge %))))

(defn sign-and-send!
  ([t]
   (sign-and-send! t (keypair)))
  ([t kp]
   (->> (fetch-defaults kp)
        (p/mapcat
          #(-> (merge t %)
               (tx/create-and-sign (keys/get-private-key kp))
               (tx/->hex)
               (chain/send-raw-transaction)))
        (p/mapcat when-mined))))
