package com.zegoggles.gist

import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.client.methods._
import collection.mutable.ListBuffer
import org.apache.http.client.utils.URLEncodedUtils
import java.net.URI
import Implicits._
import android.accounts.{AccountManager, Account}
import java.lang.Boolean
import android.net.http.AndroidHttpClient
import org.apache.http.client.HttpClient
import android.content.Context
import android.content.pm.PackageManager
import org.apache.http.params.HttpConnectionParams
import org.json.JSONObject
import org.apache.http.message.{BasicHeader, BasicNameValuePair}
import java.io.IOException
import actors.Futures
import android.app.{Dialog, Activity}
import android.net.ConnectivityManager
import org.apache.http._
import entity.{BufferedHttpEntity, StringEntity}

object Request {
  def apply(s: String, p: (String, String)*) = {
    val request = new Request(s)
    for ((k, v) <- p) request.add(k, v)
    request
  }
  implicit def string2Request(s: String): Request = new Request(s)
}

class Request(val url: String) {
  import collection.JavaConversions._

  lazy val params: ListBuffer[NameValuePair] = ListBuffer()
  var body: Option[String] = None

  def add(key: String, value: Any): Request = {
    params += new BasicNameValuePair(key, value.toString)
    this
  }

  def add(params: Map[String, Any]): Request = {
    for ((k, v) <- params) add(k, v)
    this
  }

  def body(b: String): Request    = { body = Some(b); this }
  def body(j: JSONObject):Request = body(j.toString)
  def queryString = URLEncodedUtils.format(params, "UTF-8")

  def toURI = URI.create(url + (if (params.isEmpty) "" else "?" + queryString))

  def toHTTPRequest[T <: HttpRequestBase](r: Class[T]): T = {
    val req = r.newInstance
    req.setURI(req match {
      case e: HttpEntityEnclosingRequestBase =>
        e.setEntity(new StringEntity(body.getOrElse {
          e.setHeader("Content-Type", "application/x-www-form-urlencoded")
          queryString
        }))
        URI.create(url)
      case _ => toURI
    })
    req
  }
}

case class Token(access: String)
object Api {
  type Success = HttpResponse
  type Error   = Either[Exception,HttpResponse]

  def map2Json(m: Map[String, Any]): JSONObject = {
    val obj: JSONObject = new JSONObject()
    for ((k, v) <- m) {
      obj.put(k, v match {
        case m: Map[String,Any] => map2Json(m)
        case b: Boolean => b
        case i: Int     => i
        case l: Long    => l
        case d: Double  => d
        case o: Any     => o
      })
    }
    obj
  }

  def parseTokenResponse(s: String): Option[Token] = {
    //"access_token=807e750b891b3fc47b0c951b4c11c0b610195b73&token_type=bearer"
    val token = for (
      fields <- s.split('&').map(_.split('='))
      if (fields(0) == "access_token")
    ) yield (fields(1))
    token.headOption.map(Token(_))
  }
}

class Api(val client_id: String, val client_secret: String, val redirect_uri: String, var token: Option[Token]) extends StdoutLogger {
  lazy val client = makeClient
  val baseHost = new HttpHost("api.github.com", -1, "https")
  val authorizeUrl = "https://github.com/login/oauth/authorize?client_id=" +
    client_id + "&scope=user,gist&redirect_uri=" + redirect_uri

  def get(req: Request)   = execute(req, classOf[HttpGet])
  def put(req: Request)   = execute(req, classOf[HttpPut])
  def post(req: Request)  = execute(req, classOf[HttpPost])
  def patch(req: Request) = execute(req, classOf[HttpPatch])

  def execute[T <: HttpRequestBase](request: Request, reqType: Class[T]) = (hostFor(request) match {
      case Some(host) => client.execute(host, _:HttpUriRequest)
      case None       => client.execute(_:HttpUriRequest)
    })(withAuthHeader(request.toHTTPRequest(reqType)))

  def hostFor(r: Request):Option[HttpHost] =
    if (r.url.startsWith("http://")||r.url.startsWith("https://")) None else Some(baseHost)

  def exchangeToken(code: String): Option[Token] = {
    val resp = post(Request("https://github.com/login/oauth/access_token",
      "client_id" -> client_id,
      "client_secret" -> client_secret,
      "code" -> code,
      "redirect_uri" -> redirect_uri))

    resp.getStatusLine.getStatusCode match {
      case HttpStatus.SC_OK =>
        token = Api.parseTokenResponse(resp.getEntity)
        token
      case _ => log("Invalid status code " + resp.getStatusLine); None
    }
  }

  def withAuthHeader(req:HttpUriRequest) = {
    token.map(t => req.addHeader(new BasicHeader("Authorization", "token "+t.access)))
    req
  }
  def makeClient:HttpClient = new DefaultHttpClient()
}

trait ApiActivity extends Activity with TokenHolder {
  def app = getApplication.asInstanceOf[App]
  def api = app.api

  def executeAsync(call: Request => HttpResponse, req: Request, expected: Int, progress: Option[Dialog])
                  (success: HttpResponse => Any)
                  (error: Api.Error => Any) = {

    def onUiThread(f: => Unit) {
      runOnUiThread(new Runnable() { def run() { progress.map(_.dismiss()); f } } )
    }
    progress.map(_.show())
    Futures.future {
      try {
        val resp = call(req)
        resp.setEntity(new BufferedHttpEntity(resp.getEntity))
        resp.getEntity.consumeContent()

        resp.getStatusLine.getStatusCode match {
          case code if code == expected => onUiThread { success(resp)}
          case other                    => onUiThread { error(Right(resp))}
        }
      } catch {
        case e: IOException => onUiThread { error(Left(e)) }
      }
    }
  }

  def isConnected = {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE).asInstanceOf[ConnectivityManager]
    val info = manager.getActiveNetworkInfo
    info != null && info.isConnectedOrConnecting
  }
}

trait TokenHolder extends Context {
  lazy val accountType = getString(R.string.account_type)
  def account: Option[Account] = AccountManager.get(this).getAccountsByType(accountType).headOption
  def token: Option[Token] = account.map(a => Token(AccountManager.get(this).getPassword(a)))

  def addAccount(a:Activity) =
    AccountManager.get(this).addAccount(accountType, "access_token", null, null, a, null, null)
}

trait ApiHolder extends TokenHolder {
  lazy val info = getPackageManager.getPackageInfo(getClass.getPackage.getName, PackageManager.GET_META_DATA)
  lazy val userAgent = getPackageManager.getApplicationLabel(info.applicationInfo)+" ("+info.versionName+")"
  val timeout = 10 * 1000

  lazy val api = new Api(
    getString(R.string.client_id),
    getString(R.string.client_secret),
    getString(R.string.redirect_uri),
    token) {
    override def makeClient = {
      if (android.os.Build.VERSION.SDK_INT >= 8) {
        val client = AndroidHttpClient.newInstance(userAgent, ApiHolder.this)
        HttpConnectionParams.setConnectionTimeout(client.getParams, timeout)
        HttpConnectionParams.setSoTimeout(client.getParams, timeout)
        client
      } else super.makeClient
    }
  }
}

/** @see PATCH Method for HTTP: http://tools.ietf.org/html/rfc5789 */
class HttpPatch extends HttpEntityEnclosingRequestBase {
  def getMethod = "PATCH"
}
