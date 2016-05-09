package org.denigma.kappa

import java.io.{File => JFile}
import java.nio.ByteBuffer

import akka.http.scaladsl.model.ws.BinaryMessage
import akka.http.scaladsl.model.ws.BinaryMessage.Strict
import akka.http.scaladsl.testkit.WSProbe
import akka.util.ByteString
import better.files.File
import boopickle.DefaultBasic._
import net.ceedubs.ficus.Ficus._
import org.denigma.kappa.messages._
import org.denigma.kappa.notebook.FileManager
import org.denigma.kappa.notebook.communication.WebSocketManager
import org.denigma.kappa.notebook.pages.WebSockets

import scala.collection.immutable._
import scala.util.Success

class WebSocketSuite extends BasicKappaSuite with KappaPicklers {

  val (host, port) = (config.getString("app.host"), config.getInt("app.port"))
  val filePath: String = config.as[Option[String]]("app.files").getOrElse("files/")
  val files = File(filePath)
  files.createIfNotExists(asDirectory = true)
  val fileManager = new FileManager(files)

  val transport = new WebSocketManager(system, fileManager)

  val routes = new WebSockets(transport.openChannel).routes

  def pack(buffer:  ByteBuffer): Strict = BinaryMessage(ByteString(buffer))

  "WebSockets" should {

    "get run messages and start simulations" in {
      val wsClient = WSProbe()
      // WS creates a WebSocket request for testing
      WS("/channel/notebook?username=tester1", wsClient.flow) ~>  routes ~>
        check {
          // check response for WS Upgrade headers
          isWebSocketUpgrade shouldEqual true
          val params = RunModel(abc, Some(1000), max_events = Some(10000))
          val d: ByteBuffer = Pickle.intoBytes[KappaMessage](LaunchModel("", params))
          wsClient.sendMessage(pack(d))
          wsClient.inProbe.request(1).expectNextPF{
            case BinaryMessage.Strict(bytes) if {
              Unpickle[KappaMessage].fromBytes(bytes.asByteBuffer) match {
                case c: Connected=>  true
                case _ => false} } =>
          }
          wsClient.inProbe.request(1).expectNextPF{
                        case BinaryMessage.Strict(bytes) if {
                          Unpickle[KappaMessage].fromBytes(bytes.asByteBuffer) match {
                            case sim: SimulationResult=>
                              true
                            case _ =>
                              false
                          }        } =>
                     }
          wsClient.sendCompletion()
          //wsClient.expectCompletion()
        }
    }

    "provide errors messages for wrong models" in {
      val wsClient = WSProbe()
      WS("/channel/notebook?username=tester2", wsClient.flow) ~>  routes ~>
        check {
          // check response for WS Upgrade headers
          checkConnection(wsClient)

          val model = abc
            .replace("A(x),B(x)", "A(x&*&**),*(B(&**&x)")
            .replace("A(x!_,c),C(x1~u)", "zafzafA(x!_,c),azfC(x1~u)") //note: right now sees only one error
          val params = messages.LaunchModel("", RunModel(model, Some(1000), max_events = Some(10000)))
          val d: ByteBuffer = Pickle.intoBytes[KappaMessage](params)
          wsClient.sendMessage(pack(d))

          wsClient.inProbe.request(1).expectNextPF{
            case BinaryMessage.Strict(bytes)  if {
              val mes = Unpickle[KappaMessage].fromBytes(bytes.asByteBuffer)
              mes match {
                case SyntaxErrors(server, errors, _)=>
                  //println("expected errors are: "+ errors)
                  true
                case _=> false
              }
            } =>

          }
         wsClient.sendCompletion()
         //wsClient.expectCompletion()
        }
    }

    "load projects" in {
      val wsClient = WSProbe()
      WS("/channel/notebook?username=tester3", wsClient.flow) ~>  routes ~>
        check {
          checkConnection(wsClient)
          checkTestProjects(wsClient)

        }
        wsClient.sendCompletion()
        //wsClient.expectCompletion()
    }


    "update projects" in {
      val wsClient = WSProbe()
      WS("/channel/notebook?username=tester3", wsClient.flow) ~>  routes ~>
        check {
          // check response for WS Upgrade headers
          checkConnection(wsClient)
          val big = KappaProject("big")
          val Loaded(proj :: two :: Nil) = checkTestProjects(wsClient)
          val rem: ByteBuffer = Pickle.intoBytes[KappaMessage](Remove("big"))
          checkMessage(wsClient, rem){
            case Done(Remove(_), _) =>
          }
          checkMessage(wsClient, big){
            case Failed(/*KappaProject("big", _, _)*/_, _, _) =>
          }
          val create: ByteBuffer = Pickle.intoBytes[KappaMessage](Create(proj))
          checkMessage(wsClient, create){
            case Done(Create(_, false), _) =>
          }
          checkTestProjects(wsClient)
          wsClient.sendCompletion()
          //wsClient.expectCompletion()
      }
    }


  }

  def checkMessage[T](wsClient: WSProbe, message: ByteBuffer)(partial: PartialFunction[KappaMessage, T]): T = {
    wsClient.sendMessage(pack(message))
    wsClient.inProbe.request(1).expectNextPF {
      case BinaryMessage.Strict(bytes) if {
        Unpickle[KappaMessage].fromBytes(bytes.asByteBuffer) match {
          case l =>
            if(partial.isDefinedAt(l)) true else {
            println("checkProjects failed with: " + l)
            false
            }
        }
      } =>
        val value = Unpickle[KappaMessage].fromBytes(bytes.asByteBuffer)
        partial(value)
    }
  }

  def checkMessage[T](wsClient: WSProbe, projectToLoad: KappaProject)(partial: PartialFunction[KappaMessage, T]): T =
  {
    val bytes = Pickle.intoBytes[KappaMessage](Load(projectToLoad))
    checkMessage[T](wsClient, bytes)(partial)
  }


  def checkTestProjects(wsClient: WSProbe): Loaded = checkMessage(wsClient, KappaProject("big")){
    case l @ Loaded(proj :: two :: Nil) =>
      //println("LOADED = "+ l)
      proj.name shouldEqual "big"
      proj.folder.files.map(_.name) shouldEqual Set("big_0.ka", "big_1.ka", "big_2.ka")
      l
  }

  def checkConnection(wsClient: WSProbe): Unit = {
    isWebSocketUpgrade shouldEqual true

    wsClient.inProbe.request(1).expectNextPF {
      case BinaryMessage.Strict(bytes) if {
        Unpickle[KappaMessage].fromBytes(bytes.asByteBuffer) match {
          case c: Connected => true
          case _ => false
        }
      } =>
    }
  }
}
