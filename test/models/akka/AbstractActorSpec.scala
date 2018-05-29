package models.akka

import org.scalatest.{BeforeAndAfterAll, MustMatchers, OptionValues, WordSpecLike}
import org.scalatest.concurrent.ScalaFutures
import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import models.metrics.{EventDaos, NullDaos}
import org.scalatest.time.{Millis, Seconds, Span}

/**
 * Base class for testing actors.
 */
abstract class AbstractActorSpec extends TestKit(ActorSystem()) with ImplicitSender
  with WordSpecLike with MustMatchers with BeforeAndAfterAll with ScalaFutures
  with OptionValues {

  override def afterAll = TestKit.shutdownActorSystem(system)

  /**
  * * The simple tune to avoid some issues for asynchronous operations in unit tests.
  * Probably the amount of timeout and interval should be figured out experimentally.
  */
  override implicit val patienceConfig = PatienceConfig(timeout = Span(1, Seconds), interval = Span(500, Millis))


  val nullDaos = EventDaos(new NullDaos.NullMemberEventDao, new NullDaos.NullDSLinkEventDao,
    new NullDaos.NullRequestEventDao, new NullDaos.NullResponseEventDao)
}