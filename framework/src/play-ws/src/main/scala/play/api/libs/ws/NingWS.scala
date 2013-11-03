package play.api.libs.ws


import com.ning.http.client.{Response => AHCResponse, Cookie => AHCCookie, ProxyServer => AHCProxyServer, _}

import com.ning.http.util.AsyncHttpProviderUtils
import com.ning.http.client.Realm.{RealmBuilder, AuthScheme}


import collection.immutable.TreeMap

import scala.concurrent.{Future, Promise}

import java.io.File
import java.util.concurrent.atomic.AtomicReference

import play.api.http.{Writeable, ContentTypeOf}
import play.api.libs.iteratee._
import play.api.libs.iteratee.Input.El

import play.core.utils.CaseInsensitiveOrdered
import play.core.Execution.Implicits.internalContext
import play.api.{Application, Play}


class NingWSClient(config: AsyncHttpClientConfig) extends AsyncHttpClient with WSClient {
  def underlying: AnyRef = this
}

/**
 * A WS Request.
 */
class NingWSRequest(app: Application, _method: String, _auth: Option[(String, String, WSAuthScheme)], _calc: Option[WSSignatureCalculator])
  extends RequestBuilderBase[NingWSRequest](classOf[NingWSRequest], _method, false)
  with WSRequest {

  import scala.collection.JavaConverters._

  def getStringData : String = body.getOrElse("")

  protected var body: Option[String] = None

  override def setBody(s: String) = {
    this.body = Some(s)
    super.setBody(s)
  }

  protected var calculator: Option[WSSignatureCalculator] = _calc

  protected var headers: Map[String, Seq[String]] = Map()

  protected var _url: String = null

  //this will do a java mutable set hence the {} response
  _auth.map(data => auth(data._1, data._2, authScheme(data._3))).getOrElse({})

  private def authScheme(scheme: WSAuthScheme): AuthScheme = scheme match {
    case WSAuthScheme.DIGEST => AuthScheme.DIGEST
    case WSAuthScheme.BASIC => AuthScheme.BASIC
    case WSAuthScheme.NTLM => AuthScheme.NTLM
    case WSAuthScheme.SPNEGO => AuthScheme.SPNEGO
    case WSAuthScheme.KERBEROS => AuthScheme.KERBEROS
    case WSAuthScheme.NONE => AuthScheme.NONE
    case _ => throw new RuntimeException("Unknown scheme " + scheme)
  }

  /**
   * Add http auth headers. Defaults to HTTP Basic.
   */
  private def auth(username: String, password: String, scheme: AuthScheme = AuthScheme.BASIC): WSRequest = {
    this.setRealm((new RealmBuilder)
      .setScheme(scheme)
      .setPrincipal(username)
      .setPassword(password)
      .setUsePreemptiveAuth(true)
      .build())
  }

  /**
   * Return the current headers of the request being constructed
   */
  def allHeaders: Map[String, Seq[String]] = {
    mapAsScalaMapConverter(request.asInstanceOf[com.ning.http.client.Request].getHeaders()).asScala.map(e => e._1 -> e._2.asScala.toSeq).toMap
  }

  /**
   * Return the current query string parameters
   */
  def queryString: Map[String, Seq[String]] = {
    mapAsScalaMapConverter(request.asInstanceOf[com.ning.http.client.Request].getParams()).asScala.map(e => e._1 -> e._2.asScala.toSeq).toMap
  }

  /**
   * Retrieve an HTTP header.
   */
  def header(name: String): Option[String] = headers.get(name).flatMap(_.headOption)

  /**
   * The HTTP method.
   */
  def method: String = _method

  /**
   * The URL
   */
  def url: String = _url

  /**
   * Set an HTTP header.
   */
  override def setHeader(name: String, value: String) : NingWSRequest = {
    headers = headers + (name -> List(value))
    super.setHeader(name, value)
  }

  /**
   * Add an HTTP header (used for headers with multiple values).
   */
  override def addHeader(name: String, value: String) : NingWSRequest = {
    headers = headers + (name -> (headers.get(name).getOrElse(List()) :+ value))
    super.addHeader(name, value)
  }

  /**
   * Defines the request headers.
   */
  override def setHeaders(hdrs: FluentCaseInsensitiveStringsMap) : NingWSRequest  = {
    headers = ningHeadersToMap(hdrs)
    super.setHeaders(hdrs)
  }

  /**
   * Defines the request headers.
   */
  override def setHeaders(hdrs: java.util.Map[String, java.util.Collection[String]]) : NingWSRequest  = {
    headers = ningHeadersToMap(hdrs)
    super.setHeaders(hdrs)
  }

  /**
   * Defines the request headers.
   */
  def setHeaders(hdrs: Map[String, Seq[String]]) : NingWSRequest  = {
    headers = hdrs
    hdrs.foreach(header => header._2.foreach(value =>
      super.addHeader(header._1, value)
    ))
    this
  }

  /**
   * Defines the query string.
   */
  def setQueryString(queryString: Map[String, Seq[String]]) : NingWSRequest  = {
    for ((key, values) <- queryString; value <- values) {
      this.addQueryParameter(key, value)
    }
    this
  }

  private def ningHeadersToMap(headers: java.util.Map[String, java.util.Collection[String]]) =
    mapAsScalaMapConverter(headers).asScala.map(e => e._1 -> e._2.asScala.toSeq).toMap

  private def ningHeadersToMap(headers: FluentCaseInsensitiveStringsMap) = {
    val res = mapAsScalaMapConverter(headers).asScala.map(e => e._1 -> e._2.asScala.toSeq).toMap
    //todo: wrap the case insensitive ning map instead of creating a new one (unless perhaps immutabilty is important)
    TreeMap(res.toSeq: _*)(CaseInsensitiveOrdered)
  }

  private[libs] def execute: Future[NingWSResponse] = {
    import com.ning.http.client.AsyncCompletionHandler
    var result = Promise[NingWSResponse]()
    calculator.map(_.sign(this))
    val ningClient: NingWSClient = WS.client(app).asInstanceOf[NingWSClient]
    ningClient.executeRequest(this.build(), new AsyncCompletionHandler[AHCResponse]() {
      override def onCompleted(response: AHCResponse) = {
        result.success(NingWSResponse(response))
        response
      }

      override def onThrowable(t: Throwable) = {
        result.failure(t)
      }
    })
    result.future
  }


  /**
   * Defines the URL.
   */
  override def setUrl(url: String) : NingWSRequest = {
    _url = url
    super.setUrl(url)
  }

  private[libs] def executeStream[A](consumer: WSResponseHeaders => Iteratee[Array[Byte], A])(implicit app: Application): Future[Iteratee[Array[Byte], A]] = {
    import com.ning.http.client.AsyncHandler
    var doneOrError = false
    calculator.map(_.sign(this))

    var statusCode = 0
    val iterateeP = Promise[Iteratee[Array[Byte], A]]()
    var iteratee: Iteratee[Array[Byte], A] = null

    val ningClient: NingWSClient = WS.client.asInstanceOf[NingWSClient]
    ningClient.executeRequest(this.build(), new AsyncHandler[Unit]() {

      import com.ning.http.client.AsyncHandler.STATE

      override def onStatusReceived(status: HttpResponseStatus) = {
        statusCode = status.getStatusCode()
        STATE.CONTINUE
      }

      override def onHeadersReceived(h: HttpResponseHeaders) = {
        val headers = h.getHeaders()
        iteratee = consumer(DefaultWSResponseHeaders(statusCode, ningHeadersToMap(headers)))
        STATE.CONTINUE
      }

      override def onBodyPartReceived(bodyPart: HttpResponseBodyPart) = {
        if (!doneOrError) {
          iteratee = iteratee.pureFlatFold {
            case Step.Done(a, e) => {
              doneOrError = true
              val it = Done(a, e)
              iterateeP.success(it)
              it
            }

            case Step.Cont(k) => {
              k(El(bodyPart.getBodyPartBytes()))
            }

            case Step.Error(e, input) => {
              doneOrError = true
              val it = Error(e, input)
              iterateeP.success(it)
              it
            }
          }
          STATE.CONTINUE
        } else {
          iteratee = null
          // Must close underlying connection, otherwise async http client will drain the stream
          bodyPart.markUnderlyingConnectionAsClosed()
          STATE.ABORT
        }
      }

      override def onCompleted() = {
        Option(iteratee).map(iterateeP.success)
      }

      override def onThrowable(t: Throwable) = {
        iterateeP.failure(t)
      }
    })
    iterateeP.future
  }

}


