package com.cameraip;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;

/**
 * Java Virtual Camera — OBS Virtual Camera via Shared Memory (JNA)
 * ================================================================
 * Matches OBS source: plugins/win-dshow/shared-memory-queue.c
 *
 * struct queue_header {          // MSVC x64 layout
 *   volatile uint32_t write_idx; // offset  0
 *   volatile uint32_t read_idx;  // offset  4
 *   volatile uint32_t state;     // offset  8
 *   uint32_t offsets[3];         // offset 12, 16, 20
 *   uint32_t type;               // offset 24
 *   uint32_t cx;                 // offset 28
 *   uint32_t cy;                 // offset 32
 *   // 4 bytes padding           // offset 36 (uint64_t alignment)
 *   uint64_t interval;           // offset 40
 *   uint32_t reserved[8];        // offset 48
 * };                             // sizeof = 80, ALIGN32 = 96
 *
 * Frame data: NV12 = Y plane (w*h) + UV interleaved (w*h/2)
 */
public class VirtualCamera implements AutoCloseable {

    // ── Shared memory name (must match OBS) ──────────────────
    private static final String VIDEO_NAME = "OBSVirtualCamVideo";
    private static final int FRAME_HEADER_SIZE = 32;

    // ── Queue states ─────────────────────────────────────────
    private static final int STATE_STARTING = 0;
    private static final int STATE_READY    = 1;
    private static final int STATE_STOPPING = 2;

    // ── Header offsets (MSVC x64 struct layout) ──────────────
    private static final int OFF_WRITE_IDX = 0;
    private static final int OFF_READ_IDX  = 4;
    private static final int OFF_STATE     = 8;
    private static final int OFF_OFFSETS   = 12; // 3 × uint32
    private static final int OFF_TYPE      = 24;
    private static final int OFF_CX        = 28;
    private static final int OFF_CY        = 32;
    // 4 bytes padding at 36 for uint64 alignment
    private static final int OFF_INTERVAL  = 40;
    // reserved[8] at 48..79
    private static final int HEADER_SIZEOF = 80;

    // ── State ────────────────────────────────────────────────
    private final int width;
    private final int height;
    private final int frameSize;
    private WinNT.HANDLE hMapping;
    private Pointer mem;
    private final int[] frameOff = new int[3];
    private boolean active = false;

    public VirtualCamera(int cx, int cy, int fps) {
        // NV12 requires even dimensions
        this.width  = cx & ~1;
        this.height = cy & ~1;
        this.frameSize = width * height * 3 / 2;

        // Calculate aligned offsets (matches ALIGN_SIZE(x,32))
        int hdrAligned = align32(HEADER_SIZEOF); // 96
        int slotSize   = align32(frameSize + FRAME_HEADER_SIZE);

        frameOff[0] = hdrAligned;
        frameOff[1] = hdrAligned + slotSize;
        frameOff[2] = hdrAligned + slotSize * 2;
        int totalSize  = hdrAligned + slotSize * 3;

        // Check if already in use (by OBS etc.)
        WinNT.HANDLE existing = Kernel32.INSTANCE.OpenFileMapping(
                WinNT.FILE_MAP_READ, false, VIDEO_NAME);
        if (existing != null) {
            Kernel32.INSTANCE.CloseHandle(existing);
            throw new RuntimeException(
                "OBS Virtual Camera đang được sử dụng.\n" +
                "Tắt Virtual Camera trong OBS trước khi dùng tính năng này.");
        }

        // Create shared memory
        hMapping = Kernel32.INSTANCE.CreateFileMapping(
                WinNT.INVALID_HANDLE_VALUE, null,
                WinNT.PAGE_READWRITE, 0, totalSize, VIDEO_NAME);

        if (hMapping == null) {
            int err = Kernel32.INSTANCE.GetLastError();
            throw new RuntimeException("CreateFileMapping failed, error=" + err);
        }

        mem = Kernel32.INSTANCE.MapViewOfFile(
                hMapping,
                WinNT.SECTION_MAP_WRITE | WinNT.SECTION_MAP_READ,
                0, 0, totalSize);

        if (mem == null) {
            int err = Kernel32.INSTANCE.GetLastError();
            Kernel32.INSTANCE.CloseHandle(hMapping);
            throw new RuntimeException("MapViewOfFile failed, error=" + err);
        }

        // Zero out header
        mem.clear(hdrAligned);

        // Fill header (matches struct queue_header exactly)
        long interval100ns = 10_000_000L / fps; // 100-nanosecond units

        mem.setInt(OFF_WRITE_IDX, 0);
        mem.setInt(OFF_READ_IDX,  0);
        mem.setInt(OFF_STATE,     STATE_STARTING);

        mem.setInt(OFF_OFFSETS,     frameOff[0]);
        mem.setInt(OFF_OFFSETS + 4, frameOff[1]);
        mem.setInt(OFF_OFFSETS + 8, frameOff[2]);

        mem.setInt(OFF_TYPE, 0); // VIDEO
        mem.setInt(OFF_CX,   width);
        mem.setInt(OFF_CY,   height);
        mem.setLong(OFF_INTERVAL, interval100ns);

        active = true;
        System.out.println("[VCam] OK - " + width + "x" + height +
                " @ " + fps + "fps | NV12 " + frameSize + "B/frame" +
                " | slot=" + slotSize + " total=" + totalSize +
                " | offsets=[" + frameOff[0] + "," + frameOff[1] + "," + frameOff[2] + "]");
    }

