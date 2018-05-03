package models.api.typed

sealed trait NodeResponse

sealed trait MgmtResponse extends NodeResponse

object MgmtResponse {
  final case class GetStateResponse(state: DSANodeState)
  final case class AddChildResponse(ref: NodeRef)
}

sealed trait DSAResponse extends NodeResponse

object DSAResponse {

}