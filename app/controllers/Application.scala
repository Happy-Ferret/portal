package controllers

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, Props}
import akka.util.Timeout
import common.IdGenerator
import modules.Env
import modules.communication.UserActor
import modules.identity.{AnonymousUser, User}
import modules.structure.{Position, MasheteInstance, Page}
import play.api.{Mode, Logger}
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee.Concurrent
import play.api.libs.iteratee.Concurrent.Channel
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.WS
import play.api.libs.{Crypto, EventSource}
import play.api.mvc._
import play.twirl.api.Html

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

// TODO : multitenant ????

// TODO : add account management page for one user

// TODO : add portal management page with
// TODO :   - external consumer tokens
// TODO :   - allowed SSO domains
// TODO :   - mashetes store to add mashetes to the portal from urls
// TODO :   - users management page
// TODO :   - LDAP mapping
// TODO :   - add pages management, in a global way (site map)

object Application extends Controller {

  lazy val cookieName = "PORTAL_SESSION"
  lazy val portalName = play.api.Play.current.configuration.getString("portal.name").getOrElse("Portal")
  lazy val portalCallbackUrl = play.api.Play.current.configuration.getString("portal.service-url").getOrElse("http://localhost:9000/callback")
  lazy val portalSSOServiceUrl = play.api.Play.current.configuration.getString("portal.sso-service-url").getOrElse("http://localhost:9000/validate")
  lazy val discard = Seq(
    DiscardingCookie(name = cookieName, path = "/", domain = None)
  )

  def UserAction(url: String)(f: ((Request[AnyContent], User, Page)) => Future[Result]) = {
    Logger.trace(s"Accessing secured url : $url")
    Action.async { rh =>
      val fuser: Future[User] = rh.cookies.get(cookieName).map { cookie: Cookie =>
        Crypto.decryptAES(cookie.value).split(":::").toList match {
          case hash :: userLogin :: Nil if Crypto.sign(userLogin) == hash => Env.userStore.findByEmail(userLogin)
          case _ => Future.successful(None)
        }
      }.getOrElse(Future.successful(Some(AnonymousUser))).map(_.getOrElse(AnonymousUser))
      fuser.flatMap { user =>
        Env.pageStore.findByUrl(url).flatMap {
          case Some(page) => {
            if (page.accessibleByIds.intersect(user.roles).size > 0) {
              f(rh, user, page)
            } else {
              Future.successful(Redirect(routes.Authentication.loginPage(portalCallbackUrl)))
            }
          }
          case None => Future.successful(NotFound("Page not found :'("))
        }
      }
    }
  }

  def buildCookie(login: String) = {
    Cookie(
      name = cookieName,
      value = Crypto.encryptAES(s"${Crypto.sign(login)}:::$login"),
      maxAge = Some(2592000),
      path = "/",
      domain = None
    )
  }

  def callback(ticket: String) = Action.async {
    WS.url(portalSSOServiceUrl).withQueryString("ticket" -> ticket).get().map { response =>
      val login = (response.json \ "email").as[String]
      Redirect("/").withCookies(buildCookie(login))
    }.recover {
      case _ => Redirect("/").discardingCookies(discard:_*)
    }
  }

  def index = UserAction("/") {
    case (request, user, page) => {
      for {
        subTree <- Env.pageStore.directSubPages(user, page).map(ps => Html(ps.map(p => p.toHtml(user)).mkString("")))
        all <- Env.masheteStore.findAll()
        roles <- Env.roleStore.findAll()
      } yield Ok(views.html.index(portalName, portalCallbackUrl, user, page, all, subTree, roles))
    }
  }

  def page(url: String) = UserAction("/site/" + url) {
    case (request, user, page) => {
      for {
        root <- Env.pageStore.findByUrl("/")
        subTree <- Env.pageStore.directSubPages(user, root.getOrElse(page)).map(ps => Html(ps.map(p => p.toHtml(user)).mkString("")))
        all <- Env.masheteStore.findAll()
        roles <- Env.roleStore.findAll()
      } yield Ok(views.html.index(portalName, portalCallbackUrl, user, page, all, subTree, roles))
    }
  }

  def userStreamWebsocket = WebSocket.acceptWithActor[JsValue, JsValue] { rh =>
    val user: Future[User] = rh.cookies.get(cookieName).map { cookie: Cookie =>
      Crypto.decryptAES(cookie.value).split(":::").toList match {
        case hash :: userLogin :: Nil if Crypto.sign(userLogin) == hash => Env.userStore.findByEmail(userLogin)
        case _ => Future.successful(Some(AnonymousUser))
      }
    }.getOrElse(Future.successful(Some(AnonymousUser))).map(_.getOrElse(AnonymousUser))
    def builder(out: ActorRef) = Props(classOf[UserActor], out, user)
    builder
  }

