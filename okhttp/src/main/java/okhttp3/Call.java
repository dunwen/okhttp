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

/**
 * A call is a request that has been prepared for execution. A call can be canceled. As this object
 * represents a single request/response pair (stream), it cannot be executed twice.
 *
 * 一个call就是一个已经准备好执行的请求，一个call可以被取消，这个对象代表一对请求和接收响应动作，它不能被执行两次。
 *
 */
public interface Call {
  /** Returns the original request that initiated this call.
   * 返回构造这个call的原始请求
   * */
  Request request();

  /**
   * Invokes the request immediately, and blocks until the response can be processed or is in
   * error.
   *
   * 立即执行这个请求，同时会使线程阻塞直到收到响应或者请求异常
   *
   * <p>The caller may read the response body with the response's {@link Response#body} method.  To
   * facilitate connection recycling, callers should always {@link ResponseBody#close() close the
   * response body}.
   *
   * 调用者可以使用response的方法去读取返回来的response。为了更好的促进连接回收，调用者应该记得滴啊用close方法
   * 关闭这个response对象
   *
   * <p>Note that transport-layer success (receiving a HTTP response code, headers and body) does
   * not necessarily indicate application-layer success: {@code response} may still indicate an
   * unhappy HTTP response code like 404 or 500.
   *
   * @throws IOException if the request could not be executed due to cancellation, a connectivity
   * problem or timeout. Because networks can fail during an exchange, it is possible that the
   * remote server accepted the request before the failure. 如果因为取消，连通性问题或者连接超时倒置调用失败，将会抛出ioExxx异常
   *
   *
   * @throws IllegalStateException when the call has already been executed. call已经被执行了会抛出illxxxx异常
   *
   */
  Response execute() throws IOException;

  /**
   * Schedules the request to be executed at some point in the future.
   * 设置回调接口，分发执行成功或者执行失败的处理
   *
   * <p>The {@link OkHttpClient#dispatcher dispatcher} defines when the request will run: usually
   * immediately unless there are several other requests currently being executed.
   *
   * <p>This client will later call back {@code responseCallback} with either an HTTP response or a
   * failure exception.
   *
   * @throws IllegalStateException when the call has already been executed. 当call已经在执行的时候，竟会抛出illxxxx异常
   */
  void enqueue(Callback responseCallback);

  /** Cancels the request, if possible. Requests that are already complete cannot be canceled.
   * 取消这个请求，但是也有可能请求已经结束了，这时候取消并没什么暖用
   * */
  void cancel();

  /**
   * Returns true if this call has been either {@linkplain #execute() executed} or {@linkplain
   * #enqueue(Callback) enqueued}. It is an error to execute a call more than once.
   * 木有调用execute或者enqueue这两个方法的话就返回false，否则返回true，用于检验是否发起了两个相同的call
   */
  boolean isExecuted();

  boolean isCanceled();

  interface Factory {
    Call newCall(Request request);
  }
}