/**
 * A WS Request builder.
 */
case class NingWSRequestHolder(app:Application,
                               url: String,
                               headers: Map[String, Seq[String]],
                               queryString: Map[String, Seq[String]],
                               calc: Option[WSSignatureCalculator],
                               auth: Option[(String, String, WSAuthScheme)],
                               followRedirects: Option[Boolean],
                               requestTimeout: Option[Int],
                               virtualHost: Option[String],
                               proxyServer: Option[WSProxyServer]) extends WSRequestHolder {


  /**
   * sets the signature calculator for the request
   * @param calc
   */
  def sign(calc: WSSignatureCalculator): WSRequestHolder = this.copy(calc = Some(calc))

  /**
   * sets the authentication realm
   */
  def withAuth(username: String, password: String, scheme: WSAuthScheme): WSRequestHolder =
    this.copy(auth = Some((username, password, scheme)))

  /**
   * adds any number of HTTP headers
   * @param hdrs
   */
  def withHeaders(hdrs: (String, String)*): WSRequestHolder = {
    val headers = hdrs.foldLeft(this.headers)((m, hdr) =>
      if (m.contains(hdr._1)) m.updated(hdr._1, m(hdr._1) :+ hdr._2)
      else m + (hdr._1 -> Seq(hdr._2))
    )
    this.copy(headers = headers)
  }

  /**
   * adds any number of query string parameters to the
   */
  def withQueryString(parameters: (String, String)*): WSRequestHolder =
    this.copy(queryString = parameters.foldLeft(queryString) {
      case (m, (k, v)) => m + (k -> (v +: m.get(k).getOrElse(Nil)))
    })

  /**
   * Sets whether redirects (301, 302) should be followed automatically
   */
  def withFollowRedirects(follow: Boolean): WSRequestHolder =
    this.copy(followRedirects = Some(follow))

  @scala.deprecated("use withRequestTimeout instead", "2.1.0")
  def withTimeout(timeout: Int): WSRequestHolder =
    this.withRequestTimeout(timeout)

  /**
   * Sets the maximum time in millisecond you accept the request to take.
   * Warning: a stream consumption will be interrupted when this time is reached.
   */
  def withRequestTimeout(timeout: Int): WSRequestHolder =
    this.copy(requestTimeout = Some(timeout))

  def withVirtualHost(vh: String): WSRequestHolder = {
    this.copy(virtualHost = Some(vh))
  }

  def withProxyServer(proxyServer: WSProxyServer): WSRequestHolder = {
    this.copy(proxyServer = Some(proxyServer))
  }

  /**
   * performs a get with supplied body
   */

  def get(): Future[NingWSResponse] = prepare("GET").execute

  private implicit val implicitApp = app

  /**
   * performs a get with supplied body
   * @param consumer that's handling the response
   */
  def get[A](consumer: WSResponseHeaders => Iteratee[Array[Byte], A]): Future[Iteratee[Array[Byte], A]] =
    prepare("GET").executeStream(consumer)

  /**
   * Perform a PATCH on the request asynchronously.
   */
  def patch[T](body: T)(implicit wrt: Writeable[T], ct: ContentTypeOf[T]): Future[NingWSResponse] = prepare("PATCH", body).execute

  /**
   * Perform a PATCH on the request asynchronously.
   * Request body won't be chunked
   */
  def patch(body: File): Future[NingWSResponse] = prepare("PATCH", body).execute

  /**
   * performs a POST with supplied body
   * @param consumer that's handling the response
   */
  def patchAndRetrieveStream[A, T](body: T)(consumer: WSResponseHeaders => Iteratee[Array[Byte], A])(implicit wrt: Writeable[T], ct: ContentTypeOf[T]): Future[Iteratee[Array[Byte], A]] = prepare("PATCH", body).executeStream(consumer)

  /**
   * Perform a POST on the request asynchronously.
   */
  def post[T](body: T)(implicit wrt: Writeable[T], ct: ContentTypeOf[T]): Future[NingWSResponse] = prepare("POST", body).execute

  /**
   * Perform a POST on the request asynchronously.
   * Request body won't be chunked
   */
  def post(body: File): Future[NingWSResponse] = prepare("POST", body).execute

  /**
   * performs a POST with supplied body
   * @param consumer that's handling the response
   */
  def postAndRetrieveStream[A, T](body: T)(consumer: WSResponseHeaders => Iteratee[Array[Byte], A])(implicit wrt: Writeable[T], ct: ContentTypeOf[T]): Future[Iteratee[Array[Byte], A]] = prepare("POST", body).executeStream(consumer)

  /**
   * Perform a PUT on the request asynchronously.
   */
  def put[T](body: T)(implicit wrt: Writeable[T], ct: ContentTypeOf[T]): Future[NingWSResponse] = prepare("PUT", body).execute

  /**
   * Perform a PUT on the request asynchronously.
   * Request body won't be chunked
   */
  def put(body: File): Future[NingWSResponse] = prepare("PUT", body).execute

  /**
   * performs a PUT with supplied body
   * @param consumer that's handling the response
   */
  def putAndRetrieveStream[A, T](body: T)(consumer: WSResponseHeaders => Iteratee[Array[Byte], A])(implicit wrt: Writeable[T], ct: ContentTypeOf[T]): Future[Iteratee[Array[Byte], A]] = prepare("PUT", body).executeStream(consumer)

  /**
   * Perform a DELETE on the request asynchronously.
   */
  def delete(): Future[NingWSResponse] = prepare("DELETE").execute

  /**
   * Perform a HEAD on the request asynchronously.
   */
  def head(): Future[NingWSResponse] = prepare("HEAD").execute

  /**
   * Perform a OPTIONS on the request asynchronously.
   */
  def options(): Future[NingWSResponse] = prepare("OPTIONS").execute

  /**
   * Execute an arbitrary method on the request asynchronously.
   *
   * @param method The method to execute
   */
  def execute(method: String): Future[NingWSResponse] = prepare(method).execute


  private[play] def prepare(method: String): NingWSRequest = {
    val request: NingWSRequest = new NingWSRequest(app, method, auth, calc).setUrl(url)
      .setHeaders(headers)
      .setQueryString(queryString)
    followRedirects.map(request.setFollowRedirects)
    requestTimeout.map {
      t: Int =>
        val config = new PerRequestConfig()
        config.setRequestTimeoutInMs(t)
        request.setPerRequestConfig(config)
    }

    virtualHost.map {
      v =>
        request.setVirtualHost(v)
    }

    prepareProxy(request)

    request
  }

  private[play] def prepareProxy(request: NingWSRequest) {
    proxyServer.map {
      p =>
        import com.ning.http.client.ProxyServer.Protocol
        val protocol: Protocol = p.protocol.getOrElse("http").toLowerCase match {
          case "http" => Protocol.HTTP
          case "https" => Protocol.HTTPS
          case "kerberos" => Protocol.KERBEROS
          case "ntlm" => Protocol.NTLM
          case "spnego" => Protocol.SPNEGO
          case _ => scala.sys.error("Unrecognized protocol!")
        }

        val proxyServer = new AHCProxyServer(
          protocol,
          p.host,
          p.port,
          p.principal.getOrElse(null),
          p.password.getOrElse(null))

        p.encoding.map {
          e =>
            proxyServer.setEncoding(e)
        }

        p.nonProxyHosts.map {
          nonProxyHosts =>
            nonProxyHosts.foreach {
              nonProxyHost =>
                proxyServer.addNonProxyHost(nonProxyHost)
            }
        }

        p.ntlmDomain.map {
          ntlm =>
            proxyServer.setNtlmDomain(ntlm)
        }

        request.setProxyServer(proxyServer)
    }
  }

  private[play] def prepare(method: String, body: File): NingWSRequest = {
    import com.ning.http.client.generators.FileBodyGenerator

    val bodyGenerator = new FileBodyGenerator(body)

    val request = new NingWSRequest(app, method, auth, calc).setUrl(url)
      .setHeaders(headers)
      .setQueryString(queryString)
      .setBody(bodyGenerator)
    followRedirects.map(request.setFollowRedirects)
    requestTimeout.map {
      t: Int =>
        val config = new PerRequestConfig()
        config.setRequestTimeoutInMs(t)
        request.setPerRequestConfig(config)
    }
    virtualHost.map {
      v =>
        request.setVirtualHost(v)
    }

    prepareProxy(request)

    request
  }

  private[play] def prepare[T](method: String, body: T)(implicit wrt: Writeable[T], ct: ContentTypeOf[T]): NingWSRequest = {
    val request = new NingWSRequest(app, method, auth, calc).setUrl(url)
      .setHeaders(Map("Content-Type" -> Seq(ct.mimeType.getOrElse("text/plain"))) ++ headers)
      .setQueryString(queryString)
      .setBody(wrt.transform(body))
    followRedirects.map(request.setFollowRedirects)
    requestTimeout.map {
      t: Int =>
        val config = new PerRequestConfig()
        config.setRequestTimeoutInMs(t)
        request.setPerRequestConfig(config)
    }
    virtualHost.map {
      v =>
        request.setVirtualHost(v)
    }
    prepareProxy(request)

    request
  }
}

