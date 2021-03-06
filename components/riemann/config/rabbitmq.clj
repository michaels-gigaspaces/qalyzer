;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Section: Imports
;;

(ns riemann.config
  (:import  [java.util HashMap]
            [java.util.concurrent ConcurrentHashMap])
  (:require riemann.core
            riemann.transport
            [clojure.java.io   :as io]
            [cheshire.core     :as json]
            [langohr.core      :as l-core]
            [langohr.channel   :as l-channel]
            [langohr.queue     :as l-queue]
            [langohr.consumers :as l-consumers]
            [langohr.exchange  :as l-exchange]
            [langohr.basic     :as l-basic]
            [clj-time.core     :as time-core]
            [clj-time.format   :as time-format])
  (:use     [riemann.service   :only [Service ServiceEquiv]]))

;;
;; End Section: Imports
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Section: Forward reference
;;

(def amqp-connection-service)
(def events-publisher)
(def triggers-map)
(def triggers-timetable)
(def groups-map)

;;
;; End Section: Forward reference
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Section: RabbitMQ services
;;
;; TODO: all the thread semantics are not clear to me yet, need to investigate.
;; From what I understand, the connection has its own thread(pool?) and it passes
;; events to the channel, which then processes them in its own thread(pool?)
;; which calls the subsribed consumers using that thread(pool?)
;; [another possibliy is that each consumer has its own thread(pool?) ?? not sure]
;; anyway, it is on the final thread(pool?) (either the channel or the consumer one if such exists)
;; that the queue message handler is invoked which in turn, means it is on that thread(pool?) the riemann
;; events will be processed and passed to the core streams. Thus, event processing should probably never block
;; and delegate blocking operations to some other thread pool for blocking operations.

(defn bind-queue
  [channel exchange-name routing-key queue-name]
  (l-exchange/declare channel
                      exchange-name
                      "topic"
                      :durable false
                      :auto-delete true
                      :internal false)
  (l-queue/declare channel
                   queue-name
                   :exclusive false
                   :durable false
                   :auto-delete true)
  (l-queue/bind channel
                queue-name
                exchange-name
                :routing-key routing-key))

(defn queue-message-handler [core]
  (fn [ch {:keys [content-type delivery-tag type] :as meta} ^bytes payload]
    (let [event (json/parse-string (String. payload) true)]
      (riemann.core/stream! core event))))

(defn- amqp-connection-conf
  []
  {:host (or (System/getenv "RABBITMQ_HOST") "localhost")
   :username (or (System/getenv "RABBITMQ_USER") "guest")
   :password (or (System/getenv "RABBITMQ_PASS") "guest")})

(defrecord AMQPConnection [connection]
  ServiceEquiv
  (equiv? [this other]
    (instance? AMQPConnection other))

  Service
  (conflict? [this other]
    false)

  (reload! [this new-core]
    true)

  (start! [this]
    (locking this
      (when (not @connection)
        (let [new-connection (l-core/connect (amqp-connection-conf))]
          (info "AMQP connection opened")
          (reset! connection new-connection)))))

  (stop! [this]
    (locking this
      (when @connection
        (info "AMQP connection closing")
        (l-core/close @connection)
        (reset! connection nil)))))

(defn amqp-connection []
  ; connection is opened here because we need to make sure
  ; it is started before the manager queue service is started
  (let [new-connection (l-core/connect (amqp-connection-conf))]
    (info "AMQP connection opened")
    (service! (AMQPConnection. (atom new-connection)))))

(defrecord AMQPQueueConsumer [queue-name
                              routing-key
                              core
                              channel]
  ServiceEquiv
  (equiv? [this other]
    (and
      (instance? AMQPQueueConsumer other)
      (= queue-name (:queue-name other))
      (= routing-key (:routing-key other))))

  Service
  (conflict? [this other]
    (and
      (instance? AMQPQueueConsumer other)
      (= queue-name (:queue-name other))
      (= routing-key (:routing-key other))))

  (reload! [this new-core]
    (reset! core new-core))

  (start! [this]
    (locking this
      (when (not @channel)
        (let [connection    (:connection amqp-connection-service)
              new-channel   (l-channel/open @connection)
              exchange-name "riemann-monitoring"]
          (bind-queue new-channel exchange-name routing-key queue-name)
          (l-consumers/subscribe new-channel
                                 queue-name
                                 (queue-message-handler @core)
                                 :auto-ack true)
          (info "AMQP" queue-name "consumer started")
          (reset! channel new-channel)))))

  (stop! [this]
    (locking this
      (when @channel
        (info "AMQP" queue-name "consumer stopping")
        (l-core/close @channel)
        (reset! channel nil)))))

