# Phone RTSP

Phone RTSP turns an Android phone into a local RTSP camera for an NVR or hybrid DVR.

## Stream details

- RTSP server: TCP port `8554`
- Main stream URL: `rtsp://<phone-LAN-IP>:8554/live`
- Substream URL: `rtsp://<phone-LAN-IP>:8554/sub`
- Main video: H.264; resolution selectable from 1920 × 1080, 1280 × 720, or 640 × 480; 10, 15, 20, 25, or 30 fps; 1–4 Mbps
- Substream video: H.264; automatically uses a lower resolution (up to 640 × 480; typically 640 × 360 for a 16:9 main stream) while preserving the main stream's aspect ratio, at the selected frame rate and 1 Mbps
- RTP transport: UDP or RTSP interleaved TCP
- Multiple simultaneous sessions on each path share that path's encoder
- Audio: not included

## Optional RTSP credentials

Set both a username and password in the app before starting the stream to require recorder authentication. The RTSP server accepts Digest MD5 and Basic authentication for recorder compatibility. If both fields are blank, no authentication is required. Stream settings and credentials are saved on the device; the password is encrypted using Android Keystore. RTSP does not encrypt the video or connection; Basic authentication also sends a reversible encoding of the password. Use this only on a trusted LAN.

## Use

1. Connect the phone and recorder to the same local network.
2. Optionally enter a username and password to protect the RTSP stream.
3. Open the app and select the front or rear camera.
4. Choose the main stream resolution, frame rate, and bitrate. These settings apply after restarting the stream.
5. Tap **Start streaming** and allow camera access.
6. Configure `.../live` as the DVR main stream and `.../sub` as its substream. If authentication is enabled, enter the same username and password for both.
7. Use **Stop streaming** when finished.

Keep the app open while streaming. The selected phone camera must support the chosen main resolution and a smaller output for the substream. The actual frame rate depends on camera and device capabilities. The phone and recorder network must permit RTSP traffic on TCP port 8554 and the corresponding RTP transport.

The current implementation targets Android; it uses Camera2 and the device's hardware H.264 encoder.

The RTSP listener starts only after **Start streaming** completes and the app shows **STREAMING**. Some phones cannot capture both encoder outputs at once; on those devices the main stream stays available and the app displays a warning that the substream is unavailable.
