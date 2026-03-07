package com.cameraip;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;

/**
 * Java Virtual Camera Bridge — OBS Virtual Camera via Shared Memory
 * =================================================================
 * Ghi frame trực tiếp vào vùng shared memory mà OBS Virtual Camera đọc.
 * Không cần Python, pyvirtualcam hay bất kỳ dependency nào ngoài OBS Studio.
 *
 * Protocol (từ OBS source: shared-memory-queue.c):
 * - Shared memory name: "OBSVirtualCamVideo"
 * - Format: NV12 (YUV 4:2:0), frame_size = cx * cy * 3 / 2
 * - Triple buffered (3 frame slots)
 * - Header: queue_header struct at offset 0
 * - Frame data at header.offsets[i] + FRAME_HEADER_SIZE(32)
 *
 * Yêu cầu: OBS Studio đã cài (cung cấp driver Virtual Camera).
 */
public class VirtualCamera implements AutoCloseable {

    // ─── Shared Memory Name ───────────────────────────────────
    private static final String VIDEO_NAME = "OBSVirtualCamVideo";
    private static final int FRAME_HEADER_SIZE = 32;

    // ─── Queue States ─────────────────────────────────────────
    private static final int SHARED_QUEUE_STATE_STARTING = 0;
    private static final int SHARED_QUEUE_STATE_READY = 1;
    private static final int SHARED_QUEUE_STATE_STOPPING = 2;

    // ─── Header Layout (matches OBS queue_header struct) ──────
    // offset 0:  uint32 write_idx
    // offset 4:  uint32 read_idx
    // offset 8:  uint32 state
    // offset 12: uint32 offsets[3] (3 x 4 = 12 bytes)
    // offset 24: uint32 type
    // offset 28: uint32 cx
    // offset 32: uint32 cy
    // offset 36: uint64 interval (8 bytes)
    // offset 44: uint32 reserved[8] (32 bytes)
    // Total header = ~76 bytes, but we align everything to 32-byte boundaries.

    private static final int HEADER_WRITE_IDX = 0;
    private static final int HEADER_READ_IDX = 4;
    private static final int HEADER_STATE = 8;
    private static final int HEADER_OFFSETS = 12; // 3 x uint32
    private static final int HEADER_TYPE = 24;
    private static final int HEADER_CX = 28;
    private static final int HEADER_CY = 32;
    private static final int HEADER_INTERVAL = 36;

    // ─── State ────────────────────────────────────────────────
    private final int width;
    private final int height;
    private final int frameSize;  // NV12: cx * cy * 3 / 2
    private WinNT.HANDLE hMapping;
    private Pointer sharedMem;
    private final int[] frameOffsets = new int[3];
    private int writeCounter = 0;
    private boolean active = false;

    /**
     * Tạo virtual camera với kích thước cho trước.
     * @param cx Width (phải chia hết cho 2)
     * @param cy Height (phải chia hết cho 2)
     * @param fps FPS mong muốn
     */
    public VirtualCamera(int cx, int cy, int fps) {
        // NV12 requires even dimensions
        this.width = (cx % 2 == 0) ? cx : cx - 1;
        this.height = (cy % 2 == 0) ? cy : cy - 1;
        this.frameSize = width * height * 3 / 2;

        // Calculate frame offsets (aligned to 32 bytes)
        int headerSize = align32(76); // queue_header struct
        for (int i = 0; i < 3; i++) {
            frameOffsets[i] = headerSize + i * align32(frameSize + FRAME_HEADER_SIZE);
        }
        int totalSize = frameOffsets[2] + align32(frameSize + FRAME_HEADER_SIZE);

        // ★ Check if already in use
        WinNT.HANDLE existing = Kernel32.INSTANCE.OpenFileMapping(
                WinNT.FILE_MAP_READ, false, VIDEO_NAME);
        if (existing != null) {
            Kernel32.INSTANCE.CloseHandle(existing);
            throw new RuntimeException(
                    "OBS Virtual Camera đang được sử dụng bởi OBS hoặc ứng dụng khác.\n" +
                    "Hãy tắt Virtual Camera trong OBS trước.");
        }

        // ★ Create shared memory
        hMapping = Kernel32.INSTANCE.CreateFileMapping(
                WinNT.INVALID_HANDLE_VALUE,
                null,
                WinNT.PAGE_READWRITE,
                0,
                totalSize,
                VIDEO_NAME);

        if (hMapping == null) {
            throw new RuntimeException("Không thể tạo shared memory. Error: " +
                    Kernel32.INSTANCE.GetLastError());
        }

        // ★ Map into process address space
        sharedMem = Kernel32.INSTANCE.MapViewOfFile(
                hMapping,
                WinNT.SECTION_MAP_WRITE | WinNT.SECTION_MAP_READ,
                0, 0, totalSize);

        if (sharedMem == null) {
            Kernel32.INSTANCE.CloseHandle(hMapping);
            throw new RuntimeException("Không thể map shared memory. Error: " +
                    Kernel32.INSTANCE.GetLastError());
        }

        // ★ Initialize queue header
        long interval = 10_000_000L / fps; // 100-nanosecond units
        sharedMem.setInt(HEADER_WRITE_IDX, 0);
        sharedMem.setInt(HEADER_READ_IDX, 0);
        sharedMem.setInt(HEADER_STATE, SHARED_QUEUE_STATE_STARTING);

        for (int i = 0; i < 3; i++) {
            sharedMem.setInt(HEADER_OFFSETS + i * 4, frameOffsets[i]);
        }

        sharedMem.setInt(HEADER_TYPE, 0); // SHARED_QUEUE_TYPE_VIDEO
        sharedMem.setInt(HEADER_CX, width);
        sharedMem.setInt(HEADER_CY, height);
        sharedMem.setLong(HEADER_INTERVAL, interval);

        active = true;
        System.out.println("[VCam] Created: " + width + "x" + height +
                " @ " + fps + "fps, NV12, shared memory: " + VIDEO_NAME);
    }

