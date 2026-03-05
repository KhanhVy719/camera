package com.cameraip;

import com.formdev.flatlaf.FlatDarkLaf;
import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamResolution;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Camera Host Application
 * =======================
 * Chạy trên PC CÓ camera.
 * Capture camera và stream qua WebSocket để PC khác xem.
 *
 * Usage: gradlew runHost
 */
public class CameraHostApp extends JFrame {

    // ─── Colors ──────────────────────────────────────────────
    static final Color BG_DARK = new Color(0x0d, 0x11, 0x17);
    static final Color BG_CARD = new Color(0x16, 0x1b, 0x22);
    static final Color BG_INPUT = new Color(0x21, 0x26, 0x2d);
    static final Color FG_TEXT = new Color(0xe6, 0xed, 0xf3);
    static final Color FG_DIM = new Color(0x8b, 0x94, 0x9e);
    static final Color ACCENT = new Color(0x58, 0xa6, 0xff);
    static final Color GREEN = new Color(0x3f, 0xb9, 0x50);
    static final Color RED = new Color(0xf8, 0x51, 0x49);
    static final Color YELLOW = new Color(0xd2, 0x99, 0x22);
    static final Color BORDER = new Color(0x30, 0x36, 0x3d);

    // ─── State ───────────────────────────────────────────────
    private Webcam webcam;
    private FrameServer wsServer;
    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private int jpegQuality = 70;
    private int targetFps = 30;
    private final AtomicInteger fpsCounter = new AtomicInteger(0);
    private volatile int displayFps = 0;

    // ─── UI Components ───────────────────────────────────────
    private JPanel videoPanel;
    private JLabel statusLabel, fpsLabel, resLabel, viewersLabel;
    private JComboBox<String> cameraCombo, resolutionCombo;
    private JSlider qualitySlider;
    private JLabel qualityValueLabel;
    private JTextField portField;
    private JTextArea ipTextArea;
    private JButton streamButton;
    private volatile BufferedImage currentFrame;

    public CameraHostApp() {
        super("📹 Camera Host - Remote Camera IP");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(950, 700);
        setMinimumSize(new Dimension(800, 600));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);
        setLayout(new BorderLayout(0, 0));

        buildUI();
        detectCameras();
        updateIPs();

