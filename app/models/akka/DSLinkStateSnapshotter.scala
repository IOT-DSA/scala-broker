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
  private var responderState: ResponderBehaviorState = _

  val recoverDSLinkSnapshot: Receive = {
    case SnapshotOffer(_, offeredSnapshot: DSLinkState) =>
      if (offeredSnapshot.requesterBehaviorState != null)
        receiveRecover(offeredSnapshot.requesterBehaviorState)
      if (offeredSnapshot.baseState != null)
        receiveRecover(offeredSnapshot.baseState)
      if (offeredSnapshot.folderState != null)
        receiveRecover(offeredSnapshot.folderState)

      if (offeredSnapshot.responderBehaviorState != null) {
        if (offeredSnapshot.responderBehaviorState.main != null)
          receiveRecover(offeredSnapshot.responderBehaviorState.main)
        if (offeredSnapshot.responderBehaviorState.additional != null)
          receiveRecover(offeredSnapshot.responderBehaviorState.additional)
      }

    case RecoveryCompleted =>
      log.debug("{}: recovery completed", ownId)
  }

  val snapshotReceiver: Receive = {
    case SaveSnapshotSuccess(metadata) =>
      log.debug("{}: snapshot saved successfully, metadata: {}", ownId, metadata)
      deleteMessages(metadata.sequenceNr)
      deleteSnapshots(SnapshotSelectionCriteria(metadata.sequenceNr - (DSLinkStateSnapshotter.SNAPSHOTS_NUMBER_TO_KEEP * Settings.AkkaPersistenceSnapShotInterval), metadata.timestamp))
    case SaveSnapshotFailure(metadata, reason) =>
      log.error("{}: failed to save snapshot, metadata: {}, caused by: {}", ownId, metadata, reason)
    case DeleteMessagesSuccess(toSequenceNr) =>
      log.debug("{}: messages deleted successfully, toSequenceNr is '{}'", ownId, toSequenceNr)
    case DeleteMessagesFailure(reason, toSequenceNr) =>
      log.error("{}: failed to delete messages, toSequenceNr is '{}', caused by: {}", ownId, toSequenceNr, reason)
    case DeleteSnapshotsSuccess(criteria) =>
      log.debug("{}: snapshots deleted successfully with criteria: {}", ownId, criteria)
    case DeleteSnapshotsFailure(criteria, reason) =>
      log.error("{}: failed to delete snapshots with criteria: {}, caused by: {}", ownId, criteria, reason)
  }

  override def saveSnapshot(snapshot: Any): Unit =
    if (Settings.AkkaPersistenceSnapShotInterval <= 0) {
      log.debug("{}: current lastSequenceNr = {} and Settings.AkkaPersistenceSnapShotInterval = {}", ownId, lastSequenceNr, Settings.AkkaPersistenceSnapShotInterval)
      log.warning("{}: snapshot saving is disabled as Settings.AkkaPersistenceSnapShotInterval is '{}'", ownId, Settings.AkkaPersistenceSnapShotInterval)
    } else snapshot match {
        case state: RequesterBehaviorState =>
          requesterBehaviorState = state
          tryToSaveSnapshot
        case state: DSLinkBaseState =>
          baseState = state
          tryToSaveSnapshot
        case state: DSLinkFolderState =>
          folderState = state
          tryToSaveSnapshot
        case state: ResponderBehaviorState =>
          responderState = state
          tryToSaveSnapshot
        case _ =>
          log.error("{}: not supported snapshot type {}", ownId, snapshot)
  }

  private def tryToSaveSnapshot = {
    if (lastSequenceNr != 0 && lastSequenceNr % Settings.AkkaPersistenceSnapShotInterval == 0) {
      val snapshot = DSLinkState(baseState, requesterBehaviorState, folderState, responderState)
      log.debug("{}: Saving DSLink snapshot {} ", ownId, snapshot)
      super.saveSnapshot(snapshot)
    }
  }
}

object DSLinkStateSnapshotter {
  /**
    * How many snapshots we want to keep after old ones removing.
    */
  val SNAPSHOTS_NUMBER_TO_KEEP = 3
}
