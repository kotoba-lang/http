(ns kotoba.lang.http.host.httpkit
  "JVM / Babashka host adapter for HTTP servers via `org.httpkit.server`.

  Consumers depend on `io.github.kotoba-lang/http` and add `http-kit/http-kit`
  only through the `:httpkit-host` alias — not as a top-level shipped dep."
  #?(:clj (:require [org.httpkit.server :as impl])))

#?(:clj (def run-server impl/run-server))
#?(:clj (def send! impl/send!))
#?(:clj (def close impl/close))
#?(:clj (def ring-async-response impl/ring-async-response))
#?(:clj (def on-receive impl/on-receive))
#?(:clj (def Channel impl/Channel))

#?(:clj
   (defmacro with-channel
     "Ring async / SSE / WebSocket channel binding. Delegates to http-kit."
     [req channel & body]
     `(impl/with-channel ~req ~channel ~@body)))
