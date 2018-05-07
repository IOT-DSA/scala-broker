package infrastructure.tester

import infrastructure.IT

/**
  * requester and responder connections for test purposes
  */
trait RequesterAndResponder extends TestRequester with TestResponder{
  self:IT =>

  /**
    * starts both - requester and responder
    * @param host broker host
    * @param port broker port
    * @param responderName responder id (for path)
    * @param requesterName requester id (for path)
    * @param action some actions with connected requester and responder
    */
  def withRequesterAndResponder(proto:String = "http",
                                host:String = "localhost",
                                port:Int = 9000,
                                responderName:String = "scala-test-responder",
                                requesterName:String = "scala-test-requester"
                               )(action: (TestResponderHandler,
    TestRequesterHandler)=>Unit) = {
    withResponder(host, port, responderName){responder =>
      withRequester(host, port, requesterName){ requester =>
        action(responder, requester)
      }
    }
  }
}
