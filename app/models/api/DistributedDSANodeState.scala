package models.api

import akka.actor.ActorRef
import akka.cluster.Cluster
import akka.cluster.ddata._
import models.rpc.DSAValue

@SerialVersionUID(1L)
final case class DistributedDSANodeState(
                                          value:LWWRegister[Option[DSAValue[_]]],
                                          configs:LWWMap[String, Option[DSAValue[_]]],
                                          attributes:LWWMap[String, Option[DSAValue[_]]],
                                          subscriptions:LWWMap[Int, Option[ActorRef]],
                                          listSubscriptions:LWWMap[Int, Option[ActorRef]],
                                          children: LWWMap[String, Option[DSANodeDescription]]
                                        ) extends ReplicatedData {

  type T = DistributedDSANodeState

  override def merge(that: DistributedDSANodeState): DistributedDSANodeState = copy(
    value = this.value.merge(that.value),
    configs = this.configs.merge(that.configs),
    attributes = this.attributes.merge(that.attributes),
    subscriptions = this.subscriptions.merge(that.subscriptions),
    listSubscriptions = this.listSubscriptions.merge(that.listSubscriptions),
    children = this.children.merge(that.children)
  )
}

object DistributedDSANodeState{

  def empty(implicit cluster:Cluster) = DistributedDSANodeState(
    LWWRegister(None),
    LWWMap.empty,
    LWWMap.empty,
    LWWMap.empty,
    LWWMap.empty,
    LWWMap.empty
  )

}

@SerialVersionUID(1L)
final case class DistributedDSANodeKey(_id: String) extends Key[DistributedDSANodeState](_id) with ReplicatedDataSerialization

