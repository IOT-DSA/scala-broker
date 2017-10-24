package models.metrics

import java.sql.DriverManager

import scala.concurrent.Future

import org.joda.time.DateTime

import models.Settings.{ InfluxDb, JDBC, Metrics }
import models.metrics.influxdb._
import models.metrics.jdbc._

/**
 * Encapsulates all event DAOs. It uses the application config to instantiate the proper
 * implementation of DAOs.
 */
object MetricDao {

  type DaoTuple = (MemberEventDao, DSLinkEventDao, RequestEventDao, ResponseEventDao)

  val (memberEventDao, dslinkEventDao, requestEventDao, responseEventDao) = Metrics.Collector match {
    case "jdbc"     => createJdbcDaos
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
   * Creates JDBC-based DAOs.
   */
  private def createJdbcDaos: DaoTuple = {
    Class.forName(JDBC.Driver)
    val conn = DriverManager.getConnection(JDBC.Url)
    jdbc.createDatabaseSchema(conn)
    sys.addShutdownHook(conn.close)
    (new JdbcMemberEventDao(conn), new JdbcDSLinkEventDao(conn),
      new JdbcRequestEventDao(conn), new JdbcResponseEventDao(conn))
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
    def findConnectionEvents(linkName: Option[String], from: Option[DateTime],
                             to: Option[DateTime], limit: Int): ListResult[ConnectionEvent] = Future.successful(Nil)
    def saveSessionEvent(evt: LinkSessionEvent): Unit = {}
    def findSessionEvents(linkName: Option[String], from: Option[DateTime],
                          to: Option[DateTime], limit: Int): ListResult[LinkSessionEvent] = Future.successful(Nil)
  }

  class NullRequestEventDao extends RequestEventDao {
    def saveRequestMessageEvent(evt: RequestMessageEvent): Unit = {}
    def saveRequestBatchEvent(evt: RequestBatchEvent): Unit = {}
  }

  class NullResponseEventDao extends ResponseEventDao {
    def saveResponseMessageEvent(evt: ResponseMessageEvent): Unit = {}
  }
}