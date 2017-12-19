package models.metrics.influxdb

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

import org.joda.time.DateTime

import com.paulgoldbaum.influxdbclient.{ Database, Point, Record }
import com.paulgoldbaum.influxdbclient.Parameter.Precision.MILLISECONDS

import javax.inject.Inject
import models.metrics.{ MemberEvent, MemberEventDao }

/**
 * InfluxDB-based implementation of [[MemberEventDao]].
 */
class InfluxDbMemberEventDao @Inject() (db: Database) extends InfluxDbGenericDao(db) with MemberEventDao {

  /**
   * Saves a member event as a point in 'cluster' measurement.
   */
  def saveMemberEvent(evt: MemberEvent): Unit = {
    val baseTags = tags("role" -> evt.role, "address" -> evt.address)
    val baseFields = fields("state" -> evt.state)

    val point = Point("cluster", evt.ts.getMillis, baseTags, baseFields)
    savePoint(point)
  }

  /**
   * Filters 'cluster' measurement for records satisfying the criteria.
   */
  def findMemberEvents(role: Option[String], address: Option[String],
                       from: Option[DateTime], to: Option[DateTime]): Future[List[MemberEvent]] = {

    val where = buildWhere(eq("role", role), eq("address", address),
      ge("time", from), le("time", to))

    val query = "SELECT * FROM cluster" + where + " ORDER BY time DESC"
    val fqr = db.query(query, MILLISECONDS)
    fqr map { qr =>
      val records = qr.series.flatMap(_.records)
      records map recordToMemberEvent
    }
  }

  /**
   * Converts an InfluxDB record into a member event instance.
   */
  private def recordToMemberEvent(record: Record) = {
    val ts = new DateTime(record("time").asInstanceOf[Number].longValue)
    val role = record("role").asInstanceOf[String]
    val address = record("address").asInstanceOf[String]
    val state = record("state").asInstanceOf[String]
    MemberEvent(ts, role, address, state)
  }
}