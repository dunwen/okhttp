/*
 * Copyright (C) 2013 Square, Inc.
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

import java.net.URL;
import java.util.List;
import okhttp3.internal.http.HttpMethod;

/**
 * An HTTP request. Instances of this class are immutable if their {@link #body} is null or itself
 * immutable.
 */
public final class Request {
  private final HttpUrl url;
  private final String method;
  private final Headers headers;
  private final RequestBody body;
  private final Object tag;

  private volatile CacheControl cacheControl; // Lazily initialized.

  private Request(Builder builder) {
    this.url = builder.url;
    this.method = builder.method;
    this.headers = builder.headers.build();
    this.body = builder.body;
    this.tag = builder.tag != null ? builder.tag : this;
  }

  public HttpUrl url() {
    return url;
  }

  public String method() {
    return method;
  }

  public Headers headers() {
    return headers;
  }

  public String header(String name) {
    return headers.get(name);
  }

  public List<String> headers(String name) {
    return headers.values(name);
  }

  public RequestBody body() {
    return body;
  }

  public Object tag() {
    return tag;
  }

  public Builder newBuilder() {
    return new Builder(this);
  }

  /**
   * Returns the cache control directives for this response. This is never null, even if this
   * response contains no {@code Cache-Control} header.
   */
  public CacheControl cacheControl() {
    CacheControl result = cacheControl;
    return result != null ? result : (cacheControl = CacheControl.parse(headers));
  }

  public boolean isHttps() {
    return url.isHttps();
  }

  @Override public String toString() {
    return "Request{method="
        + method
        + ", url="
        + url
        + ", tag="
        + (tag != this ? tag : null)
        + '}';
  }

  public static class Builder {
    private HttpUrl url;
    private String method;
    private Headers.Builder headers;
    private RequestBody body;
    private Object tag;

    public Builder() {
      this.method = "GET";
      this.headers = new Headers.Builder();
    }

    private Builder(Request request) {
      this.url = request.url;
      this.method = request.method;
      this.body = request.body;
      this.tag = request.tag;
      this.headers = request.headers.newBuilder();
    }

    public Builder url(HttpUrl url) {
      if (url == null) throw new NullPointerException("url == null");
      this.url = url;
      return this;
    }

    /**
     * Sets the URL target of this request.
     * //设置url，可以使用{@link HttpUrl#parse}避免抛出异常
     *
     * @throws IllegalArgumentException if {@code url} is not a valid HTTP or HTTPS URL. Avoid this
     * exception by calling {@link HttpUrl#parse}; it returns null for invalid URLs.
     */
    public Builder url(String url) {
      if (url == null) throw new NullPointerException("url == null");

      // Silently replace websocket URLs with HTTP URLs.
      //把websocket url 替换成http的url
      if (url.regionMatches(true, 0, "ws:", 0, 3)) {
        url = "http:" + url.substring(3);
      } else if (url.regionMatches(true, 0, "wss:", 0, 4)) {
        url = "https:" + url.substring(4);
      }

      HttpUrl parsed = HttpUrl.parse(url);
      if (parsed == null) throw new IllegalArgumentException("unexpected url: " + url);
      return url(parsed);
    }

    /**
     * Sets the URL target of this request.
     *
     * @throws IllegalArgumentException if the scheme of {@code url} is not {@code http} or {@code
     * https}.
     */
    public Builder url(URL url) {
      if (url == null) throw new NullPointerException("url == null");
      HttpUrl parsed = HttpUrl.get(url);
      if (parsed == null) throw new IllegalArgumentException("unexpected url: " + url);
      return url(parsed);
    }

    /**
     * Sets the header named {@code name} to {@code value}. If this request already has any headers
     * with that name, they are all replaced.
     *
     * 设置请求头，如果请求头相同，则将会被替换
     * 在内部使用了Header类维护这些header信息
     * 注意和addHeader区分使用
     *
     */
    public Builder header(String name, String value) {
      headers.set(name, value);
      return this;
    }

