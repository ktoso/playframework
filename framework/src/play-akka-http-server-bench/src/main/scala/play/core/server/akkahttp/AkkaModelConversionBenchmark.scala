package play.core.server.akkahttp

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.{ Done, NotUsed }
import org.openjdk.jmh.annotations._
import play.api.{ Application, Configuration }
import play.api.http.{ DefaultHttpErrorHandler, HttpConfiguration, HttpErrorHandler }
import akka.http.scaladsl.model._
import play.api.libs.streams.Accumulator
import play.api.mvc._
import play.core.ApplicationProvider
import play.core.server.akkahttp._
import play.core.server.common.{ ForwardedHeaderHandler, ServerResultUtils }

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }
import play.api.inject.guice._
import play.api.libs.typedmap.TypedMap
import play.api.mvc.request.{ RemoteConnection, RequestTarget }

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput, Mode.AverageTime))
class AkkaModelConversionBenchmark {

  val application: Application = GuiceApplicationBuilder().build()
  lazy val errorHandler: HttpErrorHandler = application.injector.instanceOf[HttpErrorHandler]

  @TearDown
  def shutdown(): Unit = {
    Await.result(application.stop(), 5.seconds)
  }

  private val resultUtils: ServerResultUtils = {
    val httpConfiguration = HttpConfiguration()
    new ServerResultUtils(httpConfiguration)
  }

  private val modelConversion: AkkaModelConversion = {
    val configuration: Option[Configuration] = Some(Configuration())
    val forwardedHeaderHandler = new ForwardedHeaderHandler(ForwardedHeaderHandler.ForwardedHeaderHandlerConfig(configuration))
    new AkkaModelConversion(resultUtils, forwardedHeaderHandler)
  }

  val requestHeader = new RequestHeaderImpl(
    RemoteConnection("127.0.0.1", false, None),
    "GET",
    RequestTarget("http://127.0.0.1:9000", "/hello", Map.empty),
    "1.1",
    Headers.apply(
      "Accept" -> "*/*",
      "Accept-Encoding" -> "gzip, deflate",
      "Connection" -> "keep-alive",
      "Host" -> "127.0.0.1",
      "User-Agent" -> "HTTPie/0.9.8"
    ),
    TypedMap.empty
  )

  val in = HttpRequest(uri = "http://127.0.0.1:9000/hello")
  val remoteAddress = new InetSocketAddress("127.0.0.1", 9999)

  @Benchmark def simple_to_play: RequestHeader =
    modelConversion.convertRequestHeader(0, remoteAddress, false, in)
  
  /*
    [info] Result "simple_to_play":
    [info]   2625.341 ±(99.9%) 69.586 ops/ms [Average]
    [info]   (min, avg, max) = (2558.452, 2625.341, 2693.369), stdev = 46.027
    [info]   CI (99.9%): [2555.755, 2694.927] (assumes normal distribution)
   */

  //  @Benchmark
  //  def simple_out() = {
  //    val (taggedRequestHeader, handler, newTryApp) = getHandler(requestHeader)
  //    
  //    val cleanedResult: Result = resultUtils.prepareCookies(taggedRequestHeader, result)
  //
  //    Await.result(modelConversion.convertResult(taggedRequestHeader, cleanedResult, request.protocol, errorHandler), Duration.Inf)
  //  }

  // -------------------------------------------------------------------------------------------------------------------
  // -------------------------------------------------------------------------------------------------------------------
  // -------------------------------------------------------------------------------------------------------------------
  // -------------------------------------------------------------------------------------------------------------------

  private def getHandler(requestHeader: RequestHeader): (RequestHeader, Handler, Try[Application]) = {
    getHandlerFor(requestHeader) match {
      case Left(futureResult) =>
        (
          requestHeader,
          EssentialAction(_ => Accumulator.done(futureResult)),
          Failure(new Exception("getHandler returned Result, but not Application"))
        )
      case Right((newRequestHeader, handler, newApp)) =>
        (
          newRequestHeader,
          handler,
          Success(newApp) // TODO: Change getHandlerFor to use the app that we already had
        )
    }
  }

  /**
   * Try to get the handler for a request and return it as a `Right`. If we
   * can't get the handler for some reason then return a result immediately
   * as a `Left`. Reasons to return a `Left` value:
   *
   * - If there's a "web command" installed that intercepts the request.
   * - If we fail to get the `Application` from the `applicationProvider`,
   *   i.e. if there's an error loading the application.
   * - If an exception is thrown.
   */
  def getHandlerFor(request: RequestHeader): Either[Future[Result], (RequestHeader, Handler, Application)] = {

    // Common code for handling an exception and returning an error result
    def logExceptionAndGetResult(e: Throwable): Left[Future[Result], Nothing] = {
      Left(DefaultHttpErrorHandler.onServerError(request, e))
    }

    try {
      // This applicationProvider will always return a None when calling handleWebCommand
      val applicationProvider = ApplicationProvider(application)
      applicationProvider.handleWebCommand(request) match {
        case Some(result) =>
          // The ApplicationProvider handled the result
          Left(Future.successful(result))
        case None =>
          // The ApplicationProvider didn't handle the result, so try
          // handling it with the Application
          applicationProvider.get match {
            case Success(app) =>
              // We managed to get an Application, now make a fresh request
              // using the Application's RequestFactory, then use the Application's
              // logic to handle that request.
              val factoryMadeHeader: RequestHeader = app.requestFactory.copyRequestHeader(request)
              val (handlerHeader, handler) = app.requestHandler.handlerForRequest(factoryMadeHeader)
              Right((handlerHeader, handler, app))
            case Failure(e) =>
              // The ApplicationProvider couldn't give us an application.
              // This usually means there was a compile error or a problem
              // starting the application.
              logExceptionAndGetResult(e)
          }
      }
    }
  }
}
