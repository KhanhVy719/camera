package com.cameraip;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import com.formdev.flatlaf.FlatDarkLaf;

/**
 * Camera Client Application
 * =========================
 * Chạy trên PC KHÔNG có camera.
 * Kết nối tới Camera Host, nhận video stream.
 * Có thể bật Virtual Camera (xuất hiện như webcam trong Google Meet).
 *
 * Usage: gradlew runClient
 */
public class CameraClientApp extends JFrame {

    // ─── Colors (same as Host) ───────────────────────────────
    static final Color BG_DARK  = new Color(0x0d, 0x11, 0x17);
    static final Color BG_CARD  = new Color(0x16, 0x1b, 0x22);
    static final Color BG_INPUT = new Color(0x21, 0x26, 0x2d);
    static final Color FG_TEXT  = new Color(0xe6, 0xed, 0xf3);
    static final Color FG_DIM   = new Color(0x8b, 0x94, 0x9e);
    static final Color ACCENT   = new Color(0x58, 0xa6, 0xff);
    static final Color GREEN    = new Color(0x3f, 0xb9, 0x50);
    static final Color RED      = new Color(0xf8, 0x51, 0x49);
    static final Color YELLOW   = new Color(0xd2, 0x99, 0x22);
    static final Color PURPLE   = new Color(0xbc, 0x8c, 0xff);
    static final Color BORDER   = new Color(0x30, 0x36, 0x3d);

    // ─── State ───────────────────────────────────────────────
    private WebSocketClient wsClient;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean vcamEnabled = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private final AtomicInteger fpsCounter = new AtomicInteger(0);
    private volatile int displayFps = 0;
    private volatile int frameWidth = 0, frameHeight = 0;
    private volatile long latencyMs = 0;  // ★ Measured latency
    private volatile byte[] latestJpegData;  // ★ Latest frame for vcam (avoid queue)

    // ─── UI ──────────────────────────────────────────────────
    private JPanel videoPanel;
    private JLabel statusLabel, fpsLabel, resLabel, latencyLabel, vcamStatusLabel;
    private JTextField hostField, portField;
    private JButton connectButton, vcamButton, screenshotButton, fullscreenButton;
    private volatile BufferedImage currentFrame;

    public CameraClientApp() {
        super("🖥 Camera Client - Remote Camera IP");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(950, 700);
        setMinimumSize(new Dimension(800, 600));
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);
        setLayout(new BorderLayout());

        buildUI();

