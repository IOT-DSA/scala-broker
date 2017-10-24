package models.metrics

import org.joda.time.DateTime

/**
 * Manages cluster event persistence.
 */
trait MemberEventDao {

  /**
   * Saves a broker cluster member event.
   */
  def saveMemberEvent(evt: MemberEvent): Unit

  /**
   * Saves a broker cluster member event.
   */
  def saveMemberEvent(ts: DateTime, role: String, address: String, state: String): Unit =
    saveMemberEvent(MemberEvent(ts, role, address, state))

  /**
   * Finds cluster member events satisfying the criteria.
   */
  def findMemberEvents(role: Option[String], address: Option[String],
                       from: Option[DateTime], to: Option[DateTime]): ListResult[MemberEvent]
}