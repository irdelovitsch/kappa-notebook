package org.denigma.kappa.notebook.views.papers

import org.denigma.binding.binders.Events
import org.denigma.binding.extensions._
import org.denigma.binding.views.CollectionSeqView
import org.denigma.controls.code.CodeBinder
import org.denigma.controls.papers._
import org.scalajs.dom
import org.scalajs.dom.MouseEvent
import org.scalajs.dom.raw._
import rx.Ctx.Owner.Unsafe.Unsafe
import scala.concurrent.duration._
import rx._

import scala.annotation.tailrec

/**
  * Created by antonkulaga on 2/14/16.
  */
class BookmarksView(val elem: Element, location: Var[Bookmark], textLayer: Element) extends CollectionSeqView {

  override type Item = Var[Bookmark]

  override type ItemView = BookmarkView

  override val items: Var[List[Item]] = Var(List.empty)

  val paper = location.map(_.paper)

  val page = location.map(_.page)

  val selectionRanges = Var(List.empty[org.scalajs.dom.raw.Range])

  val lastSelections: Var[List[TextLayerSelection]] = Var(List.empty[TextLayerSelection])

  val comments = Rx{
    "\n#^ :in_paper "+paper() +
      "\n#^ :on_page "+ page() + lastSelections().foldLeft(""){
      case (acc, sel) => acc +
        "\n#^ :from_chunk " + sel.fromChunk
        "\n#^ :from_token_num " + sel.fromToken
        "\n#^ :to_chunk " + sel.toChunk
        "\n#^ :to_token_num " + sel.toToken
    }
  }


  override def newItemView(item: Item): ItemView  = this.constructItemView(item){
    case (el, mp) =>
      new BookmarkView(el, item, location).withBinder(new CodeBinder(_))
  }


  val addSelection = Var(Events.createMouseEvent())

  def addSelectionHandler(event: MouseEvent) = {
      val book = location.now
      val mark = Bookmark(book.paper, book.page, lastSelections.now)
      val item = Var(mark)
      //println(s"NUMBER OF DUPLICATES: "+items.now.count(_==item))
      //println(s"NUMBER OF UNVAR DUPLICATES: "+items.now.count(_.now==item.now))
      if(!items.now.exists(_.now==mark)) items() = items.now ++ (item::Nil)
  }

  @tailrec final def inTextLayer(node: Node): Boolean = if(node == null) false
  else if (node.isEqualNode(textLayer) || textLayer == node || textLayer == node) true
  else if(node.parentNode == null) false else inTextLayer(node.parentNode)

  protected def onSelectionChange(event: Event) = {
    val selection: Selection = dom.window.getSelection()
    val count = selection.rangeCount
     inTextLayer(selection.anchorNode) || inTextLayer(selection.focusNode)  match {
      case true =>
         if (count > 0) {
           selectionRanges() = {
            for{
              i <- 0 until count
              range = selection.getRangeAt(i)
            } yield range
          }.toList
          //val text = selections.foldLeft("")((acc, el)=>acc + "\n" + el.cloneContents().textContent)
          //currentSelection() = text
        }
      case false => //println(s"something else ${selection.anchorNode.textContent}") //do nothing
    }

  }

  override protected def subscribeUpdates() = {
    super.subscribeUpdates()
    selectionRanges.afterLastChange(500 millis){
      case sels=>
        lastSelections() = sels.map{
          case s=>
            val textSelection: TextLayerSelection = TextLayerSelection.fromRange("", s)
            textSelection
        }
    }
  }

  override def bindView() = {
    super.bindView()
    dom.window.document.onselectionchange = onSelectionChange _
    addSelection.onChange(addSelectionHandler)
  }


}
