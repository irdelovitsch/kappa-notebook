package org.denigma.kappa.notebook.views.editor

import org.denigma.binding.binders.Events
import org.denigma.binding.commons.Uploader
import org.denigma.binding.extensions._
import org.denigma.binding.views.{BindableView, UpdatableView}
import org.denigma.codemirror._
import org.denigma.codemirror.addons.lint._
import org.denigma.codemirror.extensions._
import org.denigma.kappa.messages.{FileRequests, KappaSourceFile}
import org.denigma.kappa.messages.WebSimMessages.WebSimError
import org.denigma.kappa.notebook.views.common.TabItem
import org.scalajs.dom
import org.scalajs.dom.Event
import org.scalajs.dom.raw.{Element, HTMLTextAreaElement}
import rx.Ctx.Owner.Unsafe.Unsafe
import rx._

import scala.scalajs.js
import scala.util._

class CodeTab(val elem: Element,
              val path: String,
              val source: Var[KappaSourceFile],
              val selected: Var[String],
              val editorUpdates: Var[EditorUpdates],
              val kappaCursor: Var[KappaCursor],
              val errors: Rx[List[WebSimError]]
             )  
  extends BindableView 
    with EditorView
    with TabItem
{
  val wrapLines: Var[Boolean] = Var(false)

  lazy val name = path.lastIndexOf("/") match {
    case -1 => path
    case ind if ind == path.length -1 => path
    case ind => path.substring(ind+1)
  }

  override lazy val id: String = path

  override def mode = "Kappa"

  val notSaved = source.map(s => !s.saved)

  /*
  val saveClick = Var(Events.createMouseEvent())
  saveClick.triggerLater{
    val saveRequest = FileRequests.Save("", List(source.now), rewrite = true, getSaved = true)
  }
  */

  active.onChange{
    case true =>
      //if(this._editor!=null) editor.refresh()

    case false =>
      elem.style.display = "none"
      //println("active value for "+id+" is false and display is "+elem.style.display)
  }

  errors.onChange{
    case ers=>
      import scalajs.js.JSConverters._
      //dom.console.error(s"CodeTab $name ERRORS: "+ers.toList.mkString("\n"))
      val found: List[LintFound] = ers.map{case e=> e:LintFound}
      import scalajs.js.JSConverters._
      def gts(text: String, options: LintOptions, cm: Editor): js.Array[LintFound] = {
        found.toJSArray
      }
      val fun: js.Function3[String, LintOptions, Editor, js.Array[LintFound]] = gts _

      editor.setOption("lint", js.Dynamic.literal(
        getAnnotations = fun
      ))
  }

  val code = Var(source.now.content)
  code.onChange{
    case str =>
      if(source.now.content!=str) source() = source.now.copy(content = str, saved = false)
  }

  override def unbindView() = {
    super.unbindView()
    source.kill()
  }
  
  protected def onCursorActivity(ed: Editor) = {
    val c = doc.getCursor()
    kappaCursor.now match {
      case EmptyCursor =>
        editor.addLineClass(c.line, "background", "focused")
        kappaCursor() = KappaEditorCursor(source.now, editor, c.line, c.ch)

      case KappaEditorCursor(fl, editor, prevLine, prevCh) if prevLine != c.line || prevCh != c.ch || editor != ed =>
        editor.addLineClass(c.line, "background", "focused")
        editor.removeLineClass(prevLine, "background", "focused")
        kappaCursor() = KappaEditorCursor(source.now, ed, c.line, c.ch)

      case _ => //do nothing
    }
  }

  override def makeEditor(area: HTMLTextAreaElement, textValue: String, codeMode: String, readOnly: Boolean = false): Editor = {
    val params: EditorConfigurationBuilder = EditorConfig
      .mode(codeMode)
      .lineNumbers(true)
      .value(textValue)
      .readOnly(readOnly)
      .viewportMargin(Integer.MAX_VALUE)
       .extraKeys(js.Dictionary( ("Alt-F", "findPersistent")))
      .gutters(js.Array(gutters, "CodeMirror-linenumbers", "breakpoints"))
      .lineWrapping(wrapLines.now)

    val config: EditorConfiguration = params
    //config.dyn.scrollbarStyle = null
    CodeMirror.fromTextArea(area, config)
  }
  

  override def addEditor(name: String, element: ViewElement, codeMode: String): Unit = element match {
    case area: HTMLTextAreaElement =>
      editor = this.makeEditor(area, code.now, codeMode)
      code.foreach{  text =>
        if(doc.getValue()!=text) doc.setValue(text)
      }
      editor.addOnChanges(onChanges)
      val handler: (Editor) => Unit = onCursorActivity
      editor.on("cursorActivity", handler)
      if(active.now && kappaCursor.now != EmptyCursor) onCursorActivity(editor)
      editor

    case _ =>
      val message = "cannot find text area for the code!"
      throw new Exception(message)
  }

  val onLeave = Var(Events.createMouseEvent())
  onLeave.triggerLater{
    updateCursor()
  }

  val save = Var(Events.createMouseEvent())
  save.triggerLater{
    //saveAs(name, code.now)
  }

  override def onChanges(ed: Editor, ch: js.Array[EditorChangeLike]): Unit = {
    editorUpdates() = EditorUpdates(Option(ed), ch.toList)
    val value = doc.getValue()
    if(value != code.now) code() = value

    //editor.setOption("lint", true) //trigger linting
    //println("lint = true")
    updateCursor()
  }

  protected def updateCursor() = {
    val cur: Position = doc.getCursor()
    kappaCursor() = KappaEditorCursor(source.now, editor, cur.line, cur.ch)
  }
}
