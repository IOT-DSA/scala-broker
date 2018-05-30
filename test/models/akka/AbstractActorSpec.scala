package models.akka

import org.scalatest.{ BeforeAndAfterAll, MustMatchers, OptionValues, WordSpecLike }
import org.scalatest.concurrent.ScalaFutures
import akka.actor.ActorSystem
import akka.testkit.{ ImplicitSender, TestKit }
import models.metrics.{ EventDaos, NullDaos }

/**
 * Base class for testing actors.
 */
abstract class AbstractActorSpec extends TestKit(ActorSystem()) with ImplicitSender
  with WordSpecLike with MustMatchers with BeforeAndAfterAll with ScalaFutures
  with OptionValues {

  override def afterAll = TestKit.shutdownActorSystem(system)

  val nullDaos = EventDaos(new NullDaos.NullMemberEventDao, new NullDaos.NullDSLinkEventDao,
    new NullDaos.NullRequestEventDao, new NullDaos.NullResponseEventDao)
}
