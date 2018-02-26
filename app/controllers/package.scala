import org.joda.time.format.{ DateTimeFormat, PeriodFormat }
import play.api.libs.json._

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
}