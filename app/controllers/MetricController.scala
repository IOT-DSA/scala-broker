package controllers

import org.joda.time.DateTime

import akka.actor.ActorSystem
import javax.inject.{ Inject, Singleton }
import models.metrics.{ MemberEvent, MetricDao }
import play.api.libs.json.{ JsValue, Json, Writes }
import play.api.mvc.{ Action, Controller, Result }

/**
 * Handles statistics requests.
 */
@Singleton
class MetricController @Inject() (implicit actorSystem: ActorSystem) extends Controller {

  import actorSystem.dispatcher

  implicit val dateWrites = Writes.jodaDateWrites("yyyy-MM-dd HH:mm:ss.SSS")

  implicit val MemberEventWrites = Json.writes[MemberEvent]

  val dao = MetricDao.memberEventDao

  /**
   * Displays cluster member events.
   */
  def memberEvents(role: Option[String], address: Option[String], from: Option[Long], to: Option[Long]) = Action.async {
    val tsFrom = from.map(millis => new DateTime(millis))
    val tsTo = to.map(millis => new DateTime(millis))
    val fEvents = dao.findMemberEvents(role, address, tsFrom, tsTo)
    fEvents.map(Json.toJson(_): Result)
  }

  /**
   * Converts a JSON value into a (pretty-printed) HTTP Result.
   */
  implicit protected def json2result(json: JsValue): Result = Ok(Json.prettyPrint(json)).as(JSON)
}