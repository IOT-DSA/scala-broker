package models.api

import akka.cluster.ddata.{DeltaReplicatedData, LWWRegister}
import models.rpc.DSAValue

//case class DistributedDSANodeState(
//
//                                    name:LWWRegister[String],
//                                    parent:LWWRegister[String],
//                                    path:LWWRegister[String],
//                                    value:LWWRegister[DSAValue[_]]
//
//                                  ) extends DeltaReplicatedData {
//
//  override type D = DistributedDSANodeState
//
//  override def delta: Option[DistributedDSANodeState] = ???
//
//  override def mergeDelta(thatDelta: DistributedDSANodeState): DistributedDSANodeState = merge(thatDelta)
//
//  override def resetDelta: DistributedDSANodeState = ???
//
//  override type T = DistributedDSANodeState
//
//  override def merge(that: DistributedDSANodeState): DistributedDSANodeState = ???
//}
