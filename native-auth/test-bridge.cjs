const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const script = fs.readFileSync(process.argv[2], 'utf8');
function fixture(origin = 'https://trader.tradovate.com', topLevel = true) {
  const messages = [];
  class Socket {
    static OPEN = 1;
    constructor(url) { this.url = url; this.sent = []; }
    send(value) { this.sent.push(value); }
  }
  class Xhr { open() {} send() {} addEventListener(name, cb) { this.listener = cb; } }
  let response = {};
  const context = { location: {origin, href: origin + '/welcome'}, URL, WeakMap, XMLHttpRequest:Xhr, WebSocket:Socket,
    chrome: {webview:{postMessage: text => messages.push(JSON.parse(text))}},
    fetch: async () => ({ok:true, clone: () => ({text:async () => JSON.stringify(response)})}) };
  context.window = context; context.top = topLevel ? context : {};
  vm.runInNewContext(script, context);
  return {context,messages,Socket,setResponse:value=>response=value};
}
(async () => {
  const token = 'eyJhbGciOiJub25lIn0.eyJzdWIiOjQyfQ.test-only';
  const t = fixture();
  assert.equal(t.context.WebSocket.OPEN, 1);
  const ws = new t.context.WebSocket('wss://demo.tradovateapi.com/v1/websocket');
  assert.ok(ws instanceof t.Socket);
  ws.send('authorize\n1\n\n'+token);
  assert.equal(t.messages.length,1);
  assert.equal(t.messages[0].environment,'demo');
  assert.equal(t.messages[0].accessToken,token);
  const wrong = new t.context.WebSocket('wss://live.tradovateapi.com/v1/websocket');
  wrong.send('authorize\n1\n\n'+token);
  assert.equal(t.messages.length,1);
  const md = new t.context.WebSocket('wss://md-demo.tradovateapi.com/v1/websocket');
  md.send('authorize\n2\n\n'+token);
  assert.equal(t.messages.at(-1).mdAccessToken,token);
  assert.equal(t.messages.at(-1).marketUrl,'wss://md-demo.tradovateapi.com/v1/websocket');
  const before=t.messages.length;
  t.setResponse({accessToken:token,userId:42,password:'must-not-leave',secret:'must-not-leave'});
  await t.context.fetch('https://demo.tradovateapi.com/v1/auth/accesstokenrequest');
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(t.messages.length,before+1);
  assert.equal(t.messages.at(-1).userId,42);
  assert.ok(!JSON.stringify(t.messages).includes('must-not-leave'));
  t.setResponse({accessToken:token,userId:42,errorText:'denied'});
  await t.context.fetch('https://demo.tradovateapi.com/v1/auth/accesstokenrequest');
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(t.messages.length,before+1);
  for (const c of [fixture('https://evil.example'), fixture('https://trader.tradovate.com',false)]) {
    const other=new c.context.WebSocket('wss://demo.tradovateapi.com/v1/websocket');
    other.send('authorize\n1\n\n'+token);
    assert.equal(c.messages.length,0);
  }
  console.log('LOGIN_BRIDGE_TEST PASS');
})().catch(error=>{ console.error(error.name+': '+error.message); process.exitCode=1; });
