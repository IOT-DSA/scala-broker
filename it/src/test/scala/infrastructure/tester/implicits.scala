package infrastructure.tester

import scala.concurrent.duration.FiniteDuration

object implicits {

  implicit def asJavaDuration(scala:FiniteDuration):java.time.Duration =
    java.time.Duration.ofNanos(scala.toNanos)


  implicit def asScalaDuration(d: java.time.Duration) =
    scala.concurrent.duration.Duration.fromNanos(d.toNanos)

}
