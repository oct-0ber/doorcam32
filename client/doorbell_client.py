import argparse
from io import BytesIO
import queue
import threading
import tkinter as tk
from tkinter import ttk
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import urlopen

import sounddevice as sd
from PIL import Image, ImageTk
from websockets.exceptions import WebSocketException
from websockets.sync.client import connect


SAMPLE_RATE = 16000
FRAME_SAMPLES = 320
FRAME_BYTES = FRAME_SAMPLES * 2


class Audio:
    def __init__(self, report):
        self.report = report
        self.enabled = threading.Event()
        self.uplink = queue.Queue(maxsize=8)
        self.downlink = queue.Queue(maxsize=8)
        self.input_stream = None
        self.output_stream = None
        self.lock = threading.RLock()

    def start(self):
        with self.lock:
            self.stop()
            self.enabled.set()
            try:
                self.input_stream = sd.RawInputStream(
                    samplerate=SAMPLE_RATE,
                    blocksize=FRAME_SAMPLES,
                    channels=1,
                    dtype="int16",
                    callback=self._on_microphone,
                )
                self.output_stream = sd.RawOutputStream(
                    samplerate=SAMPLE_RATE,
                    blocksize=FRAME_SAMPLES,
                    channels=1,
                    dtype="int16",
                    callback=self._on_speaker,
                )
                self.input_stream.start()
                self.output_stream.start()
            except sd.PortAudioError as error:
                self.stop()
                self.report(f"Audio device error: {error}")
                raise

    def stop(self):
        with self.lock:
            self.enabled.clear()
            for stream in (self.input_stream, self.output_stream):
                if stream is not None:
                    try:
                        if stream.active:
                            stream.stop()
                    except sd.PortAudioError as error:
                        self.report(f"Audio stop error: {error}")
                    try:
                        stream.close()
                    except sd.PortAudioError as error:
                        self.report(f"Audio close error: {error}")
            self.input_stream = None
            self.output_stream = None
            self._empty(self.uplink)
            self._empty(self.downlink)

    @staticmethod
    def _empty(items):
        while True:
            try:
                items.get_nowait()
            except queue.Empty:
                return

    def _on_microphone(self, data, frames, timing, status):
        if self.enabled.is_set():
            try:
                self.uplink.put_nowait(bytes(data))
            except queue.Full:
                pass

    def _on_speaker(self, output, frames, timing, status):
        try:
            frame = self.downlink.get_nowait()
        except queue.Empty:
            frame = bytes(FRAME_BYTES)
        output[:] = frame

    def receive(self, frame):
        if len(frame) != FRAME_BYTES:
            return
        try:
            self.downlink.put_nowait(frame)
        except queue.Full:
            try:
                self.downlink.get_nowait()
            except queue.Empty:
                pass
            self.downlink.put_nowait(frame)


