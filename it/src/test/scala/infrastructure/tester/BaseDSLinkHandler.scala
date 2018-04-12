package infrastructure.tester

import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap}

import org.dsa.iot.dslink.node.Node
import org.dsa.iot.dslink.{DSLink, DSLinkHandler}
import org.slf4j.Logger

import collection.JavaConverters._
import scala.concurrent.duration._

/**
  * Base functionality for DSLink handler
  */
trait BaseDSLinkHandler extends DSLinkHandler {

  implicit val ctx = scala.concurrent.ExecutionContext.global
  val log:Logger

  /**
    * default timeout for message request
    */
  val maxEventWaitTime = 5 seconds

  protected var link:DSLink = _
  protected val nodes: ConcurrentMap[String, Node] = new ConcurrentHashMap()

  /**
    * returns link children nodes data
    * @return Map[String, Node] where key is node id (name)
    */
  def getState = nodes.asScala

  /**
    * link should be initiated on connect hook
    * @return is connected
    */
  def isConnected = link != null

}
