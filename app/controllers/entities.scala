package controllers

import akka.actor.Address

/**
 * Object passed to a view to supply various page information.
 */
case class ViewConfig(title: String, navItem: String, heading: String, description: String,
                      downCount: Option[Int] = None, upCount: Option[Int] = None)

/**
 * Broker node info.
 */
case class NodeInfo(address: Address, leader: Boolean, uid: Long, roles: Set[String], status: String)