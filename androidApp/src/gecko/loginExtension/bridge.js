// Gives login.js, which runs in the page, the same window.UniTradingAuth.postMessage that Android WebView provides.
// Runs only in the top frame; the app still checks each message's origin and frame.
(() => {
  const channel = cloneInto({}, window);
  exportFunction(payload => {
    if (typeof payload === 'string') browser.runtime.sendNativeMessage('unitrading', payload).catch(() => {});
  }, channel, { defineAs: 'postMessage' });
  window.wrappedJSObject.UniTradingAuth = channel;
})();