(defn amqp-queue-consumer [queue-name routing-key]
  (service! (AMQPQueueConsumer. queue-name
                                routing-key
                                (atom nil)
                                (atom nil))))

(defprotocol EventsPublisher
  (pub-log [this item])
  (pub-event [this item]))

(defrecord AMQPEventsPublisher [core
                                channels]
  ServiceEquiv
  (equiv? [this other]
    (instance? AMQPEventsPublisher other))

  Service
  (conflict? [this other]
    false)

  (reload! [this new-core]
    (reset! core new-core))

  (start! [this]
    (locking this
      (when (not @channels)
        (let [connection     (:connection amqp-connection-service)
              events-channel (l-channel/open @connection)
              logs-channel   (l-channel/open @connection)
              events-queue-name "riemann-events"
              logs-queue-name "riemann-logs"]
          (l-queue/declare events-channel
                 events-queue-name
                 :exclusive false
                 :durable true
                 :auto-delete true)
          (l-queue/declare logs-channel
                 logs-queue-name
                 :exclusive false
                 :durable true
                 :auto-delete true)
          (info "AMQP events publisher created")
          (reset! channels {:events-channel events-channel
                            :logs-channel logs-channel})))))

  (stop! [this]
    (locking this
      (when @channels
        (info "AMQP events publisher stopping")
        (l-core/close (:events-channel @channels))
        (l-core/close (:logs-channel @channels))
        (reset! channels nil))))

  EventsPublisher
  (pub-log [this item]
    (let [body (json/generate-string item)]
      (try
        (l-basic/publish (:logs-channel @channels)
                         ""
                         "riemann-logs"
                         body)
        (catch Exception e
          (warn "Failed publishing log:" body "(" e ")")))))

  (pub-event [this item]
    (let [body (json/generate-string item)]
      (try
        (l-basic/publish (:events-channel @channels)
                         ""
                         "riemann-events"
                         body)
        (catch Exception e
          (warn "Failed publishing event:" body "(" e ")"))))))

(defn amqp-events-publisher []
  (service! (AMQPEventsPublisher. (atom nil)
                                  (atom nil))))

;;
;; End Section: RabbitMQ services
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Section: RabbitMQ events and logs
;;

(def datetime-formatter (time-format/formatter "YYYY-MM-dd HH:mm:ss.SSS"))

(defn- current-timestamp []
  ; TODO Seems to use a different timezone than python (UTC or something)
  (time-format/unparse datetime-formatter (time-core/now)))

(defn publish-log* [context]
  (fn [message & {:keys [level] :or {level :info}}]
    (fn [event]
        (let [context (assoc context :node_id (:node_id event))]
          (pub-log events-publisher {:logger "policy"
                                     :level level
                                     :message {:text message}
                                     :context context
                                     :timestamp (current-timestamp)
                                     :message_code nil
                                     :type "riemann_log"})))))

(defn publish-policy-error* [context]
  (fn [event]
    (let [message (str "exception thrown from policy: " (:description event))]
        (pub-log events-publisher {:logger "policy_error"
                                   :level :warn
                                   :message {:text message}
                                   :context context
                                   :timestamp (current-timestamp)
                                   :message_code nil
                                   :type "riemann_log"}))))

(defn publish-policy-event* [context]
  (fn [message & {:keys [args] :or {args nil}}]
    (fn [event]
      (let [context (assoc context :node_id (:node_id event))]
          (pub-event events-publisher {:event_type "policy"
                                       :message {:text message :args args}
                                       :context context
                                       :timestamp (current-timestamp)
                                       :message_code nil
                                       :type "riemann_event"})))))


(defn publish-trigger-event
  [event-type message trigger-ctx]
  ; event though the context is identical to the trigger-ctx
  ; i prefer being explict and having the event context structure appear
  ; here instead of having to look somewhere else for it
  (let [context {:blueprint_id (:blueprint-id trigger-ctx)
                 :deployment_id (:deployment_id trigger-ctx)
                 :node_id (:node-id trigger-ctx)
                 :group (:group trigger-ctx)
                 :policy (:policy trigger-ctx)
                 :trigger (:trigger trigger-ctx)
                 :trigger-parameters (:trigger-parameters trigger-ctx)}]
    (pub-event events-publisher {:event_type event-type
                                 :message {:text message :args nil}
                                 :context context
                                 :timestamp (current-timestamp)
                                 :message_code nil
                                 :type "riemann_event"})))

