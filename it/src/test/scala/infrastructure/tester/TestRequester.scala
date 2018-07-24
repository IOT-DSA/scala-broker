package infrastructure.tester

import java.util.function.Consumer

import infrastructure.IT
import org.dsa.iot.dslink.DSLink
import org.dsa.iot.dslink.link.Requester
import org.dsa.iot.dslink.methods.StreamState
import org.dsa.iot.dslink.methods.requests.{InvokeRequest, ListRequest, SetRequest}
import org.dsa.iot.dslink.methods.responses.{InvokeResponse, ListResponse, SetResponse}
import org.dsa.iot.dslink.node.value.{SubscriptionValue, Value}
import org.dsa.iot.dslink.util.handler.Handler
import org.dsa.iot.dslink.util.json.JsonObject
import org.slf4j.{Logger, LoggerFactory}
import reactor.core.publisher.{Flux => JFlux, FluxSink => JFluxSink, Mono => JMono, MonoSink => JMonoSink}
import reactor.core.scala.publisher.{Flux, Mono}

import scala.concurrent.{Future, Promise}


trait TestRequester extends SdkHelper {
  self: IT =>

  val defaultRequesterName = "scala-test-requester"

  def withRequester(proto: String = "http", host: String = "localhost", port: Int = 9000, name: String = defaultRequesterName)(action: TestRequesterHandler => Unit): Unit = {
    implicit val requester = new TestRequesterHandler
    val requesterDSLinkProvider = createDsLink(proto, host, port, name)

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
    *
    * @param path node path
    * @return future of next event
    */
  def subscribe(path: String): Flux[SubscriptionValue] = withFlux[SubscriptionValue] {
    handler: Handler[SubscriptionValue] => requester.subscribe(path, handler)
  }()

  /**
    * list of child nodes
    *
    * @param path node path
    * @return
    */
  def list(path: String): Flux[ListResponse] = withFlux[ListResponse] {
    handler: Handler[ListResponse] => {
      val request = new ListRequest(path)
      requester.list(request, handler)
    }
  }()

  /**
    * invoke method call
    *
    * @param path   action node path
    * @param params invoke arguments
    * @return future of invoke result
    */
  def invoke(path: String, params: JsonObject): Flux[InvokeResponse] = withFlux[InvokeResponse](
    handler => {
      val req = new InvokeRequest(path, params)
      requester.invoke(req, handler)
    })(_.getState == StreamState.CLOSED)

  /**
    * setting new value to node
    *
    * @param path  node path
    * @param value new value
    * @return future response
    */
  def set(path: String, value: Value): Mono[SetResponse] = withMono[SetResponse] {
    handler =>
      val req = new SetRequest(path, value)
      requester.set(req, handler)
  }

  private def withoutCheck[R](value: R): Boolean = false

  private def withFlux[R](f: Handler[R] => Unit)(isItDone: R => Boolean = withoutCheck _): Flux[R] = Flux.create {
    sink => {
      val handler: Handler[R] = { event =>
        sink.next(event)
        if (isItDone(event)) sink.complete()
      }

      f.apply(handler)
    }
  }

  private def withMono[R](f: Handler[R] => Unit): Mono[R] = Mono.create[R] {
    sink => {
      val handler: Handler[R] = event => sink.success(event)
      f.apply(handler)
    }
  }

  /**
    * wraps requester method with future as result
    *
    * @param f some requester method with handler as argument
    * @tparam R response
    * @return future of response
    */
  private def withPromise[R](f: Handler[R] => Unit): Future[R] = {

    val promise = Promise[R]

    val handler = new Handler[R] {
      override def handle(event: R): Unit = {
        if (!promise.isCompleted) {
          promise.success(event)
        }
      }
    }

    f(handler)

    promise.future
  }

  private def requester(): Requester = {
    link.getRequester
  }


}