/**
 * WSPlugin implementation hook.
 */
class NingWSPlugin(app: Application) extends WSPlugin {

  @volatile var loaded = false

  override lazy val enabled = true

  override def onStart() {
    loaded = true
  }

  override def onStop() {
    if (loaded) {
      api.resetClient()
      loaded = false
    }
  }

  lazy val api = new NingWSAPI(app)

}

class NingWSAPI(app:Application) extends WSAPI {

  import javax.net.ssl.SSLContext

  private val clientHolder: AtomicReference[Option[NingWSClient]] = new AtomicReference(None)

  private[play] def newClient(): NingWSClient = {
    val playConfig = app.configuration
    val asyncHttpConfig = new AsyncHttpClientConfig.Builder()
      .setConnectionTimeoutInMs(playConfig.getMilliseconds("ws.timeout.connection").getOrElse(120000L).toInt)
      .setIdleConnectionTimeoutInMs(playConfig.getMilliseconds("ws.timeout.idle").getOrElse(120000L).toInt)
      .setRequestTimeoutInMs(playConfig.getMilliseconds("ws.timeout.request").getOrElse(120000L).toInt)
      .setFollowRedirects(playConfig.getBoolean("ws.followRedirects").getOrElse(true))
      .setUseProxyProperties(playConfig.getBoolean("ws.useProxyProperties").getOrElse(true))

    playConfig.getString("ws.useragent").map {
      useragent =>
        asyncHttpConfig.setUserAgent(useragent)
    }
    if (!playConfig.getBoolean("ws.acceptAnyCertificate").getOrElse(false)) {
      asyncHttpConfig.setSSLContext(SSLContext.getDefault)
    }
    new NingWSClient(asyncHttpConfig.build())
  }

