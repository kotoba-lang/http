# kotoba-lang/http

[![CI](https://github.com/kotoba-lang/http/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/http/actions/workflows/ci.yml)

**Layer 3 (I/O) of the kotoba foundational stdlib** — a request/response data
model plus a pure `parse-url`, with an `IHttp` **protocol** the host injects.
No HTTP client is baked in, so the same code runs on kotoba-WASM where the
transport must be a granted host capability. Zero third-party runtime deps;
every namespace is `.cljc` (JVM / SCI / ClojureScript / GraalVM / kotoba-WASM).
See
[`docs/adr/ADR-kotoba-lang-foundational-stdlib.md`](https://github.com/kotoba-lang/kotoba-lang/blob/main/docs/adr/ADR-kotoba-lang-foundational-stdlib.md).

## Why a protocol

A capability-confined cell cannot open a socket on its own. `http` splits: the
**data model** (`request`, `response`, header helpers, `parse-url`) is pure and
portable; the **transport** (`send`) lives behind `IHttp`, host-injected — same
seam as `kotobase.store/IStore` and `fs/IFilesystem`. A `mock-http` is provided
for tests.

## Current surface

`kotoba.lang.http`:

- `request` / `response` — data constructors
- `header` (case-insensitive get) / `set-header`
- `parse-url` — `{scheme host port path query}` from a URL string
- `IHttp` protocol: `send` (host-injected transport)
- `mock-http` — routing fn → response, for tests

## Install

```clojure
io.github.kotoba-lang/http {:git/sha "<sha>"}
```

## Use

```clojure
(require '[kotoba.lang.http :as http])

(http/parse-url "https://a.b:8443/x?y=1")  ;=> {:scheme "https" :host "a.b" :port 8443 :path "/x" :query "y=1"}
(let [c (http/mock-http (fn [req] (http/response 200 {} "ok")))]
  (-> (http/send c (http/request :get "https://a.b/x")) :http/status)) ;=> 200
```

## Verify

```sh
clojure -M:test
```
