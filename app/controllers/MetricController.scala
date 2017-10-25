package controllers

import scala.concurrent.Future

import org.joda.time.DateTime

import akka.actor.ActorSystem
import javax.inject.{ Inject, Singleton }
import models.metrics._
import models.metrics.MetricDao.{ dslinkEventDao, memberEventDao, requestEventDao, responseEventDao }
import play.api.libs.json.{ JsValue, Json, Writes }
import play.api.mvc.{ Action, Controller, Result }

/**
 * Handles statistics requests.
 */
@Singleton
class MetricController @Inject() (implicit actorSystem: ActorSystem) extends Controller {

  import actorSystem.dispatcher

  implicit val dateWrites = Writes.jodaDateWrites("yyyy-MM-dd HH:mm:ss.SSS")

  implicit val memberEventWrites = Json.writes[MemberEvent]

  implicit val connectionEventWrites = Json.writes[ConnectionEvent]

  implicit val sessionEventWrites = Json.writes[LinkSessionEvent]

  implicit val reqStatsByLinkWrites = Json.writes[RequestStatsByLink]

  implicit val reqStatsByMethodWrites = Json.writes[RequestStatsByMethod]

  implicit val rspStatsByLinkWrites = Json.writes[ResponseStatsByLink]

  /**
   * Displays cluster member events.
   */
  def memberEvents(role: Option[String], address: Option[String], from: Option[Long], to: Option[Long]) = Action.async {
    val (tsFrom, tsTo) = (optDateTime(from), optDateTime(to))
    memberEventDao.findMemberEvents(role, address, tsFrom, tsTo): Future[Result]
  }

  /**
   * Display dslink connection events.
   */
  def connectionEvents(linkName: Option[String], from: Option[Long], to: Option[Long]) = Action.async {
    val (tsFrom, tsTo) = (optDateTime(from), optDateTime(to))
    dslinkEventDao.findConnectionEvents(linkName, tsFrom, tsTo): Future[Result]
  }

  /**
   * Display dslink session events.
   */
  def sessionEvents(linkName: Option[String], from: Option[Long], to: Option[Long]) = Action.async {
    val (tsFrom, tsTo) = (optDateTime(from), optDateTime(to))
    dslinkEventDao.findSessionEvents(linkName, tsFrom, tsTo): Future[Result]
  }

  /**
   * Displays request statistics.
   */
  def requestStatsByLink(from: Option[Long], to: Option[Long]) = Action.async {
    val (tsFrom, tsTo) = (optDateTime(from), optDateTime(to))
    requestEventDao.getRequestStats(tsFrom, tsTo): Future[Result]
  }

  /**
   * Displays request batch statistics.
   */
  def requestStatsByMethod(from: Option[Long], to: Option[Long]) = Action.async {
    val (tsFrom, tsTo) = (optDateTime(from), optDateTime(to))
    requestEventDao.getRequestBatchStats(tsFrom, tsTo): Future[Result]
  }

  /**
   * Displays response statistics.
   */
  def responseStatsByLink(from: Option[Long], to: Option[Long]) = Action.async {
    val (tsFrom, tsTo) = (optDateTime(from), optDateTime(to))
    responseEventDao.getResponseStats(tsFrom, tsTo): Future[Result]
  }

  /**
   * Converts a JSON value into a (pretty-printed) HTTP Result.
   */
  implicit protected def json2result(json: JsValue): Result = Ok(Json.prettyPrint(json)).as(JSON)

  /**
   * Converts a value into a HTTP Result as a JSON entity.
   */
  implicit protected def model2result[T](obj: T)(implicit writes: Writes[T]): Result = Json.toJson(obj): Result

  /**
   * Converts a future value into a future HTTP Result.
   */
  implicit protected def futureModel2Result[T](fo: Future[T])(implicit writes: Writes[T]): Future[Result] =
    fo.map(model2result(_))

  /**
   * Converts an optional long value into an optional DateTime.
   */
  def optDateTime(om: Option[Long]) = om.map(new DateTime(_))
}