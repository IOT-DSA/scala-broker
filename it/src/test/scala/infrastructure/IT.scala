package infrastructure

import com.spotify.docker.client.{DefaultDockerClient, DockerClient}
import com.whisk.docker.impl.spotify.SpotifyDockerFactory
import com.whisk.docker.{DockerFactory, DockerKit}
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.Await

/**
  *
  * base trait for IT
  * starts all configured in child classes containers before first test
  * and stops after last
  *
  */
trait IT extends ScalaFutures
  with DockerKit
  with BeforeAndAfterAll {
  self: Suite =>

  val client: DockerClient = DefaultDockerClient.fromEnv().build()
  override implicit val dockerFactory: DockerFactory = new SpotifyDockerFactory(client)

  def dockerInitPatienceInterval =
    PatienceConfig(scaled(Span(90, Seconds)), scaled(Span(10, Millis)))

  def dockerPullImagesPatienceInterval =
    PatienceConfig(scaled(Span(1200, Seconds)), scaled(Span(250, Millis)))

  val log: Logger = LoggerFactory.getLogger(classOf[IT])

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    beforeStart()
    startAllOrFail()
    afterStart()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    beforeStop()
    var attempt = 0
    val maxAttempts = 5
    while(!stopAll() || attempt < maxAttempts ){
      log.info("trying to stop containers")
      attempt = attempt + 1
    }
    stopAllQuietly()
    afterStop()
  }

  def stopAll(): Boolean = {
    try {
      Await.ready(containerManager.stopRmAll(), StopContainersTimeout)
      true
    } catch {
      case e: Throwable =>
        log.error(e.getMessage, e)
        false
    }
  }

  def beforeStart() = log.debug("Starting project containers")
  def afterStart() = log.debug("Project containers been successfully started")

  def beforeStop() = log.debug("Stopping project containers")
  def afterStop() = log.debug("Project containers been successfully stopped")
}
