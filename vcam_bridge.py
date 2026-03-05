"""
Virtual Camera Bridge
=====================
Script nhỏ đọc JPEG frames từ stdin và xuất tới OBS Virtual Camera.
Được gọi tự động bởi CameraClientApp.java.

Protocol: [4 bytes: frame size (big-endian)][N bytes: JPEG data]

Yêu cầu:
  pip install pyvirtualcam opencv-python numpy
  OBS Studio đã cài (cho Virtual Camera driver)
"""

import sys
import struct
import argparse
import numpy as np
import cv2

def main():
    parser = argparse.ArgumentParser(description="VCam Bridge")
    parser.add_argument("--width", type=int, default=1280)
    parser.add_argument("--height", type=int, default=720)
    parser.add_argument("--fps", type=int, default=30)
    args = parser.parse_args()

    try:
        import pyvirtualcam
    except ImportError:
        print("ERROR: pyvirtualcam not installed. Run: pip install pyvirtualcam", flush=True)
        sys.exit(1)

    print(f"[VCam Bridge] Starting {args.width}x{args.height} @ {args.fps}fps", flush=True)

    try:
        with pyvirtualcam.Camera(width=args.width, height=args.height, fps=args.fps) as cam:
            print(f"[VCam Bridge] Virtual camera: {cam.device}", flush=True)
            print(f"[VCam Bridge] Ready! Select '{cam.device}' in Google Meet/Zoom", flush=True)

            stdin = sys.stdin.buffer
            while True:
                # Read 4-byte length header
                header = stdin.read(4)
                if len(header) < 4:
                    break

                frame_len = struct.unpack(">I", header)[0]
                if frame_len <= 0 or frame_len > 10_000_000:  # sanity check
                    continue

                # Read JPEG data
                jpeg_data = b""
                while len(jpeg_data) < frame_len:
                    chunk = stdin.read(frame_len - len(jpeg_data))
                    if not chunk:
                        break
                    jpeg_data += chunk

                if len(jpeg_data) != frame_len:
                    break

                # Decode JPEG to numpy array
                np_arr = np.frombuffer(jpeg_data, dtype=np.uint8)
                frame = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)
                if frame is None:
                    continue

                # Resize if needed
                h, w = frame.shape[:2]
                if w != args.width or h != args.height:
                    frame = cv2.resize(frame, (args.width, args.height))

                # Convert BGR to RGB (pyvirtualcam expects RGB)
                frame_rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)

                # Send to virtual camera
                cam.send(frame_rgb)
                cam.sleep_until_next_frame()

    except Exception as e:
        print(f"[VCam Bridge] Error: {e}", flush=True)
        sys.exit(1)

    print("[VCam Bridge] Stopped", flush=True)

if __name__ == "__main__":
    main()
