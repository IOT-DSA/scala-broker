package models.metrics

import scala.concurrent.Future

import org.joda.time.DateTime

/**
 * /dev/null DAOs (empty implementations).
 */
object NullDaos {

  /**
   * Null MemberEvent DAO.
   */
  class NullMemberEventDao extends MemberEventDao {
    def saveMemberEvent(evt: MemberEvent): Unit = {}
    def findMemberEvents(role: Option[String], address: Option[String],
                         from: Option[DateTime],
                         to:   Option[DateTime]): Future[List[MemberEvent]] = Future.successful(Nil)
  }

  /**
   * Null DSLinkEvent DAO.
   */
  class NullDSLinkEventDao extends DSLinkEventDao {
    def saveConnectionEvent(evt: ConnectionEvent): Unit = {}
    def findConnectionEvents(linkName: Option[String], from: Option[DateTime],
                             to:    Option[DateTime],
                             limit: Int): ListResult[ConnectionEvent] = Future.successful(Nil)
    def saveSessionEvent(evt: LinkSessionEvent): Unit = {}
    def findSessionEvents(linkName: Option[String], from: Option[DateTime],
                          to:    Option[DateTime],
                          limit: Int): ListResult[LinkSessionEvent] = Future.successful(Nil)
  }

  /**
   * Null RequestEvent DAO.
   */
  class NullRequestEventDao extends RequestEventDao {
    def saveRequestMessageEvent(evt: RequestMessageEvent): Unit = {}
    def getRequestStats(from: Option[DateTime], to: Option[DateTime]): ListResult[RequestStatsByLink] =
      Future.successful(Nil)
    def saveRequestBatchEvent(evt: RequestBatchEvent): Unit = {}
    def getRequestBatchStats(from: Option[DateTime], to: Option[DateTime]): ListResult[RequestStatsByMethod] =
      Future.successful(Nil)
  }

  /**
   * Null ResponseEvent DAO.
   */
  class NullResponseEventDao extends ResponseEventDao {
    def saveResponseMessageEvent(evt: ResponseMessageEvent): Unit = {}
    def getResponseStats(from: Option[DateTime], to: Option[DateTime]): ListResult[ResponseStatsByLink] =
      Future.successful(Nil)
  }
}