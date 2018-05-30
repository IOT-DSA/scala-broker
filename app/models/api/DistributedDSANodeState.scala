package models.api

import akka.actor.ActorRef
import akka.cluster.Cluster
import akka.cluster.ddata._
import models.rpc.DSAValue

@SerialVersionUID(1L)
final case class DistributedDSANodeState(
                                          value:LWWRegister[DSAValue[_]],
                                          configs:LWWMap[String, DSAValue[_]],
                                          attributes:LWWMap[String, DSAValue[_]],
                                          subscriptions:LWWMap[Int, ActorRef],
                                          listSubscriptions:LWWMap[Int, ActorRef],
                                          children: ORSet[String]
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
    LWWRegister(""),
    LWWMap.empty,
    LWWMap.empty,
    LWWMap.empty,
    LWWMap.empty,
    ORSet.empty
  )

}

@SerialVersionUID(1L)
final case class DistributedDSANodeKey(_id: String) extends Key[DistributedDSANodeState](_id) with ReplicatedDataSerialization

