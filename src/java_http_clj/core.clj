(ns java-http-clj.core
  (:refer-clojure :exclude [send get])
  (:require [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http
            HttpClient
            HttpClient$Builder
            HttpClient$Redirect
            HttpClient$Version
            HttpRequest
            HttpRequest$BodyPublishers
            HttpRequest$Builder
            HttpResponse
            HttpResponse$BodyHandlers]
           [java.time Duration]
           [java.util.concurrent CompletableFuture]
           [java.util.function Function Supplier]))

(set! *warn-on-reflection* true)

(defn- convert-timeout [t]
  (if (integer? t)
    (Duration/ofMillis t)
    t))

(defn- version-keyword->version-enum [version]
  (case version
    :http1.1 HttpClient$Version/HTTP_1_1
    :http2   HttpClient$Version/HTTP_2))

(defn- convert-follow-redirect [redirect]
  (case redirect
    :always HttpClient$Redirect/ALWAYS
    :never HttpClient$Redirect/NEVER
    :normal HttpClient$Redirect/NORMAL))

(defn client-builder
  (^HttpClient$Builder []
   (client-builder {}))
  (^HttpClient$Builder [opts]
   (let [{:keys [connect-timeout
                 cookie-handler
                 executor
                 follow-redirects
                 priority
                 proxy
                 ssl-context
                 ssl-parameters
                 version]} opts]
     (cond-> (HttpClient/newBuilder)
       connect-timeout  (.connectTimeout (convert-timeout connect-timeout))
       cookie-handler   (.cookieHandler cookie-handler)
       executor         (.executor executor)
       follow-redirects (.followRedirects (convert-follow-redirect follow-redirects))
       priority         (.priority priority)
       proxy            (.proxy proxy)
       ssl-context      (.sslContext ssl-context)
       ssl-parameters   (.sslParameters ssl-parameters)
       version          (.version (version-keyword->version-enum version))))))

(defn make-client
  ([] (.build (client-builder)))
  ([opts] (.build (client-builder opts))))

(def ^HttpClient default-client
  (delay (make-client)))

(def ^:private byte-array-class
  (Class/forName "[B"))

(defn- input-stream-supplier [s]
  (reify Supplier
    (get [this] s)))

(defn- convert-body-publisher [body]
  (cond
    (string? body)
    (HttpRequest$BodyPublishers/ofString body)

    (instance? java.io.InputStream body)
    (HttpRequest$BodyPublishers/ofInputStream (input-stream-supplier body))

    (instance? byte-array-class body)
    (HttpRequest$BodyPublishers/ofByteArray body)))

(def ^:private convert-headers-xf
  (mapcat
    (fn [[k v :as p]]
      (if (sequential? v)
        (interleave (repeat k) v)
        p))))

(defn- method-keyword->str [method]
  (str/upper-case (name method)))

(defn request-builder ^HttpRequest$Builder [opts]
  (let [{:keys [expect-continue?
                headers
                method
                timeout
                uri
                version
                body]} opts]
    (cond-> (HttpRequest/newBuilder)
      (some? expect-continue?) (.expectContinue expect-continue?)
      (seq headers)            (.headers (into-array String (eduction convert-headers-xf headers)))
      method                   (.method (method-keyword->str method) (convert-body-publisher body))
      timeout                  (.timeout (convert-timeout timeout))
      uri                      (.uri (URI/create uri))
      version                  (.version (version-keyword->version-enum version)))))

(defn make-request
  ([] (.build (request-builder {})))
  ([req-map] (.build (request-builder req-map))))

(def ^:private bh-of-string (HttpResponse$BodyHandlers/ofString))
(def ^:private bh-of-input-stream (HttpResponse$BodyHandlers/ofInputStream))
(def ^:private bh-of-byte-array (HttpResponse$BodyHandlers/ofByteArray))

(defn- convert-body-handler [mode]
  (case mode
    nil bh-of-string
    :string bh-of-string
    :input-stream bh-of-input-stream
    :byte-array bh-of-byte-array))

(defn resp->ring [^HttpResponse resp]
  {:status (.statusCode resp)
   :body (.body resp)
   :version (-> resp .version .name)
   :headers (into {}
                  (map (fn [[k v]] [k (if (> (count v) 1) (vec v) (first v))]))
                  (.map (.headers resp)))})

(defn- clj-fn->function ^Function [f]
  (reify Function
    (apply [this x] (f x))))

(def ^:private ^Function resp->ring-function
  (clj-fn->function resp->ring))

(defn- convert-request [req]
  (cond
    (map? req) (make-request req)
    (string? req) (make-request {:uri req})
    (instance? HttpRequest req) req))