;;
;; End Section: RabbitMQ events and logs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Section: Global riemann configuration
;;

(logging/init {:file "/var/log/riemann/riemann_second.log"})

;;
;; End Section: Global riemann configuration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Section: User definitions
;; Mostly helper predicates, streams, etc... used
;; when writing policies
;;

(def ^:private EVENT-TRIGGERING-STATE "triggering_state")
(def ^:private EVENT-STABLE-STATE "stable_state")

(defn- parse-params [elem event]
  (letfn [(handle-map [map-elem]
            (into {} (map #(-> [(key %1) (parse-params (val %1) event)]) elem)))
          (handle-seq [seq-elem]
            (into [] (map #(parse-params %1 event) elem)))
          (get-property? [entry]
            (let [e-key (key entry)
                  e-val (val entry)]
              (and (= e-key :get_property)
                   (sequential? e-val)
                   (= (count e-val) 2)
                   (= (first e-val) "SELF"))))]
    (cond
      (and (map? elem) (= (count elem) 1))
        (let [entry (first elem)]
          (if (get-property? entry)
            (get event (keyword (get (val entry) 1)))
            (handle-map elem)))
      (map? elem)
        (handle-map elem)
      (sequential? elem)
        (handle-seq elem)
      :else
        elem)))


(defn is-name-in [object_name names comparison]
  (let [object_name? (fn [object_name2] (comparison object_name object_name2))]
    (some object_name? names)))

; example: (where* (is-node-name-in "some_name") ...)
(defn is-node-name-in [& node-names]
  (fn [event] (is-name-in (:node_name event) node-names =)))

(defn is-service-name-contained [& service-names]
  (fn [event] (is-name-in (:service event)
                          service-names
                          (fn [n1 n2] (re-find (re-pattern n2) n1)))))

(defn parse-boolean [raw-value]
  (= "true" (clojure.string/lower-case raw-value)))


(defn register-policy-trigger
  [deployment-id trigger-name trigger]
  (let [triggers (.get triggers-map deployment-id)]
    (.put triggers trigger-name trigger)))


(defn processing-queue
  [deployment-id thread-pool-opts]
  (async-queue! (keyword deployment-id) thread-pool-opts
    (fn [event]
      (let [ctx         (:ctx event)
            process     (:process event)
            initial_msg (or (:initial_msg event) "Processing trigger")
            success_msg (or (:success_msg event) "Trigger succeeded")
            error_msg   (or (:error_msg event) "Trigger failed: ")]
        (publish-trigger-event "processing_trigger" initial_msg ctx)
        (try
          (process ctx)
          (publish-trigger-event "trigger_succeeded" success_msg ctx)
          (catch Exception e
            (publish-trigger-event "trigger_failed" (str error_msg e) ctx)))))))


(defn process-policy-triggers-stream [ctx deployment-processing-queue]
  (let [dep-triggers-map (.get triggers-map (:deployment_id ctx))
        dep-groups-map   (.get groups-map (:deployment_id ctx))
        group            ((keyword (:group ctx)) dep-groups-map)
        group-policies   (:policies group)
        group-policy     ((keyword (:policy ctx)) group-policies)
        policy-triggers  (:triggers group-policy)]
    (fn [event]
      (doseq [policy-trigger        policy-triggers]
        (let [policy-trigger-name   (key policy-trigger)
              policy-trigger-record (val policy-trigger)
              trigger-type          (:type policy-trigger-record)
              trigger-parameters    (:parameters policy-trigger-record)
              parsed-parameters     (parse-params trigger-parameters event)
              trigger               (.get dep-triggers-map trigger-type)
              ctx                   (assoc ctx :node-id (:node_id event)
                                               :trigger policy-trigger-name
                                               :trigger-parameters parsed-parameters)
              trigger-event         (assoc event :ctx ctx
                                                 :process trigger)]
          (deployment-processing-queue trigger-event))))))

(defn- check-list-of-restraints
  [ctx [restraint & restraints]]
  (or (= restraint nil)
      (and (restraint ctx)
           (or (not restraints) (check-list-of-restraints ctx restraints)))))

(defn- check-restraints-and-process** [deployment-processing-queue event restraints]
  (fn [ctx]
    (if (check-list-of-restraints ctx restraints)
      ((process-policy-triggers-stream ctx deployment-processing-queue) event))))

(defn check-restraints-and-process* [ctx deployment-processing-queue restraints]
  (fn [event]
    (let [ctx           (assoc ctx :node-id (:node_id event))
          process-event {:ctx ctx
                         :process (check-restraints-and-process** deployment-processing-queue
                                                                  event
                                                                  restraints)
                         :initial_msg "Checking policy restraints"
                         :success_msg "Policy restraints computing succeeded"
                         :error_msg "Policy restraints computing failed: "}]
      (deployment-processing-queue process-event))))

;TODO those checks will be later moved to separate files defined in blueprints
(defn- is-started [ctx]
  (let [node-id               (:node-id ctx)
        manager-ip            (or (System/getenv "MANAGEMENT_IP") "127.0.0.1")
        raw-manager-rest-port (or (System/getenv "MANAGER_REST_PORT") "8101")
        manager-rest-port     (Integer/parseInt raw-manager-rest-port)
        base-uri              (str "http://" manager-ip ":" manager-rest-port "/api/v2")
        node-endpoint         (str "/node-instances/" node-id)
        node-resource-uri     (str base-uri node-endpoint)
        get-node              (fn [] (clj-http.client/get node-resource-uri {:accept :json}))
        get-state             (fn [node-response]
                                (:state (cheshire.core/parse-string (:body node-response) true)))
        check-if-error-404    (fn [e] (= (:status (:object (.getData e))) 404))]
      (try
        (= (get-state (get-node)) "started")
        (catch Exception e (if (check-if-error-404 e) false (throw e))))))

(defn- no-concurrent-workflows [interval-between-workflows]
 (fn [ctx]
  (let [node-id              (:node-id ctx)
        trigger-timetable    (.get triggers-timetable (:deployment_id ctx))
        last-trigger         (.get trigger-timetable node-id)
        min-interval         interval-between-workflows]
    (if (and last-trigger
             (> (time-core/in-secs (time-core/interval last-trigger (time-core/now)))
             min-interval))
      (.replace trigger-timetable node-id last-trigger (time-core/now))
      (and (not last-trigger)
           (= nil (.putIfAbsent trigger-timetable node-id (time-core/now))))))))

;Returns a list of restraints based on the options from the blueprint
(defn get-workflow-restraints
  [is-started-option interval-between-workflows]
  (concat (if is-started-option [is-started])
          (if (> interval-between-workflows 0) [(no-concurrent-workflows interval-between-workflows)])))

(create-ns 'autohealing)
(create-ns 'threshold-computing)

(let [inequality  (fn [upper_bound] (if (parse-boolean upper_bound) >= <=))
      downstream* (fn [index process-policy-triggers]
                     (sdo (where (state EVENT-TRIGGERING-STATE)
                            process-policy-triggers)
                          index))]
    (intern 'autohealing 'downstream* downstream*)
    (intern 'threshold-computing 'inequality inequality))

;;
;; End Section: User definitions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Section: Deployments core administration
;;
;
(def config-lock              (Object.))
(def cores-map                (HashMap.))
(def triggers-map             (ConcurrentHashMap.))
(def groups-map               (ConcurrentHashMap.))
(def triggers-timetable       (ConcurrentHashMap.))
(def amqp-connection-service  (amqp-connection))
(def events-publisher         (amqp-events-publisher))

(defn start-config! [& children]
  (fn [event]
    (locking config-lock
      (let [config-path   (:config_path event)
            deployment-id (:deployment_id event)
            ok-path       (str config-path "/ok")
            groups-path   (str config-path "/groups")
            groups        (json/parse-string (slurp groups-path) true)]

        ; prepare config cores
        (reset! core      (riemann.core/core))
        (reset! next-core (riemann.core/core))

        (.put groups-map deployment-id groups)
        (.put triggers-map deployment-id (ConcurrentHashMap.))
        (.put triggers-timetable deployment-id (ConcurrentHashMap.))

        ; load configuration files and apply to create new core
        (include config-path)
        (apply!)

        ; external indication new node is up
        (spit ok-path "ok")

        (.put cores-map deployment-id @core)))))

(defn stop-config! [& children]
  (fn [event]
    (locking config-lock
      (let [ok-path       (str (:config_path event) "/ok")
            deployment-id (:deployment_id event)
            ; get core instance to stop and remove it from the map
            stopped-core  (.remove cores-map deployment-id)]

        (when stopped-core
          (io/delete-file ok-path)
          (riemann.core/stop! stopped-core))))))

;;
;; End Section: Deployments core administration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Section: Management core configuration
;;

(amqp-queue-consumer "riemann" "riemann")

(periodically-expire 5)

(let [index (index)]
  (streams
    (default :ttl 60 index)
    ))

;;
;; End Section: Management core configuration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