    /**
     * Adds a header with {@code name} and {@code value}. Prefer this method for multiply-valued
     * headers like "Cookie".
     * 添加header，这个方法设置重复的header比如cookie（可能会有多个cookie），如果这时候用header这个方法，
     * 添加同样的name的header将会被替换掉之前的，使用这个方法就不会，，
     *
     * <p>Note that for some headers including {@code Content-Length} and {@code Content-Encoding},
     * OkHttp may replace {@code value} with a header derived from the request body.
     */
    public Builder addHeader(String name, String value) {
      headers.add(name, value);
      return this;
    }

    public Builder removeHeader(String name) {
      headers.removeAll(name);
      return this;
    }

    /** Removes all headers on this builder and adds {@code headers}. */
    public Builder headers(Headers headers) {
      this.headers = headers.newBuilder();
      return this;
    }

    /**
     * Sets this request's {@code Cache-Control} header, replacing any cache control headers already
     * present. If {@code cacheControl} doesn't define any directives, this clears this request's
     * cache-control headers.
     * 设置这个缓存指令
     * CacheControl.FORCE_NETWORK  或者CacheControl.FORCE_CACHE 使用两个现成的，当然，你也可以自己new一个
     * 这个header参考 baike.baidu.com/link?url=OOugEHmFF-kLu4ZYEWeSjOa9o7pPVs0oDmhdbN--Omj1DKFpfXl24uPfgbWj_lyXzsIoFQ17ovxwvnEBv8CcBq
     */
    public Builder cacheControl(CacheControl cacheControl) {
      String value = cacheControl.toString();
      if (value.isEmpty()) return removeHeader("Cache-Control");
      return header("Cache-Control", value);
    }

    /*
    * GET
      通过请求URI得到资源
      POST,
      用于添加新的内容
      PUT
      用于修改某个内容
      DELETE,
      删除某个内容
      CONNECT,
      用于代理进行传输，如使用SSL
      OPTIONS
      询问可以执行哪些方法
      PATCH,
      部分文档更改
      PROPFIND, (wedav)
      查看属性
      PROPPATCH, (wedav)
      设置属性
      MKCOL, (wedav)
      创建集合（文件夹）
      COPY, (wedav)
      拷贝
      MOVE, (wedav)
      移动
      LOCK, (wedav)
      加锁
      UNLOCK (wedav)
      解锁
      TRACE
      用于远程诊断服务器
      HEAD
      类似于GET, 但是不返回body信息，用于检查对象是否存在，以及得到对象的元数据
    * */
    public Builder get() {
      return method("GET", null);
    }

    public Builder head() {
      return method("HEAD", null);
    }

    public Builder post(RequestBody body) {
      return method("POST", body);
    }

    public Builder delete(RequestBody body) {
      return method("DELETE", body);
    }

    public Builder delete() {
      return delete(RequestBody.create(null, new byte[0]));
    }

    public Builder put(RequestBody body) {
      return method("PUT", body);
    }

    public Builder patch(RequestBody body) {
      return method("PATCH", body);
    }


    /**
     *这个requestBody，可以使用在它内部的工厂方法create构造我们需要的request body，
     * 同时，也可以使用FromBody.builder 或者 MultipartBody.builder 分别构造表单body或者复杂的body
     * */
    public Builder method(String method, RequestBody body) {
      if (method == null) throw new NullPointerException("method == null");
      if (method.length() == 0) throw new IllegalArgumentException("method.length() == 0");
      if (body != null && !HttpMethod.permitsRequestBody(method)) {
        throw new IllegalArgumentException("method " + method + " must not have a request body.");
      }
      if (body == null && HttpMethod.requiresRequestBody(method)) {
        throw new IllegalArgumentException("method " + method + " must have a request body.");
      }
      this.method = method;
      this.body = body;
      return this;
    }

    /**
     * Attaches {@code tag} to the request. It can be used later to cancel the request. If the tag
     * is unspecified or null, the request is canceled by using the request itself as the tag.
     *
     * 给请求依附一个tag，这个tag的作用有比如识别唯一的请求或者取消这个请求，如果请求木有指定或者指定为null，
     * 那么request的tag就是这个request
     *
     */
    public Builder tag(Object tag) {
      this.tag = tag;
      return this;
    }

    public Request build() {
      if (url == null) throw new IllegalStateException("url == null");
      return new Request(this);
    }
  }
}