        scheduler = Executors.newScheduledThreadPool(4);
        scheduler.scheduleAtFixedRate(() -> {
            displayFps = fpsCounter.getAndSet(0);
            SwingUtilities.invokeLater(() -> {
                fpsLabel.setText("FPS: " + displayFps);
                latencyLabel.setText("Latency: " + latencyMs + "ms");
            });
        }, 1, 1, TimeUnit.SECONDS);
    }

    // ═══════════════════════════════════════════════════════════
    //  UI BUILD
    // ═══════════════════════════════════════════════════════════
    private void buildUI() {
        // ─── Top bar ─────────────────────────────────────────
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(BG_DARK);
        topBar.setBorder(new EmptyBorder(12, 20, 8, 20));

        JLabel title = new JLabel("🖥 Camera Client");
        title.setFont(new Font("Segoe UI", Font.BOLD, 22));
        title.setForeground(ACCENT);
        topBar.add(title, BorderLayout.WEST);

        statusLabel = new JLabel("⏸ Chưa kết nối");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        statusLabel.setForeground(YELLOW);
        topBar.add(statusLabel, BorderLayout.EAST);

        add(topBar, BorderLayout.NORTH);

        // ─── Content ────────────────────────────────────────
        JPanel content = new JPanel(new BorderLayout(10, 0));
        content.setBackground(BG_DARK);
        content.setBorder(new EmptyBorder(0, 15, 15, 15));

        // Video panel
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
                    int nw = (int)(iw * scale), nh = (int)(ih * scale);
                    int x = (pw - nw) / 2, y = (ph - nh) / 2;
                    g2.drawImage(currentFrame, x, y, nw, nh, null);
                } else {
                    g2.setColor(FG_DIM);
                    g2.setFont(new Font("Segoe UI", Font.PLAIN, 16));
                    String text = connected.get() ? "Đang chờ video..." : "Nhập IP và kết nối để xem camera";
                    FontMetrics fm = g2.getFontMetrics();
                    int x = (getWidth() - fm.stringWidth(text)) / 2;
                    g2.drawString(text, x, getHeight() / 2);
                }
            }
        };
        videoPanel.setBackground(Color.BLACK);
        videoPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) toggleFullscreen();
            }
        });
        videoCard.add(videoPanel, BorderLayout.CENTER);

        // Stats bar
        JPanel statsBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
        statsBar.setBackground(BG_CARD);
        fpsLabel = makeStatLabel("FPS: --", GREEN);
        resLabel = makeStatLabel("Res: --", FG_DIM);
        latencyLabel = makeStatLabel("Latency: --", YELLOW);
        statsBar.add(fpsLabel);
        statsBar.add(resLabel);
        statsBar.add(latencyLabel);
        videoCard.add(statsBar, BorderLayout.SOUTH);

        content.add(videoCard, BorderLayout.CENTER);

        // ─── Right panel ─────────────────────────────────────
        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setBackground(BG_DARK);
        right.setPreferredSize(new Dimension(280, 0));

        // Connection card
        JPanel connCard = createCard();
        connCard.setLayout(new BoxLayout(connCard, BoxLayout.Y_AXIS));
        addCardTitle(connCard, "🔌 Kết nối");

        JPanel hostRow = createRow("Host IP:");
        hostField = createStyledField("192.168.1.x", 14);
        hostRow.add(hostField);
        connCard.add(hostRow);
        connCard.add(Box.createVerticalStrut(6));

        JPanel portRow = createRow("Port:");
        portField = createStyledField("9000", 6);
        portRow.add(portField);
        connCard.add(portRow);
        connCard.add(Box.createVerticalStrut(6));

        // Hint label
        JLabel hint = new JLabel("<html><small>💡 Dùng ZeroTier IP để connect qua internet</small></html>");
        hint.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        hint.setForeground(FG_DIM);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        connCard.add(hint);

        connCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, connCard.getPreferredSize().height + 10));
        right.add(connCard);
        right.add(Box.createVerticalStrut(10));

        // Connect button
        connectButton = new JButton("🔗  Kết nối");
        connectButton.setFont(new Font("Segoe UI", Font.BOLD, 14));
        connectButton.setForeground(Color.WHITE);
        connectButton.setBackground(GREEN);
        connectButton.setFocusPainted(false);
        connectButton.setBorderPainted(false);
        connectButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        connectButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 45));
        connectButton.setAlignmentX(LEFT_ALIGNMENT);
        connectButton.addActionListener(e -> toggleConnect());
        right.add(connectButton);
        right.add(Box.createVerticalStrut(10));

        // Virtual Camera card
        JPanel vcamCard = createCard();
        vcamCard.setLayout(new BoxLayout(vcamCard, BoxLayout.Y_AXIS));
        addCardTitle(vcamCard, "🎥 Virtual Camera");

        vcamStatusLabel = new JLabel("⏸ Tắt");
        vcamStatusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        vcamStatusLabel.setForeground(FG_DIM);
        vcamStatusLabel.setAlignmentX(LEFT_ALIGNMENT);
        vcamCard.add(vcamStatusLabel);
        vcamCard.add(Box.createVerticalStrut(6));

        JLabel vcamHint = new JLabel("<html><small>Bật để dùng camera remote<br/>trong Google Meet / Zoom</small></html>");
        vcamHint.setFont(new Font("Segoe UI", Font.PLAIN, 10));
        vcamHint.setForeground(FG_DIM);
        vcamHint.setAlignmentX(LEFT_ALIGNMENT);
        vcamCard.add(vcamHint);

        vcamCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, vcamCard.getPreferredSize().height + 10));
        right.add(vcamCard);
        right.add(Box.createVerticalStrut(6));

        vcamButton = new JButton("📷  Bật Virtual Camera");
        vcamButton.setFont(new Font("Segoe UI", Font.BOLD, 13));
        vcamButton.setForeground(Color.WHITE);
        vcamButton.setBackground(PURPLE);
        vcamButton.setFocusPainted(false);
        vcamButton.setBorderPainted(false);
        vcamButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        vcamButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        vcamButton.setAlignmentX(LEFT_ALIGNMENT);
        vcamButton.setEnabled(false);
        vcamButton.addActionListener(e -> toggleVirtualCamera());
        right.add(vcamButton);
        right.add(Box.createVerticalStrut(10));

        // Tools card
        JPanel toolsCard = createCard();
        toolsCard.setLayout(new BoxLayout(toolsCard, BoxLayout.Y_AXIS));
        addCardTitle(toolsCard, "🛠 Tools");

        screenshotButton = createToolButton("📸 Screenshot", e -> takeScreenshot());
        toolsCard.add(screenshotButton);
        toolsCard.add(Box.createVerticalStrut(4));

        fullscreenButton = createToolButton("⛶ Fullscreen (double-click)", e -> toggleFullscreen());
        toolsCard.add(fullscreenButton);

        toolsCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, toolsCard.getPreferredSize().height + 10));
        right.add(toolsCard);
        right.add(Box.createVerticalGlue());

        content.add(right, BorderLayout.EAST);
        add(content, BorderLayout.CENTER);
    }

    // ═══════════════════════════════════════════════════════════
    //  CONNECTION
    // ═══════════════════════════════════════════════════════════
    private void toggleConnect() {
        if (!connected.get()) {
            connect();
        } else {
            disconnect();
        }
    }

    private void connect() {
        String host = hostField.getText().trim();
        String port = portField.getText().trim();
        if (host.isEmpty()) {
            statusLabel.setText("❌ Nhập IP của Camera Host");
            statusLabel.setForeground(RED);
            return;
        }

        String uri = "ws://" + host + ":" + port;
        statusLabel.setText("⏳ Đang kết nối...");
        statusLabel.setForeground(YELLOW);

        try {
            wsClient = new WebSocketClient(new URI(uri)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    connected.set(true);
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("✅ Đã kết nối tới " + host);
                        statusLabel.setForeground(GREEN);
                        connectButton.setText("❌  Ngắt kết nối");
                        connectButton.setBackground(RED);
                        hostField.setEditable(false);
                        portField.setEditable(false);
                        vcamButton.setEnabled(true);
                    });
                    // ★ Start ping for latency measurement
                    CameraClientApp.this.startPingLoop();
                }

                @Override
                public void onMessage(String message) {
                    // ★ Handle pong for latency measurement
                    if (message.contains("\"pong\"") && message.contains("\"time\"")) {
                        try {
                            String timeStr = message.replaceAll(".*\"time\":", "").replaceAll("[^0-9]", "");
                            long serverTime = Long.parseLong(timeStr);
                            latencyMs = System.currentTimeMillis() - serverTime;
                        } catch (Exception e) {}
                    }
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    // ★ Binary = [8-byte timestamp][JPEG data]
                    try {
                        int total = bytes.remaining();
                        if (total < 10) return; // Too small

                        // ★ Extract timestamp header (8 bytes, big-endian)
                        long sendTime = 0;
                        for (int i = 0; i < 8; i++) {
                            sendTime = (sendTime << 8) | (bytes.get() & 0xFF);
                        }
                        latencyMs = System.currentTimeMillis() - sendTime;

                        // ★ Read JPEG data (remaining bytes)
                        byte[] jpegData = new byte[bytes.remaining()];
                        bytes.get(jpegData);

                        // Store for vcam
                        latestJpegData = jpegData;

                        // ★ Decode and display
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpegData));
                        if (img != null) {
                            currentFrame = img;
                            frameWidth = img.getWidth();
                            frameHeight = img.getHeight();
                            fpsCounter.incrementAndGet();
                            videoPanel.repaint();

                            // Update resolution label occasionally
                            if (fpsCounter.get() % 60 == 1) {
                                SwingUtilities.invokeLater(() ->
                                        resLabel.setText("Res: " + frameWidth + "x" + frameHeight));
                            }
                        }
                    } catch (Exception e) {
                        // skip bad frames
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    connected.set(false);
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("❌ Ngắt kết nối" + (remote ? " (bởi host)" : ""));
                        statusLabel.setForeground(RED);
                        connectButton.setText("🔗  Kết nối");
                        connectButton.setBackground(GREEN);
                        hostField.setEditable(true);
                        portField.setEditable(true);
                        vcamButton.setEnabled(false);
                        currentFrame = null;
                        videoPanel.repaint();
                        if (vcamEnabled.get()) stopVirtualCamera();
                    });
                }

                @Override
                public void onError(Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("❌ Lỗi: " + ex.getMessage());
                        statusLabel.setForeground(RED);
                    });
                }
            };
            wsClient.setConnectionLostTimeout(30);
            wsClient.setTcpNoDelay(true);  // ★ Disable Nagle's algorithm
            wsClient.connect();
        } catch (Exception ex) {
            statusLabel.setText("❌ URL sai: " + ex.getMessage());
            statusLabel.setForeground(RED);
        }
    }

    private void disconnect() {
        if (wsClient != null) {
            try { wsClient.close(); } catch (Exception e) {}
        }
        connected.set(false);
        currentFrame = null;
        videoPanel.repaint();
        statusLabel.setText("⏸ Đã ngắt kết nối");
        statusLabel.setForeground(YELLOW);
        connectButton.setText("🔗  Kết nối");
        connectButton.setBackground(GREEN);
        hostField.setEditable(true);
        portField.setEditable(true);
        vcamButton.setEnabled(false);
        if (vcamEnabled.get()) stopVirtualCamera();
    }

    /** ★ Latency reporting loop — sends latency back to Host for adaptive bitrate */
    private void startPingLoop() {
        scheduler.submit(() -> {
            while (connected.get()) {
                try {
                    if (wsClient != null && wsClient.isOpen()) {
                        // Send ping for pong-based latency
                        wsClient.send("{\"type\":\"ping\",\"timestamp\":" + System.currentTimeMillis() + "}");
                        // ★ Report measured latency to Host (for adaptive bitrate)
                        if (latencyMs > 0) {
                            wsClient.send("{\"type\":\"latency_report\",\"latency\":" + latencyMs + "}");
                        }
                    }
                    Thread.sleep(500);  // Report every 500ms (Discord-like frequency)
                } catch (Exception e) {
                    break;
                }
            }
        });
    }

    // ═══════════════════════════════════════════════════════════
    //  VIRTUAL CAMERA (MJPEG server → OBS Media Source)
    // ═══════════════════════════════════════════════════════════
    private com.sun.net.httpserver.HttpServer mjpegServer;
    private static final int MJPEG_PORT = 4747;
    private static final String MJPEG_BOUNDARY = "--jpgboundary";

    private void toggleVirtualCamera() {
        if (!vcamEnabled.get()) {
            startVirtualCamera();
        } else {
            stopVirtualCamera();
        }
    }

    private void startVirtualCamera() {
        if (frameWidth == 0 || frameHeight == 0) {
            vcamStatusLabel.setText("⚠️ Chờ nhận frame đầu tiên...");
            vcamStatusLabel.setForeground(YELLOW);
            return;
        }

        try {
            // ★ Start MJPEG HTTP server on localhost
            mjpegServer = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("0.0.0.0", MJPEG_PORT), 0);

            mjpegServer.createContext("/stream", exchange -> {
                exchange.getResponseHeaders().set("Content-Type",
                        "multipart/x-mixed-replace; boundary=" + MJPEG_BOUNDARY);
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, 0);

                java.io.OutputStream out = exchange.getResponseBody();
                System.out.println("[MJPEG] Client connected: " + exchange.getRemoteAddress());

                try {
                    while (vcamEnabled.get()) {
                        byte[] jpeg = latestJpegData;
                        if (jpeg != null) {
                            String header = MJPEG_BOUNDARY + "\r\n" +
                                    "Content-Type: image/jpeg\r\n" +
                                    "Content-Length: " + jpeg.length + "\r\n\r\n";
                            out.write(header.getBytes());
                            out.write(jpeg);
                            out.write("\r\n".getBytes());
                            out.flush();
                        }
                        Thread.sleep(33); // ~30fps
                    }
                } catch (Exception e) {
                    // Client disconnected
                    System.out.println("[MJPEG] Client disconnected");
                }
            });

            mjpegServer.setExecutor(scheduler);
            mjpegServer.start();

            vcamEnabled.set(true);
            vcamButton.setText("⏹  Tắt MJPEG Server");
            vcamButton.setBackground(RED);
            vcamStatusLabel.setText("🟢 MJPEG: http://localhost:" + MJPEG_PORT + "/stream");
            vcamStatusLabel.setForeground(GREEN);

            System.out.println("[MJPEG] Server started on port " + MJPEG_PORT);
            System.out.println("[MJPEG] URL: http://localhost:" + MJPEG_PORT + "/stream");
            System.out.println("[MJPEG] → Mở OBS → Sources → + Media Source");
            System.out.println("[MJPEG]   Uncheck 'Local File'");
            System.out.println("[MJPEG]   Input: http://localhost:" + MJPEG_PORT + "/stream");
            System.out.println("[MJPEG] → Start Virtual Camera trong OBS");

            // Show instructions
            JOptionPane.showMessageDialog(this,
                    "MJPEG Server đang chạy!\n\n" +
                    "Trong OBS Studio:\n" +
                    "1. Sources → + → Media Source\n" +
                    "2. Bỏ tick 'Local File'\n" +
                    "3. Input URL: http://localhost:" + MJPEG_PORT + "/stream\n" +
                    "4. Bấm OK → Start Virtual Camera\n" +
                    "5. Trong Meet chọn 'OBS Virtual Camera'",
                    "📡 MJPEG Server", JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception ex) {
            vcamStatusLabel.setText("❌ " + ex.getMessage());
            vcamStatusLabel.setForeground(RED);
        }
    }

    private void stopVirtualCamera() {
        vcamEnabled.set(false);
        if (mjpegServer != null) {
            mjpegServer.stop(0);
            mjpegServer = null;
            System.out.println("[MJPEG] Server stopped");
        }
        vcamButton.setText("📷  Bật Virtual Camera");
        vcamButton.setBackground(PURPLE);
        vcamStatusLabel.setText("⏸ Tắt");
        vcamStatusLabel.setForeground(FG_DIM);
    }

    // ═══════════════════════════════════════════════════════════
    //  TOOLS
    // ═══════════════════════════════════════════════════════════
    private void takeScreenshot() {
        if (currentFrame == null) return;
        try {
            String filename = "screenshot_" + System.currentTimeMillis() + ".png";
            File file = new File(System.getProperty("user.home") + File.separator + "Desktop" + File.separator + filename);
            ImageIO.write(currentFrame, "png", file);
            statusLabel.setText("📸 Saved: " + file.getName());
            statusLabel.setForeground(GREEN);
        } catch (Exception ex) {
            statusLabel.setText("❌ Screenshot failed: " + ex.getMessage());
            statusLabel.setForeground(RED);
        }
    }

    private boolean isFullscreen = false;
    private void toggleFullscreen() {
        GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        if (!isFullscreen) {
            dispose();
            setUndecorated(true);
            gd.setFullScreenWindow(this);
            isFullscreen = true;
        } else {
            gd.setFullScreenWindow(null);
            dispose();
            setUndecorated(false);
            setVisible(true);
            isFullscreen = false;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  UI HELPERS
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
        label.setPreferredSize(new Dimension(70, 20));
        row.add(label);
        return row;
    }

    private JTextField createStyledField(String placeholder, int cols) {
        JTextField field = new JTextField(cols);
        field.setFont(new Font("Consolas", Font.PLAIN, 12));
        field.setBackground(BG_INPUT);
        field.setForeground(FG_TEXT);
        field.setCaretColor(FG_TEXT);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER), new EmptyBorder(3, 6, 3, 6)));
        // Placeholder
        field.setText(placeholder);
        field.setForeground(FG_DIM);
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                if (field.getText().equals(placeholder)) {
                    field.setText("");
                    field.setForeground(FG_TEXT);
                }
            }
            @Override
            public void focusLost(FocusEvent e) {
                if (field.getText().isEmpty()) {
                    field.setText(placeholder);
                    field.setForeground(FG_DIM);
                }
            }
        });
        return field;
    }

    private JButton createToolButton(String text, ActionListener action) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
        btn.setForeground(FG_TEXT);
        btn.setBackground(BG_INPUT);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        btn.setAlignmentX(LEFT_ALIGNMENT);
        btn.addActionListener(action);
        return btn;
    }

    private JLabel makeStatLabel(String text, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("Consolas", Font.PLAIN, 12));
        l.setForeground(color);
        return l;
    }

    // ═══════════════════════════════════════════════════════════
    //  MAIN
    // ═══════════════════════════════════════════════════════════
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(new FlatDarkLaf());
            } catch (Exception e) {
                e.printStackTrace();
            }
            new CameraClientApp().setVisible(true);
        });
    }

    @Override
    public void dispose() {
        disconnect();
        if (scheduler != null) scheduler.shutdownNow();
        super.dispose();
    }
}
