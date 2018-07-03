package models.akka

import akka.actor.ActorLogging
import akka.persistence._
import models.Settings
import models.util.DsaToAkkaCoder._

trait DSLinkStateSnapshotter extends PersistentActor with ActorLogging {

  private val ownId = s"DSLinkStateSnapshotter[${self.path.name.forDsa}]"

  private var requesterBehaviorState: RequesterBehaviorState = _
  private var baseState: DSLinkBaseState = _
  private var folderState: DSLinkFolderState = _

  val recoverDSLinkSnapshot: Receive = {
    case SnapshotOffer(metadata, offeredSnapshot: DSLinkState) =>
//      log.debug("{}: recovering with snapshot {}, metadata: {}", ownId, offeredSnapshot, metadata)
      if (offeredSnapshot.requesterBehaviorState != null)
        receiveRecover(offeredSnapshot.requesterBehaviorState)
      if (offeredSnapshot.baseState != null)
        receiveRecover(offeredSnapshot.baseState)
      if (offeredSnapshot.folderState != null)
        receiveRecover(offeredSnapshot.folderState)
    case RecoveryCompleted =>
      log.debug("{}: recovery completed", ownId)
  }

  val snapshotReceiver: Receive = {
    case SaveSnapshotSuccess(metadata) =>
      log.debug("{}: snapshot saved successfully, metadata: {}", ownId, metadata)
    case SaveSnapshotFailure(metadata, reason) =>
      log.error("{}: failed to save snapshot, metadata: {}, caused by: {}", ownId, metadata, reason)
  }

  override def saveSnapshot(snapshot: Any): Unit = snapshot match {
    case state: RequesterBehaviorState =>
      requesterBehaviorState = state
      tryToSaveSnapshot
    case state: DSLinkBaseState =>
      baseState = state
      tryToSaveSnapshot
    case state: DSLinkFolderState =>
      folderState = state
      tryToSaveSnapshot
    case _ =>
      log.error("{}: not supported snapshot type {}", ownId, snapshot)
  }

  private def tryToSaveSnapshot = {
    if (isSnapshottingOn) {
      val snapshot = DSLinkState(baseState, requesterBehaviorState, folderState)
      log.debug("{}: SAVING DSLink snapshot {} ", ownId, snapshot)
      super.saveSnapshot(snapshot)
    }
  }

  private def isSnapshottingOn: Boolean = {

    if (Settings.AkkaPersistenceSnapShotInterval <= 0) {
      log.warning("{}: snapshot saving is disabled as akka-persistence-snapshot-interval is '{}'", ownId, Settings.AkkaPersistenceSnapShotInterval)
      return false
    }

    log.debug("{}: current lastSequenceNr = {}", ownId, lastSequenceNr)
    if (lastSequenceNr != 0 && lastSequenceNr % Settings.AkkaPersistenceSnapShotInterval == 0)
      return true

    false
  }
}
