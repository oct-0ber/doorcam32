package com.goouuu.doorbell;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public final class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 1;
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 320;
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;
    private static final int MAX_JPEG_BYTES = 512 * 1024;
    private static final long CONTROL_TIMEOUT_MS = 3500;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
    private final ArrayBlockingQueue<byte[]> speakerQueue =
            new ArrayBlockingQueue<>(8);

    private EditText hostInput;
    private EditText tokenInput;
    private TextView statusText;
    private ImageView cameraView;
    private Button connectButton;
    private Button answerButton;
    private Button hangupButton;
    private Button talkButton;
    private TextView statusDot;

    private volatile WebSocket webSocket;
    private volatile boolean connected;
    private volatile boolean connectionPending;
    private volatile boolean ringing;
    private volatile boolean answered;
    private volatile boolean directTalk;
    private volatile boolean audioRunning;
    private volatile boolean videoRunning;
    private volatile java.net.HttpURLConnection cameraConnection;
    private Thread microphoneThread;
    private Thread speakerThread;
    private Thread cameraThread;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private AudioManager audioManager;
    private int previousAudioMode = AudioManager.MODE_NORMAL;
    private boolean previousSpeakerphoneState;
    private boolean audioConfigured;
    private boolean hangupPending;
    private boolean hangupTimedOut;
    private boolean talkPending;
    private boolean talkStopping;
    private boolean talkPermissionPending;
    private boolean answerPermissionPending;
    private final Runnable controlTimeout = () -> {
        if (hangupPending) {
            hangupPending = false;
            hangupTimedOut = true;
            showStatus(getString(R.string.hang_up_timeout));
        } else if (talkPending) {
            talkPending = false;
            talkStopping = false;
            showStatus(getString(R.string.talk_timeout));
        }
        updateButtons();
    };
    private ToneGenerator ringTone;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildInterface();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        updateButtons();
    }

    private void buildInterface() {
        int padding = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, dp(18), padding, dp(28));
        content.setBackgroundColor(0xff0b1120);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(0xfff4f7ff);
        title.setTextSize(27);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        addWithTopMargin(content, title, 0);

        addWithTopMargin(content, sectionLabel(R.string.connection_label),
                dp(2));

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusCard.setBackground(rounded(0xff151f32, dp(16), 0xff26334b));
        statusDot = new TextView(this);
        statusDot.setBackground(oval(0xff8a97aa));
        LinearLayout.LayoutParams dotParams =
                new LinearLayout.LayoutParams(dp(9), dp(9));
        dotParams.rightMargin = dp(12);
        statusCard.addView(statusDot, dotParams);
        statusText = new TextView(this);
        statusText.setText(R.string.startup_status);
        statusText.setTextColor(0xffdce5f4);
        statusText.setTextSize(14);
        statusCard.addView(statusText, matchWrap());
        addWithTopMargin(content, statusCard, dp(14));

        addWithTopMargin(content, sectionLabel(R.string.camera_label),
                dp(22));

        cameraView = new ImageView(this);
        cameraView.setBackground(rounded(0xff151f32, dp(18), 0xff26334b));
        cameraView.setContentDescription(
                getString(R.string.camera_description));
        cameraView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cameraView.setClipToOutline(true);
        LinearLayout.LayoutParams cameraParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(225));
        cameraParams.topMargin = dp(8);
        content.addView(cameraView, cameraParams);

        addWithTopMargin(content, sectionLabel(R.string.intercom_label),
                dp(22));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        answerButton = actionButton(R.string.answer, 0xff16866c);
        answerButton.setOnClickListener(view -> requestAnswer());
        LinearLayout.LayoutParams callButtonParams =
                new LinearLayout.LayoutParams(0, dp(54), 1);
        callButtonParams.rightMargin = dp(8);
        buttons.addView(answerButton, callButtonParams);

        hangupButton = actionButton(R.string.hang_up, 0xffba4758);
        hangupButton.setOnClickListener(view -> hangUp());
        buttons.addView(hangupButton,
                new LinearLayout.LayoutParams(0, dp(54), 1));
        addWithTopMargin(content, buttons, dp(8));

        talkButton = actionButton(R.string.talk, 0xff176c82);
        talkButton.setOnClickListener(view -> toggleDirectTalk());
        addWithTopMargin(content, talkButton, dp(10), dp(54));

        TextView feedbackNote = new TextView(this);
        feedbackNote.setText(R.string.audio_output_warning);
        feedbackNote.setTextColor(0xff98a8bf);
        feedbackNote.setTextSize(12);
        addWithTopMargin(content, feedbackNote, dp(8));

        addWithTopMargin(content, sectionLabel(R.string.settings_label),
                dp(24));
        LinearLayout settingsCard = new LinearLayout(this);
        settingsCard.setOrientation(LinearLayout.VERTICAL);
        settingsCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        settingsCard.setBackground(rounded(0xff151f32, dp(18), 0xff26334b));

        hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setHint(R.string.host_hint);
        hostInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        styleInput(hostInput);
        settingsCard.addView(hostInput, matchWrap());

        tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setHint(R.string.token_hint);
        tokenInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        styleInput(tokenInput);
        LinearLayout.LayoutParams tokenParams = matchWrap();
        tokenParams.topMargin = dp(10);
        settingsCard.addView(tokenInput, tokenParams);

        connectButton = actionButton(R.string.connect, 0xff405fe8);
        connectButton.setOnClickListener(view -> toggleConnection());
        LinearLayout.LayoutParams connectParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        connectParams.topMargin = dp(12);
        settingsCard.addView(connectButton, connectParams);
        addWithTopMargin(content, settingsCard, dp(8));

        TextView note = new TextView(this);
        note.setText(R.string.keep_open_note);
        note.setTextColor(0xff7f8da4);
        note.setTextSize(12);
        addWithTopMargin(content, note, dp(14));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        scroll.setBackgroundColor(0xff0b1120);
        setContentView(scroll);
    }

    private TextView sectionLabel(int textId) {
        TextView label = new TextView(this);
        label.setText(textId);
        label.setTextColor(0xff8da4c4);
        label.setTextSize(11);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        return label;
    }

    private Button actionButton(int textId, int color) {
        Button button = new Button(this);
        button.setText(textId);
        button.setAllCaps(false);
        button.setTextColor(0xfff7f9ff);
        button.setTextSize(15);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(rounded(color, dp(16), color));
        button.setElevation(dp(2));
        return button;
    }

    private void styleInput(EditText input) {
        input.setTextColor(0xfff4f7ff);
        input.setHintTextColor(0xff7f8da4);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackground(rounded(0xff0e1728, dp(12), 0xff2a3952));
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radius);
        shape.setStroke(dp(1), strokeColor);
        return shape;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setShape(GradientDrawable.OVAL);
        return shape;
    }

    private void addWithTopMargin(LinearLayout parent, View child, int margin) {
        addWithTopMargin(parent, child, margin,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void addWithTopMargin(LinearLayout parent, View child, int margin,
                                  int height) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.topMargin = margin;
        parent.addView(child, params);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void connect() {
        String host = normalizeHost(hostInput.getText().toString());
        String token = tokenInput.getText().toString().trim();
        if (host.isEmpty() || token.length() < 16) {
            showStatus(getString(R.string.missing_host_token));
            return;
        }

        hideKeyboard();
        disconnect();
        connectionPending = true;
        updateButtons();
        String socketUrl = "ws://" + host + ":81/";
        Request request = new Request.Builder().url(socketUrl).build();
        showStatus(getString(R.string.connecting, host));

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                if (socket != webSocket) {
                    socket.cancel();
                    return;
                }
                mainHandler.post(() ->
                        showStatus(getString(R.string.authenticating)));
            }

            @Override
            public void onMessage(WebSocket socket, String message) {
                if (socket != webSocket) {
                    return;
                }
                if ("AUTH_REQUIRED".equals(message)) {
                    if (!socket.send("AUTH " + token)) {
                        mainHandler.post(() ->
                                showStatus(getString(R.string.auth_send_failed)));
                    }
                } else if ("READY".equals(message)) {
                    connected = true;
                    connectionPending = false;
                    mainHandler.post(() -> {
                        showStatus(getString(R.string.connected_waiting));
                        updateButtons();
                        startCamera(host, token);
                    });
                } else if ("RING".equals(message)) {
                    ringing = true;
                    mainHandler.post(() -> {
                        showStatus(getString(R.string.ringing));
                        playRingTone();
                        updateButtons();
                    });
                } else if ("ANSWERED".equals(message)) {
                    ringing = false;
                    answered = true;
                    hangupPending = false;
                    hangupTimedOut = false;
                    mainHandler.removeCallbacks(controlTimeout);
                    mainHandler.post(() -> {
                        stopRingTone();
                        showStatus(getString(R.string.call_connected));
                        updateButtons();
                        try {
                            startAudio();
                        } catch (RuntimeException error) {
                            showStatus(getString(R.string.audio_start_failed,
                                    error.getMessage()));
                            if (!socket.send("HANGUP")) {
                                showStatus(getString(R.string.hang_up_send_failed));
                            }
                        }
                    });
                } else if ("TALK_STARTED".equals(message)) {
                    talkPending = false;
                    talkStopping = false;
                    directTalk = true;
                    mainHandler.removeCallbacks(controlTimeout);
                    mainHandler.post(() -> {
                        showStatus(getString(R.string.talk_active));
                        updateButtons();
                        try {
                            startAudio();
                        } catch (RuntimeException error) {
                            showStatus(getString(R.string.audio_start_failed,
                                    error.getMessage()));
                            sendTalkStop();
                        }
                    });
                } else if ("TALK_STOPPED".equals(message)) {
                    talkPending = false;
                    talkStopping = false;
                    directTalk = false;
                    mainHandler.removeCallbacks(controlTimeout);
                    mainHandler.post(() -> {
                        stopAudio();
                        showStatus(getString(R.string.talk_ended));
                        updateButtons();
                    });
                } else if ("BUSY".equals(message)) {
                    talkPending = false;
                    talkStopping = false;
                    mainHandler.removeCallbacks(controlTimeout);
                    mainHandler.post(() -> {
                        showStatus(getString(R.string.busy));
                        updateButtons();
                    });
                } else if ("CALL_END".equals(message)) {
                    ringing = false;
                    answered = false;
                    directTalk = false;
                    hangupPending = false;
                    hangupTimedOut = false;
                    talkPending = false;
                    talkStopping = false;
                    mainHandler.removeCallbacks(controlTimeout);
                    mainHandler.post(() -> {
                        stopRingTone();
                        stopAudio();
                        showStatus(getString(R.string.call_ended));
                        updateButtons();
                    });
                }
            }

            @Override
            public void onMessage(WebSocket socket, ByteString bytes) {
                if (socket != webSocket || !audioRunning) {
                    return;
                }
                byte[] frame = bytes.toByteArray();
                if (frame.length != FRAME_BYTES) {
                    return;
                }
                if (!speakerQueue.offer(frame)) {
                    speakerQueue.poll();
                    speakerQueue.offer(frame);
                }
            }

            @Override
            public void onFailure(WebSocket socket, Throwable error,
                                 Response response) {
                if (socket != webSocket) {
                    return;
                }
                connected = false;
                connectionPending = false;
                ringing = false;
                answered = false;
                directTalk = false;
                hangupPending = false;
                talkPending = false;
                talkStopping = false;
                mainHandler.removeCallbacks(controlTimeout);
                mainHandler.post(() -> {
                    stopRingTone();
                    stopAudio();
                    stopCamera();
                    showStatus(getString(R.string.connection_failed,
                            error.getMessage()));
                    updateButtons();
                });
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                if (socket != webSocket) {
                    return;
                }
                connected = false;
                connectionPending = false;
                ringing = false;
                answered = false;
                directTalk = false;
                hangupPending = false;
                talkPending = false;
                talkStopping = false;
                mainHandler.removeCallbacks(controlTimeout);
                mainHandler.post(() -> {
                    stopRingTone();
                    stopAudio();
                    stopCamera();
                    showStatus(getString(R.string.disconnected));
                    updateButtons();
                });
            }
        });
    }

    private String normalizeHost(String value) {
        String host = value.trim();
        host = host.replaceFirst("^https?://", "");
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    private void toggleConnection() {
        if (connected) {
            disconnect();
            showStatus(getString(R.string.disconnected));
        } else if (!connectionPending) {
            connect();
        }
    }

    private void requestAnswer() {
        if (!connected) {
            showStatus(getString(R.string.connect_first));
            return;
        }
        if (!ringing) {
            showStatus(getString(R.string.no_incoming_call));
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            sendAnswer();
        } else {
            answerPermissionPending = true;
            talkPermissionPending = false;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO);
        }
    }

    private void toggleDirectTalk() {
        if (!connected) {
            showStatus(getString(R.string.connect_first));
            return;
        }
        if (directTalk) {
            sendTalkStop();
            return;
        }
        if (ringing || answered) {
            showStatus(getString(R.string.busy));
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            sendTalkStart();
        } else {
            talkPermissionPending = true;
            answerPermissionPending = false;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && talkPermissionPending) {
                talkPermissionPending = false;
                sendTalkStart();
            } else if (granted && answerPermissionPending) {
                answerPermissionPending = false;
                sendAnswer();
            } else {
                talkPermissionPending = false;
                answerPermissionPending = false;
                showStatus(getString(R.string.microphone_permission_required));
            }
        }
    }

    private void sendAnswer() {
        WebSocket socket = webSocket;
        if (socket != null && connected && ringing) {
            if (socket.send("ANSWER")) {
                ringing = false;
                showStatus(getString(R.string.answering));
                updateButtons();
            } else {
                showStatus(getString(R.string.answer_send_failed));
            }
        }
    }

    private void hangUp() {
        if (!answered || hangupPending) {
            return;
        }
        WebSocket socket = webSocket;
        if (socket == null || !socket.send("HANGUP")) {
            hangupPending = false;
            hangupTimedOut = true;
            stopAudio();
            showStatus(getString(R.string.hang_up_send_failed));
            updateButtons();
            return;
        }
        hangupPending = true;
        hangupTimedOut = false;
        stopAudio();
        mainHandler.removeCallbacks(controlTimeout);
        mainHandler.postDelayed(controlTimeout, CONTROL_TIMEOUT_MS);
        showStatus(getString(R.string.ending_call));
        updateButtons();
    }

    private void sendTalkStart() {
        if (!connected || ringing || answered || directTalk) {
            showStatus(getString(R.string.busy));
            return;
        }
        WebSocket socket = webSocket;
        if (socket == null) {
            showStatus(getString(R.string.talk_send_failed));
            return;
        }
        talkPending = true;
        talkStopping = false;
        mainHandler.removeCallbacks(controlTimeout);
        mainHandler.postDelayed(controlTimeout, CONTROL_TIMEOUT_MS);
        if (!socket.send("TALK_START")) {
            talkPending = false;
            mainHandler.removeCallbacks(controlTimeout);
            showStatus(getString(R.string.talk_send_failed));
        } else {
            showStatus(getString(R.string.starting_talk));
        }
        updateButtons();
    }

    private void sendTalkStop() {
        if (!directTalk || talkPending) {
            return;
        }
        WebSocket socket = webSocket;
        if (socket == null) {
            showStatus(getString(R.string.talk_send_failed));
            return;
        }
        talkPending = true;
        talkStopping = true;
        stopAudio();
        mainHandler.removeCallbacks(controlTimeout);
        mainHandler.postDelayed(controlTimeout, CONTROL_TIMEOUT_MS);
        if (!socket.send("TALK_STOP")) {
            talkPending = false;
            talkStopping = false;
            mainHandler.removeCallbacks(controlTimeout);
            showStatus(getString(R.string.talk_send_failed));
        } else {
            showStatus(getString(R.string.stopping_talk));
        }
        updateButtons();
    }

    private void disconnect() {
        connected = false;
        connectionPending = false;
        ringing = false;
        answered = false;
        directTalk = false;
        hangupPending = false;
        hangupTimedOut = false;
        talkPending = false;
        talkStopping = false;
        talkPermissionPending = false;
        answerPermissionPending = false;
        mainHandler.removeCallbacks(controlTimeout);
        stopRingTone();
        stopAudio();
        stopCamera();
        WebSocket oldSocket = webSocket;
        webSocket = null;
        if (oldSocket != null) {
            oldSocket.close(1000, "Disconnected by user");
        }
        speakerQueue.clear();
        updateButtons();
    }

    private void updateButtons() {
        if (connectButton == null) {
            return;
        }
        connectButton.setText(connected
                ? R.string.disconnect
                : connectionPending ? R.string.connecting_button
                : R.string.connect);
        setActionEnabled(connectButton, connected || !connectionPending);
        setActionEnabled(answerButton, connected && ringing && !answered);
        setActionEnabled(hangupButton,
                connected && answered && !hangupPending);
        hangupButton.setText(hangupPending
                ? R.string.ending_call
                : hangupTimedOut ? R.string.retry_hang_up : R.string.hang_up);
        if (talkPending) {
            talkButton.setText(talkStopping
                    ? R.string.stopping_talk : R.string.starting_talk);
            setActionEnabled(talkButton, false);
        } else if (directTalk) {
            talkButton.setText(R.string.stop_talk);
            setActionEnabled(talkButton, connected);
        } else {
            talkButton.setText(R.string.talk);
            setActionEnabled(talkButton, connected);
        }
    }

    private void setActionEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.45f);
    }

    private void showStatus(String message) {
        if (statusText != null) {
            statusText.setText(message);
            if (statusDot != null) {
                int color = !connected ? 0xff8a97aa
                        : ringing ? 0xffffbf5b
                        : answered ? 0xff45d29c
                        : 0xff48d9e8;
                statusDot.setBackground(oval(color));
            }
        }
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        }
    }

    private void playRingTone() {
        stopRingTone();
        ringTone = new ToneGenerator(AudioManager.STREAM_RING, 70);
        ringTone.startTone(ToneGenerator.TONE_PROP_BEEP2, 600);
    }

    private void stopRingTone() {
        if (ringTone != null) {
            ringTone.stopTone();
            ringTone.release();
            ringTone = null;
        }
    }

    private void startAudio() {
        if (audioRunning) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    getString(R.string.microphone_permission_required));
        }
        int inputMin = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int outputMin = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (inputMin <= 0 || outputMin <= 0) {
            throw new IllegalStateException("Unsupported 16 kHz audio format");
        }

        AudioFormat inputFormat = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        AudioFormat outputFormat = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

        audioRecord = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(inputFormat)
                .setBufferSizeInBytes(Math.max(inputMin, FRAME_BYTES * 4))
                .build();
        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(outputFormat)
                .setBufferSizeInBytes(Math.max(outputMin, FRAME_BYTES * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED
                || audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            stopAudio();
            throw new IllegalStateException("Could not initialize phone audio");
        }

        previousAudioMode = audioManager.getMode();
        previousSpeakerphoneState = audioManager.isSpeakerphoneOn();
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        audioManager.setSpeakerphoneOn(true);
        audioConfigured = true;
        audioRunning = true;
        speakerQueue.clear();
        audioTrack.play();
        audioRecord.startRecording();
        startSpeakerThread();
        startMicrophoneThread();
    }

    private void startMicrophoneThread() {
        AudioRecord recorder = audioRecord;
        microphoneThread = new Thread(() -> {
            byte[] frame = new byte[FRAME_BYTES];
            while (audioRunning) {
                int filled = 0;
                while (filled < frame.length && audioRunning) {
                    int count = recorder.read(frame, filled,
                            frame.length - filled, AudioRecord.READ_BLOCKING);
                    if (count < 0) {
                        mainHandler.post(() ->
                                showStatus(getString(
                                        R.string.microphone_read_failed, count)));
                        audioRunning = false;
                        break;
                    }
                    filled += count;
                }
                WebSocket socket = webSocket;
                if (audioRunning && filled == frame.length && socket != null) {
                    socket.send(ByteString.of(frame.clone()));
                }
            }
        }, "doorbell-microphone");
        microphoneThread.start();
    }

    private void startSpeakerThread() {
        speakerThread = new Thread(() -> {
            while (audioRunning) {
                try {
                    byte[] frame = speakerQueue.take();
                    AudioTrack track = audioTrack;
                    if (audioRunning && track != null) {
                        track.write(frame, 0, frame.length,
                                AudioTrack.WRITE_BLOCKING);
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "doorbell-speaker");
        speakerThread.start();
    }

    private void stopAudio() {
        audioRunning = false;
        speakerQueue.clear();
        if (microphoneThread != null) {
            microphoneThread.interrupt();
            microphoneThread = null;
        }
        if (speakerThread != null) {
            speakerThread.interrupt();
            speakerThread = null;
        }
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState()
                        == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (IllegalStateException ignored) {
            }
            audioRecord.release();
            audioRecord = null;
        }
        if (audioTrack != null) {
            try {
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.stop();
                }
            } catch (IllegalStateException ignored) {
            }
            audioTrack.release();
            audioTrack = null;
        }
        if (audioManager != null && audioConfigured) {
            audioManager.setSpeakerphoneOn(previousSpeakerphoneState);
            audioManager.setMode(previousAudioMode);
            audioConfigured = false;
        }
    }

    private void startCamera(String host, String token) {
        stopCamera();
        videoRunning = true;
        Uri streamUri = Uri.parse("http://" + host + ":82/stream")
                .buildUpon()
                .appendQueryParameter("token", token)
                .build();
        cameraThread = new Thread(() -> readCameraStream(streamUri),
                "doorbell-camera");
        cameraThread.start();
    }

    private void readCameraStream(Uri streamUri) {
        boolean reportedFrame = false;
        while (videoRunning && !Thread.currentThread().isInterrupted()) {
            java.net.HttpURLConnection connection = null;
            try {
                connection = (java.net.HttpURLConnection) new java.net.URL(
                        streamUri.toString()).openConnection();
                cameraConnection = connection;
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("Accept",
                        "multipart/x-mixed-replace");
                connection.connect();
                int code = connection.getResponseCode();
                if (code != 200) {
                    throw new IOException("Camera returned HTTP " + code);
                }

                BufferedInputStream input =
                        new BufferedInputStream(connection.getInputStream());
                while (videoRunning) {
                    String boundary = readLine(input);
                    if (boundary == null || boundary.equals("--")) {
                        break;
                    }
                    if (!boundary.startsWith("--")) {
                        continue;
                    }

                    int contentLength = -1;
                    String header;
                    while ((header = readLine(input)) != null
                            && !header.isEmpty()) {
                        int separator = header.indexOf(':');
                        if (separator > 0
                                && header.substring(0, separator)
                                .trim().equalsIgnoreCase("Content-Length")) {
                            contentLength = Integer.parseInt(
                                    header.substring(separator + 1).trim());
                        }
                    }
                    if (contentLength <= 0 || contentLength > MAX_JPEG_BYTES) {
                        throw new IOException("Invalid camera frame length");
                    }

                    byte[] jpeg = readExact(input, contentLength);
                    readLine(input);
                    Bitmap bitmap = BitmapFactory.decodeByteArray(
                            jpeg, 0, jpeg.length);
                    if (bitmap != null) {
                        Bitmap frame = bitmap;
                        mainHandler.post(() -> cameraView.setImageBitmap(frame));
                        if (!reportedFrame) {
                            reportedFrame = true;
                            mainHandler.post(() -> showStatus(
                                    answered
                                            ? getString(R.string.camera_ready)
                                            : getString(R.string.connected_waiting)));
                        }
                    }
                }
            } catch (Exception error) {
                if (videoRunning) {
                    String message = error.getMessage();
                    mainHandler.post(() -> showStatus(
                            getString(R.string.camera_error, message == null
                                    ? getString(R.string.camera_stream_stopped)
                                    : message)));
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                cameraConnection = null;
            }

            if (videoRunning) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() <= 4096) {
            int value = input.read();
            if (value < 0) {
                return line.size() == 0 ? null
                        : line.toString(StandardCharsets.US_ASCII.name());
            }
            if (value == '\n') {
                byte[] bytes = line.toByteArray();
                int length = bytes.length;
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(bytes, 0, length,
                        StandardCharsets.US_ASCII);
            }
            line.write(value);
        }
        throw new IOException("Camera response line too long");
    }

    private byte[] readExact(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) {
                throw new IOException("Camera stream ended mid-frame");
            }
            offset += count;
        }
        return bytes;
    }

    private void stopCamera() {
        videoRunning = false;
        java.net.HttpURLConnection connection = cameraConnection;
        if (connection != null) {
            connection.disconnect();
        }
        if (cameraThread != null) {
            cameraThread.interrupt();
            cameraThread = null;
        }
    }

    @Override
    public void onBackPressed() {
        disconnect();
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        disconnect();
        httpClient.dispatcher().cancelAll();
        httpClient.connectionPool().evictAll();
        super.onDestroy();
    }

}
