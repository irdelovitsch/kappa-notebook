package org.denigma.kappa

import java.io.{File => JFile}
import java.nio.ByteBuffer

import akka.http.scaladsl.model.ws.BinaryMessage
import akka.http.scaladsl.testkit.WSProbe
import better.files.File
import boopickle.DefaultBasic._
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import org.denigma.kappa.messages.KappaMessage.{ServerCommand, ServerResponse}
import org.denigma.kappa.messages.ServerMessages._
import org.denigma.kappa.messages.WebSimMessages.RunModel
import org.denigma.kappa.messages._
import org.denigma.kappa.notebook.FileManager
import org.denigma.kappa.notebook.communication.WebSocketManager
import org.denigma.kappa.notebook.pages.WebSockets

import scala.collection.immutable._

class WebSocketSimulationSuite extends BasicWebSocketSuite {

  val filePath: String = config.as[Option[String]]("app.files").getOrElse("files/")
  val files = File(filePath)
  files.createIfNotExists(asDirectory = true)
  val fileManager = new FileManager(files, log)

  val transport = new WebSocketManager(system, fileManager)

  val routes = new WebSockets(transport.openChannel).routes

  val defaultServers = config.as[List[ServerConnection]]("app.servers")

  val serverName = defaultServers.head.server

  "WebSockets" should {

    "get run messages and start index" in {
      val wsClient = WSProbe()
      // WS creates a WebSocket request for testing
      WS("/channel/notebook?username=tester1", wsClient.flow) ~> routes ~>
        check {
          // check response for WS Upgrade headers
          isWebSocketUpgrade shouldEqual true
          val params = RunModel(abc, Some(1000), max_events = Some(10000))
          println("SERVER NAME = "+serverName)
          val d: ByteBuffer = Pickle.intoBytes[KappaMessage](ServerCommand(serverName, LaunchModel.fromRunModel(abc, params)))
          wsClient.sendMessage(pack(d))
          wsClient.inProbe.request(1).expectNextPF {
            case BinaryMessage.Strict(bytes) if {
              Unpickle[KappaMessage].fromBytes(bytes.asByteBuffer) match {
                case c: Connected => true
                case _ => false
              }
            } => println("connected works")
          }
          wsClient.inProbe.request(1).expectNextPF {
            case BinaryMessage.Strict(bytes) if {
              Unpickle[KappaMessage].fromBytes(bytes.asByteBuffer) match {
                case ServerResponse(server, s: SimulationResult) =>
                  println("FLUX MAPS = ")
                  pprint.pprintln(s.simulationStatus.flux_maps)
                  true
                case _ =>
                  false
              }
            } =>
          }
          wsClient.sendCompletion()
          //wsClient.expectCompletion()
        }
    }

    "provide errors messages for wrong models" in {
      val wsClient = WSProbe()
      WS("/channel/notebook?username=tester2", wsClient.flow) ~> routes ~>
        check {
          // check response for WS Upgrade headers
          checkConnection(wsClient)

          val model = abc
            .replace("A(x),B(x)", "A(x&*&**),*(B(&**&x)")
            .replace("A(x!_,c),C(x1~u)", "zafzafA(x!_,c),azfC(x1~u)") //note: right now sees only one error
          val params = RunModel(model, Some(1000), max_events = Some(10000))
          val d: ByteBuffer = Pickle.intoBytes[KappaMessage](ServerCommand(serverName, LaunchModel.fromRunModel(abc, params)))
          wsClient.sendMessage(pack(d))

          wsClient.inProbe.request(1).expectNextPF {
            case BinaryMessage.Strict(bytes) if {
              val mes = Unpickle[KappaMessage].fromBytes(bytes.asByteBuffer)
              mes match {
                case ServerResponse(server, SyntaxErrors(_, _)) =>
                  //println("expected errors are: "+ errors)
                  true
                case _ => false
              }
            } =>

          }
          wsClient.sendCompletion()
          //wsClient.expectCompletion()
        }
    }

    "get connection map" in {
      val wsClient = WSProbe()
      WS("/channel/notebook?username=tester3", wsClient.flow) ~> routes ~>
        check {
          // check response for WS Upgrade headers
          checkConnection(wsClient)

          val model = abc
          val params = ParseModel(List("abc"->abc))
          val d: ByteBuffer = Pickle.intoBytes[KappaMessage](ServerCommand(serverName, params))
          wsClient.sendMessage(pack(d))
          wsClient.inProbe.request(1).expectNextPF {
            case BinaryMessage.Strict(bytes) if {
              val mes = Unpickle[KappaMessage].fromBytes(bytes.asByteBuffer)
              mes match {
                case ServerResponse(server, ServerMessages.ParseResult(cm)) =>
                  //println("expected errors are: "+ errors)
                  true
                case _ =>
                  false
              }
            } =>

          }
          wsClient.sendCompletion()
          //wsClient.expectCompletion()
        }
    }

    /*
    "get fluxes" in {
      val wsClient = WSProbe()
      WS("/channel/notebook?username=tester4", wsClient.flow) ~> routes ~>
        check {
          // check response for WS Upgrade headers
          checkConnection(wsClient)

          val model = abc
          val params = ParseModel(List("abc"->abc))
          val d: ByteBuffer = Pickle.intoBytes[KappaMessage](ServerCommand(params))
          wsClient.sendMessage(pack(d))
          wsClient.inProbe.request(1).expectNextPF {
            case BinaryMessage.Strict(bytes) if {
              val mes = Unpickle[KappaMessage].fromBytes(bytes.asByteBuffer)
              mes match {
                case ServerResponse(server, ServerMessages.ParseResult(cm)) =>
                  //println("expected errors are: "+ errors)
                  true
                case _ =>
                  false
              }
            } =>

          }
          wsClient.sendCompletion()
          //wsClient.expectCompletion()
        }
    }
    */
  }
}
