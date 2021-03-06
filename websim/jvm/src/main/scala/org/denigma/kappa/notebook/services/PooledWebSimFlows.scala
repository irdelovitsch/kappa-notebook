package org.denigma.kappa.notebook.services

import java.time.LocalDateTime

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.Http.HostConnectionPool
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}
import akka.stream._
import akka.stream.scaladsl._
import io.circe.generic.auto._
import org.denigma.kappa.messages._

import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util._
import akka.http.extensions._
import WebSimMessages._
import org.denigma.kappa.messages.ServerMessages.LaunchModel
import pprint.PPrint


trait PoolMessage
{
  def time: LocalDateTime
}

case class TimePoolMessage(time: LocalDateTime) extends PoolMessage

case class ModelPoolMessage(runParams: RunModel,
                            time: LocalDateTime,
                            token: Option[Int] = None,
                            results: List[SimulationStatus] = List.empty,
                            errors: List[String] = List.empty)
  extends PoolMessage



trait PooledWebSimFlows extends WebSimFlows {

  type TryResponse = (Try[HttpResponse], PoolMessage)

  type ParseResult = Either[ContactMap, List[WebSimError]]

  type TokenResult = Either[Token, List[WebSimError]]

  type TokenContactResult = Either[(Token, ContactMap), List[WebSimError]]

  type SimulationContactResult= Either[(Token, SimulationStatus, ContactMap), List[WebSimError]]

  type Runnable[T] = (T, LaunchModel) //type that contains the result and initial run parameters

  implicit val system: ActorSystem

  implicit val mat: ActorMaterializer

  implicit protected def context: ExecutionContextExecutor = system.dispatcher

  protected def pool: Flow[(HttpRequest, PoolMessage), TryResponse, HostConnectionPool] //note: should be extended by lazy val

  val timePool: Flow[HttpRequest, TryResponse, NotUsed] = Flow[HttpRequest].map(req => req -> TimePoolMessage(LocalDateTime.now)).via(pool)

  val versionFlow = versionRequestFlow.via(timePool).via(unmarshalFlow[Version]).sync

  val running: Flow[Any, Array[Int], NotUsed] = runningRequestFlow.via(timePool).via(unmarshalFlow[Array[Int]].sync)

  def safeUnmarshalFlow[T, U](onfailure: (HttpResponse, Throwable) => Future[U])
                             (implicit um: Unmarshaller[HttpResponse, T]): Flow[TryResponse, Future[Either[T,U]], NotUsed] =
    Flow[TryResponse].map{
      case (Success(resp), message) =>
        val fut = Unmarshal(resp).to[T]
        fut.map[Either[T, U]](Left(_)).recoverWith{
          case exception => onfailure(resp, exception).map[Either[T, U]](Right(_))
        }
      case (Failure(exception), time) => Future.failed(exception)
    }

  protected def wrongUnmarshal(th: Throwable,resp: HttpResponse) = {
    system.log.error("==\n WRONG UNMARSHAL FOR:\n "+resp+"==\n")
    system.log.error("THE ERROR is:"+th)
    Unmarshal(resp).to[String].onComplete{
      case Success(str)=>
        system.log.error("POSSIBLE String/JSON for marshal value is:\n"+str)
      case _=>
    }
    Future.failed(th)
  }

  protected val safeParseUnmarshalFlow: Flow[TryResponse, Future[ParseResult], NotUsed] = safeUnmarshalFlow[ContactMap, List[WebSimError]]{
    case (resp, time) =>
      Unmarshal(resp).to[List[WebSimError]].recoverWith{
        case th=> wrongUnmarshal(th, resp)
      }
  }


  protected val safeTokenUnmarshalFlow: Flow[TryResponse, Future[TokenResult], NotUsed] = safeUnmarshalFlow[Int, List[WebSimError]]{
    case (resp, time) =>
      Unmarshal(resp).to[List[WebSimError]].recoverWith{
        case th=> wrongUnmarshal(th, resp)
      }
  }

  def unmarshalFlow[T](implicit um: Unmarshaller[HttpResponse, T]): Flow[TryResponse, Future[T], NotUsed] = Flow[TryResponse].map{
    case (Success(resp), time) =>
      Unmarshal(resp).to[T].recoverWith{
        case th=> wrongUnmarshal(th, resp)
      }
    case (Failure(exception), time) =>
      Future.failed(exception)
  }

  def response2String(resp: HttpResponse, timeout: FiniteDuration = 300 millis): Future[String] = {
    resp.entity.toStrict(timeout).map { _.data }.map(_.utf8String)
  }

  val parseFlow: Flow[ParseCode, (scala.Either[ContactMap, List[WebSimError]], ParseCode), NotUsed] =
    parseRequestFlow.inputZipWith(timePool.via(safeParseUnmarshalFlow.sync)){
      case (params, either)=> either -> params
    }

