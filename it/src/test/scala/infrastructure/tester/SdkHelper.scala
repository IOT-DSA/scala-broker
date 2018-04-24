package infrastructure.tester

import java.io.{File, PrintWriter}
import java.util.concurrent.TimeUnit

import infrastructure.IT
import org.dsa.iot.dslink.{DSLinkFactory, DSLinkHandler, DSLinkProvider}

trait SdkHelper {
  self: IT =>

  val maxRetries = 30

  private[this] def copyDslinkJson(name:String) = {
    val dslinkFile = File.createTempFile("dslink", ".json")
    val nodesFile = File.createTempFile("nodes", ".json")
    dslinkFile.deleteOnExit
    nodesFile.deleteOnExit

    val target = new PrintWriter(dslinkFile)
    json(name, nodesFile.getAbsolutePath).lines foreach target.println
    target.close

    dslinkFile
  }

  /**
    * Generic dslink connection method with connection closing and retries
    * @param dsLink
    * @param dsLinkProvider
    * @param action
    * @tparam T
    */
  protected def connectDSLink[T <: BaseDSLinkHandler](dsLink:T, dsLinkProvider:DSLinkProvider)(action: T  => Unit): Unit = {
    var attempt = 0;
    while (!dsLink.isConnected && attempt < maxRetries) {
      log.info(s"trying to connect ${dsLink.getClass} instance to broker")
      TimeUnit.SECONDS.sleep(1)
      attempt = attempt + 1
    }

    try{
      action(dsLink)
    } finally {
      dsLink.stop()
      dsLinkProvider.stop()
    }
  }


  /**
    * Generic dslink connection method with connection closing and retries
    * @param dsLink
    * @param dsLinkProvider
    * @tparam T
    */
  protected def connectLink[T <: BaseDSLinkHandler](dsLink:T, dsLinkProvider:DSLinkProvider): Unit = {
    var attempt = 0;
    while (!dsLink.isConnected && attempt < maxRetries) {
      log.info(s"trying to connect ${dsLink.getClass} instance to broker")
      TimeUnit.SECONDS.sleep(1)
      attempt = attempt + 1
    }
  }


  /**
    * create new DSLink with all configs using provided DSLinkHandler
    * @param host broker host
    * @param port broker port
    * @param name DSLink name (id)
    * @param handler DSLinkHandler instance
    * @return DSLinkProvider
    */
  def createDsLink(host: String, port: Int, name:String)(implicit handler: DSLinkHandler): DSLinkProvider = {
    val dslinkJson = copyDslinkJson(name)
    val args = Array(s"-b http://$host:$port/conn", s"-d ${dslinkJson.getPath}")
    val generated = DSLinkFactory.generate(args, handler)
    generated.start()
    generated
  }


  /**
    * generates json for DSLink
    * @param name DSLink name (id)
    * @param nodesFile nodes file path
    * @return String with dslink.json content
    */
  def json(name:String, nodesFile:String) =
    s"""
       |{
       |	"name":"$name",
       |	"version":"0.1.0-SNAPSHOT",
       |	"description":"Scala DSLink Test Responder",
       |	"license":"Apache",
       |	"author":{
       |		"name":"Isaev Dmitry",
       |		"email":"isaev_dmitry@mail.ru"
       |	},
       |	"main":"bin/sdk-dslink-scala-test",
       |	"repository":{
       |		"type":"git",
       |		"url":"https://github.com/IOT-DSA/sdk-dslink-scala"
       |	},
       |	"bugs":{
       |		"url":"https://github.com/IOT-DSA/sdk-dslink-scala/issues"
       |	},
       |	"configs":{
       |		"name":{
       |			"type":"string",
       |			"default":"$name"
       |		},
       |		"broker":{
       |			"type":"url"
       |		},
       |		"token":{
       |			"type":"string"
       |		},
       |		"nodes":{
       |			"type":"path",
       |			"default":"$nodesFile"
       |		},
       |		"key":{
       |			"type":"path",
       |			"default":".key"
       |		},
       |		"log":{
       |			"type":"enum",
       |			"default":"info"
       |		},
       |		"handler_class":{
       |			"type":"string",
       |			"default":"infrastructure.tester.TestResponderHandler"
       |		}
       |	}
       |}
  """.stripMargin

}
