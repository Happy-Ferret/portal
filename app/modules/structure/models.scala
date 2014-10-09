package modules.structure

import modules.identity.{User, RolesStore, Role}
import play.api.libs.json._

import scala.concurrent.{Future, ExecutionContext}

case class Position(column: Int, line: Int)

case class Mashete(id: String, name: String, description: String, url: String, defaultParam: JsObject)

case class MasheteInstance(id: String, masheteId: String, position: Position, instanceConfig: JsObject)

case class Page(
                 id: String,
                 name: String,
                 description: String,
                 url: String,
                 accessibleBy: Seq[String],
                 subPages: Seq[Page],
                 mashetes: Seq[MasheteInstance],
                 leftColSize: Int = play.api.Play.current.configuration.getInt("portal.left-width").getOrElse(6),
                 rightColSize: Int = play.api.Play.current.configuration.getInt("portal.right-width").getOrElse(6)
       ) {

  def accessibleByRoles(implicit ec: ExecutionContext) = Future.sequence(accessibleBy.map(RolesStore.role).map(_.collect { case Some(role) => role }))
  def toJson = Page.pageFmt.writes(this)
  def toJsonString = Json.stringify(toJson)
  def toHtml(user: User): String = {
    if (subPages.nonEmpty) {
      s"""<li class="dropdown">
        <a href="$url" class="dropdown-toggle" data-toggle="dropdown">$name<span class="caret"></span></a>
          <ul class="dropdown-menu" role="menu">""" +
            subPages.filter(p => p.accessibleBy.intersect(user.roles).size > 0).map(_.toHtml(user)).mkString("") + "</ul></li>"
    } else {
      s"""<li><a href="$url">$name</a></li>"""
    }
  }
}

object Mashete {
  implicit val masheteFmt = Json.format[Mashete]
}

object Position {
  implicit val positionFmt = Json.format[Position]
}

object MasheteInstance {
  implicit val masheteFmt = Json.format[MasheteInstance]
}

object Page {
  implicit val pageFmt = Json.format[Page]
}