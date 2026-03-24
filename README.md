# Race condition in `HttpClientResponse.body()` / `end()`

## Summary

`HttpClientResponse.body()` (and `end()`) can permanently hang when used
in a `.compose()` chain after `HttpClient.request().compose(req -> req.send())`.
The `Future` returned by `body()` is never completed, causing the caller to
time out.

Affects **Vert.x 4.5.x** and **5.0.x**. Observed at a rate of roughly
**0.02 %** per request on an idle system with loopback networking.

## Root cause

`HttpClientResponseImpl` lazily creates an internal `HttpEventHandler`
(which holds the `bodyPromise` / `endPromise`) the first time `body()`,
`end()`, `handler()`, or `endHandler()` is called.

`handleEnd(MultiMap)` — called when the response is fully received — reads
this field under `synchronized(conn)` and, if it is still `null`, **silently
does nothing**:

```java
// HttpClientResponseImpl (identical in Vert.x 4 and 5)
void handleEnd(MultiMap trailers) {
    HttpEventHandler handler;
    synchronized (conn) {
        this.trailers = trailers;
        handler = eventHandler;       // ← null if body() hasn't been called yet
    }
    if (handler != null) {
        handler.handleEnd();          // ← skipped!
    }
}
```

`body()` is **not** synchronized on `conn`:

```java
@Override
public Future<Buffer> body() {
    return eventHandler(true).body(); // creates eventHandler + bodyPromise
}
```

Inside `HttpEventHandler.handleEnd()`, the body promise is completed:

```java
void handleEnd() {
    // ...
    if (bodyPromise != null) {
        bodyPromise.tryComplete(body);
    }
    if (endPromise != null) {
        endPromise.tryComplete();
    }
}
```

### The race

When a caller writes:

```java
httpClient.request(options)
    .compose(req -> req.send())
    .compose(resp -> resp.body())   // body() called in a compose callback
    .timeout(3, SECONDS);
```

the following sequence can occur:

1. `req.send()` completes — the response `Future` resolves.
2. The `.compose(resp -> resp.body())` callback is **scheduled** on the
   Vert.x context but has not run yet.
3. Meanwhile, the Netty pipeline has already received the full response
   (for a 204: headers + `LastHttpContent`; for a small 200: headers +
   body chunk + `LastHttpContent` — all in one TCP segment on loopback).
4. `Http1xClientConnection` processes the response end →
   `stream.handleEnd()` → `InboundMessageQueue.write()`.
5. The `InboundMessageQueue` drains and calls
   `HttpClientResponseImpl.handleEnd()`.
6. `handleEnd()` reads `eventHandler` under `synchronized(conn)` — it is
   **`null`** because `body()` has not been called yet.
7. `handleEnd()` silently returns without completing any promise.
8. The `.compose` callback finally runs, calls `resp.body()`, which creates
   a **new** `eventHandler` with a fresh `bodyPromise`.
9. **`bodyPromise` is never completed** — `handleEnd()` already fired and
   will not fire again.
10. The caller times out.

### Why it is rare

Steps 2–8 must interleave in a specific order. With
`setEventLoopPoolSize(1)`, the event loop handles both the I/O completion
and the context task scheduling, making the window slightly wider. On
loopback, small responses arrive in a single read cycle, which increases
the chance that the `InboundMessageQueue` drains the end message before the
compose callback is dispatched.

## Affected response types

| Response | Reproducible? | Why |
|---|---|---|
| 204 No Content (no body) | ✅ most frequent | Headers + `LastHttpContent` in one read cycle |
| 200 + small JSON body | ✅ less frequent | Headers + body + `LastHttpContent` in one TCP segment |
| 200 + large body | ❌ not observed | Body chunks arrive over multiple read cycles; `body()` is called before the last chunk |

## How to reproduce

```bash
# Against Vert.x 5 (default: 5.0.8):
./gradlew test --rerun

# Against Vert.x 4:
./gradlew test --rerun -PvertxVersion=4.5.25
```

The test runs 50 000 iterations × multiple HTTP methods. With
`setEventLoopPoolSize(1)`, it typically fails within a few thousand
requests.

## Possible fix

`handleEnd()` should record that the response has ended (it already sets
`this.trailers`). A subsequent `body()` / `end()` call should check this
flag and immediately complete the promise with the (empty) buffered body.

Alternatively, `body()` could be synchronized on `conn` so that it cannot
race with `handleEnd()`, and `handleEnd()` could always create the
`eventHandler` if it is `null` (ensuring the promise exists before trying
to complete it).

## Environment

- **Vert.x versions**: 4.5.25, 5.0.8 (likely all 4.x and 5.x)
- **Java**: 21
- **CPU**: AMD Ryzen 9 7950X
- **OS**: Linux 6.17.0-19-generic (amd64)
- **Networking**: loopback (127.0.0.1)
