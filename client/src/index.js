const Elm = require('./Main');
require('../styles/main.scss');


function readData(id, el) {
  const value = el.getElementById(id).textContent.trim();
  return value && JSON.parse(value);
}

function dims() {
  return [
    window.innerWidth || document.documentElement.clientWidth || document.body.clientWidth,
    window.innerHeight || document.documentElement.clientHeight || document.body.clientHeight
  ];
}

let ws = null;
let wsUrl = null;

const appSetup = readData('appSetup', document);
appSetup.dims = dims();

const game = Elm.fullscreen(Elm.Main, {
  raceInput: null,
  gameActionsInput: { tag: 'NoOp' },
  appSetup
});

game.ports.playerOutput.subscribe((output) => {
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ tag: 'PlayerInput', playerInput: output }));
  }
});

game.ports.chatOutput.subscribe((output) => {
  if (output && ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ tag: 'NewMessage', content: output }));
  }
});

game.ports.chatScrollDown.subscribe(() => {
  const el = document.getElementsByClassName('messages')[0];
  if (el) {
    setTimeout(() => {
      el.scrollTop = el.scrollHeight;
    }, 30);
  }
});

game.ports.activeTrack.subscribe((id) => {
  if (ws) {
    ws.close();
  }

  if (id) {
    wsUrl = window.jsRoutes.controllers.WebSockets.trackPlayer(id).webSocketURL();
    ws = new WebSocket(wsUrl);

    ws.onmessage = (event) => {
      const frame = JSON.parse(event.data);
      if (frame.tag === 'RaceUpdate') {
        game.ports.raceInput.send(frame.raceUpdate);
      } else {
        game.ports.gameActionsInput.send(frame);
      }
    };
  } else {
    game.ports.raceInput.send(null);
  }
});