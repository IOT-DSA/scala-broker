package models.sdk

import models.rpc.DSAValue.{DSAMap, DSAVal}
import models.sdk.node.NodeStatus

package object dsa {

  /**
    * Name of the root Node actor bound to this DSA Node actor.
    */
  val RootNode = "root"

  /**
    * Helper methods for creating a changed status copy.
    *
    * @param status
    */
  implicit private[dsa] class RichNodeStatus(val status: NodeStatus) extends AnyVal {

    def withValue(v: Option[DSAVal]) = status.copy(value = v)

    def plusAttribute(name: String, value: DSAVal) = status.copy(attributes = status.attributes + (name -> value))
    def minusAttribute(name: String) = status.copy(attributes = status.attributes - name)
    def withAttributes(attrs: DSAMap) = status.copy(attributes = attrs)

    def plusConfig(name: String, value: DSAVal) = status.copy(configs = status.configs + (name -> value))
    def minusConfig(name: String) = status.copy(configs = status.configs - name)
    def withConfigs(cfgs: DSAMap) = status.copy(configs = cfgs)
  }

  /**
    * Returns a node status for a newly added child.
    *
    * @param name
    * @return
    */
  def newNodeStatus(name: String) = NodeStatus(name)
}
