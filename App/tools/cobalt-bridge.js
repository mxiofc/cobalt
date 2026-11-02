/*
 * Cobalt native bridge.
 *
 * This file is injected into the built web app. It does three things:
 *   1. forces the dark (black) theme and disables local processing,
 *   2. routes blob downloads to the native Android bridge,
 *   3. routes direct links to the native download manager / browser.
 *
 * It is loaded from the same https origin as the app, so the CSP and
 * clipboard APIs keep working.
 */
(function () {
    'use strict';

    try {
        var stored = JSON.parse(localStorage.getItem('settings') || '{}');
        stored.schemaVersion = 6;
        stored.appearance = Object.assign({}, stored.appearance, {
            theme: 'dark',
            hideRemuxTab: true
        });
        stored.save = Object.assign({}, stored.save, {
            localProcessing: 'disabled'
        });
        localStorage.setItem('settings', JSON.stringify(stored));
    } catch (error) {
        /* localStorage can be unavailable in private modes. */
    }

    // The native shell saves files itself; hide the web share API so the
    // app falls back to the download path instead of a missing share sheet.
    try {
        Object.defineProperty(navigator, 'share', {
            value: undefined,
            configurable: true
        });
    } catch (error) {
        /* ignore */
    }

    function hasBridge() {
        return typeof window.Android !== 'undefined' && window.Android !== null;
    }

    function saveBlob(blob, filename) {
        if (!hasBridge()) {
            return false;
        }
        var reader = new FileReader();
        reader.onloadend = function () {
            var result = reader.result || '';
            var base64 = result.indexOf(',') >= 0 ? result.split(',')[1] : result;
            try {
                window.Android.saveBlob(
                    base64,
                    filename || 'download',
                    blob.type || 'application/octet-stream'
                );
            } catch (error) {
                console.error('Cobalt bridge: saveBlob failed', error);
            }
        };
        reader.readAsDataURL(blob);
        return true;
    }

    function openExternal(url) {
        if (!hasBridge()) {
            return false;
        }
        try {
            window.Android.openUrl(url);
            return true;
        } catch (error) {
            return false;
        }
    }

    // The web app creates a temporary <a download> element for blob files.
    // Intercept its click and hand the blob to the native save bridge.
    var originalClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
        var href = this.href || '';
        if (href.indexOf('blob:') === 0) {
            var filename = this.download || 'download';
            fetch(href)
                .then(function (response) {
                    return response.blob();
                })
                .then(function (blob) {
                    saveBlob(blob, filename);
                })
                .catch(function (error) {
                    console.error('Cobalt bridge: blob fetch failed', error);
                });
            return;
        }
        return originalClick.call(this);
    };

    // Direct links use window.open in the web app. Route them to the native
    // download manager and return a truthy stub so the app does not show
    // its "popup blocked" fallback dialog.
    var originalOpen = window.open;
    window.open = function (url, target, features) {
        if (typeof url === 'string' && /^(https?:)/i.test(url)) {
            openExternal(url);
            return {
                closed: false,
                close: function () {},
                focus: function () {},
                blur: function () {}
            };
        }
        return originalOpen.call(window, url, target, features);
    };

    // Some versions of the app call navigator.share for files. The native
    // bridge handles saving instead.
    if (navigator.share) {
        try {
            Object.defineProperty(navigator, 'share', {
                value: undefined,
                configurable: true
            });
        } catch (error) {
            /* ignore */
        }
    }
})();
