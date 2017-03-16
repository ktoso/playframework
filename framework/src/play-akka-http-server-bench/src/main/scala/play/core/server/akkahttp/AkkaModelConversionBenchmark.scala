package play.core.server.akkahttp

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.{Done, NotUsed}
import org.openjdk.jmh.annotations._
import play.api.{Application, Configuration}
import play.api.http.{DefaultHttpErrorHandler, HttpConfiguration}
import akka.http.scaladsl.model._
import play.api.libs.streams.Accumulator
import play.api.mvc.{EssentialAction, Handler, RequestHeader, Result}
import play.core.server.akkahttp._
import play.core.server.common.{ForwardedHeaderHandler, ServerResultUtils}

import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class AkkaModelConversionBenchmark {

  implicit val system = ActorSystem("EmptySourceBenchmark")
  val materializerSettings = ActorMaterializerSettings(system).withDispatcher("akka.test.stream-dispatcher")
  implicit val materializer = ActorMaterializer(materializerSettings)

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
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

  val in = HttpRequest()

  @Benchmark
  def simple_in() = ???

  @Benchmark
  def simple_out() = {
    val cleanedResult: Result = resultUtils.prepareCookies(taggedRequestHeader, result)

    Await.result(modelConversion.convertResult(taggedRequestHeader, cleanedResult, request.protocol, errorHandler), Duration.Inf)
  }

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
      applicationProvider.handleWebCommand(request) match {
        case Some(result) =>
          // The ApplicationProvider handled the result
          Left(Future.successful(result))
        case None =>
          // The ApplicationProvider didn't handle the result, so try
          // handling it with the Application
          applicationProvider.get match {
            case Success(application) =>
              // We managed to get an Application, now make a fresh request
              // using the Application's RequestFactory, then use the Application's
              // logic to handle that request.
              val factoryMadeHeader: RequestHeader = application.requestFactory.copyRequestHeader(request)
              val (handlerHeader, handler) = application.requestHandler.handlerForRequest(factoryMadeHeader)
              Right((handlerHeader, handler, application))
            case Failure(e) =>
              // The ApplicationProvider couldn't give us an application.
              // This usually means there was a compile error or a problem
              // starting the application.
              logExceptionAndGetResult(e)
          }
      }
    } catch {
      case e: ThreadDeath => throw e
      case e: VirtualMachineError => throw e
      case e: Throwable =>
        logExceptionAndGetResult(e)
    }
  }
  
  
}

