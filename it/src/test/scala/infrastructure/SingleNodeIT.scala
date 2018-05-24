package infrastructure


import com.whisk.docker.{DockerContainer, DockerReadyChecker, LogLineReceiver}
import org.scalatest.Suite
import org.slf4j.{Logger, LoggerFactory}
;

/**
  *
  * base trait for IT
  * starts scala-broker :latest image before all specs
  * and stops after
  *
  */
trait SingleNodeIT extends IT { self: Suite =>

  val dockerLogger: Logger = LoggerFactory.getLogger("docker")

  lazy val singleNodeContainer: DockerContainer = DockerContainer("iotdsa/broker-scala:latest")
    .withPorts(
      9000 -> Some(9000),
      9443 -> Some(9443),
      9005 -> Some(9005)
    )
    .withEnv(s"JAVA_OPTS=-agentlib:jdwp=transport=dt_socket,address=9005,server=y,suspend=n ")
    .withLogLineReceiver(new LogLineReceiver(true, line => dockerLogger.info(line)))
//    .withReadyChecker(DockerReadyChecker.LogLineContains("p.c.s.AkkaHttpServer - Listening for HTTP on /0.0.0.0:9443"))


  abstract override def dockerContainers: List[DockerContainer] =
    singleNodeContainer :: super.dockerContainers

}
