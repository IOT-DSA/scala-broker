//package models.influx
//
//import com.paulgoldbaum.influxdbclient.{ Database, Point }
//import com.paulgoldbaum.influxdbclient.Parameter.Consistency.ANY
//import com.paulgoldbaum.influxdbclient.Parameter.Precision.MILLISECONDS
//
//trait TSLogger {
//  def savePoint(point: Point, retention: String = null): Unit
//
//  def savePoints(points: Seq[Point], retention: String = null): Unit
//}
//
//class NullTSLogger extends TSLogger {
//  def savePoint(point: Point, retention: String = null) = {}
//  def savePoints(points: Seq[Point], retention: String = null) = {}
//}
//
//class InfluxTSLogger(db: Database) extends TSLogger {
//
//  def savePoint(point: Point, retention: String = null) =
//    db.write(point, MILLISECONDS, ANY, retention)
//
//  def savePoints(points: Seq[Point], retention: String = null) =
//    db.bulkWrite(points, MILLISECONDS, ANY, retention)
//}
//
//object TSLogger extends TSLogger {
//  import models.Settings._
//
//  private val dbConn = if (CollectMetrics) Some(connectToInfluxDB) else None
//  private val db = dbConn map (_.selectDatabase(Influx.DbName))
//
//  private val logger = db map (new InfluxTSLogger(_)) getOrElse (new NullTSLogger)
//
//  sys.addShutdownHook { dbConn foreach (_.close) }
//
//  def savePoint(point: Point, retention: String = null) = logger.savePoint(point, retention)
//
//  def savePoints(points: Seq[Point], retention: String = null) = logger.savePoints(points, retention)
//}