package org.denigma.kappa.notebook.pages

import akka.http.extensions.security._
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.ws.{Message, UpgradeToWebSocket}
import akka.http.scaladsl.server._
import akka.stream.scaladsl.Flow

import scala.concurrent.Future


class WebSockets(
                //loginByName: (String, String) => Future[LoginResult],
                //loginByEmail: (String, String) => Future[LoginResult],
                makeChannel: (String, String) => Flow[Message, Message, Any]
                ) extends AuthDirectives with Directives with WithLoginRejections with WithRegistrationRejections
{
  def routes: Route =
    pathPrefix("channel"){
      pathPrefix("notebook"){
        parameter("username"){
          username=>
            println(s"username = $username")
            handleWebSocketMessages(makeChannel("notebook", username))
        }
      }
    }
}