(defn send
  ([req]
   (send req {}))
  ([req {:keys [as client raw?] :as opts}]
   (let [^HttpClient client (or client @default-client)
         req' (convert-request req)
         resp (.send client req' (convert-body-handler as))]
     (if raw? resp (resp->ring resp)))))

(defn send-async
  ([req]
   (send-async req {} nil nil))
  ([req opts]
   (send-async req opts nil nil))
  ([req {:keys [as client raw?] :as opts} callback ex-handler]
   (let [^HttpClient client (or client @default-client)
         req' (convert-request req)]
     (cond-> (.sendAsync client req' (convert-body-handler as))
       (not raw?)  (.thenApply resp->ring-function)
       callback    (.thenApply (clj-fn->function callback))
       ex-handler  (.exceptionally (clj-fn->function ex-handler))))))


(defn- shorthand-docstring [method]
  (str "Send a " (method-keyword->str method) " request to `uri`.

   See [[send]] for a description of the `req-map` and `opts` parameters."))

(defn- defshorthand [method]
  `(defn ~(symbol (name method))
     ~(shorthand-docstring method)
     (~['uri]
       (send ~{:uri 'uri :method method} {}))
     (~['uri 'req-map]
       (send (merge ~'req-map ~{:uri 'uri :method method}) {}))
     (~['uri 'req-map 'opts]
       (send (merge ~'req-map ~{:uri 'uri :method method}) ~'opts))))

(defmacro ^:private def-all-shorthands []
  `(do ~@(map #(defshorthand %) [:get :head :post :put :delete])))

(def-all-shorthands)


;; ==============================  DOCSTRINGS  ==============================


(defmacro ^:private add-docstring [var docstring]
  `(alter-meta! ~var #(assoc % :doc ~docstring)))

(add-docstring #'default-client
  "Used for request unless client is explicitly passed. Equivalent to `(make-client)`.")

(add-docstring #'client-builder
  "Same as [[make-client]], but returns a [HttpClient.Builder](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpClient.Builder.html) instead of a [HttpClient](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpClient.html).

  See [[make-client]] for a description of `opts`.")


(add-docstring #'make-client
  "Used to build a client. See [HttpClient.Builder](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpClient.Builder.html) for a more detailed description of the options.

  The `opts` map takes the following keys:

  - `:connect-timeout` - connection timeout in milliseconds or a `java.time.Duration`
  - `:cookie-handler` - a [java.net.CookieHandler](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/CookieHandler.html)
  - `:executor` - a [java.util.concurrent.Executor](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/concurrent/Executor.html)
  - `:follow-redirects` - one of `:always`, `:never` and `:normal`. Maps to the corresponding [HttpClient.Redirect](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpClient.Redirect.html) enum.
  - `:priority` - the [priority](https://developers.google.com/web/fundamentals/performance/http2/#stream_prioritization) of the request (only used for HTTP/2 requests)
  - `:proxy` - a [java.net.ProxySelector](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/net/ProxySelector.html)
  - `:ssl-context` - a [javax.net.ssl.SSLContext](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/javax/net/ssl/SSLContext.html)
  - `:ssl-parameters` - a [javax.net.ssl.SSLParameters](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/javax/net/ssl/SSLParameters.html)
  - `:version` - the HTTP protocol version, one of `:http1.1` or `:http2`

  Equivalent to `(.build (client-builder opts))`.")

(add-docstring #'request-builder
  "Same as [[make-request]], but returns a [HttpRequest.Builder](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html) instead of a [HttpRequest](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpRequest.html).")

(add-docstring #'make-request
  "Constructs a [java.net.http.HttpRequest](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpRequest.html) from a map.

  See [[send]] for a description of `req-map`.

  Equivalent to `(.build (request-builder req-map))`.")

(add-docstring #'send
  "Sends a HTTP request and blocks until a response is returned or the request
  takes longer than the specified `timeout`. If the request times out, a [HttpTimeoutException](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpTimeoutException.html)
  is thrown.

  The `req` parameter can be a either string URL, a request map, or a [java.net.http.HttpRequest](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpRequest.html).

  The request map takes the following keys:

  - `:body` - the request body. Can be either a string, a primitive Java byte array, or a java.io.InputStream.
  - `:expect-continue?` - See the [javadoc](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html#expectContinue%28boolean%29)
  - `:headers` - a map of string to a string or a list of strings
  - `:method` - the HTTP method as a keyword (e.g `:get`, `:put`, `:post`)
  - `:timeout` - the request timeout in milliseconds or a `java.time.Duration`
  - `:uri` - the request uri
  - `:version` - which HTTP protocol to use: one of `:http1.1` or `:http2`

  `opts` is a map containing one of the following keywords:

  `:as` - converts the response body to one of the following formats:
    `:string` - a java.lang.String (the default)
    `:byte-array` - a Java primitive byte array.
    `:input-stream` - a java.io.InputStream.

  `:client` - the `java.net.http.HttpClient` to use for the request. Defaults to [[default-client]].

  `:raw?` - if true, skip the Ring format conversion and return the `java.net.http.HttpResponse`")

(add-docstring #'send-async
  "Sends a request asynchronously and immediately returns a [CompletableFuture](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html). Converts the
   eventual response to a map as per [[resp->ring]].

  See [[send]] for a description of `req` and `opts`.

  `callback` is a one argument function that will be applied to the response map.

  `ex-handler` is a one argument function that will be called if an exception is thrown
   anywhere during the request.")

(add-docstring #'resp->ring
  "Converts a [HttpResponse](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/HttpResponse.html) into a map.

  The response map contains the following keys:

   - `:body` - the response body
   - `:headers` - the response headers (map of string form string or list of string)
   - `:status` - the HTTP status code
   - `:version` - the version of the HTTP protocol that was used (`:http1.1` or `:http2`")
