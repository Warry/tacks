module Screens.Login.View where

import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (..)

import AppTypes exposing (..)
import Models exposing (..)

import Screens.Login.Types exposing (..)
import Screens.Login.Updates exposing (addr)

import Screens.Utils exposing (..)
import Screens.Layout as Layout


view : Context -> Screen -> Html
view ctx screen =
  Layout.layoutWithNav "login" ctx
    [ container ""
      [ h1 [] [ text "Login" ]
      , row [ col' 6 [ loginForm screen ] ]
      ]
    ]

loginForm : Screen -> Html
loginForm {email, password, loading, error} =
  div [ class "form-login form-vertical" ]

    [ div [ class "form-group" ]
      [ textInput
        [ value email
        , onInput addr SetEmail
        , onEnter addr Submit
        , placeholder "Email"
        ]
      ]
    , div [ class "form-group" ]
      [ passwordInput
        [ value password
        , onInput addr SetPassword
        , onEnter addr Submit
        , placeholder "Password"
        ]
      ]
    , errorLine error
    , div []
      [ button
        [ class "btn btn-primary"
        , disabled loading
        , onClick addr Submit
        ]
        [ text "Submit" ]
      ]
    ]

errorLine : Bool -> Html
errorLine error =
  if error then
    p [ class "form-error-global" ] [ text "Login failure. Wrong credentials?" ]
  else
    text ""
