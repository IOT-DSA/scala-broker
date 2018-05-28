package models.api

import akka.cluster.Cluster
import akka.cluster.ddata._
import models.rpc.DSAValue

@SerialVersionUID(1L)
final case class DistributedDSANodeState(
                                    name:LWWRegister[String],
                                    displayName:LWWRegister[String],
                                    parent:LWWRegister[Option[String]],
                                    path:LWWRegister[String],
                                    value:LWWRegister[DSAValue[_]],
                                    valueType: LWWRegister[String],
                                    profile: LWWRegister[String],

                                  ) extends ReplicatedData {

  type T = DistributedDSANodeState

  override def merge(that: DistributedDSANodeState): DistributedDSANodeState = copy(
    name = this.name.merge(that.name),
    displayName = this.name.merge(that.displayName),
    parent = this.parent.merge(that.parent),
    path = this.path.merge(that.path),
    value = this.value.merge(that.value),
    valueType = this.valueType.merge(that.valueType),
    profile = this.profile.merge(that.profile)
  )
}

object DistributedDSANodeState{

  def empty(implicit cluster:Cluster) = DistributedDSANodeState(
    LWWRegister(""),
    LWWRegister(""),
    LWWRegister(Option[String](null)),
    LWWRegister(""),
    LWWRegister(DSAValue.StringValue("").asInstanceOf[DSAValue[_]]),
    LWWRegister(""),
    LWWRegister("")
  )

}

@SerialVersionUID(1L)
final case class DistributedDSANodeKey(_id: String) extends Key[DistributedDSANodeState](_id) with ReplicatedDataSerialization

