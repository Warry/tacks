module Page.Game.Update (..) where

import List exposing (take, (::))
import Task exposing (Task, succeed, map, andThen)
import Task.Extra exposing (delay)
import Time exposing (millisecond, second)
import String
import Time exposing (Time)
import Signal
import Dict exposing (Dict)
import Result exposing (Result(Ok, Err))
import Effects exposing (Effects, Never, none, tick)
import Response exposing (..)
import Model
import Model.Shared exposing (..)
import Page.Game.Model exposing (..)
import ServerApi
import Game.Models exposing (defaultGame, GameState)
import Game.Steps exposing (gameStep)
import Game.Inputs as Input
import Game.Outputs as Output
import Update.Utils as Utils


addr : Signal.Address Action
addr =
  Utils.pageAddr Model.GameAction


mount : String -> ( Model, Effects Action )
mount id =
  taskRes initial (loadLiveTrack id)


update : Player -> Action -> Model -> ( Model, Effects Action )
update player action model =
  case action of
    LoadLiveTrack result ->
      case result of
        Ok liveTrack ->
          res model (tick <| InitGameState liveTrack)

        Err _ ->
          res { model | notFound = True } none

    InitGameState liveTrack time ->
      let
        gameState =
          defaultGame time liveTrack.track.course player

        newModel =
          { model | gameState = Just gameState }
            |> applyLiveTrack liveTrack
      in
        res newModel pingServer

    PingServer time ->
      if model.live then
        res model none
      else
        res { model | gameState = updateTime time model.gameState } pingServer

    SetTab tab ->
      res { model | tab = tab } none

    StartRace ->
      let
        startTask =
          Signal.send Output.serverAddress Output.StartRace
            |> Task.map (\_ -> NoOp)
      in
        taskRes model startTask

    ExitRace ->
      let
        newModel =
          { model | gameState = Maybe.map clearCrossedGates model.gameState }

        exitTask =
          Signal.send Output.serverAddress Output.EscapeRace
            |> Task.map (\_ -> NoOp)
      in
        taskRes newModel exitTask

    GameUpdate gameInput ->
      let
        newGameState =
          Maybe.map (gameStep gameInput) model.gameState
      in
        res { model | gameState = newGameState, live = True } none

    EnterChat ->
      let
        newGameState =
          Maybe.map (\gs -> { gs | chatting = True }) model.gameState
      in
        res { model | gameState = newGameState } none

    LeaveChat ->
      let
        newGameState =
          Maybe.map (\gs -> { gs | chatting = False }) model.gameState
      in
        res { model | gameState = newGameState } none

    UpdateMessageField s ->
      res { model | messageField = s } none

    SubmitMessage ->
      if model.live then
        taskRes { model | messageField = "" } (sendMessage model.messageField)
      else
        res model none

    NewMessage msg ->
      res { model | messages = take 30 (msg :: model.messages) } none

    AddGhost runId player ->
      let
        newGhostRuns =
          Dict.insert runId player model.ghostRuns
      in
        taskRes { model | ghostRuns = newGhostRuns } (addGhost runId player)

    RemoveGhost runId ->
      let
        newGhostRuns =
          Dict.remove runId model.ghostRuns
      in
        taskRes { model | ghostRuns = newGhostRuns } (removeGhost runId)

    UpdateLiveTrack liveTrack ->
      res (applyLiveTrack liveTrack model) none

    NoOp ->
      res model none


applyLiveTrack : LiveTrack -> Model -> Model
applyLiveTrack ({ track, players, races } as liveTrack) model =
  let
    racePlayers =
      List.concatMap .players races

    inRace p =
      List.member p racePlayers

    freePlayers =
      List.filter (not << inRace) players
  in
    { model | liveTrack = Just liveTrack, races = races, freePlayers = freePlayers }


loadLiveTrack : String -> Task Never Action
loadLiveTrack id =
  ServerApi.getLiveTrack id
    |> Task.map LoadLiveTrack


pingServer : Effects Action
pingServer =
  tick PingServer


sendMessage : String -> Task error Action
sendMessage content =
  if String.isEmpty content then
    Task.succeed NoOp
  else
    Signal.send Output.serverAddress (Output.SendMessage content)
      |> Task.map (\_ -> NoOp)


addGhost : String -> Player -> Task error Action
addGhost runId player =
  Signal.send Output.serverAddress (Output.AddGhost runId player)
    |> Task.map (\_ -> NoOp)


removeGhost : String -> Task error Action
removeGhost runId =
  Signal.send Output.serverAddress (Output.RemoveGhost runId)
    |> Task.map (\_ -> NoOp)


clearCrossedGates : GameState -> GameState
clearCrossedGates ({ playerState } as gameState) =
  { gameState | playerState = { playerState | crossedGates = [] } }


updateTime : Time -> Maybe GameState -> Maybe GameState
updateTime time =
  let
    updateTime timers t =
      { timers | localTime = time }
  in
    Maybe.map (\gs -> { gs | timers = updateTime gs.timers time })