    /**
     * Ghi một frame BufferedImage vào virtual camera.
     * Tự động convert sang NV12 format.
     */
    public void writeFrame(BufferedImage img) {
        if (!active || sharedMem == null) return;

        // Scale if needed
        BufferedImage src = img;
        if (img.getWidth() != width || img.getHeight() != height) {
            src = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var g = src.createGraphics();
            g.drawImage(img, 0, 0, width, height, null);
            g.dispose();
        }

        // Convert to NV12
        byte[] nv12 = rgbToNV12(src);

        // Write to next slot (triple buffering)
        writeCounter++;
        int idx = writeCounter % 3;
        int offset = frameOffsets[idx];

        // Frame header: timestamp at offset (uint64)
        long timestamp = System.nanoTime() / 100; // 100ns units
        sharedMem.setLong(offset, timestamp);

        // Frame data after FRAME_HEADER_SIZE
        sharedMem.write(offset + FRAME_HEADER_SIZE, nv12, 0, nv12.length);

        // Update indices
        sharedMem.setInt(HEADER_WRITE_IDX, writeCounter);
        sharedMem.setInt(HEADER_READ_IDX, writeCounter);
        sharedMem.setInt(HEADER_STATE, SHARED_QUEUE_STATE_READY);
    }

    /**
     * Convert BufferedImage (RGB) → NV12 byte array.
     * NV12 = Y plane (full res) + interleaved UV plane (half res).
     */
    private byte[] rgbToNV12(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] nv12 = new byte[w * h * 3 / 2];

        // Get pixel data
        int[] rgb;
        if (img.getType() == BufferedImage.TYPE_INT_RGB ||
            img.getType() == BufferedImage.TYPE_INT_ARGB) {
            rgb = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        } else {
            // Fallback: get RGB values
            rgb = img.getRGB(0, 0, w, h, null, 0, w);
        }

        // Y plane
        int yIdx = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pixel = rgb[y * w + x];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                // ITU-R BT.601 Y
                nv12[yIdx++] = (byte) clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
            }
        }

        // UV plane (interleaved, half resolution)
        int uvIdx = w * h;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int pixel = rgb[y * w + x];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                // ITU-R BT.601 U, V
                nv12[uvIdx++] = (byte) clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                nv12[uvIdx++] = (byte) clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
            }
        }
        return nv12;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int align32(int size) {
        return (size + 31) & ~31;
    }

    public boolean isActive() {
        return active;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    @Override
    public void close() {
        if (!active) return;
        active = false;

        if (sharedMem != null) {
            // Signal stopping
            sharedMem.setInt(HEADER_STATE, SHARED_QUEUE_STATE_STOPPING);
            Kernel32.INSTANCE.UnmapViewOfFile(sharedMem);
            sharedMem = null;
        }
        if (hMapping != null) {
            Kernel32.INSTANCE.CloseHandle(hMapping);
            hMapping = null;
        }
        System.out.println("[VCam] Closed");
    }
}
