package models.akka.responder

import akka.routing.Routee
import models.akka.{GroupCallRegistryRestoreProcess, LookupRidRestoreProcess, LookupRidSaved, OriginAdded, SimpleResponderBehaviorState}


object OriginUpdater{

  implicit class LookupRidSavedUpdater(item:LookupRidRestoreProcess){

    def update(routeeUpdater: Routee => Routee):LookupRidRestoreProcess = {
      item match {
        case lookup:LookupRidSaved =>
          val origin = lookup.origin.map(o => o.copy(source = routeeUpdater(o.source)))
          lookup.copy(origin = origin)
        case anyOther => anyOther
      }
    }

  }

  implicit class GroupCallRegistryRestoreProcessUpdater(item:GroupCallRegistryRestoreProcess){

    def update(routeeUpdater: Routee => Routee):GroupCallRegistryRestoreProcess = {
      item match {
        case originAdded: OriginAdded =>
          val origin = originAdded.origin.copy(source = routeeUpdater(originAdded.origin.source))
          originAdded.copy(origin = origin)
        case anythingElse => anythingElse
      }
    }
  }

  implicit class GroupCallRecordUpdate(record: GroupCallRecord){

    def update(routeeUpdater: Routee => Routee):GroupCallRecord = {

      val origins = record.origins.map(origin => origin.copy(source = routeeUpdater(origin.source)))
      val lastResponse = record.lastResponse

      new GroupCallRecord(origins, lastResponse)
    }
  }

  implicit class SimpleResponderBehaviorStateUpdater(state:SimpleResponderBehaviorState){

    def update(routeeUpdater: Routee => Routee):SimpleResponderBehaviorState = {

      val listBindings = state.listBindings.map{
        case (rid, record) => (rid, record.update(routeeUpdater))
      }

      val subsBindings = state.subsBindings.map{
        case (rid, record) => (rid, record.update(routeeUpdater))
      }

      SimpleResponderBehaviorState(listBindings, subsBindings)

    }

  }




}





