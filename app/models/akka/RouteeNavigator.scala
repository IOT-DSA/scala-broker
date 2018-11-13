package models.akka

import akka.actor.ActorPath
import akka.routing.{ActorSelectionRoutee, Routee}

trait RouteeNavigator {

  /**
    * Base impl for local actors
    * @param path
    * @return
    */
  def routee(path:ActorPath):Routee

  /**
    * Function for routee update in case of recovery etc
    * Base impl - without updating unithing actually
    * @param routee
    * @return
    */
  def updateRoutee(routee:Routee):Routee

}
