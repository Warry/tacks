package actors

import play.api.Logger
import scala.concurrent.Future

import scala.concurrent.duration._
import akka.actor.{ActorRef, Props, Actor}
import akka.pattern.{ask,pipe}
import akka.util.Timeout
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
import org.joda.time.DateTime

import models._
import dao._


case class LiveCenterState(
  onlinePlayers: Map[PlayerId, (Player, ActorRef, DateTime)] = Map.empty,
  chatRoom: Seq[(Player, ActorRef)] = Nil
) {
  def ping(player: Player, ref: ActorRef, time: DateTime): LiveCenterState = {
    copy(
      onlinePlayers = onlinePlayers + (player.id -> (player, ref, time))
    )
  }

  def removeStale(time: DateTime): LiveCenterState = {
    copy(
      onlinePlayers = onlinePlayers.filter(_._2._3.isAfter(time))
    )
  }

  def listOnlinePlayers = {
    onlinePlayers.values.toSeq.map(_._1)
  }
}

case object GetOnlinePlayers
case object Refresh
case class BroadcastLiveStatus(liveStatus: LiveStatus)

class LiveCenter extends Actor {

  var state = LiveCenterState()
  // implicit val timeout = Timeout(1.seconds)

  Akka.system.scheduler.schedule(1.second, 1.second, self, Refresh)

  def receive = {

    case ActivityMsg(player, ref, msg) =>
      import Emit._

      msg match {
        case Ping =>
          state = state.ping(player, ref, DateTime.now)

        case Poke(playerId) =>
          state.onlinePlayers.get(playerId).foreach {
            case (_, ref, _) =>
              ref ! Receive.PokedBy(player)
          }
      }

    case Refresh =>
      state = state.removeStale(DateTime.now.minusSeconds(5))
      LiveCenter.getLiveTracks()
        .map(LiveStatus(_, state.listOnlinePlayers))
        .map(BroadcastLiveStatus(_))
        .pipeTo(self)

    case BroadcastLiveStatus(liveStatus) =>
      state.onlinePlayers.values.foreach { case (_, ref, _) =>
        ref ! Receive.RefreshLiveStatus(liveStatus)
      }

    // case m: Chat.NewMessage => {
    //   state.chatRoom.foreach(_._2 ! m)
    //   Logger.debug("Player sent new message: " + m.toString)
    // }

    // case Chat.NewStatus(user, content) => {
    //   state = LiveCenter.updateStatus(state, user.id, content)
    //   Logger.debug("Player submitted new status: " + user.toString)
    //   LiveCenter.sendPlayersUpdate(state)
    //   UserDAO.updateStatus(user.id, Some(content))
    // }

    case GetOnlinePlayers => sender ! state.listOnlinePlayers
  }
}

object LiveCenter {
  val actorRef = Akka.system.actorOf(Props[LiveCenter])

  implicit val timeout = Timeout(1.seconds)

  def sendPlayersUpdate(state: LiveCenterState) = {
    val distinctPlayers = (state.listOnlinePlayers ++ state.chatRoom.map(_._1)).distinct.sortBy(_.handleOpt)
  }

  def getLiveTracks(): Future[Seq[LiveTrack]] = {
    val tracksFu = (RacesSupervisor.actorRef ? SupervisorAction.GetTracks).mapTo[Seq[LiveTrack]]
    for {
      tracks <- tracksFu
      liveTracks = tracks.filter(_.track.isOpen).sortBy(_.meta.rankings.length).reverse
    }
    yield liveTracks
  }

  // def updateStatus(state: LiveCenterState, playerId: BSONObjectID, status: String): LiveCenterState = {
  //   state.copy(
  //     subscribers = state.subscribers.map(updatePlayerStatus(playerId, status)),
  //     chatRoom = state.chatRoom.map(updatePlayerStatus(playerId, status))
  //   )
  // }

  // def updatePlayerStatus(id: BSONObjectID, status: String)(pair: (Player, ActorRef)): (Player, ActorRef) = {
  //   val (p, ref) = pair
  //   p match {
  //     case u: User if u.id == id => (u.copy(status = Some(status)), ref)
  //     case _ => (p, ref)
  //   }
  // }
}
