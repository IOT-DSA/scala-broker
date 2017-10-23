package models.metrics

import scala.concurrent.Future

import org.joda.time.DateTime

import models.Settings.{ InfluxDb, Metrics }
import models.metrics.influxdb._

/**
 * Encapsulates all event DAOs. It uses the application config to instantiate the proper
 * implementation of DAOs.
 */
object MetricDao {

  type DaoTuple = (MemberEventDao, DSLinkEventDao, RequestEventDao, ResponseEventDao)

  val (memberEventDao, dslinkEventDao, requestEventDao, responseEventDao) = Metrics.Collector match {
    case "influxdb" => createInfluxDaos
    case _          => createNullDaos
  }

  /**
   * Creates InfluxDB-based DAOs.
   */
  private def createInfluxDaos: DaoTuple = {
    val dbConn = influxdb.connectToInfluxDB
    sys.addShutdownHook(dbConn.close)
    val db = dbConn.selectDatabase(InfluxDb.DbName)
    (new InfluxDbMemberEventDao(db), new InfluxDbDSLinkEventDao(db),
      new InfluxDbRequestEventDao(db), new InfluxDbResponseEventDao(db))
  }

  /**
   * Creates empty DAO stubs.
   */
  private def createNullDaos: DaoTuple = (new NullMemberEventDao, new NullDSLinkEventDao,
    new NullRequestEventDao, new NullResponseEventDao)

  /*
   * Null DAOs (empty implementation).
   */

  class NullMemberEventDao extends MemberEventDao {
    def saveMemberEvent(evt: MemberEvent): Unit = {}
    def findMemberEvents(role: Option[String], address: Option[String],
                         from: Option[DateTime],
                         to: Option[DateTime]): Future[List[MemberEvent]] = Future.successful(Nil)
  }

  class NullDSLinkEventDao extends DSLinkEventDao {
    def saveConnectionEvent(evt: ConnectionEvent): Unit = {}
    def saveSessionEvent(evt: LinkSessionEvent): Unit = {}
  }

  class NullRequestEventDao extends RequestEventDao {
    def saveRequestMessageEvent(evt: RequestMessageEvent): Unit = {}
    def saveRequestBatchEvent(evt: RequestBatchEvent): Unit = {}
  }

  class NullResponseEventDao extends ResponseEventDao {
    def saveResponseMessageEvent(evt: ResponseMessageEvent): Unit = {}
  }
}