  def client: WSClient = {
    clientHolder.get.getOrElse({
      // A critical section of code. Only one caller has the opportuntity of creating a new client.
      synchronized {
        clientHolder.get match {
          case None => {
            val client = newClient()
            clientHolder.set(Some(client))
            client
          }
          case Some(client) => client
        }

      }
    })
  }

  def url(url: String) = NingWSRequestHolder(app, url, Map(), Map(), None, None, None, None, None, None)


  /**
   * resets the underlying AsyncHttpClient
   */
  private[play] def resetClient(): Unit = {
    clientHolder.getAndSet(None).map(oldClient => oldClient.close())
  }

}

/**
 * The Ning implementation of a WS cookie.
 */
private class NingWSCookie(ahcCookie: AHCCookie) extends WSCookie {

  private def noneIfEmpty(value: String): Option[String] = {
    if (value.isEmpty) None else Some(value)
  }

  /**
   * The underlying cookie object for the client.
   */
  def underlying = ahcCookie

  /**
   * The domain.
   */
  def domain: String = ahcCookie.getDomain

  /**
   * The cookie name.
   */
  def name: Option[String] = noneIfEmpty(ahcCookie.getName)

  /**
   * The cookie value.
   */
  def value: Option[String] = noneIfEmpty(ahcCookie.getValue)

