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
  const sharedAccountSocket = new t.context.WebSocket('wss://live.tradovateapi.com/v1/websocket');
  sharedAccountSocket.send('authorize\n1\n\n'+token+'.cached');
  assert.equal(t.messages.length,2);
  assert.equal(t.messages.at(-1).environment,'demo');
  assert.equal(t.messages.at(-1).accessToken,token+'.cached');
  const outside = new t.context.WebSocket('wss://evil.example/v1/websocket');
  outside.send('authorize\n1\n\n'+token);
  assert.equal(t.messages.length,2);
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
  const shared = fixture();
  shared.setResponse({d:{access_token:token,user_id:42,expires_in:3600}});
  await shared.context.fetch('https://live.tradovateapi.com/v1/auth/accesstokenrequest');
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(shared.messages.length,1);
  assert.equal(shared.messages[0].environment,'demo');
  assert.equal(shared.messages[0].accessToken,token);
  assert.equal(shared.messages[0].userId,42);
  assert.equal(shared.messages[0].expires_in,3600);
  assert.equal(shared.messages[0].mdAccessToken,undefined);
  await shared.context.fetch('https://live.tradovateapi.com/v1/auth/renewaccesstoken');
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(shared.messages.at(-1).authSource,'renewal');
  const count=shared.messages.length;
  shared.setResponse({accessToken:token,userId:42,environment:'live'});
  await shared.context.fetch('https://live.tradovateapi.com/v1/auth/accesstokenrequest');
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(shared.messages.length,count);
  shared.setResponse({accessToken:token,userId:42});
  for (const url of ['http://live.tradovateapi.com/v1/auth/session','https://live.tradovateapi.com.evil.test/v1/auth/session','https://user:secret@live.tradovateapi.com/v1/auth/session','https://live.tradovateapi.com/order/placeorder']) {
    await shared.context.fetch(url);
  }
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(shared.messages.length,count);
  for (const c of [fixture('https://evil.example'), fixture('https://trader.tradovate.com',false)]) {
    const other=new c.context.WebSocket('wss://demo.tradovateapi.com/v1/websocket');
    other.send('authorize\n1\n\n'+token);
    assert.equal(c.messages.length,0);
  }
  console.log('LOGIN_BRIDGE_TEST PASS');
})().catch(error=>{ console.error(error.name+': '+error.message); process.exitCode=1; });
