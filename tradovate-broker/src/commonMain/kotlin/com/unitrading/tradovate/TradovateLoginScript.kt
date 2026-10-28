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
          let last = '';
          const emit = data => {
            if (!data || typeof data !== 'object' || data.error || data.errorText || data['p-ticket'] || data['p-captcha']) return;
            const msg = {kind:'auth', environment:env};
            for (const key of ['accessToken','mdAccessToken','userName','name','expirationTime','marketUrl']) {
              if (typeof data[key] === 'string' && data[key].length <= 65536) msg[key] = data[key];
            }
            if (typeof data.userId === 'number' || typeof data.userId === 'string') msg.userId = data.userId;
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
              return u.protocol === 'https:' && u.hostname === apiHost && u.port === '' &&
                ['/v1/auth/accesstokenrequest','/v1/auth/renewaccesstoken'].includes(u.pathname.toLowerCase());
            } catch (_) { return false; }
          };
          const originalFetch = window.fetch;
          if (originalFetch) window.fetch = async function(input, init) {
            const response = await originalFetch.apply(this, arguments);
            const url = typeof input === 'string' ? input : input && input.url;
            if (response.ok && authResponse(url)) response.clone().text().then(text => {
              if (text.length <= 131072) { try { emit(JSON.parse(text)); } catch (_) {} }
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
                  if (this.responseType === 'json') emit(this.response);
                  else if ((!this.responseType || this.responseType === 'text') && this.responseText.length <= 131072) emit(JSON.parse(this.responseText));
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
              const kind = target.hostname === apiHost ? 'accessToken' : target.hostname === mdHost || env === 'demo' && target.hostname === 'md.tradovateapi.com' ? 'mdAccessToken' : null;
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
