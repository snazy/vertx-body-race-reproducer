package reproducer;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

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
 * <p>The natural race is easiest to trigger with body-less responses (204 No Content) because the
 * entire response — headers plus {@code LastHttpContent} — arrives in a single Netty read cycle.
 * It also reproduces with small response bodies on loopback.
 *
 * <p>This test does not rely on natural timing luck: it waits for the internal state where
 * {@code handleEnd(...)} already ran ({@code trailers != null}) while {@code eventHandler} is
 * still {@code null}, then invokes {@code body()}.
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
  private static final int EVENT_LOOP_POOL_SIZE =
      Math.max(1, Integer.getInteger("reproducer.eventLoopPoolSize", 1));
  private static final int ITERATIONS = Integer.getInteger("reproducer.iterations", 5_000);
  private static final int REQUEST_TIMEOUT_MS = Integer.getInteger("reproducer.timeoutMs", 1_000);
  private static final int WAIT_FOR_END_MS = Integer.getInteger("reproducer.waitForEndMs", 200);
  private static final int POLL_INTERVAL_MS =
      Math.max(1, Integer.getInteger("reproducer.pollIntervalMs", 1));

  private static final Class<?> RESPONSE_IMPL_CLASS;
  private static final Field EVENT_HANDLER_FIELD;
  private static final Field TRAILERS_FIELD;

  private static Vertx vertx;
  private static HttpClient httpClient;
  private static HttpServer server;
  private static int serverPort;

  static {
    try {
      RESPONSE_IMPL_CLASS = Class.forName("io.vertx.core.http.impl.HttpClientResponseImpl");
      EVENT_HANDLER_FIELD = findField("eventHandler");
      TRAILERS_FIELD = findField("trailers");
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeAll
  static void setUp() throws Exception {
    vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(EVENT_LOOP_POOL_SIZE));
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

  @ParameterizedTest
  @CsvSource({
          "GET, /no-body",
          "GET, /with-resp-body",
//          "HEAD, /no-body",
//          "HEAD, /with-resp-body",
//          "DELETE, /no-body",
//          "DELETE, /with-resp-body"
  })
  void testCase(HttpMethod method, String path) {
      runIterations(method, path);
  }

  private static void runIterations(HttpMethod method, String path) {
    for (int i = 1; i <= ITERATIONS; i++) {
      try {
        executeRequest(method, path);
      } catch (Throwable t) {
        throw new AssertionError(
            "Failure on "
                + method
                + " "
                + path
                + " #"
                + i
                + " (eventLoopPoolSize="
                + EVENT_LOOP_POOL_SIZE
                + ", timeoutMs="
                + REQUEST_TIMEOUT_MS
                + ", waitForEndMs="
                + WAIT_FOR_END_MS
                + ", pollIntervalMs="
                + POLL_INTERVAL_MS
                + ")",
            t);
      }
    }
  }

  private static void executeRequest(HttpMethod method, String path) throws Exception {
    VertxCompat.blockingGet(newRequestFuture(method, path));
  }

  private static Future<Buffer> newRequestFuture(HttpMethod method, String path) {
    var options =
        new RequestOptions()
            .setHost("127.0.0.1")
            .setPort(serverPort)
            .setMethod(method)
            .setURI(path);
    return httpClient
        .request(options)
        .compose(HttpClientRequest::send)
        .compose(HttpClientResponseBodyRaceTest::invokeBodyAfterEndWithoutHandler)
        .timeout(REQUEST_TIMEOUT_MS, MILLISECONDS);
  }

  /**
   * Waits for the internal state where:
   *
   * <ul>
   *   <li>response trailers are already set (end has been processed)</li>
   *   <li>eventHandler is still null (body()/end()/handler() never called)</li>
   * </ul>
   *
   * <p>Calling {@code response.body()} in that state deterministically exposes the race in affected
   * Vert.x versions.
   */
  private static Future<Buffer> invokeBodyAfterEndWithoutHandler(HttpClientResponse response) {
    Promise<Buffer> promise = Promise.promise();
    long deadlineNanos = System.nanoTime() + MILLISECONDS.toNanos(WAIT_FOR_END_MS);
    waitForEndedWithoutHandler(response, promise, deadlineNanos);
    return promise.future();
  }

  private static void waitForEndedWithoutHandler(
      HttpClientResponse response, Promise<Buffer> promise, long deadlineNanos) {
    if (promise.future().isComplete()) {
      return;
    }
    try {
      if (hasEndedWithoutHandler(response)) {
        response.body().onComplete(promise);
        return;
      }
    } catch (Throwable t) {
      promise.tryFail(t);
      return;
    }
    if (System.nanoTime() >= deadlineNanos) {
      promise.tryFail(
          new IllegalStateException(
              "Did not observe ended-without-handler state; " + responseDebugState(response)));
      return;
    }
    vertx.setTimer(
        POLL_INTERVAL_MS,
        ignored -> waitForEndedWithoutHandler(response, promise, deadlineNanos));
  }

  private static boolean hasEndedWithoutHandler(HttpClientResponse response) throws Exception {
    Object trailers = TRAILERS_FIELD.get(response);
    Object eventHandler = EVENT_HANDLER_FIELD.get(response);
    return trailers != null && eventHandler == null;
  }

  private static String responseDebugState(HttpClientResponse response) {
    try {
      Object trailers = TRAILERS_FIELD.get(response);
      Object eventHandler = EVENT_HANDLER_FIELD.get(response);
      return "responseClass="
          + response.getClass().getName()
          + ", trailers="
          + (trailers != null)
          + ", eventHandler="
          + (eventHandler != null);
    } catch (Exception e) {
      return "responseClass=" + response.getClass().getName() + ", stateError=" + e;
    }
  }

  private static Field findField(String fieldName) {
    Class<?> current = RESPONSE_IMPL_CLASS;
    while (current != null) {
      try {
        Field field = current.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
      } catch (NoSuchFieldException ignored) {
        current = current.getSuperclass();
      }
    }
    throw new RuntimeException(RESPONSE_IMPL_CLASS.getName() + "#" + fieldName);
  }
}