        // FPS counter
        scheduler = Executors.newScheduledThreadPool(2);
        scheduler.scheduleAtFixedRate(() -> {
            displayFps = fpsCounter.getAndSet(0);
            SwingUtilities.invokeLater(() -> fpsLabel.setText("FPS: " + displayFps));
        }, 1, 1, TimeUnit.SECONDS);
    }

    // ═══════════════════════════════════════════════════════════
    // UI BUILD
    // ═══════════════════════════════════════════════════════════
    private void buildUI() {
        // ─── Top bar ─────────────────────────────────────────
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(BG_DARK);
        topBar.setBorder(new EmptyBorder(12, 20, 8, 20));

        JLabel title = new JLabel("📹 Camera Host");
        title.setFont(new Font("Segoe UI", Font.BOLD, 22));
        title.setForeground(ACCENT);
        topBar.add(title, BorderLayout.WEST);

        statusLabel = new JLabel("⏸ Chưa stream");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        statusLabel.setForeground(YELLOW);
        topBar.add(statusLabel, BorderLayout.EAST);

        add(topBar, BorderLayout.NORTH);

        // ─── Content ────────────────────────────────────────
        JPanel content = new JPanel(new BorderLayout(10, 0));
        content.setBackground(BG_DARK);
        content.setBorder(new EmptyBorder(0, 15, 15, 15));

        // Video preview
        JPanel videoCard = createCard();
        videoCard.setLayout(new BorderLayout());

        videoPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                if (currentFrame != null) {
                    int pw = getWidth(), ph = getHeight();
                    int iw = currentFrame.getWidth(), ih = currentFrame.getHeight();
                    double scale = Math.min((double) pw / iw, (double) ph / ih);
                    int nw = (int) (iw * scale), nh = (int) (ih * scale);
                    int x = (pw - nw) / 2, y = (ph - nh) / 2;
                    g2.drawImage(currentFrame, x, y, nw, nh, null);
                } else {
                    g2.setColor(FG_DIM);
                    g2.setFont(new Font("Segoe UI", Font.PLAIN, 16));
                    String text = "Camera Preview";
                    FontMetrics fm = g2.getFontMetrics();
                    int x = (getWidth() - fm.stringWidth(text)) / 2;
                    int y = getHeight() / 2;
                    g2.drawString(text, x, y);
                }
            }
        };
        videoPanel.setBackground(Color.BLACK);
        videoCard.add(videoPanel, BorderLayout.CENTER);

        // Stats bar
        JPanel statsBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
        statsBar.setBackground(BG_CARD);
        fpsLabel = makeStatLabel("FPS: --", GREEN);
        resLabel = makeStatLabel("Res: --", FG_DIM);
        viewersLabel = makeStatLabel("👁 Viewers: 0", ACCENT);
        statsBar.add(fpsLabel);
        statsBar.add(resLabel);
        statsBar.add(Box.createHorizontalGlue());
        statsBar.add(viewersLabel);
        videoCard.add(statsBar, BorderLayout.SOUTH);

        content.add(videoCard, BorderLayout.CENTER);

        // ─── Right panel (controls) ──────────────────────────
        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setBackground(BG_DARK);
        right.setPreferredSize(new Dimension(280, 0));

        // Camera card
        JPanel camCard = createCard();
        camCard.setLayout(new BoxLayout(camCard, BoxLayout.Y_AXIS));
        addCardTitle(camCard, "🎥 Camera");

        cameraCombo = createStyledCombo();
        camCard.add(cameraCombo);
        camCard.add(Box.createVerticalStrut(8));

        // Resolution
        JPanel resRow = createRow("Resolution:");
        resolutionCombo = createStyledCombo();
        resolutionCombo.addItem("320x240");
        resolutionCombo.addItem("640x480");
        resolutionCombo.addItem("1280x720");
        resolutionCombo.addItem("1920x1080");
        resolutionCombo.setSelectedItem("1280x720");
        resRow.add(resolutionCombo);
        camCard.add(resRow);
        camCard.add(Box.createVerticalStrut(8));

        // Quality
        JPanel qualRow = createRow("JPEG Quality:");
        qualityValueLabel = new JLabel(jpegQuality + "%");
        qualityValueLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
        qualityValueLabel.setForeground(ACCENT);
        qualRow.add(qualityValueLabel);
        camCard.add(qualRow);

        qualitySlider = new JSlider(20, 100, jpegQuality);
        qualitySlider.setBackground(BG_CARD);
        qualitySlider.setForeground(ACCENT);
        qualitySlider.addChangeListener(e -> {
            jpegQuality = qualitySlider.getValue();
            qualityValueLabel.setText(jpegQuality + "%");
        });
        camCard.add(qualitySlider);
        camCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, camCard.getPreferredSize().height + 20));
        right.add(camCard);
        right.add(Box.createVerticalStrut(10));

        // Network card
        JPanel netCard = createCard();
        netCard.setLayout(new BoxLayout(netCard, BoxLayout.Y_AXIS));
        addCardTitle(netCard, "🌐 Network");

        JPanel portRow = createRow("Port:");
        portField = new JTextField("9000", 6);
        portField.setFont(new Font("Consolas", Font.PLAIN, 12));
        portField.setBackground(BG_INPUT);
        portField.setForeground(FG_TEXT);
        portField.setCaretColor(FG_TEXT);
        portField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER), new EmptyBorder(3, 6, 3, 6)));
        portRow.add(portField);
        netCard.add(portRow);
        netCard.add(Box.createVerticalStrut(6));

        JLabel ipTitle = new JLabel("IP Addresses:");
        ipTitle.setFont(new Font("Segoe UI", Font.BOLD, 11));
        ipTitle.setForeground(FG_TEXT);
        ipTitle.setAlignmentX(LEFT_ALIGNMENT);
        netCard.add(ipTitle);
        netCard.add(Box.createVerticalStrut(4));

        ipTextArea = new JTextArea(5, 20);
        ipTextArea.setFont(new Font("Consolas", Font.PLAIN, 11));
        ipTextArea.setBackground(BG_INPUT);
        ipTextArea.setForeground(GREEN);
        ipTextArea.setEditable(false);
        ipTextArea.setBorder(new EmptyBorder(6, 8, 6, 8));
        JScrollPane ipScroll = new JScrollPane(ipTextArea);
        ipScroll.setBorder(BorderFactory.createLineBorder(BORDER));
        ipScroll.setAlignmentX(LEFT_ALIGNMENT);
        netCard.add(ipScroll);
        netCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, netCard.getPreferredSize().height + 20));
        right.add(netCard);
        right.add(Box.createVerticalGlue());

        // Stream button
        streamButton = new JButton("▶  Bắt đầu Stream");
        streamButton.setFont(new Font("Segoe UI", Font.BOLD, 15));
        streamButton.setForeground(Color.WHITE);
        streamButton.setBackground(GREEN);
        streamButton.setFocusPainted(false);
        streamButton.setBorderPainted(false);
        streamButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        streamButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        streamButton.setAlignmentX(LEFT_ALIGNMENT);
        streamButton.addActionListener(e -> toggleStream());
        right.add(Box.createVerticalStrut(10));
        right.add(streamButton);

        content.add(right, BorderLayout.EAST);
        add(content, BorderLayout.CENTER);
    }

    // ═══════════════════════════════════════════════════════════
    // CAMERA
    // ═══════════════════════════════════════════════════════════
    private void detectCameras() {
        List<Webcam> webcams = Webcam.getWebcams();
        cameraCombo.removeAllItems();
        if (webcams.isEmpty()) {
            cameraCombo.addItem("❌ Không tìm thấy camera");
        } else {
            for (int i = 0; i < webcams.size(); i++) {
                cameraCombo.addItem("Camera " + i + " - " + webcams.get(i).getName());
            }
        }
    }

    private Dimension getSelectedResolution() {
        String res = (String) resolutionCombo.getSelectedItem();
        if (res == null)
            return WebcamResolution.VGA.getSize();
        String[] parts = res.split("x");
        return new Dimension(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    // ═══════════════════════════════════════════════════════════
    // NETWORK
    // ═══════════════════════════════════════════════════════════
    private void updateIPs() {
        StringBuilder sb = new StringBuilder();
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface ni = nets.nextElement();
                if (ni.isLoopback() || !ni.isUp())
                    continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        String label;
                        String niName = ni.getDisplayName().toLowerCase();
                        if (niName.contains("zerotier") || ip.startsWith("10.147.") || ip.startsWith("10.243.")) {
                            label = "🔗 ZeroTier";
                        } else if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            label = "📶 LAN";
                        } else {
                            label = "🌐 Other";
                        }
                        sb.append(label).append(": ").append(ip).append("\n");
                    }
                }
            }
        } catch (SocketException e) {
            sb.append("⚠️ Error detecting IPs");
        }
        ipTextArea.setText(sb.toString().trim());
    }

    // ═══════════════════════════════════════════════════════════
    // STREAM CONTROL
    // ═══════════════════════════════════════════════════════════
    private void toggleStream() {
        if (!streaming.get()) {
            startStream();
        } else {
            stopStream();
        }
    }

    private void startStream() {
        // Open webcam
        List<Webcam> webcams = Webcam.getWebcams();
        int idx = cameraCombo.getSelectedIndex();
        if (idx < 0 || idx >= webcams.size()) {
            statusLabel.setText("❌ Không có camera");
            statusLabel.setForeground(RED);
            return;
        }
        webcam = webcams.get(idx);
        Dimension res = getSelectedResolution();
        webcam.setCustomViewSizes(new Dimension[] { res });
        webcam.setViewSize(res);

        try {
            webcam.open();
        } catch (Exception ex) {
            statusLabel.setText("❌ Không mở được camera: " + ex.getMessage());
            statusLabel.setForeground(RED);
            return;
        }

        // Start WebSocket server
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            port = 9000;
        }
        try {
            wsServer = new FrameServer(port);
            wsServer.start();
        } catch (Exception ex) {
            statusLabel.setText("❌ Không mở được port: " + ex.getMessage());
            statusLabel.setForeground(RED);
            webcam.close();
            return;
        }

        streaming.set(true);

        // Update UI
        streamButton.setText("⏹  Dừng Stream");
        streamButton.setBackground(RED);
        statusLabel.setText("🔴 Đang stream trên port " + port);
        statusLabel.setForeground(GREEN);
        cameraCombo.setEnabled(false);
        resolutionCombo.setEnabled(false);
        portField.setEditable(false);
        resLabel.setText("Res: " + res.width + "x" + res.height);

        // Capture loop
        scheduler.submit(() -> {
            while (streaming.get() && webcam.isOpen()) {
                try {
                    BufferedImage frame = webcam.getImage();
                    if (frame == null)
                        continue;
                    currentFrame = frame;
                    videoPanel.repaint();

                    // Encode JPEG
                    byte[] jpegData = encodeJpeg(frame);
                    if (jpegData != null && wsServer != null) {
                        wsServer.broadcast(jpegData);
                        fpsCounter.incrementAndGet();
                    }

                    // Throttle
                    Thread.sleep(1000 / targetFps);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    // ignore frame errors
                }
            }
        });
    }

    private void stopStream() {
        streaming.set(false);

        if (webcam != null && webcam.isOpen()) {
            try {
                webcam.close();
            } catch (Exception e) {
            }
        }
        if (wsServer != null) {
            try {
                wsServer.stop(500);
            } catch (Exception e) {
            }
            wsServer = null;
        }

        currentFrame = null;
        videoPanel.repaint();

        streamButton.setText("▶  Bắt đầu Stream");
        streamButton.setBackground(GREEN);
        statusLabel.setText("⏸ Đã dừng");
        statusLabel.setForeground(YELLOW);
        cameraCombo.setEnabled(true);
        resolutionCombo.setEnabled(true);
        portField.setEditable(true);
        viewersLabel.setText("👁 Viewers: 0");
    }

    private byte[] encodeJpeg(BufferedImage img) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            var writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext())
                return null;
            var writer = writers.next();
            var param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(jpegQuality / 100f);
            writer.setOutput(ImageIO.createImageOutputStream(baos));
            writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
            writer.dispose();
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════
    // WEBSOCKET SERVER
    // ═══════════════════════════════════════════════════════════
    private class FrameServer extends WebSocketServer {
        FrameServer(int port) {
            super(new InetSocketAddress("0.0.0.0", port));
            setReuseAddr(true);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            System.out.println("✅ Client connected: " + conn.getRemoteSocketAddress());
            updateViewerCount();
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            System.out.println("❌ Client disconnected: " + conn.getRemoteSocketAddress());
            updateViewerCount();
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            // Handle control messages (ping/pong)
            if (message.contains("\"ping\"")) {
                conn.send("{\"type\":\"pong\",\"time\":" + System.currentTimeMillis() + "}");
            }
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            System.err.println("WS Error: " + ex.getMessage());
        }

        @Override
        public void onStart() {
            System.out.println("🚀 WebSocket server started");
        }

        private void updateViewerCount() {
            int count = getConnections().size();
            SwingUtilities.invokeLater(() -> viewersLabel.setText("👁 Viewers: " + count));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // UI HELPERS
    // ═══════════════════════════════════════════════════════════
    private JPanel createCard() {
        JPanel card = new JPanel();
        card.setBackground(BG_CARD);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(12, 14, 12, 14)));
        card.setAlignmentX(LEFT_ALIGNMENT);
        return card;
    }

    private void addCardTitle(JPanel card, String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.BOLD, 13));
        label.setForeground(FG_TEXT);
        label.setAlignmentX(LEFT_ALIGNMENT);
        card.add(label);
        card.add(Box.createVerticalStrut(8));
    }

    private JPanel createRow(String labelText) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        row.setBackground(BG_CARD);
        row.setAlignmentX(LEFT_ALIGNMENT);
        JLabel label = new JLabel(labelText);
        label.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        label.setForeground(FG_DIM);
        label.setPreferredSize(new Dimension(100, 20));
        row.add(label);
        return row;
    }

    @SuppressWarnings("unchecked")
    private JComboBox<String> createStyledCombo() {
        JComboBox<String> combo = new JComboBox<>();
        combo.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        combo.setBackground(BG_INPUT);
        combo.setForeground(FG_TEXT);
        combo.setAlignmentX(LEFT_ALIGNMENT);
        combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        return combo;
    }

    private JLabel makeStatLabel(String text, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Consolas", Font.PLAIN, 12));
        l.setForeground(color);
        return l;
    }

    // ═══════════════════════════════════════════════════════════
    // MAIN
    // ═══════════════════════════════════════════════════════════
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(new FlatDarkLaf());
            } catch (Exception e) {
                e.printStackTrace();
            }
            new CameraHostApp().setVisible(true);
        });
    }

    @Override
    public void dispose() {
        stopStream();
        if (scheduler != null)
            scheduler.shutdownNow();
        super.dispose();
    }
}
