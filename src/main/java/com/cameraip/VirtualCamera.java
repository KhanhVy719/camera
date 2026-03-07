package com.cameraip;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.FileWriter;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;

/**
 * Java Virtual Camera — OBS Virtual Camera via Shared Memory (JNA)
 * ================================================================
 * Compatible with OBS Studio 32.0.x (verified against source code)
 *
 * Protocol: plugins/win-dshow/shared-memory-queue.c
 * Filter:   plugins/win-dshow/virtualcam-module/virtualcam-filter.cpp
 *
 * The OBS VCam DirectShow filter:
 * 1. On init: reads resolution from %APPDATA%\obs-virtualcam.txt
 *    (format: "WIDTHxHEIGHTxINTERVAL_100NS")
 * 2. On each frame: calls video_queue_open() → checks shared memory
 *    named "OBSVirtualCamVideo"
 * 3. If READY: reads NV12 frame via video_queue_read()
 * 4. If not ready: shows placeholder/grey frame
 *
 * struct queue_header {          // MSVC x64 layout
 *   volatile uint32_t write_idx; // offset  0
 *   volatile uint32_t read_idx;  // offset  4
 *   volatile uint32_t state;     // offset  8
 *   uint32_t offsets[3];         // offset 12
 *   uint32_t type;               // offset 24
 *   uint32_t cx;                 // offset 28
 *   uint32_t cy;                 // offset 32
 *   // 4 bytes padding
 *   uint64_t interval;           // offset 40
 *   uint32_t reserved[8];        // offset 48
 * };  // sizeof=80, ALIGN32=96
 */
public class VirtualCamera implements AutoCloseable {

    private static final String VIDEO_NAME = "OBSVirtualCamVideo";
    private static final int FRAME_HEADER_SIZE = 32;

    // Queue states
    private static final int STATE_STARTING = 0;
    private static final int STATE_READY    = 1;
    private static final int STATE_STOPPING = 2;

    // Header offsets (MSVC x64)
    private static final int OFF_WRITE_IDX = 0;
    private static final int OFF_READ_IDX  = 4;
    private static final int OFF_STATE     = 8;
    private static final int OFF_OFFSETS   = 12;
    private static final int OFF_TYPE      = 24;
    private static final int OFF_CX        = 28;
    private static final int OFF_CY        = 32;
    private static final int OFF_INTERVAL  = 40; // uint64_t aligned to 8
    private static final int HEADER_SIZEOF = 80;

    private final int width, height, frameSize;
    private WinNT.HANDLE hMapping;
    private Pointer mem;
    private final int[] frameOff = new int[3];
    private boolean active = false;

    public VirtualCamera(int cx, int cy, int fps) {
        this.width  = cx & ~1; // even
        this.height = cy & ~1;
        this.frameSize = width * height * 3 / 2; // NV12

        long interval100ns = 10_000_000L / fps;

        // ★ Step 1: Write obs-virtualcam.txt so the DirectShow filter
        // knows our resolution BEFORE it connects to shared memory.
        // (OBS 32 filter reads this file in constructor when queue is not open)
        writeResolutionHint(width, height, interval100ns);

        // Calculate aligned offsets
        int hdrAligned = align32(HEADER_SIZEOF); // 96
        int slotSize   = align32(frameSize + FRAME_HEADER_SIZE);
        frameOff[0] = hdrAligned;
        frameOff[1] = hdrAligned + slotSize;
        frameOff[2] = hdrAligned + slotSize * 2;
        int totalSize  = hdrAligned + slotSize * 3;

        // Check if already in use
        WinNT.HANDLE existing = Kernel32.INSTANCE.OpenFileMapping(
                WinNT.FILE_MAP_READ, false, VIDEO_NAME);
        if (existing != null) {
            Kernel32.INSTANCE.CloseHandle(existing);
            throw new RuntimeException(
                "OBS Virtual Camera đang được sử dụng.\n" +
                "Tắt Virtual Camera trong OBS trước.");
        }

        // ★ Step 2: Create shared memory
        hMapping = Kernel32.INSTANCE.CreateFileMapping(
                WinNT.INVALID_HANDLE_VALUE, null,
                WinNT.PAGE_READWRITE, 0, totalSize, VIDEO_NAME);

        if (hMapping == null) {
            throw new RuntimeException("CreateFileMapping failed: " +
                    Kernel32.INSTANCE.GetLastError());
        }

        mem = Kernel32.INSTANCE.MapViewOfFile(
                hMapping,
                WinNT.SECTION_MAP_WRITE | WinNT.SECTION_MAP_READ,
                0, 0, totalSize);

        if (mem == null) {
            Kernel32.INSTANCE.CloseHandle(hMapping);
            throw new RuntimeException("MapViewOfFile failed: " +
                    Kernel32.INSTANCE.GetLastError());
        }

        // Zero entire mapping
        for (int i = 0; i < totalSize; i += 4) {
            mem.setInt(i, 0);
        }

        // Fill header
        mem.setInt(OFF_WRITE_IDX, 0);
        mem.setInt(OFF_READ_IDX,  0);
        mem.setInt(OFF_STATE,     STATE_STARTING);
        mem.setInt(OFF_OFFSETS,     frameOff[0]);
        mem.setInt(OFF_OFFSETS + 4, frameOff[1]);
        mem.setInt(OFF_OFFSETS + 8, frameOff[2]);
        mem.setInt(OFF_TYPE, 0);
        mem.setInt(OFF_CX, width);
        mem.setInt(OFF_CY, height);
        mem.setLong(OFF_INTERVAL, interval100ns);

        active = true;
        System.out.println("[VCam] OK - " + width + "x" + height +
                " @ " + fps + "fps | NV12 " + frameSize + "B" +
                " | offsets=[" + frameOff[0] + "," + frameOff[1] + "," + frameOff[2] + "]");
    }