    /**
     * Write a BufferedImage to the virtual camera (auto-converts to NV12).
     */
    public void writeFrame(BufferedImage img) {
        if (!active || mem == null) return;

        // Resize if needed
        BufferedImage src = img;
        if (img.getWidth() != width || img.getHeight() != height) {
            src = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var g = src.createGraphics();
            g.drawImage(img, 0, 0, width, height, null);
            g.dispose();
        }

        // Convert RGB → NV12
        byte[] nv12 = toNV12(src);

        // Pre-increment write_idx (matches OBS: long inc = ++qh->write_idx)
        int inc = mem.getInt(OFF_WRITE_IDX) + 1;
        mem.setInt(OFF_WRITE_IDX, inc);

        int idx = ((inc % 3) + 3) % 3; // unsigned modulo
        int off = frameOff[idx];

        // Write timestamp (uint64 at frame offset)
        mem.setLong(off, System.nanoTime() / 100);

        // Write NV12 data after FRAME_HEADER_SIZE
        mem.write(off + FRAME_HEADER_SIZE, nv12, 0, nv12.length);

        // Update read_idx and state
        mem.setInt(OFF_READ_IDX, inc);
        mem.setInt(OFF_STATE, STATE_READY);
    }

    /**
     * Convert BufferedImage → NV12 byte array.
     * NV12: Y plane (full res) + interleaved UV (half height, full width).
     */
    private byte[] toNV12(BufferedImage img) {
        int w = width, h = height;
        byte[] nv12 = new byte[frameSize];

        // Get ARGB pixels
        int[] px;
        if (img.getType() == BufferedImage.TYPE_INT_RGB ||
            img.getType() == BufferedImage.TYPE_INT_ARGB) {
            px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        } else {
            px = img.getRGB(0, 0, w, h, null, 0, w);
        }

        // Y plane: offset 0..w*h-1
        for (int i = 0; i < w * h; i++) {
            int r = (px[i] >> 16) & 0xFF;
            int g = (px[i] >>  8) & 0xFF;
            int b =  px[i]        & 0xFF;
            nv12[i] = (byte) clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
        }

        // UV plane (interleaved): offset w*h .. w*h*3/2
        int uvOff = w * h;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                // Average 2×2 block for better color
                int rr = 0, gg = 0, bb = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int p = px[(y + dy) * w + (x + dx)];
                        rr += (p >> 16) & 0xFF;
                        gg += (p >>  8) & 0xFF;
                        bb +=  p        & 0xFF;
                    }
                }
                rr >>= 2; gg >>= 2; bb >>= 2;
                nv12[uvOff++] = (byte) clamp(((-38 * rr - 74 * gg + 112 * bb + 128) >> 8) + 128); // U
                nv12[uvOff++] = (byte) clamp(((112 * rr - 94 * gg -  18 * bb + 128) >> 8) + 128); // V
            }
        }
        return nv12;
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(v, 255); }
    private static int align32(int s) { return (s + 31) & ~31; }

    public boolean isActive() { return active; }
    public int getWidth()  { return width; }
    public int getHeight() { return height; }

    @Override
    public void close() {
        if (!active) return;
        active = false;
        if (mem != null) {
            mem.setInt(OFF_STATE, STATE_STOPPING);
            Kernel32.INSTANCE.UnmapViewOfFile(mem);
            mem = null;
        }
        if (hMapping != null) {
            Kernel32.INSTANCE.CloseHandle(hMapping);
            hMapping = null;
        }
        System.out.println("[VCam] Closed");
    }
}
