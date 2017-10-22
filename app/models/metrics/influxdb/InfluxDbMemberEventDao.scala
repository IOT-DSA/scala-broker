package models.metrics.influxdb

import com.paulgoldbaum.influxdbclient.{ Database, Point }

import models.metrics.{ MemberEvent, MemberEventDao }

/**
 * InfluxDB-based implementation of [[MemberEventDao]].
 */
class InfluxDbMemberEventDao(db: Database) extends InfluxDbGenericDao(db) with MemberEventDao {

  /**
   * Saves a member event as a point in 'cluster' measurement.
   */
  def saveMemberEvent(evt: MemberEvent): Unit = {
    val baseTags = tags("role" -> evt.role, "address" -> evt.address)
    val baseFields = fields("state" -> evt.state)

    val point = Point("cluster", evt.ts.getMillis, baseTags, baseFields)
    savePoint(point)
  }
}