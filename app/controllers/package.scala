import org.joda.time.format.{ DateTimeFormat, PeriodFormat }
import play.api.libs.json._
import play.api.mvc.QueryStringBindable

/**
 * Constants and helper methods for broker controllers.
 */
package object controllers {

  /* json converters */

  val DateFmt = DateTimeFormat.mediumTime
  val PeriodFmt = PeriodFormat.getDefault

  implicit val durationWrites = Writes[org.joda.time.Duration] { duration =>
    Json.toJson(PeriodFmt.print(duration.toPeriod))
  }
  implicit val intervalWrites = Writes[org.joda.time.Interval] { interval =>
    Json.toJson(PeriodFmt.print(interval.toPeriod) + " starting at " + DateFmt.print(interval.getStart))
  }

  /* binders */

  type QSB[A] = QueryStringBindable[A]

  implicit def configBinder(implicit intBinder: QSB[Int], longBinder: QSB[Long],
                            boolBinder: QSB[Boolean]) = new QSB[BenchmarkConfig] {
    import BenchmarkConfig.Default

    def bind(key: String, params: Map[String, Seq[String]]) = Some(for {
      subscribe <- boolBinder.bind("subscribe", params).getOrElse(Right(Default.subscribe))
      reqCount <- intBinder.bind("reqCount", params).getOrElse(Right(Default.reqCount))
      rspCount <- intBinder.bind("rspCount", params).getOrElse(Right(Default.rspCount))
      rspNodeCount <- intBinder.bind("rspNodeCount", params).getOrElse(Right(Default.rspNodeCount))
      batchSize <- intBinder.bind("batchSize", params).getOrElse(Right(Default.batchSize))
      timeout <- longBinder.bind("timeout", params).getOrElse(Right(Default.batchTimeout))
      json <- boolBinder.bind("parseJson", params).getOrElse(Right(Default.parseJson))
    } yield {
      BenchmarkConfig(subscribe, reqCount, rspCount, rspNodeCount, batchSize, timeout, json)
    })

    def unbind(key: String, config: BenchmarkConfig) =
      boolBinder.unbind("subscribe", config.subscribe) + "&" +
        intBinder.unbind("reqCount", config.reqCount) + "&" +
        intBinder.unbind("rspCount", config.rspCount) + "&" +
        intBinder.unbind("rspNodeCount", config.rspNodeCount) + "&" +
        intBinder.unbind("batchSize", config.batchSize) + "&" +
        longBinder.unbind("timeout", config.batchTimeout) + "&" +
        boolBinder.unbind("json", config.parseJson)
  }
}