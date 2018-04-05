package infrastructure.tester

import infrastructure.IT
import org.dsa.iot.dslink.DSLink
import org.dsa.iot.dslink.node.{Node, Permission, Writable}
import org.dsa.iot.dslink.node.actions.{Action, ActionResult}
import org.dsa.iot.dslink.node.value.{Value, ValueType}
import org.dsa.iot.dslink.util.handler.Handler
import org.dsa.iot.dslink.util.json.JsonObject

import collection.JavaConverters._
import org.slf4j.LoggerFactory

trait TestResponder extends SdkHelper {
  self: IT =>

  val defaultResponderName = "scala-test-responder"

  def withResponder(host: String = "localhost", port: Int = 9000, name: String = defaultResponderName)(action: TestResponderHandler => Unit): Unit = {

    implicit val responder = new TestResponderHandler
    val responderDSLinkProvider = createDsLink(host, port, name)

    connectDSLink(responder, responderDSLinkProvider)(action)
  }

}

/**
  * Responder DSLinkHandler implementation for test purposes
  */
class TestResponderHandler extends BaseDSLinkHandler {

  override val log = LoggerFactory.getLogger(getClass)

  override val isResponder = true
  override val isRequester = false

  override def onResponderInitialized(in: DSLink) = {
    link = in
    log.info("Responder initialized")
  }

  override def onResponderConnected(in: DSLink) = {
    link = in
    val superRoot = link.getNodeManager.getSuperRoot
    val builder = superRoot.createChild("Create_Node", true).
      setSerializable(false).
      setDisplayName("Create Node").
      setAction(new Action(Permission.WRITE,
        new Handler[ActionResult] {
          def handle(event: ActionResult) = {
            event.getJsonIn.get("rid")
            event.getJsonIn.get("params")

            val jsonParams = event.getJsonIn.get[JsonObject]("params")

            val value = jsonParams.get[String]("value")
            val valueType = jsonParams.get[String]("type")
            val id = jsonParams.get[String]("id")
            val name = jsonParams.get[String]("name")
            createNode(TestUtils.toValue(value), valueType, id, name)
          }
        }))
    builder.build
    log.info("Responder connected")
  }

  /**
    * create node
    *
    * @param value   value
    * @param valType value type
    * @param id      id (node name)
    * @param name    node display name
    */
  def createNode(value: Value, valType: String, id: String, name: String): Unit = {
    createNode(value, ValueType.toValueType(valType), id, name)
  }

  /**
    * create node
    *
    * @param value   value
    * @param valType value type
    * @param id      id (node name)
    * @param name    node display name
    */
  def createNode(value: Value, valType: ValueType, id: String, name: String): Unit = {
    val superRoot = link.getNodeManager.getSuperRoot
    val builder = superRoot.
      createChild(id, true).
      setDisplayName(name).
      setValueType(valType).
      setWritable(Writable.WRITE).
      setValue(value)
    val node = builder
      .build

    nodes.put(id, node)
    log.info(s"node $id been added")
  }

  /**
    * remove node
    * @param node node to remove
    */
  def deleteNode(node: Node) = {
    val superRoot = link.getNodeManager.getSuperRoot
    superRoot.removeChild(node, true)
    log.info(s"node ${node.getName} been removed")
  }

  /**
    * change node value
    * @param id node id
    * @param value new value
    * @param ms updated at epoch ms
    */
  def changeNodeValue(id: String, value: Any, ms: Option[Long] = None) = {
    val data = TestUtils.toValue(value)
    nodes.asScala.get(id).foreach { node => node.setValue(data) }
  }

}
