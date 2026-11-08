package com.unitrading.tradovate

/** Injected only into the official login window; never reads form fields or passwords. */
object TradovateLoginScript {
    fun forEnvironment(environment: TradovateEnvironment): String = """
        (() => {
          if (location.origin !== 'https://trader.tradovate.com' || window.top !== window || window.__uniTradingAuth) return;
          Object.defineProperty(window, '__uniTradingAuth', {value: true});
          const env = '${environment.wire}';
          const apiHost = '${environment.host}';
          const mdHost = '${environment.marketHost}';
          const authHosts = new Set(['live.tradovateapi.com', 'demo.tradovateapi.com', 'tv.tradovateapi.com', 'tv-demo.tradovateapi.com']);
          let last = '';
          const emit = (response, authSource = 'socket') => {
            const data = response && response.d && typeof response.d === 'object' ? response.d : response;
            if (!data || typeof data !== 'object' || data.error || data.errorText || data['p-ticket'] || data['p-captcha']) return;
            if (['demo','live'].includes(data.environment) && data.environment !== env) return;
            const msg = {kind:'auth', environment:env, authSource};
            const access = data.accessToken ?? data.access_token ?? data.token;
            if (typeof access === 'string' && access.length <= 32768) msg.accessToken = access;
            for (const key of ['mdAccessToken','expirationTime','marketUrl']) {
              if (typeof data[key] === 'string' && data[key].length <= 32768) msg[key] = data[key];
            }
            const name = data.userName ?? data.name;
            if (typeof name === 'string') msg.userName = name.slice(0,256);
            const userId = data.userId ?? data.user_id;
            if (typeof userId === 'number' || typeof userId === 'string') msg.userId = userId;
            const duration = Number(data.expires_in);
            if (Number.isInteger(duration) && duration > 0 && duration <= 604800) msg.expires_in = duration;
            const routes = data.apiHosts;
            if (routes && typeof routes === 'object' && !Array.isArray(routes)) {
              msg.apiHosts = {};
              for (const key of ['demo','live','mdDemo','mdLive']) {
                if (routes[key]) msg.apiHosts[key] = typeof routes[key] === 'string' ? routes[key].slice(0,254) : '<invalid>';
              }
            } else if (routes && routes.length !== 0) msg.apiHosts = '<invalid>';
            if (!msg.accessToken && !msg.mdAccessToken) return;
            const payload = JSON.stringify(msg);
            if (payload === last) return;
            last = payload;
            if (window.UniTradingAuth && typeof window.UniTradingAuth.postMessage === 'function') window.UniTradingAuth.postMessage(payload);
            else if (window.chrome && window.chrome.webview) window.chrome.webview.postMessage(payload);
          };
          const authResponse = value => {
            try {
              const u = new URL(value, location.href);
              return u.protocol === 'https:' && authHosts.has(u.hostname) && u.port === '' && !u.username && !u.password &&
                /^\/(v1\/)?auth\/[a-z][a-z0-9_-]*$/i.test(u.pathname);
            } catch (_) { return false; }
          };
          const originalFetch = window.fetch;
          if (originalFetch) window.fetch = async function(input, init) {
            const response = await originalFetch.apply(this, arguments);
            const url = typeof input === 'string' ? input : input && input.url;
            if (response.ok && authResponse(url)) response.clone().text().then(text => {
              if (text.length <= 131072) { try { emit(JSON.parse(text), /renewaccesstoken/i.test(url) ? 'renewal' : 'response'); } catch (_) {} }
            }).catch(() => {});
            return response;
          };
          const open = XMLHttpRequest.prototype.open;
          const send = XMLHttpRequest.prototype.send;
          const urls = new WeakMap();
          XMLHttpRequest.prototype.open = function(method, url) { urls.set(this, String(url)); return open.apply(this, arguments); };
          XMLHttpRequest.prototype.send = function() {
            if (authResponse(urls.get(this))) this.addEventListener('load', () => {
              try {
                if (this.status >= 200 && this.status < 300) {
                  const source = /renewaccesstoken/i.test(urls.get(this)) ? 'renewal' : 'response';
                  if (this.responseType === 'json') emit(this.response, source);
                  else if ((!this.responseType || this.responseType === 'text') && this.responseText.length <= 131072) emit(JSON.parse(this.responseText), source);
                }
              } catch (_) {}
            }, {once:true});
            return send.apply(this, arguments);
          };
          const NativeSocket = window.WebSocket;
          window.WebSocket = new Proxy(NativeSocket, {
            construct(Target, args) {
              const socket = Reflect.construct(Target, args);
              let target;
              try { target = new URL(String(args[0])); } catch (_) { return socket; }
              if (target.protocol !== 'wss:' || target.port !== '' || target.pathname !== '/v1/websocket') return socket;
              const accountSocket = ['live.tradovateapi.com','demo.tradovateapi.com'].includes(target.hostname);
              const kind = accountSocket ? 'accessToken' : target.hostname === mdHost || env === 'demo' && target.hostname === 'md.tradovateapi.com' ? 'mdAccessToken' : null;
              if (!kind) return socket;
              const nativeSend = socket.send;
              socket.send = function(value) {
                const result = nativeSend.apply(this, arguments);
                if (typeof value === 'string' && value.startsWith('authorize\n')) {
                  const token = value.split('\n\n').slice(1).join('\n\n').trim();
                  if (token.length >= 16 && token.length <= 65536) {
                    const data = {[kind]:token};
                    if (kind === 'mdAccessToken') data.marketUrl = target.href;
                    emit(data);
                  }
                }
                return result;
              };
              return socket;
            }
          });
        })();
    """.trimIndent()
}
