package actors

import tools.Conf

import scala.concurrent.duration._
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits._
import akka.actor._
import org.joda.time.DateTime
import models._

case class Start(at: DateTime)

case class WatcherContext(watcher: Player, state: WatcherState, ref: ActorRef)
case class WatcherJoin(watcher: Player)
case class WatcherQuit(watcher: Player)

class RaceActor(race: Race, master: Player) extends Actor with ManageWind {

  val id = race.id
  val course = race.course

  type PlayerId = String
  case class SpellCast(by: PlayerId, spell: Spell, at: DateTime, to: Seq[PlayerId]) {
    def isExpired = at.plusSeconds(spell.duration).isBeforeNow
  }

  val players = scala.collection.mutable.Map[PlayerId, PlayerContext]()
  var playersGates = scala.collection.mutable.Map[Player, Seq[Long]]()
  var leaderboard = Seq[PlayerTally]()
  var finishersCount = 0

  val watchers = scala.collection.mutable.Map[PlayerId, WatcherContext]()

  var startTime: Option[DateTime] = None

  def millisBeforeStart: Option[Long] = startTime.map(_.getMillis - DateTime.now.getMillis)
  def startScheduled = startTime.isDefined
  def started = startTime.exists(_.isBeforeNow)
  def clock: Long = DateTime.now.getMillis

  def finished = playersGates.nonEmpty && playersGates.values.forall(_.length == course.gatesToCross)

  val ticks = Seq(
    Akka.system.scheduler.schedule(10.seconds, 10.seconds, self, AutoClean),
    Akka.system.scheduler.schedule(0.seconds, 20.seconds, self, SpawnGust),
    Akka.system.scheduler.schedule(0.seconds, Conf.frameMillis.milliseconds, self, FrameTick)
  )

  def receive = {

    /**
     * player join => added to context Map
     */
    case PlayerJoin(player) => {
      players += player.id.stringify -> PlayerContext(player, PlayerInput.initial, PlayerState.initial(player), sender())
    }

    /**
     * player quit => removed from context Map
     */
    case PlayerQuit(player) => {
      players -= player.id.stringify
    }

    /**
     * game heartbeat:
     *  - update wind (origin, speed and gusts positions)
     *  - send a race update to watchers actors for websocket transmission
     *  - send a race update to players actor for websocket transmission
     *  - tell player actor to run step
     */
    case FrameTick => {

      updateWind()

      watchers.values.foreach {
        case WatcherContext(watcher, state, ref) => {
          ref ! raceUpdateForWatcher(watcher)
        }
      }

      players.values.foreach {
        case PlayerContext(player, input, state, ref) => {
          ref ! raceUpdateForPlayer(player, Some(state))
          ref ! RunStep(state, input, clock, wind, course, started, opponentsTo(player.id.stringify))
        }
      }
    }

    /**
     * player input coming from websocket through player actor
     * context is updated, race started if requested
     */
    case PlayerUpdate(player, input) => {
      val id = player.id.stringify

      players.get(id).foreach { context =>
        if (input.startCountdown) startCountdown(byPlayerId = id)
        players += (id -> context.copy(input = input))
      }
    }

    /**
     * step result coming from player actor
     * context is updated
     */
    case StepResult(prevState, newState) => {
      val id = newState.player.id.stringify

      players.get(id).foreach { context =>
        players += (id -> context.copy(state = newState))
        if (prevState.crossedGates != newState.crossedGates) updateTally()
      }
    }

    /**
     * watcher joins => added to context Map
     */
    case WatcherJoin(watcher) => {
      watchers += watcher.id.stringify -> WatcherContext(watcher, WatcherState(watcher.id.stringify), sender())
    }

    /**
     * watcher quits => removed from context Map
     */
    case WatcherQuit(watcher) => {
      watchers -= watcher.id.stringify
    }

    /**
     * watcher updates watcher player id => context updated
     */
    case WatcherUpdate(watcher, input) => {
      val id = watcher.id.stringify
      watchers.get(id).foreach { context =>
        val newState = WatcherState(input.watchedPlayerId.getOrElse(id))
        watchers += id -> context.copy(state = newState)
      }
    }

    /**
     * race status, for live center
     */
    case GetStatus => sender ! (startTime, players.values.map(_.state).toSeq)

    /**
     * new gust
     */
    case SpawnGust => generateGust()

    /**
     * clean obsolete races
     * kill remaining players and watchers actors
     */
    case AutoClean => {
      val deserted = race.creationTime.plusMinutes(1).isBeforeNow && players.isEmpty
      val finished = startTime.exists(_.plusMinutes(20).isBeforeNow)
      if (deserted || finished) {
        players.values.foreach(_.ref ! PoisonPill)
        watchers.values.foreach(_.ref ! PoisonPill)
        self ! PoisonPill
      }
    }
  }

  private def startCountdown(byPlayerId: String) = {
    if (startTime.isEmpty && byPlayerId == master.id.stringify) {
      val at = DateTime.now.plusSeconds(race.countdownSeconds)
      startTime = Some(at)
    }
  }

  private def updateTally() = {
    players.values.foreach { context =>
      playersGates += context.player -> context.state.crossedGates
    }

    leaderboard = playersGates.toSeq.map { case (player, gates) =>
      PlayerTally(player.id, player match {
        case g: Guest => None
        case u: User => Some(u.handle)
      }, gates)
    }.sortBy { pt =>
      (-pt.gates.length, pt.gates.headOption)
    }

    val fc = playersGates.values.count(_.length == course.gatesToCross)

    if (fc != finishersCount) {
      fc match {
        case 1 => // ignore
        case 2 => Race.save(race.copy(tally = leaderboard, startTime = startTime))
        case _ => Race.updateTally(race, leaderboard)
      }
    }
    finishersCount = fc
  }

  private def opponentsTo(playerId: String): Seq[PlayerState] = {
    players.toSeq.filterNot(_._1 == playerId).map(_._2.state)
  }

  private def raceUpdateForPlayer(player: Player, playerState: Option[PlayerState]) = {
    val id = player.id.stringify
    RaceUpdate(
      playerId = id,
      now = DateTime.now,
      startTime = startTime,
      course = None, // already transmitted in initial update
      playerState = playerState,
      wind = wind,
      opponents = opponentsTo(id),
      leaderboard = leaderboard,
      isMaster = id == master.id.stringify,
      watching = false,
      timeTrial = false
    )
  }

  private def raceUpdateForWatcher(watcher: Player) = {
    RaceUpdate(
      playerId = watcher.id.stringify,
      now = DateTime.now,
      startTime = startTime,
      course = None, // already transmitted in initial update
      playerState = None,
      wind = wind,
      opponents = players.values.map(_.state).toSeq,
      leaderboard = leaderboard,
      isMaster = false,
      watching = true,
      timeTrial = false
    )
  }


  override def postStop() = ticks.foreach(_.cancel())
}

object RaceActor {
  def props(race: Race, master: Player) = Props(new RaceActor(race, master))
}