class DoorbellClient:
    def __init__(self, host, token, updates):
        self.uri = f"ws://{host}:81/"
        self.video_uri = f"http://{host}:82/stream?token={token}"
        self.token = token
        self.updates = updates
        self.audio = Audio(self.report)
        self.stop_event = threading.Event()
        self.connection_ready = threading.Event()
        self.connection = None
        self.send_lock = threading.Lock()
        self.worker = threading.Thread(target=self._run, daemon=True)
        self.video_worker = None
        self.video_frames = queue.Queue(maxsize=1)

    def start(self):
        self.worker.start()

    def stop(self):
        self.stop_event.set()
        self.audio.stop()
        connection = self.connection
        if connection is not None:
            try:
                connection.close()
            except (OSError, WebSocketException) as error:
                self.report(f"Connection close error: {error}")

    def report(self, message):
        self.updates.put(("status", message))

    def answer(self):
        self._send_command("ANSWER")

    def hang_up(self):
        self.audio.stop()
        self._send_command("HANGUP")

    def _send_command(self, command):
        connection = self.connection
        if connection is None or not self.connection_ready.is_set():
            self.report("Not connected to the doorbell")
            return
        try:
            with self.send_lock:
                connection.send(command)
        except (OSError, WebSocketException) as error:
            self.report(f"Could not send command: {error}")

    def _send_audio(self, connection):
        while not self.stop_event.is_set() and self.connection is connection:
            try:
                frame = self.audio.uplink.get(timeout=0.1)
            except queue.Empty:
                continue
            if not self.audio.enabled.is_set():
                continue
            try:
                with self.send_lock:
                    connection.send(frame)
            except (OSError, WebSocketException) as error:
                self.report(f"Audio send failed: {error}")
                return

    def _handle_message(self, message):
        if isinstance(message, bytes):
            self.audio.receive(message)
        elif message == "RING":
            self.updates.put(("ring", "Doorbell is ringing"))
        elif message == "ANSWERED":
            try:
                self.audio.start()
                self.updates.put(("answered", "Call connected"))
            except sd.PortAudioError:
                self._send_command("HANGUP")
        elif message == "CALL_END":
            self.audio.stop()
            self.updates.put(("ended", "Call ended"))
        elif message == "READY":
            self.report("Connected; waiting for a doorbell call")
            if self.video_worker is None or not self.video_worker.is_alive():
                self.video_worker = threading.Thread(
                    target=self._run_video, daemon=True
                )
                self.video_worker.start()

    @staticmethod
    def _read_exact(stream, length):
        chunks = []
        remaining = length
        while remaining:
            chunk = stream.read(remaining)
            if not chunk:
                raise ConnectionError("Camera stream ended mid-frame")
            chunks.append(chunk)
            remaining -= len(chunk)
        return b"".join(chunks)

    def _publish_video(self, jpeg):
        try:
            self.video_frames.put_nowait(jpeg)
        except queue.Full:
            try:
                self.video_frames.get_nowait()
            except queue.Empty:
                pass
            self.video_frames.put_nowait(jpeg)

    def _run_video(self):
        while not self.stop_event.is_set():
            try:
                with urlopen(self.video_uri, timeout=5) as response:
                    content_type = response.headers.get("Content-Type", "")
                    if "multipart/x-mixed-replace" not in content_type:
                        raise ConnectionError(
                            f"Unexpected camera response: {content_type or 'no content type'}"
                        )

                    while not self.stop_event.is_set():
                        boundary = response.readline().strip()
                        if not boundary:
                            raise ConnectionError("Camera stream closed")
                        if boundary == b"--":
                            return
                        if not boundary.startswith(b"--"):
                            continue

                        content_length = None
                        while True:
                            header = response.readline()
                            if header in (b"\r\n", b"\n", b""):
                                break
                            name, separator, value = header.partition(b":")
                            if separator and name.strip().lower() == b"content-length":
                                content_length = int(value.strip())
                        if content_length is None or content_length <= 0:
                            raise ConnectionError(
                                "Camera frame omitted Content-Length"
                            )

                        jpeg = self._read_exact(response, content_length)
                        response.readline()
                        self._publish_video(jpeg)
            except (OSError, TimeoutError, HTTPError, URLError, ConnectionError) as error:
                if not self.stop_event.is_set():
                    self.report(f"Camera: {error}")
                    self.stop_event.wait(3)

    def _run(self):
        while not self.stop_event.is_set():
            self.report(f"Connecting to {self.uri}")
            try:
                with connect(self.uri, open_timeout=5, ping_interval=20) as ws:
                    self.connection = ws
                    self.connection_ready.set()
                    ws.send(f"AUTH {self.token}")
                    sender = threading.Thread(
                        target=self._send_audio, args=(ws,), daemon=True
                    )
                    sender.start()
                    for message in ws:
                        if self.stop_event.is_set():
                            break
                        self._handle_message(message)
            except (OSError, TimeoutError, WebSocketException) as error:
                if not self.stop_event.is_set():
                    self.report(f"Connection error: {error}")
                    self.stop_event.wait(3)
            finally:
                self.connection_ready.clear()
                self.connection = None
                self.audio.stop()
        self.report("Client stopped")


def main():
    parser = argparse.ArgumentParser(
        description="Local Wi-Fi two-way audio client for the Goouuu ESP32-S3 doorbell"
    )
    parser.add_argument(
        "--host",
        default="goouuu-doorbell.local",
        help="doorbell mDNS name or IP address",
    )
    parser.add_argument(
        "--token",
        help="access token from include/secrets.h or client/token.txt",
    )
    args = parser.parse_args()

    token_file = Path(__file__).with_name("token.txt")
    token = args.token
    if token is None and token_file.is_file():
        token = token_file.read_text(encoding="utf-8").strip()
    if token is None or len(token) < 16:
        parser.error(
            "provide a 16-character access token with --token or client/token.txt"
        )

    updates = queue.Queue()
    client = DoorbellClient(args.host, token, updates)

    root = tk.Tk()
    root.title("Goouuu Doorbell")
    root.resizable(False, False)

    status = tk.StringVar(value="Starting...")
    ttk.Label(root, textvariable=status, width=48, padding=12).grid(
        row=0, column=0, columnspan=2
    )
    video_label = ttk.Label(root, text="Waiting for camera...", anchor="center")
    video_label.configure(width=60)
    video_label.grid(row=1, column=0, columnspan=2, padx=8, pady=4)
    answer_button = ttk.Button(root, text="Answer", command=client.answer)
    answer_button.grid(row=2, column=0, padx=8, pady=8)
    hangup_button = ttk.Button(root, text="Hang up", command=client.hang_up)
    hangup_button.grid(row=2, column=1, padx=8, pady=8)

    def process_updates():
        latest_video = None
        while True:
            try:
                kind, message = updates.get_nowait()
            except queue.Empty:
                break
            if kind == "video":
                latest_video = message
            else:
                status.set(message)
            if kind == "ring":
                root.bell()
            if kind == "ended":
                answer_button.state(["!disabled"])
                hangup_button.state(["disabled"])
            elif kind == "answered":
                answer_button.state(["disabled"])
                hangup_button.state(["!disabled"])

        try:
            latest_video = client.video_frames.get_nowait()
        except queue.Empty:
            pass
        if latest_video is not None:
            try:
                with Image.open(BytesIO(latest_video)) as image:
                    image.thumbnail((480, 360))
                    photo = ImageTk.PhotoImage(image.copy())
                video_label.configure(image=photo, text="")
                video_label.image = photo
            except OSError as error:
                status.set(f"Camera image error: {error}")
        root.after(100, process_updates)

    def close():
        client.stop()
        root.destroy()

    hangup_button.state(["disabled"])
    root.protocol("WM_DELETE_WINDOW", close)
    client.start()
    root.after(100, process_updates)
    root.mainloop()


if __name__ == "__main__":
    main()
