package infrastructure.tester

import infrastructure.IT
import org.dsa.iot.dslink.DSLink
import org.dsa.iot.dslink.link.Requester
import org.dsa.iot.dslink.methods.requests.{InvokeRequest, ListRequest, SetRequest}
import org.dsa.iot.dslink.methods.responses.{InvokeResponse, ListResponse, SetResponse}
import org.dsa.iot.dslink.node.value.{SubscriptionValue, Value}
import org.dsa.iot.dslink.util.handler.Handler
import org.dsa.iot.dslink.util.json.JsonObject
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{Future, Promise}


trait TestRequester extends SdkHelper {
  self: IT =>

  val defaultRequesterName = "scala-test-requester"

  def withRequester(host: String = "localhost", port: Int = 9000, name: String = defaultRequesterName)(action: TestRequesterHandler => Unit): Unit = {
    implicit val requester = new TestRequesterHandler
    val requesterDSLinkProvider = createDsLink(host, port, name)

    connectDSLink(requester, requesterDSLinkProvider)(action)
  }

}

/**
  * Requester DSLinkHandler implementation for test purposes
  */
class TestRequesterHandler extends BaseDSLinkHandler {

  override val log: Logger = LoggerFactory.getLogger(getClass)

  override val isRequester = true
  override val isResponder = false

  override def onRequesterConnected(in: DSLink) = {
    link = in
    log.info("Requester connected")
  }

  override def onRequesterInitialized(in: DSLink) = {
    link = in
    log.info("Requester initialized")
  }

  //TODO implement some stream based subscription (to get all events instead of next one)

  /**
    * subscription to node events
    * @param path node path
    * @return future of next event
    */
  def subscribe(path: String): Future[SubscriptionValue] = withPromise[SubscriptionValue]{
    handler =>
      requester.subscribe(path, handler)
  }

  /**
    * list of child nodes
    * @param path node path
    * @return
    */
  def list(path: String): Future[ListResponse] = withPromise[ListResponse]{
    handler =>
      val request = new ListRequest(path)
      requester.list(request, handler)
  }

  /**
    * invoke method call
    * @param path action node path
    * @param params invoke arguments
    * @return future of invoke result
    */
  def invoke(path: String, params: JsonObject): Future[InvokeResponse] = withPromise[InvokeResponse]{
    handler =>
      val req = new InvokeRequest(path, params)
      requester.invoke(req, handler)
  }

  /**
    * setting new value to node
    * @param path node path
    * @param value new value
    * @return future response
    */
  def set(path: String, value: Value): Future[SetResponse] = withPromise[SetResponse]{
    handler =>
      val req = new SetRequest(path, value)
      requester.set(req, handler)
  }

  /**
    * wraps requester method with future as result
    * @param f some requester method with handler as argument
    * @tparam R response
    * @return future of response
    */
  private def withPromise[R](f: Handler[R] => Unit): Future[R] = {

    val promise = Promise[R]

    val handler = new Handler[R] {
      override def handle(event: R): Unit = {
        promise.success(event)
      }
    }

    f(handler)

    promise.future
  }

  private def requester(): Requester = {
    link.getRequester
  }


}
