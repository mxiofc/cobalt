package com.cobalt.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Native shell for the Cobalt web app.
 *
 * The web app is served from the APK assets through a small asset loader,
 * which gives the WebView a real https origin. That keeps absolute asset
 * URLs, clipboard access and the API calls working while the app remains
 * fully self-contained.
 */
public class MainActivity extends Activity {

    private static final String ASSET_HOST = "https://appassets.androidplatform.net";
    private static final int STORAGE_PERMISSION_REQUEST = 1001;
    private static final int FILE_CHOOSER_REQUEST = 2001;

    private WebView webView;
    private View splashOverlay;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        splashOverlay = findViewById(R.id.splashOverlay);
        webView = (WebView) findViewById(R.id.webView);

        webView.setBackgroundColor(Color.BLACK);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(settings.getUserAgentString() + " CobaltApp/1.0");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return interceptAsset(request.getUrl());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return interceptAsset(Uri.parse(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                hideSplash();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    hideSplash();
                    toast("Could not load Cobalt. Please check your connection.");
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                request.grant(request.getResources());
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                // Blob downloads are handled by the JavaScript bridge. Regular
                // URLs are handed to the native download manager.
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    handleDownload(url);
                }
            }
        });

        webView.addJavascriptInterface(new AndroidBridge(), "Android");

        ImageView logo = (ImageView) findViewById(R.id.splashLogo);
        TextView title = (TextView) findViewById(R.id.splashTitle);
        TextView subtitle = (TextView) findViewById(R.id.splashSubtitle);

        if (logo != null) {
            logo.startAnimation(AnimationUtils.loadAnimation(this, R.anim.splash_logo_in));
        }
        if (title != null) {
            title.startAnimation(AnimationUtils.loadAnimation(this, R.anim.splash_text_in));
        }
        if (subtitle != null) {
            subtitle.startAnimation(AnimationUtils.loadAnimation(this, R.anim.splash_text_in));
        }

        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        ensureStoragePermission();

        webView.loadUrl(ASSET_HOST + "/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    } else if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toast("Storage permission granted");
            } else {
                toast("Storage permission is needed to save downloads");
            }
        }
    }

    private void hideSplash() {
        if (splashOverlay != null && splashOverlay.getVisibility() == View.VISIBLE) {
            splashOverlay.animate()
                    .alpha(0f)
                    .setDuration(500)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            splashOverlay.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    private boolean handleUrl(String url) {
        if (url == null) {
            return false;
        }
        if (url.startsWith(ASSET_HOST)) {
            return false;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            openExternal(url);
            return true;
        }
        if (url.startsWith("intent:")) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ignored) {
            }
            return true;
        }
        if (url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("sms:")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception ignored) {
            }
            return true;
        }
        return false;
    }

    private void openExternal(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("Could not open link");
        }
    }

    private WebResourceResponse interceptAsset(Uri uri) {
        if (uri == null) {
            return null;
        }
        if (!"https".equals(uri.getScheme()) || !"appassets.androidplatform.net".equals(uri.getHost())) {
            return null;
        }

        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            path = "/index.html";
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        String[] candidates = new String[]{
                path,
                path + ".html",
                path + "/index.html",
                "/404.html"
        };

        for (String candidate : candidates) {
            String assetPath = "www" + candidate;
            try {
                InputStream input = getAssets().open(assetPath);
                String mimeType = mimeFor(assetPath);
                WebResourceResponse response = new WebResourceResponse(mimeType, "UTF-8", input);
                Map<String, String> headers = new HashMap<>();
                headers.put("Access-Control-Allow-Origin", "*");
                headers.put("Cache-Control", "no-cache");
                response.setResponseHeaders(headers);
                return response;
            } catch (IOException ignored) {
                // Try the next candidate.
            }
        }
        return null;
    }

    private String mimeFor(String path) {
        String extension = "";
        int dot = path.lastIndexOf('.');
        if (dot >= 0) {
            extension = path.substring(dot + 1).toLowerCase();
        }
        switch (extension) {
            case "html":
            case "htm":
                return "text/html";
            case "js":
            case "mjs":
                return "application/javascript";
            case "css":
                return "text/css";
            case "json":
            case "map":
                return "application/json";
            case "webmanifest":
                return "application/manifest+json";
            case "wasm":
                return "application/wasm";
            case "png":
                return "image/png";
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "gif":
                return "image/gif";
            case "webp":
                return "image/webp";
            case "svg":
                return "image/svg+xml";
            case "ico":
                return "image/x-icon";
            case "woff":
                return "font/woff";
            case "woff2":
                return "font/woff2";
            case "ttf":
                return "font/ttf";
            case "otf":
                return "font/otf";
            case "mp4":
                return "video/mp4";
            case "webm":
                return "video/webm";
            case "mp3":
                return "audio/mpeg";
            case "ogg":
                return "audio/ogg";
            case "opus":
                return "audio/opus";
            case "wav":
                return "audio/wav";
            case "txt":
                return "text/plain";
            case "xml":
                return "text/xml";
            case "pdf":
                return "application/pdf";
            default:
                return "application/octet-stream";
        }
    }

    private void ensureStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        STORAGE_PERMISSION_REQUEST);
            }
        }
    }

    private void saveBlobToFile(final String base64, final String filename, final String mimeType) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                saveBlobToFileInternal(base64, filename, mimeType);
            }
        }).start();
    }

    private void saveBlobToFileInternal(String base64, String filename, String mimeType) {
        try {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                    && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast("Storage permission is needed to save downloads");
                        requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                STORAGE_PERMISSION_REQUEST);
                    }
                });
                return;
            }

            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            String safeName = sanitizeFilename(filename, mimeType);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, safeName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                Uri item = getContentResolver().insert(collection, values);
                if (item == null) {
                    throw new IOException("Could not create download entry");
                }

                try (OutputStream output = getContentResolver().openOutputStream(item)) {
                    if (output == null) {
                        throw new IOException("Could not open download stream");
                    }
                    output.write(data);
                }

                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(item, values, null, null);

                showToastOnUiThread("Saved to Downloads: " + safeName);
            } else {
                File downloads = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!downloads.exists() && !downloads.mkdirs()) {
                    throw new IOException("Could not create Downloads folder");
                }
                File file = new File(downloads, safeName);
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(data);
                }
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)));
                showToastOnUiThread("Saved to Downloads: " + safeName);
            }
        } catch (Exception e) {
            showToastOnUiThread("Could not save file: " + e.getMessage());
        }
    }

    private String sanitizeFilename(String filename, String mimeType) {
        String name = filename == null ? "" : filename.trim();
        if (name.isEmpty()) {
            name = "download";
        }
        name = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.length() > 120) {
            name = name.substring(0, 120);
        }
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            if (extension != null) {
                name = name + "." + extension;
            }
        }
        return name;
    }

    private void openUrlInBrowser(String url) {
        if (url == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("Could not open link");
        }
    }

    private void handleDownload(String url) {
        if (url == null) {
            return;
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType("application/octet-stream");
            request.addRequestHeader("User-Agent", webView.getSettings().getUserAgentString());
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, null, "application/octet-stream"));
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);

            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager != null) {
                manager.enqueue(request);
                toast("Downloading…");
                return;
            }
        } catch (Exception ignored) {
            // Fall through to the system browser.
        }
        openUrlInBrowser(url);
    }

    private void showToastOnUiThread(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                toast(message);
            }
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void saveBlob(String base64, String filename, String mimeType) {
            saveBlobToFile(base64, filename, mimeType);
        }

        @JavascriptInterface
        public void openUrl(String url) {
            openUrlInBrowser(url);
        }

        @JavascriptInterface
        public String getVersion() {
            try {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            } catch (Exception e) {
                return "1.0";
            }
        }

        @JavascriptInterface
        public void toast(String message) {
            MainActivity.this.toast(message);
        }
    }
}
