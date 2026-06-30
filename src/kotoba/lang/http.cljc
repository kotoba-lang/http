(ns kotoba.lang.http
  "HTTP request/response data model + pure parse-url + a host-injected IHttp
  protocol. Layer 3 (I/O) of the kotoba foundational stdlib.

  No HTTP client is baked in: a capability-confined cell cannot open a socket,
  so transport lives behind the IHttp protocol, host-injected (same seam as
  kotobase.store/IStore and fs/IFilesystem). The data model and parse-url are
  pure and portable.

  Zero third-party runtime deps; .cljc (JVM / SCI / CLJS / GraalVM / kotoba-WASM)."
  (:require [clojure.string :as str]))

;; ---------- request / response data ----------

(defn request
  "Construct a request map. `method` may be a keyword or string; `url` is the
  full URL string. Options: `:headers` (map), `:body`, `:query-params`."
  ([method url] (request method url nil))
  ([method url opts]
   (cond-> {:http/method (keyword (str/lower-case (name method)))
            :http/url    url}
     (:headers opts)       (assoc :http/headers (:headers opts))
     (contains? opts :body) (assoc :http/body (:body opts))
     (:query-params opts)  (assoc :http/query-params (:query-params opts)))))

(defn response
  ([status] (response status nil nil))
  ([status headers body]
   {:http/status  (int status)
    :http/headers (or headers {})
    :http/body    body}))

;; ---------- headers (case-insensitive) ----------

(defn header
  "Case-insensitive header lookup. Returns the first value for `name`, or
  `default` (nil) if absent."
  ([headers name] (header headers name nil))
  ([headers name default]
   (let [target (str/lower-case (str name))]
     (reduce (fn [acc [k v]]
               (if (= (str/lower-case (str k)) target)
                 (reduced v)
                 acc))
             default headers))))

(defn set-header
  "Associate a header (replaces existing case-variant of the same name)."
  [headers name value]
  (let [target (str/lower-case (str name))
        filtered (into {} (remove (fn [[k _]] (= (str/lower-case (str k)) target)) headers))]
    (assoc filtered name value)))

;; ---------- pure url parsing ----------

(defn parse-url
  "Parse a URL string into `{:scheme :host :port :path :query}`. Minimal:
  handles `scheme://host[:port]/path?query`. Path/query may be nil."
  [s]
  (let [s (str s)]
    (if-let [m (re-matches #"(?s)^([^:/?#]+)://([^:/?#]+)(?::(\d+))?(/[^?#]*)?(\?[^#]*)?(#.*)?$" s)]
      (let [[_ scheme host port path query] m]
        (cond-> {:scheme scheme :host host}
          (some? port)  (assoc :port (try #?(:clj (Integer/parseInt port) :cljs (js/parseInt port 10))
                                          (catch #?(:clj Throwable :cljs :default) _ nil)))
          (some? path)  (assoc :path path)
          (some? query) (assoc :query (subs query 1))))   ; strip leading '?'
      (throw (ex-info "http/parse-url: malformed url" {:input s})))))

;; ---------- IHttp protocol (host-injected transport) ----------

(defprotocol IHttp
  (send [http req] "Perform `req`, return a response map."))

(defn mock-http
  "An IHttp whose `send` routes each request through `handler` (fn of req →
  response). For tests / OSS standalone."
  [handler]
  (reify IHttp
    (send [_ req] (handler req))))
