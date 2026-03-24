package reproducer;

import io.vertx.core.Future;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientBuilder;
import io.vertx.core.http.HttpClientRequest;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Minimal compatibility shim so the reproducer works against both Vert.x 4 and 5. Detects the
 * version at class-load time via the return type of {@code HttpClientRequest.reset()}.
 */
public final class VertxCompat {
  private VertxCompat() {}

  private static final MethodHandle HTTP_CLIENT_BUILDER_BUILD;
  private static final BlockingGet BLOCKING_GET;

  interface BlockingGet {
    <T> T get(Future<T> future, long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException;

    <T> T join(Future<T> future) throws ExecutionException, InterruptedException, TimeoutException;
  }

  static {
    try {
      var resetMethod = HttpClientRequest.class.getMethod("reset");
      var lookup = MethodHandles.lookup();

      if (resetMethod.getReturnType() == boolean.class) {
        // Vert.x 4
        HTTP_CLIENT_BUILDER_BUILD =
            lookup.findVirtual(
                HttpClientBuilder.class, "build", MethodType.methodType(HttpClient.class));
        BLOCKING_GET =
            new BlockingGet() {
              @Override
              public <T> T get(Future<T> future, long timeout, TimeUnit unit)
                  throws InterruptedException, ExecutionException, TimeoutException {
                return future.toCompletionStage().toCompletableFuture().get(timeout, unit);
              }

              @Override
              public <T> T join(Future<T> future)
                  throws ExecutionException, InterruptedException, TimeoutException {
                try {
                  return future.toCompletionStage().toCompletableFuture().get();
                } catch (ExecutionException e) {
                  if (e.getCause() instanceof TimeoutException te) {
                    throw te;
                  }
                  throw e;
                }
              }
            };
      } else if (resetMethod.getReturnType() == Future.class) {
        // Vert.x 5
        var httpClientAgentClass = Class.forName("io.vertx.core.http.HttpClientAgent");
        HTTP_CLIENT_BUILDER_BUILD =
            lookup.findVirtual(
                HttpClientBuilder.class, "build", MethodType.methodType(httpClientAgentClass));
        var awaitWithTimeout =
            lookup.findVirtual(
                Future.class,
                "await",
                MethodType.methodType(Object.class, long.class, TimeUnit.class));
        var awaitNoArgs =
            lookup.findVirtual(Future.class, "await", MethodType.methodType(Object.class));
        BLOCKING_GET =
            new BlockingGet() {
              @Override
              @SuppressWarnings("unchecked")
              public <T> T get(Future<T> future, long timeout, TimeUnit unit)
                  throws TimeoutException {
                try {
                  return (T) awaitWithTimeout.invoke(future, timeout, unit);
                } catch (TimeoutException e) {
                  throw e;
                } catch (RuntimeException e) {
                  throw e;
                } catch (Throwable e) {
                  throw new RuntimeException(e);
                }
              }

              @Override
              @SuppressWarnings("unchecked")
              public <T> T join(Future<T> future) {
                try {
                  return (T) awaitNoArgs.invoke(future);
                } catch (RuntimeException e) {
                  throw e;
                } catch (Throwable e) {
                  throw new RuntimeException(e);
                }
              }
            };
      } else {
        throw new RuntimeException("Cannot determine Vert.x version (4 or 5)");
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static HttpClient buildHttpClient(HttpClientBuilder builder) {
    try {
      return (HttpClient) HTTP_CLIENT_BUILDER_BUILD.bindTo(builder).invoke();
    } catch (RuntimeException e) {
      throw e;
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  public static <T> T blockingGet(Future<T> future, long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    return BLOCKING_GET.get(future, timeout, unit);
  }

  public static <T> T blockingGet(Future<T> future)
      throws InterruptedException, ExecutionException, TimeoutException {
    return BLOCKING_GET.join(future);
  }
}

