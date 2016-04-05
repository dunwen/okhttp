/*
 * Copyright (C) 2014 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.logging.Level;
import okhttp3.internal.NamedRunnable;
import okhttp3.internal.http.HttpEngine;
import okhttp3.internal.http.RequestException;
import okhttp3.internal.http.RouteException;
import okhttp3.internal.http.StreamAllocation;

import static okhttp3.internal.Internal.logger;
import static okhttp3.internal.http.HttpEngine.MAX_FOLLOW_UPS;

final class RealCall implements Call {
  private final OkHttpClient client;

  // Guarded by this.
  private boolean executed;
  volatile boolean canceled;

  /** The application's original request unadulterated by redirects or auth headers. */
  Request originalRequest;
  HttpEngine engine;

  protected RealCall(OkHttpClient client, Request originalRequest) {
    this.client = client;
    this.originalRequest = originalRequest;
  }

  @Override public Request request() {
    return originalRequest;
  }


  /**
   * 同步请求
   * */
  @Override public Response execute() throws IOException {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    try {
      client.dispatcher().executed(this);//这个方法单纯的把当前的call加入队列

      //那么请重点关注这里，这里完成了整个网络的所有逻辑
      Response result = getResponseWithInterceptorChain(false);
      if (result == null) throw new IOException("Canceled");
      return result;
    } finally {
      client.dispatcher().finished(this);
    }
  }

  Object tag() {
    return originalRequest.tag();
  }

  @Override public void enqueue(Callback responseCallback) {
    enqueue(responseCallback, false);
  }

  void enqueue(Callback responseCallback, boolean forWebSocket) {
    synchronized (this) {
      if (executed) throw new IllegalStateException("Already Executed");
      executed = true;
    }
    client.dispatcher().enqueue(new AsyncCall(responseCallback, forWebSocket));
  }

  @Override public void cancel() {
    canceled = true;
    if (engine != null) engine.cancel();
  }

  @Override public synchronized boolean isExecuted() {
    return executed;
  }

  @Override public boolean isCanceled() {
    return canceled;
  }

  final class AsyncCall extends NamedRunnable {
    private final Callback responseCallback;
    private final boolean forWebSocket;

    private AsyncCall(Callback responseCallback, boolean forWebSocket) {
      super("OkHttp %s", originalRequest.url().toString());
      this.responseCallback = responseCallback;
      this.forWebSocket = forWebSocket;
    }

    String host() {
      return originalRequest.url().host();
    }

    Request request() {
      return originalRequest;
    }

    Object tag() {
      return originalRequest.tag();
    }

    void cancel() {
      RealCall.this.cancel();
    }

    RealCall get() {
      return RealCall.this;
    }

    @Override protected void execute() {
      boolean signalledCallback = false;
      try {
        Response response = getResponseWithInterceptorChain(forWebSocket);
        if (canceled) {
          signalledCallback = true;
          responseCallback.onFailure(RealCall.this, new IOException("Canceled"));
        } else {
          signalledCallback = true;
          responseCallback.onResponse(RealCall.this, response);
        }
      } catch (IOException e) {
        if (signalledCallback) {
          // Do not signal the callback twice!
          logger.log(Level.INFO, "Callback failure for " + toLoggableString(), e);
        } else {
          responseCallback.onFailure(RealCall.this, e);
        }
      } finally {
        client.dispatcher().finished(this);
      }
    }
  }

  /**
   * Returns a string that describes this call. Doesn't include a full URL as that might contain
   * sensitive information.
   */
  private String toLoggableString() {
    String string = canceled ? "canceled call" : "call";
    HttpUrl redactedUrl = originalRequest.url().resolve("/...");
    return string + " to " + redactedUrl;
  }

  private Response getResponseWithInterceptorChain(boolean forWebSocket) throws IOException {
    Interceptor.Chain chain = new ApplicationInterceptorChain(0, originalRequest, forWebSocket);
    //跟进这里
    return chain.proceed(originalRequest);
  }

  class ApplicationInterceptorChain implements Interceptor.Chain {
    private final int index;
    private final Request request;
    private final boolean forWebSocket;

    ApplicationInterceptorChain(int index, Request request, boolean forWebSocket) {
      this.index = index;
      this.request = request;
      this.forWebSocket = forWebSocket;
    }

    @Override public Connection connection() {
      return null;
    }

    @Override public Request request() {
      return request;
    }

    @Override public Response proceed(Request request) throws IOException {
      // If there's another interceptor in the chain, call that.
      //如果在client里面add了一个拦截器（这个拦截器记得调用China.process方法，不然就木有response返回了），那么就调用那个拦截器
      //但是如果我设置了多个拦截器呢？只会执行一个？
      if (index < client.interceptors().size()) {
        Interceptor.Chain chain = new ApplicationInterceptorChain(index + 1, request, forWebSocket);
        Interceptor interceptor = client.interceptors().get(index);
        Response interceptedResponse = interceptor.intercept(chain);

        if (interceptedResponse == null) {
          throw new NullPointerException("application interceptor " + interceptor
              + " returned null");
        }

        return interceptedResponse;
      }

      // No more interceptors. Do HTTP.

      //这里执行http
      return getResponse(request, forWebSocket);
    }
  }

  /**
   * Performs the request and returns the response. May return null if this call was canceled.
   * 执行请求并返回响应，如果call被取消了，那么返回null
   */
  Response getResponse(Request request, boolean forWebSocket) throws IOException {
    // Copy body metadata to the appropriate request headers.
    RequestBody body = request.body();
    if (body != null) {
      Request.Builder requestBuilder = request.newBuilder();

      MediaType contentType = body.contentType();
      if (contentType != null) {
        requestBuilder.header("Content-Type", contentType.toString());
      }

      long contentLength = body.contentLength();
      if (contentLength != -1) {
        requestBuilder.header("Content-Length", Long.toString(contentLength));
        requestBuilder.removeHeader("Transfer-Encoding");
      } else {
        requestBuilder.header("Transfer-Encoding", "chunked");
        requestBuilder.removeHeader("Content-Length");
      }

      request = requestBuilder.build();
    }

    // Create the initial HTTP engine. Retries and redirects need new engine for each attempt.
    engine = new HttpEngine(client, request, false, false, forWebSocket, null, null, null);

    int followUpCount = 0;
    while (true) {
      if (canceled) {
        engine.releaseStreamAllocation();
        throw new IOException("Canceled");
      }

      boolean releaseConnection = true;
      try {
        //0.对request进行了一次额外封装，增加了几个常用的请求头，使用cookiejar，往请求里面增加cookie 等等
        //1.判断是否只使用缓存，若是，直接设置结果，否则，进行下一步网络请求
        //2.现在需要通过网络完成这个请求，但是也有可能也许只是验证一个conditional GET，反正就是正规的网络请求开始
        //3.重点关注connect();逻辑
        engine.sendRequest();


        engine.readResponse();
        releaseConnection = false;
      } catch (RequestException e) {
        // The attempt to interpret the request failed. Give up.
        throw e.getCause();
      } catch (RouteException e) {
        // The attempt to connect via a route failed. The request will not have been sent.
        HttpEngine retryEngine = engine.recover(e.getLastConnectException(), null);
        if (retryEngine != null) {
          releaseConnection = false;
          engine = retryEngine;
          continue;
        }
        // Give up; recovery is not possible.
        throw e.getLastConnectException();
      } catch (IOException e) {
        // An attempt to communicate with a server failed. The request may have been sent.
        HttpEngine retryEngine = engine.recover(e, null);
        if (retryEngine != null) {
          releaseConnection = false;
          engine = retryEngine;
          continue;
        }

        // Give up; recovery is not possible.
        throw e;
      } finally {
        // We're throwing an unchecked exception. Release any resources.
        if (releaseConnection) {
          StreamAllocation streamAllocation = engine.close();
          streamAllocation.release();
        }
      }

      Response response = engine.getResponse();
      Request followUp = engine.followUpRequest();

      //如果链接没有需要继续跟下去的请求，那么就返回这个response了。到这里，整个请求完成。
      if (followUp == null) {
        if (!forWebSocket) {
          engine.releaseStreamAllocation();
        }
        return response;
      }


      //以下如果还有下一级请求，那么根据下一级请求，继续循环
      StreamAllocation streamAllocation = engine.close();

      if (++followUpCount > MAX_FOLLOW_UPS) {
        streamAllocation.release();
        throw new ProtocolException("Too many follow-up requests: " + followUpCount);
      }

      if (!engine.sameConnection(followUp.url())) {
        streamAllocation.release();
        streamAllocation = null;
      }

      request = followUp;
      engine = new HttpEngine(client, request, false, false, forWebSocket, streamAllocation, null,
          response);
    }
  }
}