  val runModelFlow: Flow[LaunchModel, (TokenContactResult, LaunchModel), NotUsed] = {
    val parseFlow = parseModelFlow.via(timePool.via(safeParseUnmarshalFlow.sync))

    val leftMaps: Flow[(ParseResult, LaunchModel), Runnable[ContactMap], NotUsed] = Flow[Runnable[ParseResult]].collect { case (Left(mp), initial) => mp -> initial }

    val rightErrors: Flow[Runnable[ParseResult], (TokenContactResult, LaunchModel), NotUsed] = Flow[Runnable[ParseResult]].collect { case (Right(res), initial) => Right(res) -> initial }

    val tokFlow: Flow[(ContactMap,LaunchModel), TokenResult, NotUsed]=
      Flow[Runnable[ContactMap]].map{ case (mp, model)=> model}.via(runModelRequestFlow).via(timePool.via(safeTokenUnmarshalFlow.sync))

    val zipRun = ZipWith[LaunchModel, ParseResult, Runnable[ParseResult]] {
      case (model, result) => result -> model
    }

    val zipToken = ZipWith[Runnable[ContactMap], TokenResult, Runnable[TokenContactResult]] {
      case ( (mp, model), either ) => either.left.map{ case token=> token-> mp } -> model
    }


    val flow: Flow[LaunchModel, (TokenContactResult, LaunchModel), NotUsed] = Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val runcast = builder.add(Broadcast[LaunchModel](2))

      val parse: FlowShape[LaunchModel, ParseResult] = builder.add(parseFlow)

      val runZipper = builder.add(zipRun)

      val parsecast = builder.add(Broadcast[Runnable[ParseResult]](2))

      val errors = builder.add(rightErrors)

      val maps = builder.add(leftMaps)

      val contactcast = builder.add(Broadcast[Runnable[ContactMap]](2))

      val tokener = builder.add(tokFlow)

      val tokenZipper = builder.add(zipToken)

      val merger = builder.add(Merge[Runnable[TokenContactResult]](2))

      //input ~> runcast
      runcast ~> runZipper.in0
      runcast ~> parse ~> runZipper.in1

      runZipper.out ~> parsecast

      parsecast ~> errors ~> merger

      parsecast ~> maps ~> contactcast
      contactcast ~> tokenZipper.in0
      contactcast ~> tokener ~> tokenZipper.in1

      tokenZipper.out ~>  merger

      //merger ~> output
      FlowShape(runcast.in, merger.out)
    })

    flow
  }

  protected def unmarshalSimulationStatus() = Flow[TryResponse].map{
      case (Success(resp), time) =>
        Unmarshal(resp).to[SimulationStatus].recoverWith{
          case th=> wrongUnmarshal(th, resp)
        }
      case (Failure(exception), time) =>
        Future.failed(exception)
    }


  val simulationStatusFlow: Flow[Token, (Token, SimulationStatus), NotUsed] =
    simulationStatusRequestFlow.inputZipWith(timePool.via(unmarshalFlow[SimulationStatus]/*unmarshalSimulationStatus()*/).sync){
      case (token, result)=> token -> result
  }

  def simulationStream(updateInterval: FiniteDuration, parallelism: Int) =  Flow[Token].flatMapMerge(parallelism, {
    case token =>
      val source = Source.tick(0 millis, updateInterval, token)
      source.via( simulationStatusFlow )
        .upTo{
          case (t, sim) =>
            sim.percentage >= 100.0 || !sim.is_running//.getOrElse(false)
        }
  })

  lazy val syncSimulationStream: Flow[Token, (Token, SimulationStatus), NotUsed] = simulationStream(300 millis, 1)

  def simulationResultStream(updateInterval: FiniteDuration, parallelism: Int): Flow[LaunchModel, Runnable[SimulationContactResult], NotUsed] =
    runModelFlow.flatMapMerge(parallelism, {
      case (Left((token, contacts)), model) =>
        val source = Source.tick(0 millis, updateInterval, token)
        val result: Source[Runnable[SimulationContactResult], Cancellable] = source.via( simulationStatusFlow ).upTo{
          case (t, sim) => sim.percentage >= 100.0 || !sim.is_running
        }.map{
         case (tok, res) =>
           Left((tok, res, contacts)) -> model
        }
        result

      case (Right(errors), model) =>
        Source.single(Right(errors) -> model)
    }
    )

  lazy val syncSimulationResultStream: Flow[LaunchModel, Runnable[SimulationContactResult], NotUsed] = simulationResultStream(300 millis, 1)

  val stopFlow: Flow[Token, (Token, SimulationStatus), NotUsed] = stopRequestFlow.inputZipWith(timePool.via(unmarshalFlow[SimulationStatus]).sync){
    case (token, result)=> token -> result
  }

}
