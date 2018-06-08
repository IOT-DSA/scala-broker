package it


import infrastructure.{LoggingBridge}
import org.dsa.iot.dslink.util.log.LogManager
import org.scalatest.{FlatSpec}


class ExampleSpec extends FlatSpec with BasicCases
{
  LogManager.setBridge(new LoggingBridge)

  "it test" should "run" in {
    addNodeGetListNodes()
  }
}