  def userStreamSSEFallbackIn(token: String) = Action(parse.text) { rh =>
    rh.cookies.get(cookieName).map { cookie: Cookie =>
      Crypto.decryptAES(cookie.value).split(":::").toList match {
        case hash :: userLogin :: Nil if Crypto.sign(userLogin) == hash => Env.userStore.findByEmail(userLogin)
        case _ => Future.successful(Some(AnonymousUser))
      }
    }.getOrElse(Future.successful(Some(AnonymousUser))).map(_.getOrElse(AnonymousUser)).map { user =>
      val actor = s"/user/fallback-actor-sse-${user.email}-${token}"
      println("fetching actor " + actor)
      // TODO : make it work in a distributed environement
      Akka.system(play.api.Play.current).actorSelection(actor) ! Json.parse(rh.body)
    }
    Ok
  }

  def userStreamSSEFallbackOut(token: String) = Action { rh =>
    val fuser = rh.cookies.get(cookieName).map { cookie: Cookie =>
      Crypto.decryptAES(cookie.value).split(":::").toList match {
        case hash :: userLogin :: Nil if Crypto.sign(userLogin) == hash => Env.userStore.findByEmail(userLogin)
        case _ => Future.successful(Some(AnonymousUser))
      }
    }.getOrElse(Future.successful(Some(AnonymousUser))).map(_.getOrElse(AnonymousUser))
    val enumerator = Concurrent.unicast[JsValue] { channel =>
      fuser.map { user =>
        val out = Akka.system(play.api.Play.current).actorOf(Props(classOf[FallbackResponseActor], channel))
        val actor = s"fallback-actor-sse-${user.email}-${token}"
        try {
          Akka.system(play.api.Play.current).actorOf(Props(classOf[UserActor], out, Future.successful(user)), actor)
        } catch{
          case e: Throwable => e.printStackTrace()
        }
        println("created " + actor)
      }
    }
    Ok.feed(enumerator &> EventSource()).as("text/event-stream")
  }

  def userStreamHttpFallbackInOut(token: String) = Action.async(parse.text) { rh =>
    // TODO : cannot work with server push
    val promise = Promise[JsValue]()
    val fuser = rh.cookies.get(cookieName).map { cookie: Cookie =>
      Crypto.decryptAES(cookie.value).split(":::").toList match {
        case hash :: userLogin :: Nil if Crypto.sign(userLogin) == hash => Env.userStore.findByEmail(userLogin)
        case _ => Future.successful(Some(AnonymousUser))
      }
    }.getOrElse(Future.successful(Some(AnonymousUser))).map(_.getOrElse(AnonymousUser)).map { user =>
      val actorKey = s"fallback-actor-http-${user.email}-${token}"
      val actorRef = s"/user/fallback-actor-http-${user.email}-${token}"
      val out = Akka.system(play.api.Play.current).actorOf(Props(classOf[FallbackHttpResponseActor], promise))
      Akka.system(play.api.Play.current).actorSelection(actorRef).resolveOne()(Timeout(1, TimeUnit.SECONDS)).onComplete {
        case Success(ref) => ref ! Json.parse(rh.body)
        case Failure(e) => {
          val ref = Akka.system(play.api.Play.current).actorOf(Props(classOf[UserActor], out, Future.successful(user)), actorKey)
          ref ! Json.parse(rh.body)
        }
      }
    }
    promise.future.map(p => Ok(p))
  }

  def devEnv(masheteId: String) = Action.async {
    play.api.Play.current.mode match {
      case Mode.Dev => {
        Env.masheteStore.findById(masheteId) map {
          case None => InternalServerError(s"Mashete $masheteId does not exist")
          case Some(mashete) => {
            val instance = MasheteInstance(IdGenerator.uuid, mashete._id, Position(0, 0), mashete.defaultParam)
            Ok(views.html.dev(instance, mashete))
          }
        }
      }
      case _ => Future.successful(InternalServerError("Dev environment is not available in Prod mode"))
    }
  }
}

class FallbackResponseActor(channel: Channel[JsValue]) extends Actor {
  override def receive: Receive = {
    case js: JsValue => channel.push(js)
    case _ =>
  }
}

class FallbackHttpResponseActor(channel: Promise[JsValue]) extends Actor {
  override def receive: Receive = {
    case js: JsValue => channel.trySuccess(js)
    case _ => channel.tryFailure(new RuntimeException("Bad payload"))
  }
}
