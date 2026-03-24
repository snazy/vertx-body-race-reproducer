package reproducer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Reproducer for a race condition in {@code HttpClientResponse.body()}.
 *
 * <h2>Bug summary</h2>
 *
 * {@code HttpClientResponseImpl.handleEnd()} reads the lazily-created {@code eventHandler} field
 * under {@code synchronized(conn)}. If the field is still {@code null}, the method silently does
 * nothing — no body promise is completed, no end handler is called.
 *
 * <p>The {@code eventHandler} is created lazily by {@code body()} (or {@code end()}, {@code
 * handler()}, etc.). When the caller uses a {@code .compose(resp -> resp.body())} chain, the {@code
 * body()} call happens in a compose callback that is scheduled on the context. If the {@code
 * InboundMessageQueue} drains the end-of-response message <b>before</b> that compose callback
 * runs, {@code handleEnd()} sees {@code eventHandler == null} and drops the completion signal. The
 * subsequently created {@code bodyPromise} is then never completed, causing a timeout.
 *
 * <p>The race is easiest to trigger with body-less responses (204 No Content) because the entire
 * response — headers plus {@code LastHttpContent} — arrives in a single Netty read cycle. However,
 * it also reproduces with small response bodies on loopback, where headers, body chunk, and {@code
 * LastHttpContent} are all delivered in one TCP segment and drained before the compose callback
 * runs.
 *
 * <p>Observed on both Vert.x 4.5.x and 5.0.x at a rate of ~0.02% on an idle system with loopback
 * networking.
 *
 * <h2>How to run</h2>
 *
 * <pre>
 *   # Against Vert.x 5 (default):
 *   ./gradlew test --rerun
 *
 *   # Against Vert.x 4:
 *   ./gradlew test --rerun -PvertxVersion=4.5.25
 * </pre>
 */
class HttpClientResponseBodyRaceTest {

  private static final String RESPONSE_BODY = "{\"status\":\"ok\"}";

  private static Vertx vertx;
  private static HttpClient httpClient;
  private static HttpServer server;
  private static int serverPort;

  @BeforeAll
  static void setUp() throws Exception {
    vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
    httpClient = VertxCompat.buildHttpClient(vertx.httpClientBuilder());

    var portRef = new AtomicInteger();
    var latch = new CountDownLatch(1);

    server =
        vertx
            .createHttpServer()
            .requestHandler(
                req -> {
                  var resp = req.response();
                  if (req.path().startsWith("/with-resp-body")) {
                    resp.putHeader("Content-Type", "application/json")
                        .setStatusCode(200)
                        .end(Buffer.buffer(RESPONSE_BODY, UTF_8.name()));
                  } else {
                    resp.setStatusCode(204).end();
                  }
                });
    server
        .listen(0)
        .onSuccess(
            s -> {
              portRef.set(s.actualPort());
              latch.countDown();
            });
    assertThat(latch.await(5, SECONDS)).isTrue();
    serverPort = portRef.get();
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (httpClient != null) {
      VertxCompat.blockingGet(httpClient.close());
    }
    if (server != null) {
      VertxCompat.blockingGet(server.close());
    }
    if (vertx != null) {
      VertxCompat.blockingGet(vertx.close());
    }
  }

  /**
   * No response body (204). The entire response — headers + {@code LastHttpContent} — arrives in
   * one Netty read cycle, making the race between {@code handleEnd()} and {@code body()} very
   * likely.
   */
  @TestFactory
  Stream<DynamicTest> noResponseBody() {
    var methods = new HttpMethod[] {HttpMethod.GET, HttpMethod.HEAD, HttpMethod.DELETE};
    return iterations(methods, "/no-body");
  }

  /**
   * Small response body (200 + JSON). On loopback, headers + body + {@code LastHttpContent} fit in
   * a single TCP segment and are drained in one batch, triggering the same race.
   */
  @TestFactory
  Stream<DynamicTest> withResponseBody() {
    var methods = new HttpMethod[] {HttpMethod.GET, HttpMethod.DELETE};
    return iterations(methods, "/with-resp-body");
  }

  private static Stream<DynamicTest> iterations(HttpMethod[] methods, String path) {
    int count = 50_000;
    return IntStream.rangeClosed(1, count)
        .boxed()
        .flatMap(
            i ->
                Stream.of(methods)
                    .map(
                        method ->
                            DynamicTest.dynamicTest(
                                method + " " + path + " #" + i,
                                () -> executeRequest(method, path))));
  }

  private static void executeRequest(HttpMethod method, String path) throws Exception {
    var options =
        new RequestOptions()
            .setHost("127.0.0.1")
            .setPort(serverPort)
            .setMethod(method)
            .setURI(path);
    var future =
        httpClient
            .request(options)
            .compose(req -> req.send())
            .compose(resp -> resp.body())
            .timeout(3, SECONDS);
    VertxCompat.blockingGet(future);
  }
}

