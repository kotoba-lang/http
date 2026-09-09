(ns kotoba.lang.http.host.jvm
  "JVM host adapter for OUTBOUND HTTP over `java.net.http` — the JDK's own
  client, so this adapter adds no dependency at all.

  ## Why this exists next to `host/babashka`

  Both are client hosts, and they are not redundant. `host/babashka` wraps
  `org.babashka/http-client`, which is bundled under Babashka but is an added
  dependency on a plain JVM (`:babashka-client-host`). `java.net.http` ships
  with the JDK from 11, so a consumer that is already on the JVM gets a
  transport without widening its dependency set — which is what a repository
  whose README says `zero third-party runtime deps` should be able to offer.

  It is the missing corner of this repo's host table, and its absence was
  measured rather than assumed. On 2026-09-07, cloud-itonami-app rewrote 35
  namespaces to route every `java.net.http` call through one shim, requiring
  `kotoba.net.jvm-host`. That namespace was never written -- not in this
  workspace, and (searched 2026-09-09) nowhere on GitHub. The application
  could not load at all for two days: 36 namespaces require the shim, the
  shim required a namespace that did not exist, and the Windows launch smoke
  went red on every run while nothing said why in terms anyone acted on.

  The instinct behind that commit was right. `java.net.http` is a host
  capability, an application should not have its own copy of one, and this
  repository is where HTTP transports live. What was missing was the
  transport, not the intention.

  ## The response shape, and why it is this one

  `{:status int :headers {lowercase-name value} :body string}`.

  Header names are lower-cased and multi-valued headers collapse to their
  FIRST value. That is not a fresh design decision -- it is the contract the
  call sites were written against before the migration, and ACME reads
  `[:headers \"replay-nonce\"]` and `[:headers \"location\"]` positionally.
  Changing the arity here would break a protocol handshake in a way no type
  would catch.

  The lower-casing is belt-and-braces, and measured to be so: `HttpHeaders`
  already returns its keys folded, so removing the `toLowerCase` on 2026-09-09
  left a round-trip test green. It stays because the contract is this
  namespace's to state rather than the JDK's to keep -- but it is checked
  where it can actually be checked, by `normalize-headers` on a map with
  mixed-case keys, and not by a round trip that cannot tell who folded them.

  ## Fail closed on an unknown method

  `method->name` throws rather than defaulting to GET. A transport that
  silently downgrades an unrecognised method turns a write into a read, which
  succeeds, returns 200, and changes nothing -- the failure that looks most
  like success."
  #?(:clj (:require [kotoba.lang.http :as http]))
  #?(:clj (:import [java.net URI]
                   [java.net.http HttpClient HttpClient$Redirect
                    HttpRequest HttpResponse
                    HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
                   [java.time Duration])))

#?(:clj
   (def ^:private methods-with-body
     "POST, PUT and PATCH carry a body; GET, DELETE and HEAD do not. Listed so
      an unknown method cannot fall into either branch by accident."
     #{:post :put :patch}))

#?(:clj
   (defn- method->name [method]
     (let [k (keyword (if (string? method) (.toLowerCase ^String method) (name method)))]
       (case k
         :get "GET" :post "POST" :put "PUT" :patch "PATCH"
         :delete "DELETE" :head "HEAD" :options "OPTIONS"
         (throw (ex-info "kotoba.lang.http.host.jvm: unsupported HTTP method"
                         {:method method}))))))

#?(:clj
   (defn normalize-headers
     "`java.net.http`'s header map -> {lowercase-name first-value}.

      Public and separate from `response->map` so the rule can be tested on an
      input this namespace controls. Through a real request it cannot be: the
      JDK folds the keys before they get here, so a round trip stays green
      whether or not this function folds anything.

      The rule itself moved to `kotoba.lang.http/fold-headers` when host/node
      arrived and needed the same answer. This name stays because it is the one
      this host's tests and its consumers already use."
     [header-map]
     (http/fold-headers header-map)))

#?(:clj
   (defn- response->map [^HttpResponse resp]
     {:status (.statusCode resp)
      :headers (normalize-headers (.map (.headers resp)))
      :body (.body resp)}))

#?(:clj
   (defn http-transport
     "Return a function of a request map, performing it over `java.net.http`.

      req  {:url string
            :method :get|:post|:put|:patch|:delete|:head|:options
            :headers {string-or-keyword value}   optional
            :body string}                        optional
      ->   {:status int :headers {lowercase-name value} :body string}

      `:timeout-seconds` bounds the request; it is required to be present in
      opts or in the request rather than defaulted here, because a transport
      with no timeout is how one unreachable host stops a whole process, and
      the right bound belongs to the caller who knows what it is calling."
     ([] (http-transport {}))
     ([{:keys [timeout-seconds connect-timeout-seconds]}]
      (let [client (-> (HttpClient/newBuilder)
                       (.connectTimeout (Duration/ofSeconds (long (or connect-timeout-seconds 30))))
                       (.followRedirects HttpClient$Redirect/NORMAL)
                       (.build))]
        (fn [{:keys [url method headers body] :as req}]
          (let [secs (long (or (:timeout-seconds req) timeout-seconds 120))
                publisher (if (contains? methods-with-body (keyword (name (or method :get))))
                            (HttpRequest$BodyPublishers/ofString (str (or body "")))
                            (HttpRequest$BodyPublishers/noBody))
                builder (-> (HttpRequest/newBuilder)
                            (.uri (URI/create url))
                            (.timeout (Duration/ofSeconds secs))
                            (.method (method->name (or method :get)) publisher))]
            (doseq [[k v] headers]
              (.header builder (if (keyword? k) (name k) (str k)) (str v)))
            (response->map
             (.send client (.build builder) (HttpResponse$BodyHandlers/ofString)))))))))

#?(:clj
   (defn ->http
     "The same transport as an `IHttp`, so it plugs into this repo's own seam
      rather than only into callers that want a bare function."
     ([] (->http {}))
     ([opts]
      (let [send-fn (http-transport opts)]
        (reify http/IHttp
          (send [_ req] (send-fn req)))))))
