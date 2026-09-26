package com.campusbotx;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final String PREFS = "campusbot_addresses";
    private static final String DEFAULT_BASE_URL = "http://192.168.4.1";
    private static final String DEFAULT_STREAM_URL = "http://192.168.4.1:81/stream";
    private static final int BLUE = Color.rgb(21, 94, 239);
    private static final int RED = Color.rgb(180, 35, 24);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService controlExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService statusExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private EditText baseUrlInput;
    private EditText streamUrlInput;
    private TextView connectionStatus;
    private TextView liveStatus;
    private TextView streamStatus;
    private WebView cameraView;
    private ScheduledFuture<?> heldMove;
    private ScheduledFuture<?> autopilotLease;
    private volatile String activeBaseUrl = DEFAULT_BASE_URL;
    private String activeStreamUrl = DEFAULT_STREAM_URL;
    private volatile boolean leavingForeground;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(18, 59, 114));
        getWindow().setNavigationBarColor(Color.rgb(18, 59, 114));

        var preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        activeBaseUrl = preferences.getString("base_url", DEFAULT_BASE_URL);
        activeStreamUrl = preferences.getString("stream_url", DEFAULT_STREAM_URL);
        buildScreen();
        baseUrlInput.setText(activeBaseUrl);
        streamUrlInput.setText(activeStreamUrl);
        loadCameraStream();

        statusExecutor.scheduleWithFixedDelay(this::refreshStatus, 0, 2, TimeUnit.SECONDS);
    }

    private void buildScreen() {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(14), dp(18), dp(24));
        scrollView.addView(content);
        setContentView(scrollView);

        TextView title = label("CampusBot X", 26, true);
        content.addView(title);
        content.addView(label("Connect to the robot's CampusBot Wi-Fi before sending commands.", 14, false));

        content.addView(label("Robot base URL", 16, true));
        baseUrlInput = editText("http://192.168.4.1", false);
        content.addView(baseUrlInput, matchWidth());
        content.addView(label("ESP32-CAM stream URL", 16, true));
        streamUrlInput = editText("http://192.168.4.1:81/stream", false);
        content.addView(streamUrlInput, matchWidth());

        LinearLayout addressButtons = row();
        Button save = button("Save addresses");
        Button test = button("Test connection");
        addressButtons.addView(save, weighted());
        addressButtons.addView(test, weighted());
        content.addView(addressButtons);
        connectionStatus = label("Connection: checking…", 14, false);
        content.addView(connectionStatus);
        save.setOnClickListener(view -> saveAddresses(true));
        test.setOnClickListener(view -> testConnection());

        content.addView(label("Live status", 18, true));
        liveStatus = label("Waiting for robot status…", 15, false);
        liveStatus.setTextColor(Color.rgb(16, 24, 40));
        content.addView(liveStatus);

        content.addView(label("Manual drive — press and hold", 18, true));
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER);
        Button forward = button("▲  Forward");
        Button left = button("◀  Left");
        Button stop = button("STOP");
        Button right = button("Right  ▶");
        Button backward = button("▼  Backward");
        addDriveButton(forward, "forward");
        addDriveButton(left, "left");
        addDriveButton(right, "right");
        addDriveButton(backward, "backward");
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER);
        top.addView(forward, weighted());
        controls.addView(top);
        LinearLayout middle = row();
        middle.setGravity(Gravity.CENTER);
        middle.addView(left, weighted());
        middle.addView(stop, weighted());
        middle.addView(right, weighted());
        controls.addView(middle);
        LinearLayout bottom = row();
        bottom.setGravity(Gravity.CENTER);
        bottom.addView(backward, weighted());
        controls.addView(bottom);
        stop.setBackgroundTintList(android.content.res.ColorStateList.valueOf(RED));
        stop.setTextColor(Color.WHITE);
        stop.setOnClickListener(view -> stopRobot("Robot stopped."));
        content.addView(controls);

        LinearLayout safetyButtons = row();
        Button emergency = button("Emergency stop");
        Button resetEmergency = button("Reset emergency");
        emergency.setBackgroundTintList(android.content.res.ColorStateList.valueOf(RED));
        emergency.setTextColor(Color.WHITE);
        safetyButtons.addView(emergency, weighted());
        safetyButtons.addView(resetEmergency, weighted());
        content.addView(safetyButtons);
        emergency.setOnClickListener(view -> emergencyStop());
        resetEmergency.setOnClickListener(view -> resetEmergency());

        content.addView(label("Autopilot", 18, true));
        LinearLayout autoButtons = row();
        Button startAuto = button("Start obstacle avoid");
        Button stopAuto = button("Stop autopilot");
        autoButtons.addView(startAuto, weighted());
        autoButtons.addView(stopAuto, weighted());
        content.addView(autoButtons);
        startAuto.setOnClickListener(view -> startAutopilot());
        stopAuto.setOnClickListener(view -> stopAutopilot());

        content.addView(label("Camera", 18, true));
        streamStatus = label("Camera stream not loaded.", 14, false);
        content.addView(streamStatus);
        cameraView = new WebView(this);
        cameraView.setBackgroundColor(Color.BLACK);
        cameraView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    streamStatus.setText("Camera stream failed: " + error.getDescription());
                }
            }

            @Override
            public void onReceivedHttpError(
                    WebView view, WebResourceRequest request, android.webkit.WebResourceResponse response) {
                if (request.isForMainFrame()) {
                    streamStatus.setText("Camera stream returned HTTP " + response.getStatusCode());
                }
            }
        });
        WebSettings settings = cameraView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        content.addView(cameraView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(230)));
        LinearLayout cameraButtons = row();
        Button loadCamera = button("Load camera");
        Button launchCamera = button("Launch camera");
        cameraButtons.addView(loadCamera, weighted());
        cameraButtons.addView(launchCamera, weighted());
        content.addView(cameraButtons);
        loadCamera.setOnClickListener(view -> {
            if (saveAddresses(false)) {
                loadCameraStream();
            }
        });
        launchCamera.setOnClickListener(view -> launchCamera());
    }

    private void addDriveButton(Button button, String direction) {
        button.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                view.setPressed(true);
                startMoving(direction);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                view.setPressed(false);
                stopRobot("Drive released; stopping.");
                return true;
            }
            return true;
        });
    }

    private void startMoving(String direction) {
        if (!saveAddresses(false)) {
            return;
        }
        cancelHeldMove();
        stopAutopilotLease();
        heldMove = controlExecutor.scheduleWithFixedDelay(
                () -> sendControl("GET", "/move?dir=" + direction, null),
                0, 350, TimeUnit.MILLISECONDS);
    }

    private void stopRobot(String message) {
        cancelHeldMove();
        stopAutopilotLease();
        enqueueControl("POST", "/stop", message);
    }

    private void emergencyStop() {
        cancelHeldMove();
        stopAutopilotLease();
        enqueueControl("POST", "/emergency-stop", "Emergency stop latched.");
    }

    private void resetEmergency() {
        cancelHeldMove();
        stopAutopilotLease();
        enqueueControl("POST", "/emergency-reset", "Emergency stop reset; motors remain stopped.");
    }

    private void startAutopilot() {
        if (!saveAddresses(false)) {
            return;
        }
        cancelHeldMove();
        enqueueControl("POST", "/autopilot/start", "Autopilot start");
        stopAutopilotLease();
        autopilotLease = controlExecutor.scheduleWithFixedDelay(
                () -> sendControl("POST", "/autopilot/start", null),
                1, 1, TimeUnit.SECONDS);
    }

    private void stopAutopilot() {
        stopAutopilotLease();
        enqueueControl("POST", "/autopilot/stop", "Autopilot stopped.");
    }

    private void cancelHeldMove() {
        if (heldMove != null) {
            heldMove.cancel(false);
            heldMove = null;
        }
    }

    private void stopAutopilotLease() {
        if (autopilotLease != null) {
            autopilotLease.cancel(false);
            autopilotLease = null;
        }
    }

    private void sendControl(String method, String path, String successMessage) {
        String base = activeBaseUrl;
        try {
            String response = request(method, base + path);
            JSONObject json = new JSONObject(response);
            if (!json.optBoolean("ok", false)) {
                throw new IOException(json.optString("error", "Robot rejected the command."));
            }
            if (successMessage != null) {
                postConnection(successMessage);
            }
        } catch (Exception exception) {
            String prefix = successMessage == null ? "Control request" : successMessage;
            postConnection(prefix + " failed: " + readableError(exception));
        }
    }

    private void enqueueControl(String method, String path, String successMessage) {
        controlExecutor.execute(() -> sendControl(method, path, successMessage));
    }

    private boolean saveAddresses(boolean showToast) {
        try {
            String base = normalizedUrl(baseUrlInput.getText().toString(), false);
            String rawStream = streamUrlInput.getText().toString().trim();
            String stream = rawStream.isEmpty() ? "" : normalizedUrl(rawStream, true);
            activeBaseUrl = base;
            activeStreamUrl = stream;
            baseUrlInput.setText(base);
            streamUrlInput.setText(stream);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("base_url", base)
                    .putString("stream_url", stream)
                    .apply();
            if (showToast) {
                Toast.makeText(this, "Addresses saved.", Toast.LENGTH_SHORT).show();
            }
            return true;
        } catch (IllegalArgumentException exception) {
            connectionStatus.setText("Address error: " + exception.getMessage());
            return false;
        }
    }

    private void testConnection() {
        if (!saveAddresses(false)) {
            return;
        }
        connectionStatus.setText("Connection: testing…");
        String base = activeBaseUrl;
        statusExecutor.execute(() -> {
            try {
                JSONObject status = new JSONObject(request("GET", base + "/status"));
                if (!status.optBoolean("ok", false)) {
                    throw new IOException(status.optString("error", "Robot rejected the request."));
                }
                mainHandler.post(() -> {
                    connectionStatus.setText("Connection: connected to " + base);
                    updateLiveStatus(status);
                });
            } catch (Exception exception) {
                mainHandler.post(() -> connectionStatus.setText(
                        "Connection failed: " + readableError(exception)));
            }
        });
    }

    private void refreshStatus() {
        if (leavingForeground) {
            return;
        }
        String base = activeBaseUrl;
        try {
            JSONObject status = new JSONObject(request("GET", base + "/status"));
            if (!status.optBoolean("ok", false)) {
                throw new IOException(status.optString("error", "Robot rejected the request."));
            }
            mainHandler.post(() -> {
                updateLiveStatus(status);
                connectionStatus.setText("Connection: connected to " + base);
            });
        } catch (Exception exception) {
            mainHandler.post(() -> {
                liveStatus.setText("Live status unavailable: " + readableError(exception));
                connectionStatus.setText("Connection: unavailable");
            });
        }
    }

    private void updateLiveStatus(JSONObject status) {
        String distance = status.isNull("distanceCm")
                ? "unknown"
                : status.optString("distanceCm", "unknown") + " cm";
        String moving = status.optString("moving", "stopped");
        boolean autopilot = status.optBoolean("autopilot", false);
        boolean emergency = status.optBoolean("emergencyStop", false);
        liveStatus.setText("Distance: " + distance
                + "\nMotion: " + moving
                + "\nAutopilot: " + (autopilot ? "ON" : "off")
                + "\nEmergency stop: " + (emergency ? "LATCHED" : "clear"));
    }

    private void loadCameraStream() {
        if (activeStreamUrl.isBlank()) {
            streamStatus.setText("Enter a camera stream URL.");
            return;
        }
        streamStatus.setText("Loading " + activeStreamUrl);
        cameraView.loadUrl(activeStreamUrl);
    }

    private void launchCamera() {
        if (!saveAddresses(false)) {
            return;
        }
        try {
            startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, Uri.parse(activeStreamUrl)));
        } catch (android.content.ActivityNotFoundException exception) {
            streamStatus.setText("No browser is available to open the camera stream.");
        }
    }

    private static String request(String method, String address) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(900);
            connection.setReadTimeout(900);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = stream == null ? "" : readBody(stream);
            if (code < 200 || code >= 300) {
                String detail = body.isBlank() ? "HTTP " + code : body;
                throw new IOException("HTTP " + code + ": " + detail);
            }
            return body;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readBody(InputStream inputStream) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private static String normalizedUrl(String raw, boolean allowHttps) {
        String value = raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("URL cannot be empty.");
        }
        if (!value.contains("://")) {
            value = "http://" + value;
        }
        Uri uri = Uri.parse(value);
        boolean supportedScheme = "http".equalsIgnoreCase(uri.getScheme())
                || (allowHttps && "https".equalsIgnoreCase(uri.getScheme()));
        if (!supportedScheme || uri.getHost() == null || uri.getUserInfo() != null
                || (!allowHttps && uri.getQuery() != null) || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    allowHttps ? "Use an HTTP(S) URL with a host and no credentials."
                            : "Use an HTTP URL with a host and no credentials/query.");
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String readableError(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    private void postConnection(String message) {
        mainHandler.post(() -> connectionStatus.setText("Connection: " + message));
    }

    private TextView label(String text, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(16, 24, 40));
        if (bold) {
            view.setTypeface(null, android.graphics.Typeface.BOLD);
        }
        view.setPadding(0, dp(9), 0, dp(5));
        return view;
    }

    private EditText editText(String hint, boolean singleLine) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(singleLine);
        input.setTextSize(15);
        return input;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BLUE));
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        params.setMargins(dp(3), dp(2), dp(3), dp(2));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStop() {
        leavingForeground = true;
        cancelHeldMove();
        stopAutopilotLease();
        controlExecutor.execute(() -> sendControl("POST", "/stop", "Stopped on app background."));
        super.onStop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        leavingForeground = false;
    }

    @Override
    protected void onDestroy() {
        statusExecutor.shutdownNow();
        controlExecutor.shutdown();
        if (cameraView != null) {
            cameraView.stopLoading();
            cameraView.destroy();
        }
        super.onDestroy();
    }
}
