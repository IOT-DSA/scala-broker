package models.akka

import akka.actor.{ActorLogging, ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider}
import akka.persistence._
import com.typesafe.config.Config
import models.Settings
import models.util.DsaToAkkaCoder._

trait DSLinkStateSnapshotter extends PersistentActor with ActorLogging {

  private val ownId = s"DSLinkStateSnapshotter[${self.path.name.forDsa}]"

  private var requesterBehaviorState: RequesterBehaviorState = _
  private var baseState: DSLinkBaseState = _
  private var folderState: DSLinkFolderState = _
  private var responderState: ResponderBehaviorState = _
  private val settings = DSLinkSnapshotterSettings(context.system)

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
  }

  val snapshotReceiver: Receive = {
    case SaveSnapshotSuccess(metadata) =>
      log.debug("{}: snapshot saved successfully, metadata: {}", ownId, metadata)
      deleteMessages(metadata.sequenceNr)
      deleteSnapshots(SnapshotSelectionCriteria(metadata.sequenceNr - (settings.snapshotNumberToKeep * Settings.AkkaPersistenceSnapShotInterval), metadata.timestamp))
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
    if (Settings.AkkaPersistenceSnapShotInterval <= 0)
      log.warning("{}: snapshot saving is disabled as Settings.AkkaPersistenceSnapShotInterval is '{}'", ownId, Settings.AkkaPersistenceSnapShotInterval)
    else snapshot match {
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
    log.debug("{}: persistenceId: '{}', lastSequenceNr: '{}', Settings.AkkaPersistenceSnapShotInterval: '{}'", ownId, persistenceId, lastSequenceNr, Settings.AkkaPersistenceSnapShotInterval)
    if (lastSequenceNr != 0 && lastSequenceNr % Settings.AkkaPersistenceSnapShotInterval == 0) {
      val snapshot = DSLinkState(baseState, requesterBehaviorState, folderState, responderState)
      log.debug("{}: Saving DSLink snapshot {} with persistenceId '{}'", ownId, snapshot, persistenceId)
      super.saveSnapshot(snapshot)
    }
  }
}

class DSLinkSnapshotterSettingsImpl(config: Config) extends Extension {

  val snapshotNumberToKeep: Int = config.getInt("broker.dslink.stateSnapshotter.snapshotNumbersToKeep")
}

object DSLinkSnapshotterSettings extends ExtensionId[DSLinkSnapshotterSettingsImpl] with ExtensionIdProvider {
  override def lookup = DSLinkSnapshotterSettings

  override def createExtension(system: ExtendedActorSystem) = new DSLinkSnapshotterSettingsImpl(system.settings.config)

  /**
    * Java API: retrieve the Settings extension for the given system.
    */
  override def get(system: ActorSystem) = super.get(system)
}