  /**
   * The path.
   */
  def path: String = ahcCookie.getPath

  /**
   * The maximum age.
   */
  def maxAge: Int = ahcCookie.getMaxAge

  /**
   * If the cookie is secure.
   */
  def secure: Boolean = ahcCookie.isSecure

  /**
   * The cookie version.
   */
  def version: Int = ahcCookie.getVersion

  /*
   * Cookie ports should not be used; cookies for a given host are shared across
   * all the ports on that host.
   */

  override def toString: String = ahcCookie.toString
}


/**
 * A WS HTTP response.
 */
case class NingWSResponse(ahcResponse: AHCResponse) extends WSResponse {

  import scala.xml._
  import play.api.libs.json._

  /**
   * Get the underlying response object.
   */
  @deprecated("Use underlying", "2.3.0")
  def getAHCResponse = ahcResponse

  /**
   * @return The underlying response object.
   */
  def underlying = ahcResponse

  /**
   * The response status code.
   */
  def status: Int = ahcResponse.getStatusCode

  /**
   * The response status message.
   */
  def statusText: String = ahcResponse.getStatusText

  /**
   * Get a response header.
   */
  def header(key: String): Option[String] = Option(ahcResponse.getHeader(key))

  /**
   * Get all the cookies.
   */
  def cookies: Seq[WSCookie] = {
    import scala.collection.JavaConverters._
    ahcResponse.getCookies.asScala.map(new NingWSCookie(_))
  }

  /**
   * Get only one cookie, using the cookie name.
   */
  def cookie(name: String): Option[WSCookie] = cookies.find(_.name == Option(name))

  /**
   * The response body as String.
   */
  lazy val body: String = {
    // RFC-2616#3.7.1 states that any text/* mime type should default to ISO-8859-1 charset if not
    // explicitly set, while Plays default encoding is UTF-8.  So, use UTF-8 if charset is not explicitly
    // set and content type is not text/*, otherwise default to ISO-8859-1
    val contentType = Option(ahcResponse.getContentType).getOrElse("application/octet-stream")
    val charset = Option(AsyncHttpProviderUtils.parseCharset(contentType)).getOrElse {
      if (contentType.startsWith("text/"))
        AsyncHttpProviderUtils.DEFAULT_CHARSET
      else
        "utf-8"
    }
    ahcResponse.getResponseBody(charset)
  }

  /**
   * The response body as Xml.
   */
  lazy val xml: Elem = Play.XML.loadString(body)

  /**
   * The response body as Json.
   */
  lazy val json: JsValue = Json.parse(ahcResponse.getResponseBodyAsBytes)

}