    /**
     * Write %APPDATA%\obs-virtualcam.txt
     * Format: "WIDTHxHEIGHTxINTERVAL"
     * The OBS DirectShow filter reads this on init when shared memory isn't available yet.
     */
    private void writeResolutionHint(int cx, int cy, long interval) {
        try {
            String appData = System.getenv("APPDATA");
            if (appData == null) return;
            File f = new File(appData, "obs-virtualcam.txt");
            try (FileWriter fw = new FileWriter(f)) {
                fw.write(cx + "x" + cy + "x" + interval);
            }
            System.out.println("[VCam] Wrote " + f.getAbsolutePath() +
                    " → " + cx + "x" + cy + "x" + interval);
        } catch (Exception e) {
            System.err.println("[VCam] Warning: could not write obs-virtualcam.txt: " + e);
        }
    }

    public void writeFrame(BufferedImage img) {
        if (!active || mem == null) return;

        BufferedImage src = img;
        if (img.getWidth() != width || img.getHeight() != height) {
            src = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var g = src.createGraphics();
            g.drawImage(img, 0, 0, width, height, null);
            g.dispose();
        }

        byte[] nv12 = toNV12(src);

        // Pre-increment write_idx (matches OBS: long inc = ++qh->write_idx)
        int inc = mem.getInt(OFF_WRITE_IDX) + 1;
        mem.setInt(OFF_WRITE_IDX, inc);

        int idx = ((inc % 3) + 3) % 3;
        int off = frameOff[idx];

        // Timestamp (uint64 at frame offset)
        mem.setLong(off, System.nanoTime() / 100);

        // NV12 data after FRAME_HEADER_SIZE
        mem.write(off + FRAME_HEADER_SIZE, nv12, 0, nv12.length);

        // Update read_idx and state
        mem.setInt(OFF_READ_IDX, inc);
        mem.setInt(OFF_STATE, STATE_READY);
    }

    private byte[] toNV12(BufferedImage img) {
        int w = width, h = height;
        byte[] nv12 = new byte[frameSize];

        int[] px;
        if (img.getType() == BufferedImage.TYPE_INT_RGB ||
            img.getType() == BufferedImage.TYPE_INT_ARGB) {
            px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        } else {
            px = img.getRGB(0, 0, w, h, null, 0, w);
        }

        // Y plane
        for (int i = 0; i < w * h; i++) {
            int r = (px[i] >> 16) & 0xFF;
            int g = (px[i] >>  8) & 0xFF;
            int b =  px[i]        & 0xFF;
            nv12[i] = (byte) clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
        }

        // UV plane (interleaved, 2x2 block averaged)
        int uvOff = w * h;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
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
                nv12[uvOff++] = (byte) clamp(((-38 * rr - 74 * gg + 112 * bb + 128) >> 8) + 128);
                nv12[uvOff++] = (byte) clamp(((112 * rr - 94 * gg -  18 * bb + 128) >> 8) + 128);
            }
        }
        return nv12;
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(v, 255); }
    private static int align32(int s) { return (s + 31) & ~31; }

    public boolean isActive() { return active; }

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
