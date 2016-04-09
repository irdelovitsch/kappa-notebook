package org.denigma.kappa.notebook.views

import fastparse.all._
import org.denigma.binding.binders._
import org.denigma.binding.commons.Uploader
import org.denigma.binding.extensions._
import org.denigma.binding.views.BindableView
import org.denigma.codemirror.{Editor, EditorChangeLike}
import org.denigma.controls.code.CodeBinder
import org.denigma.controls.login.Session
import org.denigma.controls.sockets.WebSocketSubscriber
import org.denigma.kappa.notebook.views.editor.{CommentsWatcher, EditorUpdates, KappaCodeEditor}
import org.denigma.kappa.notebook.{KappaHub, WebSocketTransport}
import org.scalajs.dom
import org.scalajs.dom._
import org.scalajs.dom.html._
import org.scalajs.dom.raw.Element
import rx._
import rx.Ctx.Owner.Unsafe.Unsafe

import scala.scalajs.js
import scala.util._
import scalatags.JsDom.all._
import org.denigma.kappa.WebSim

class NotebookView(val elem: Element, val session: Session) extends BindableView with Uploader
{
  self =>

  lazy val subscriber = WebSocketSubscriber("notebook", "guest" + Math.random() * 1000)

  val hub: KappaHub = KappaHub.empty


  val connector: WebSocketTransport = WebSocketTransport(subscriber, hub)
  subscriber.onOpen.triggerLater{
    println("websocket opened onload")
    connector.send(WebSim.Load("model.ka"))
  }

  val run = Var(org.denigma.binding.binders.Events.createMouseEvent)
  run.triggerLater{
    //dom.console.log("sending the code...")
    hub.runParameters() = hub.runParameters.now.copy(code = code.now)
    connector.send(hub.runParameters.now)
  }

  val initialCode =
    """
      |####### ADD YOUR CODE HERE #############
      |
      |#### Signatures
      |
      |#### Rules
      |
      |#### Variables
      |
      |#### Observables
      |
      |#### Initial conditions
      |
      |#### Modifications
    """.stripMargin

  val code = Var(initialCode)
  code.onChange{ case txt=>
    hub.kappaCode() = hub.kappaCode.now.copy(text = txt)
  }

  hub.kappaCode.onChange{case v =>
    if(v.isEmpty) code.set("") else code.set(v.text)
  }


  val save = Var(Events.createMouseEvent())
  save.triggerLater{
    saveAs(hub.name.now, code.now)
  }

  val onUpload: Var[Event] = Var(Events.createEvent())
  onUpload.onChange(ev =>
    this.uploadHandler(ev){
      case Success((file, text))=>
        hub.name() = file.name
        //hub.runParameters.set(hub.runParameters.now.copy(fileName = file.name))
        code.set(text)
      case Failure(th) => dom.console.error(s"File upload failure: ${th.toString}")
    })


  val editorsUpdates: Var[EditorUpdates] = Var(EditorUpdates.empty) //collect updates of all editors together

  val commentManager = new CommentsWatcher(editorsUpdates, hub.paperLocation)

   override lazy val injector = defaultInjector
     .register("Parameters")((el, args) => new RunnerView(el, hub.name, hub.runParameters).withBinder(n => new CodeBinder(n)))
     .register("KappaEditor")((el, args) => new KappaCodeEditor(el, hub, editorsUpdates).withBinder(n => new CodeBinder(n)))
     .register("Tabs")((el, args) => new TabsView(el, hub).withBinder(n => new CodeBinder(n)))
     .register("GraphView")((el, args) => new GraphView(el).withBinder(n => new CodeBinder(n)))



}



