module Render.Relative where

import Render.Utils (..)
import Core (..)
import Geo (..)
import Game (..)
import String
import Text

import Debug

renderStartLine : Gate -> Float -> Bool -> Form
renderStartLine gate markRadius started =
  let lineStyle = if started then dotted white else solid white
      markColor = if started then green else red
      line = segment left right |> traced lineStyle
      (left,right) = getGateMarks gate
      marks = map (\g -> circle markRadius |> filled markColor |> move g) [left, right]
  in  group (line :: marks)

renderGate : Gate -> Float -> Bool -> Form
renderGate gate markRadius isNext =
  let (left,right) = getGateMarks gate
      (markStyle,lineStyle) = 
        if isNext
          then (filled orange, traced (dotted orange))
          else (filled white, traced (solid colors.seaBlue))
      line = segment left right |> lineStyle
      leftMark = circle markRadius |> markStyle |> move left
      rightMark = circle markRadius |> markStyle |> move right
  in  group [line, leftMark, rightMark]

  --    isNext = nextGate == Just gate.location
  --    line = segment left right |> traced (dotted orange)
  --    markStyle = if isNext then filled orange else filled white
  --    leftMark = circle markRadius |> markStyle |> move left
  --    rightMark = circle markRadius |> markStyle |> move right
  --    marks = [leftMark, rightMark]
  --in  if isNext then group (line :: marks) else group marks

renderBoatAngles : Boat -> Form
renderBoatAngles boat =
  let drawLine a = segment (fromPolar (15, a)) (fromPolar (25, a)) |> traced (solid white)
      directionLine = drawLine (toRadians boat.direction) |> alpha 0.8
      windLine = drawLine (toRadians (boat.direction - boat.windAngle)) |> alpha 0.3
      windAngleText = (show (abs boat.windAngle)) ++ "&deg;" |> baseText
        |> (if boat.controlMode == FixedWindAngle then line Under else id)
        |> centered |> toForm 
        |> move (fromPolar (40, toRadians (boat.direction - (boat.windAngle / 2))))
        |> alpha 0.8
  in  group [directionLine, windLine, windAngleText] 

renderEqualityLine : Point -> Float -> Form
renderEqualityLine (x,y) windOrigin =
  let left = (fromPolar (50, toRadians (windOrigin - 90)))
      right = (fromPolar (50, toRadians (windOrigin + 90)))
  in  segment left right |> traced (dotted white) |> alpha 0.2

renderBoat : Boat -> Form
renderBoat boat =
  let 
    hull = image 8 19 "/assets/images/icon-boat-white.png"
      |> toForm
      |> rotate (toRadians (boat.direction + 90))
    angles = renderBoatAngles boat
    eqLine = renderEqualityLine boat.position boat.windOrigin
  in 
    group [angles, eqLine, hull]
      |> move boat.position

renderOpponent : Opponent -> Form
renderOpponent opponent =
  image 8 19 "/assets/images/icon-boat-white.png"
    |> toForm
    |> alpha 0.3
    |> rotate (toRadians (opponent.direction + 90))
    |> move opponent.position

renderBounds : (Point, Point) -> Form
renderBounds box =
  let (ne,sw) = box
      w = fst ne - fst sw
      h = snd ne - snd sw
      cw = (fst ne + fst sw) / 2
      ch = (snd ne + snd sw) / 2
  in rect w h |> filled colors.seaBlue
              |> move (cw, ch)

renderGust : Wind -> Gust -> Form
renderGust wind gust =
  let
    c = circle gust.radius |> filled black |> alpha (0.05 + gust.speedImpact / 2)
    --a = toRadians wind.origin
    --a' = toRadians (wind.origin + gust.originDelta)
    --s = segment (0,0) (fromPolar (gust.radius * 0.2, a))
    --  |> traced (solid white) |> alpha 0.1
    --s' = segment (0,0) (fromPolar (gust.radius * 0.5, a'))
    --  |> traced (solid white) |> alpha 0.1
  in
    group [c] |> move gust.position

renderGusts : Wind -> Form
renderGusts wind =
  group <| map (renderGust wind) wind.gusts

renderIsland : Island -> Form
renderIsland {location,radius} =
  let --grad = radial (0,0) (radius - 15) (0,0) radius [(0, colors.sand), (1, colors.seaBlue)]
      ground = circle radius |> filled colors.sand
      --palmWidth = minimum [round radius, 100]
      --palm = fittedImage palmWidth palmWidth "/assets/images/palmtree.png" |> toForm --|> move (0, island.radius/5)
      --palm = image palmWidth palmWidth "/assets/images/palmtree.png" |> toForm |> move (0, (toFloat palmWidth) / 2)
  in group [ground] |> move location

renderIslands : GameState -> Form
renderIslands gameState =
  group (map renderIsland gameState.course.islands)

renderLaylines : Boat -> Course -> Form
renderLaylines boat course = 
  let upwindVmgAngleR = toRadians upwindVmg
      upwindMark = course.upwind
      (left,right) = getGateMarks upwindMark
      windAngleR = toRadians boat.windOrigin

      leftLL = add left (fromPolar (500, windAngleR + upwindVmgAngleR - pi))

      l1 = segment left leftLL |> traced (solid white)
  in group [l1] |> alpha 0.3

renderCountdown : GameState -> Boat -> Maybe Form
renderCountdown gameState boat = 
  let messageBuilder msg = baseText msg |> centered |> toForm |> move (0, gameState.course.downwind.y + 50)
  in  if | gameState.countdown > 0 -> 
             let cs = gameState.countdown |> inSeconds |> ceiling
                 m = cs `div` 60
                 s = cs `rem` 60
                 msg = "Start in " ++ (show m) ++ "'" ++ (show s) ++ "\"..."
             in  Just (messageBuilder msg)
         | (isEmpty boat.passedGates) -> Just (messageBuilder "Go!")
         | otherwise -> Nothing

renderRelative : GameState -> Form
renderRelative ({boat,opponents,course} as gameState) =
  let nextGate = findNextGate boat course.laps
      downwindOrStartLine = if isEmpty boat.passedGates
        then renderStartLine course.downwind course.markRadius (gameState.countdown <= 0)
        else renderGate course.downwind course.markRadius (nextGate == Just Downwind)
      justForms = [
        renderBounds gameState.course.bounds,
        renderIslands gameState,
        downwindOrStartLine,
        renderGate course.upwind course.markRadius (nextGate == Just Upwind),
        --renderLaylines boat gameState.course,
        renderBoat boat,
        group (map renderOpponent opponents),
        renderGusts gameState.wind
      ]
      maybeForms = [
        renderCountdown gameState boat
      ]
  in  group (justForms ++ (compact maybeForms)) |> move (neg boat.center)
