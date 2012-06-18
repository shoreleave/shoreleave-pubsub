(ns shoreleave.pubsubs.protocols
  (:require [shoreleave.efunction :as efn]
            [shoreleave.worker :as swk]
            [shoreleave.browser.storage.localstorage :as ls]))

;; Publish/Subscribe
;; -----------------
;;
;; Shoreleave allows you to compose your programs by
;; declaratively binding functions, atoms, workers, and and localStorage
;; through a publish/subscribe bus.
;;
;; The system is built upon two protocols: IMessageBrokerBus and IPublishable
;;

;; ###IMessageBrokerBus
;; This protocol abstracts away the implementation details of the bus itself,
;; enabling you to program aginst the interface instead of the implementation.
;;
;; You can imagine there different types of buses you might use:
;; local buses, cross-document buses, buses that are a proxy for other servers...
;;
;; The protocol defines the following functions:
;;
;;  * subscribe - for a given bus, bind the handler-fn to the topic.
;;  * subscribe-once - for a given bus, create a one-time binding between the topic and the handler-fn
;;  * unsubscribe - for a given bus, unbind the handler-fn and the topic
;;  * publish - for a given bus, publish data to all the handler-fn's bound to the topic
(defprotocol IMessageBrokerBus
  (subscribe [broker-bus topic handler-fn])
  (subscribe-once [broker-bus topic handler-fn])
  #_(subscribe-> [broker-bus hf1 hf2]
               [broker-bus hf1 hf2 hf3]
               [broker-bus hf1 hf2 hf3 hf4]
               [broker-bus hf1 hf2 hf3 hf4 hf5]
               [broker-bus hf1 hf2 hf3 hf4 hf5 hf6]
               [broker-bus hf1 hf2 hf3 hf4 hf5 hf6 hf7]
               [broker-bus hf1 hf2 hf3 hf4 hf5 hf6 hf7 hf8])
  (unsubscribe [broker-bus topic handler-fn])
  (publish
    [broker-bus topic data]
    [broker-bus topic data more-data]))

;; In addition to strings and keywords, functions as topics, allowing for a
;; pipeline-like binding to take place.  For example:
;;
;; `(subscribe my-bus user-search render-search-results)`
;;
;; ... where `user-search` is a function that returns some search results,
;; and `render-search-results` takes search data and renders it to the DOM.
;;
;; When `user-search` is called, the results are automatically rendered to the DOM.
;; This frees the developer from having to liter DOM, view, and state logic
;; throughout the application.  Shoreleave's pubsub allows you to bind together
;; pure functions to build out functionality.
;;
;; Additionally, cross-cutting functionality (like logging) is more easily managed
;; through the pubsub system.

;; ###IPublishable
;; The second protocol is used to define things that can be published,
;; _ie:_ things that be used as a topic.
;;
;; Publishables are usually constructed as a decorator (using Shoreleave's
;; Function type), or as an adapter to IWatchable interfaces
;;
;; A Publishable knows how to make a string-based topic of itself (`topicify`),
;; can tell if it has already been decorated (`publishized?`), and can generate
;; the decorator form that is bound to a bus (`publishize`)
(defprotocol IPublishable
  (topicify [t])
  (publishized? [t])
  (publishize [t broker-bus]))

;; Publishables
;; ------------
;; Shoreleave comes with out-of-the-box support for the most common
;; publishables
;;
;; ###Functions and Function types
;; Functions need to be decorated (much like how `memoize` works).
;;
;; For example:
;;
;;      (defn some-fn [] 5)
;;      (def some-fn-p (publishize some-fn))
;;
;;      (some-fn) ;; This DOES NOT get sent to the bus
;;      (some-fn-p) ;; The results of this call are published on the bus
;; 
;; Anything that is subscribed to `some-fn-p` will get the value `5` when the
;; function is called, as shown above.
;;
;; ###Atoms
;; Atoms can also be topics.  This is no different than _watching_ the atom.
;; 
;; All subscribed functions will be passed a map: `{:old some-val :new another-val}`
;;
;; ###LocalStorage
;; localStorage behaves exactly like an atom, as described above
;;
;; ###WorkerFn
;; Embedded workers behave exactly like atoms, as described above
;;
;; ###The default case
;; You can also use strings and keywords as topics (the most useful case for
;; cross-cutting functionality).

(extend-protocol IPublishable

  function
  (topicify [t]
    (or (publishized? t)
        (-> t hash str)))
  (publishized? [t]
    (:sl-published (meta t)))
  (publishize [fn-as-topic bus]
    (if (-> (meta fn-as-topic) :sl-buses (get (-> bus hash keyword)))
      fn-as-topic
      (let [published-topic (topicify fn-as-topic)
            new-meta (assoc (meta fn-as-topic) :sl-published published-topic
                                               :sl-buses (-> (get (meta fn-as-topic) :sl-buses #{}) (conj (-> bus hash keyword))))]
        (efn/Function. (fn [& args]
                         (let [ret (apply fn-as-topic args)]
                           (publish bus published-topic ret)
                           ret))
                       new-meta))))

  efn/Function
  (topicify [t]
    (or (publishized? t)
        (topicify (.-f t))))
  (publishized? [t]
    (:sl-published (meta t)))
  (publishize [fn-as-topic bus]
    (if (-> (meta fn-as-topic) :sl-buses (get (-> bus hash keyword)))
      fn-as-topic
      (let [published-topic (topicify fn-as-topic)
            new-meta (assoc (meta fn-as-topic) :sl-published published-topic
                                               :sl-buses (-> (get (meta fn-as-topic) :sl-buses #{}) (conj (-> bus hash keyword))))]
        (efn/Function. (fn [& args]
                         (let [ret (apply (.-f fn-as-topic) args)]
                           (publish bus published-topic ret)
                           ret))
                       new-meta))))

  swk/WorkerFn
  (topicify [t]
    (or (publishized? t)
        (-> t hash str)))
  (publishized? [t]
    (-> t hash str))
  (publishize [worker-as-topic bus]
    (let [published-topic (topicify worker-as-topic)
          bus-key (-> bus hash keyword)]
      (do
        (add-watch worker-as-topic bus-key #(publish bus published-topic {:old %3 :new %4}))
        worker-as-topic)))

  Atom
  (topicify [t]
    (or (publishized? t)
        (-> t hash str)))
  (publishized? [t]
    (-> t hash str))
  (publishize [atom-as-topic bus]
    (let [published-topic (topicify atom-as-topic)
          bus-key (-> bus hash keyword)]
      (do
        (add-watch atom-as-topic bus-key #(publish bus published-topic {:old %3 :new %4}))
        atom-as-topic)))

  js/localStorage
  (topicify [t]
    (or (publishized? t)
        (-> t hash str)))
  (publishized? [t]
    (-> t hash str))
  (publishize [ls-as-topic bus]
    (let [published-topic (topicify ls-as-topic)
          bus-key (-> bus hash keyword)]
      (do
        (add-watch ls-as-topic bus-key #(publish bus published-topic {:old %3 :new %4}))
        ls-as-topic)))
  
  default
  (topicify [t]
    (str t)))

