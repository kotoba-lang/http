;; A live composition: kotoba-lang/org-ietf-tls as the byte transport,
;; kotoba.lang.http.wire as the HTTP/1.1 client on top of it.
;;
;;   clojure -M:live scripts/live_fetch.clj
;;
;; org-ietf-tls is a SCRIPT-SCOPE dependency (the :live alias) and nothing
;; else. `http` itself must not depend on TLS: it is Layer 3 of the
;; foundational stdlib and the superproject enforces a layer DAG. The whole
;; of the coupling is `tls-transport` below -- twenty lines that turn
;; tls.client/write! and read! into the {:write :read} pair the wire layer
;; asks for. Anything else that can move bytes both ways would do as well,
;; which is the point.
;;
;; Exit 0 only if every check passed AND at least `check-floor` checks ran:
;; a run that measured nothing must not report success (root ADR-2608136000).

(require '[tls.client :as tls]
         '[tls.provider.jvm :as provider]
         '[tls.transport.jvm :as tp]
         '[tls.result :as r]
         '[clojure.string :as str]
         '[kotoba.lang.http :as http]
         '[kotoba.lang.http.wire :as w])

(import '[java.security MessageDigest])

(def host "kotobase.net")
(def port 443)
(def pin "50602ad366823fcf5274a7c917baa4fd24b9de4fd15635ff501177c83d05473e")
(def check-floor 10)

(def checks (atom {:ran 0 :failed 0}))

(defn check! [label expected actual]
  (let [pass (= expected actual)]
    (swap! checks (fn [m] (-> m (update :ran inc) (update :failed #(if pass % (inc %))))))
    (println (format "  [%s] %-28s expected %s  actual %s"
                     (if pass "PASS" "FAIL") label (pr-str expected) (pr-str actual)))))

(defn sha256-hex
  "JDK digest, deliberately: the point is to check our assembled body against
  something that is not our own code."
  [octets]
  (let [md (MessageDigest/getInstance "SHA-256")
        ba (byte-array (map #(unchecked-byte %) octets))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md ba)))))

(defn- unsign
  "Every Java byte-array in `x` becomes a vector of unsigned octets, which is
  the representation the rest of org-ietf-tls uses."
  [x]
  (cond
    (bytes? x) (mapv #(bit-and % 0xff) x)
    (map? x) (into {} (map (fn [[k v]] [k (unsign v)])) x)
    (vector? x) (mapv unsign x)
    :else x))

(defn- ->ba
  "A sequential collection of ints becomes a Java byte-array; anything else
  (a keyword, a length) is passed through untouched."
  [x]
  (if (and (sequential? x) (every? integer? x))
    (byte-array (map #(unchecked-byte %) x))
    x))

(defn- adapt
  "Byte-arrays in, unsigned octets out -- the representation contract the rest
  of org-ietf-tls actually uses."
  [f]
  (fn [& args] (unsign (apply f (map ->ba args)))))

(defn- wrap-leaves [m]
  (into {} (map (fn [[k v]] [k (cond (fn? v) (adapt v) (map? v) (wrap-leaves v) :else v)])) m))

(defn tls-provider
  "org-ietf-tls's shipped JVM provider, with every byte-array output normalised
  to unsigned octets.

  MEASURED 2026-08-22 against org-ietf-tls b37f912. `tls.provider.jvm` returns
  raw Java byte-arrays (signed, -128..127); the rest of the library represents
  bytes as vectors of unsigned ints, and its test-scope `tls.jdk-provider`
  masks with 0xff on every path (`->vec`). The public provider does not, so a
  consumer gets:

    :random -> returns signed bytes, so the handshake dies at ServerHello with
               #:tls{:alert :illegal_parameter :reason :session-id-echo-mismatch}
               -- legacy_session_id_echo is parsed unsigned and compared against
               what was sent signed.
    :sig    -> its scheme table is keyed with hyphens, the protocol with
               underscores, so CertificateVerify is [:error
               :signature/unknown-scheme] for every scheme that exists.
    :hmac   -> REFUSES a vector of unsigned ints with [:error :hmac/bad-input],
               which is what the key schedule hands it. That error value then
               flows into the derived write IV as data, and tls.record/nonce
               throws IllegalArgumentException \"bit operation not supported
               for: class clojure.lang.Keyword\" while XORing it.

  So the adaptation has to run in both directions: byte-arrays in, unsigned
  octets out.

  org-ietf-tls's own live script works because it runs on the test provider.
  This is a defect in org-ietf-tls, not in the wire layer, and this shim exists
  to be deleted once the provider masks its own output. It is a workaround, not
  a contract."
  []
  (-> (wrap-leaves (provider/provider))
      (update-in [:signature :verify]
                 (fn [f]
                   ;; Third mismatch: its scheme table is keyed
                   ;; :ecdsa-secp256r1-sha256 while the protocol -- and its own
                   ;; tls.extension number table, tls.client default list and
                   ;; test provider -- say :ecdsa_secp256r1_sha256. Every real
                   ;; CertificateVerify therefore comes back
                   ;; [:error :signature/unknown-scheme].
                   (fn [scheme & args]
                     (apply f (keyword (str/replace (name scheme) "_" "-")) args))))))

(defn tls-transport
  "Adapt a live TLS 1.3 connection to the wire layer's transport seam.

  This is the ENTIRE coupling between TLS and HTTP. `read!` hands back one
  record at a time and signals close_notify as a value; the socket underneath
  it signals a timeout by throwing, so that one throw is converted here --
  at the seam, so that nothing above this line ever has to catch."
  [conn]
  {:write (fn [octets]
            (let [res (tls/write! conn (vec octets))]
              (if (r/ok? res) [:ok (count octets)] [:error :tls-write-failed (r/err res)])))
   :read (fn []
           (try
             (let [res (tls/read! conn)]
               (cond
                 (r/error? res) [:error :tls-read-failed (r/err res)]
                 (:tls/closed (r/val res)) [:eof]
                 :else [:ok (:tls/content (r/val res))]))
             (catch Exception e
               [:error :transport-io {:message (.getMessage e)
                                      :class (.getName (class e))}])))})

(defn fetch
  "One request over one fresh TLS connection. Returns the wire layer's own
  [:ok resp] / [:error reason detail] value, plus handshake facts."
  [path & [{:keys [spki-pin]}]]
  (let [t (tp/socket-transport host port {:timeout-ms 15000})]
    (try
      (let [hs (tls/handshake (tls-provider) t
                              {:server-name host
                               :pin-spki-sha256 (or spki-pin pin)})]
        (if (r/error? hs)
          {:handshake-error (r/err hs)}
          (let [conn (r/val hs)
                res (w/exchange (tls-transport conn)
                                (http/request :get (str "https://" host path)
                                              {:headers {"Connection" "close"
                                                         "User-Agent" "kotoba-lang/http.wire"}}))]
            (tls/close! conn)
            {:suite (:tls/suite conn)
             :scheme (:tls/certificate-verify-scheme conn)
             :auth (:tls/authentication conn)
             :result res})))
      (finally ((:close t))))))

(defn report [label path expect-status expect-len expect-sha]
  (println)
  (println (str "=== GET " path " ==="))
  (let [{:keys [suite scheme auth result handshake-error]} (fetch path)]
    (if handshake-error
      (do (println "  handshake FAILED:" (pr-str handshake-error))
          (swap! checks (fn [m] (-> m (update :ran inc) (update :failed inc)))))
      (do
        (println (format "  tls          %s / %s / %s" suite scheme (pr-str auth)))
        (if (w/error? result)
          (do (println "  http ERROR   " (w/reason result) (pr-str (w/detail result)))
              (swap! checks (fn [m] (-> m (update :ran inc) (update :failed inc)))))
          (let [resp (w/value result)
                body (:http/body resp)
                declared (http/header (:http/headers resp) "content-length")]
            (println (format "  status       %d %s (%s)"
                             (:http/status resp) (:http/reason-phrase resp) (:http/version resp)))
            (println (format "  content-type %s" (pr-str (http/header (:http/headers resp) "content-type"))))
            (println (format "  declared CL  %s" (pr-str declared)))
            (println (format "  assembled    %d octets" (count body)))
            (println (format "  sha256       %s" (sha256-hex body)))
            (check! (str label " status") expect-status (:http/status resp))
            (check! (str label " bytes") expect-len (count body))
            (when declared
              (check! (str label " CL vs bytes") (str (count body)) declared))
            (when expect-sha
              (check! (str label " sha256") expect-sha (sha256-hex body)))))))))

(println (str "live composition: org-ietf-tls transport + kotoba.lang.http.wire client"))
(println (str "host " host ":" port "  spki-pin " pin))

(report "llms.txt" "/llms.txt" 200 6391 nil)

(report "empty-block"
        "/ipfs/bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku"
        200 0 "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

(println)
(println "=== GET /ipfs/bafkreicjlihajri6k5g4n66xvq3xsffb45qddog7q2wpolck5kddammoey ===")
(let [{:keys [result handshake-error]}
      (fetch "/ipfs/bafkreicjlihajri6k5g4n66xvq3xsffb45qddog7q2wpolck5kddammoey")]
  (if (or handshake-error (w/error? result))
    (do (println "  FAILED:" (pr-str (or handshake-error result)))
        (swap! checks (fn [m] (-> m (update :ran inc) (update :failed inc)))))
    (let [resp (w/value result)]
      (println (format "  status       %d %s" (:http/status resp) (:http/reason-phrase resp)))
      (println (format "  assembled    %d octets" (count (:http/body resp))))
      (check! "missing-block status" 404 (:http/status resp)))))

;; The pin must be load-bearing. A check that has never refused has not
;; discriminated, and a green run of it means nothing.
(println)
(println "=== negative control: wrong SPKI pin ===")
(let [{:keys [handshake-error]} (fetch "/llms.txt" {:spki-pin (apply str (repeat 64 "a"))})]
  (check! "wrong pin refused" true (some? handshake-error))
  (when handshake-error
    (println "  refusal      " (pr-str handshake-error))
    (check! "refusal reason" :spki-pin-mismatch (:tls/reason handshake-error))))

(println)
(let [{:keys [ran failed]} @checks]
  (println (format "LIVE-CHECKS\t%d\tFAILED\t%d\tFLOOR\t%d" ran failed check-floor))
  (cond
    (zero? ran) (do (println "REFUSING TO REPORT A PASS: no checks ran") (System/exit 2))
    (< ran check-floor) (do (println "REFUSING TO REPORT A PASS: below check floor") (System/exit 2))
    (pos? failed) (System/exit 1)
    :else (println "all live checks passed")))
