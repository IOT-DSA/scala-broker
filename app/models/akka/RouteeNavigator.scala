package models.akka

import akka.routing.Routee

trait RouteeNavigator {


  /**
    * Returns a [[Routee]] that can be used for sending messages to a specific downlink.
    */
  def getDownlinkRoutee(dsaName: String): Routee

  /**
    * Returns a [[Routee]] that can be used for sending messages to a specific uplink.
    */
  def getUplinkRoutee(dsaName: String): Routee

}
