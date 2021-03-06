package org.denigma.kappa.notebook.communication

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.ws._
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.util.ByteString
import org.denigma.kappa.messages._
import org.denigma.kappa.notebook.FileManager
import org.denigma.kappa.notebook.communication.SocketMessages._
import boopickle.DefaultBasic._
import com.typesafe.config.Config


/**
  * Websocket transport that unplickles/pickles messages
  */
class WebSocketManager(system: ActorSystem, fileManager: FileManager){

  val config: Config = system.settings.config

  val allRoom = system.actorOf(Props(classOf[RoomActor], "all"))

  val servers = system.actorOf(Props[KappaServerActor])

  protected def makeIncomingFlow(channel: String, username: String) = Flow[Message].map {  case mes => SocketMessages.IncomingMessage(channel, username, mes) }

  protected val outgoingFlow = Flow[SocketMessages.OutgoingMessage].map{ case SocketMessages.OutgoingMessage(_, _, message, _) => message }


  /**
    * Creates a websocket flow to process
    * @param channel name of a websocket channel to connect to
    * @param username name of a user that connects
    * @return
    */
  def openChannel(channel: String, username: String = "guest"): Flow[Message, Message, Any] = {
    val partial: Graph[FlowShape[Message, Message], ActorRef] = GraphDSL.create(
      Source.actorPublisher[OutgoingMessage](Props(classOf[UserActor],
        username,
        servers, //receives a reference to server actor
        fileManager))
    )
    {
      implicit builder => user =>
      import GraphDSL.Implicits._
        val fromWebsocket: FlowShape[Message, IncomingMessage] = builder.add( makeIncomingFlow(channel, username) )
        val backToWebsocket: FlowShape[OutgoingMessage, Message] = builder.add( outgoingFlow )
        val actorAsSource: PortOps[SocketMessages.ChannelMessage] = builder.materializedValue.map{ case actor =>  SocketMessages.UserJoined(username, channel, actor) }

        //send messages to the actor, if send also UserLeft(user) before stream completes.
        val chatActorSink: Sink[ChannelMessage, NotUsed] = Sink.actorRef[SocketMessages.ChannelMessage](allRoom, UserLeft(username, channel))

        val merge: UniformFanInShape[ChannelMessage, ChannelMessage] = builder.add(Merge[SocketMessages.ChannelMessage](2))
        //Message from websocket is converted into IncommingMessage and should be send to each in room
        fromWebsocket ~> merge.in(0)

        //If Source actor is just created should be send as UserJoined and registered as particiant in room
        actorAsSource ~> merge.in(1)

        //Merges both pipes above and forward messages to chatroom Represented by ChatRoomActor
        merge ~> chatActorSink

        user ~> backToWebsocket

      FlowShape( fromWebsocket.in, backToWebsocket.out )

    }.named("socket_flow")

    Flow.fromGraph(partial).recover { case ex =>
      val message = s"WS stream for $channel failed for $username with the following cause:\n  $ex"
      this.system.log.error(message)
      val d = Pickle.intoBytes[KappaMessage](ServerErrors(List(message)))
      BinaryMessage(ByteString(d))
      //throw ex

    }
  }//.via(reportErrorsFlow(channel, username)) // ... then log any processing errors on stdin

}
