package models

import java.sql.{Connection, DriverManager}

import scala.collection.Seq
import com.paulgoldbaum.influxdbclient.Database
import _root_.akka.actor._
import _root_.akka.actor.{ActorRef => AkkaActorRef}
import _root_.akka.cluster.{Cluster => AkkaCluster}
import _root_.akka.cluster.ddata.DistributedData
import javax.inject.{Inject, Provider, Singleton}

import models.Settings.{InfluxDb, JDBC, Metrics}
import models.akka.{BrokerActors, DSLinkManager}
import akka.cluster.{ClusteredDSLinkManager}
import models.akka.local.LocalDSLinkManager
import models.api.DistributedNodesRegistry
import models.handshake.LocalKeys
import models.metrics._
import models.metrics.NullDaos._
import models.metrics.influxdb._
import models.metrics.jdbc._
import play.api.{Configuration, Environment}
import play.api.inject.Module
import com.google.inject.name.Names

/**
 * Provides module bindings.
 */
class MainModule extends Module {

  def bindings(environment: Environment, configuration: Configuration) = {
    Seq(
      bind[LocalKeys].to(LocalKeys.getFromClasspath("/keys")),
      bind[BrokerActors].toSelf.eagerly) ++ createEventDaoBindings

  }

  private def createEventDaoBindings = Metrics.Collector match {
    case "jdbc"     => jdbcBindings
    case "influxdb" => influxDbBindings
    case _          => nullBindings
  }

  private def jdbcBindings = Seq(
    bind[Connection].toProvider[JdbcConnectionProvider],
    bind[MemberEventDao].to[JdbcMemberEventDao],
    bind[DSLinkEventDao].to[JdbcDSLinkEventDao],
    bind[RequestEventDao].to[JdbcRequestEventDao],
    bind[ResponseEventDao].to[JdbcResponseEventDao])

  private def influxDbBindings = Seq(
    bind[Database].toProvider[InfluxDatabaseProvider],
    bind[MemberEventDao].to[InfluxDbMemberEventDao],
    bind[DSLinkEventDao].to[InfluxDbDSLinkEventDao],
    bind[RequestEventDao].to[InfluxDbRequestEventDao],
    bind[ResponseEventDao].to[InfluxDbResponseEventDao])

  private def nullBindings = Seq(
    bind[MemberEventDao].to[NullMemberEventDao],
    bind[DSLinkEventDao].to[NullDSLinkEventDao],
    bind[RequestEventDao].to[NullRequestEventDao],
    bind[ResponseEventDao].to[NullResponseEventDao])
}


/**
 * Provides an instance of InfluxDb database.
 */
@Singleton
class InfluxDatabaseProvider extends Provider[Database] {
  private val dbConn = influxdb.connectToInfluxDB(InfluxDb.Host, InfluxDb.Port)
  sys.addShutdownHook(dbConn.close)
  private val db = dbConn.selectDatabase(InfluxDb.DbName)

  def get = db
}

/**
 * Provides an instance of JDBC connection.
 */
@Singleton
class JdbcConnectionProvider extends Provider[Connection] {
  Class.forName(JDBC.Driver)
  private val conn = DriverManager.getConnection(JDBC.Url)
  createDatabaseSchema(conn)
  sys.addShutdownHook(conn.close)

  def get = conn
}