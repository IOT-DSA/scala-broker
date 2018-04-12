package infrastructure

import java.io.File
import org.dsa.iot.dslink.util.log.{LogBridge, LogLevel}

/**
  * just dummy for java-sdk logging tools
  */
class LoggingBridge extends LogBridge{

  private var level = LogLevel.INFO

  override def configure(logPath: File): Unit = {

  }

  override def setLevel(level: LogLevel): Unit = {

  }

  override def getLevel: LogLevel = {
    level
  }

}
