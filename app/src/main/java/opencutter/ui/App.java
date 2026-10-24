package opencutter.ui;

import opencutter.device.Device;
import opencutter.device.Discovery;
import opencutter.importer.CutImporter;
import opencutter.job.CutController;
import opencutter.job.Presets;
import opencutter.protocol.Protocol;
import opencutter.protocol.Session;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;

/**
 * OpenCutter — simple UI to drive the Cricut Explore Air 2 directly.
 * Flow: machines appear in a list -> connect -> load file -> START (prompt) -> load mat -> Go -> cut -> Done.
 */
public final class App extends JFrame {

    private Session session;
    private CutController controller;
    private CutImporter.Result job;
    private File jobFile;
    private Device connected;

    private final DefaultListModel<Device> deviceModel = new DefaultListModel<>();
    private final JList<Device> deviceList = new JList<>(deviceModel);
    private final JButton connectBtn = new JButton("Connect");
    private final JLabel connState = new JLabel("No machine connected");
    private final JButton loadFileBtn = new JButton("Load file…");
    private final JLabel fileLabel = new JLabel("No file loaded");
    private final PreviewPanel preview = new PreviewPanel();
    private final JButton startBtn = new JButton("START");
    private final JButton doneBtn = new JButton("Unload");
    private volatile boolean scanning = false;
    private volatile Boolean matLoaded = null;      // last known mat state; null = unknown
    private volatile CutController.Status jobStatus = CutController.Status.IDLE;
    private volatile boolean busy = false;          // an unload/disconnect is in progress
    private volatile boolean polling = false;
    private int missedPolls = 0;                    // EDT only
    private final JProgressBar progress = new JProgressBar();
    private final JTextArea log = new JTextArea(8, 40);

    public App() {
        super("OpenCutter");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { shutdown(); }
        });
        setLayout(new BorderLayout(8, 8));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        add(buildConnectPanel(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildSouth(), BorderLayout.SOUTH);

        wireActions();
        startDiscovery();
        startStatePolling();
        updateEnabled();
        setSize(760, 640);
        setLocationRelativeTo(null);
    }

    private JPanel buildConnectPanel() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createTitledBorder("Machine"));
        connState.setFont(connState.getFont().deriveFont(Font.BOLD));
        p.add(connState, BorderLayout.NORTH);
        deviceList.setVisibleRowCount(3);
        deviceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deviceList.setCellRenderer((list, v, i, sel, foc) -> {
            JLabel l = new JLabel(v.display());
            l.setOpaque(true);
            if (sel) { l.setBackground(list.getSelectionBackground()); l.setForeground(list.getSelectionForeground()); }
            l.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
            return l;
        });
        p.add(new JScrollPane(deviceList), BorderLayout.CENTER);
        p.add(connectBtn, BorderLayout.EAST);
        return p;
    }

    private JComponent buildCenter() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(loadFileBtn); top.add(fileLabel);
        p.add(top, BorderLayout.NORTH);
        preview.setBorder(BorderFactory.createTitledBorder("Sheet preview (to scale)"));
        p.add(preview, BorderLayout.CENTER);
        return p;
    }

    private JComponent buildSouth() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        startBtn.setFont(startBtn.getFont().deriveFont(Font.BOLD, 16f));
        buttons.add(startBtn); buttons.add(doneBtn);
        p.add(buttons, BorderLayout.NORTH);
        progress.setStringPainted(true);
        p.add(progress, BorderLayout.CENTER);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        p.add(new JScrollPane(log), BorderLayout.SOUTH);
        return p;
    }

    private void wireActions() {
        connectBtn.addActionListener(e -> { if (session != null) disconnect(null); else connect(); });
        loadFileBtn.addActionListener(e -> chooseFile());
        startBtn.addActionListener(e -> start());
        doneBtn.addActionListener(e -> done());
        deviceList.addListSelectionListener(e -> updateEnabled());
    }

    // --- discovery: refresh the machine list on a timer; the scan itself runs off the EDT ---
    private void startDiscovery() {
        new Timer(1500, e -> {
            if (connected != null || scanning) return;
            scanning = true;
            runBg(() -> {
                try {
                    List<Device> found = Discovery.scan();
                    ui(() -> applyScan(found));
                } finally { scanning = false; }
            });
        }).start();
    }

    /**
     * While connected and idle, poll the machine once a second: keeps the Unload button in step with the
     * mat (including one loaded with the machine's own Load button) and notices a machine that went away.
     */
    private void startStatePolling() {
        new Timer(1000, e -> {
            CutController c = controller;
            if (c == null || c.isRunning() || busy || polling) return;
            polling = true;
            runBg(() -> {
                Boolean m = null;
                try { m = c.matLoaded(); } catch (Exception ignored) {}
                final Boolean mat = m;
                ui(() -> {
                    polling = false;
                    if (c != controller) return;                 // disconnected meanwhile
                    if (mat == null) {
                        if (++missedPolls >= 3) disconnect("Lost contact with the machine.\n\n"
                                + "If its power light is red, switch it off and on again, then click Connect.");
                        return;
                    }
                    missedPolls = 0;
                    if (!mat.equals(matLoaded)) { matLoaded = mat; updateEnabled(); }
                });
            });
        }).start();
    }

    /** Drop the link (Disconnect button, or the machine went away). reason != null shows it to the user. */
    private void disconnect(String reason) {
        CutController c = controller;
        Session sess = session;
        controller = null; session = null; connected = null;
        matLoaded = null; missedPolls = 0; jobStatus = CutController.Status.IDLE;
        busy = true;
        connState.setText(reason != null ? "Disconnected — machine not responding" : "No machine connected");
        appendLog(reason != null ? "lost contact with the machine; disconnected" : "disconnected");
        progress.setValue(0); progress.setString("");
        updateEnabled();
        runBg(() -> {
            try { if (c != null) c.stop(); } catch (Exception ignored) {}
            try { if (sess != null) sess.close(); } catch (Exception ignored) {}
            ui(() -> { busy = false; updateEnabled(); if (reason != null) msg(reason); });
        });
    }

    private void applyScan(List<Device> found) {
        if (connected != null) return;
        Device sel = deviceList.getSelectedValue();
        deviceModel.clear();
        for (Device d : found) deviceModel.addElement(d);
        if (sel != null) for (int i = 0; i < deviceModel.size(); i++)
            if (deviceModel.get(i).address().equals(sel.address())) { deviceList.setSelectedIndex(i); break; }
        if (deviceList.getSelectedIndex() < 0 && !found.isEmpty()) deviceList.setSelectedIndex(0);
        updateEnabled();
    }

    private void connect() {
        Device d = deviceList.getSelectedValue();
        if (d == null) return;
        if (!d.supported()) { msg("That machine type isn't supported yet."); return; }
        ui(() -> { connState.setText("Connecting to " + d.display() + " …"); appendLog("connecting " + d.kind() + " " + d.address()); });
        runBg(() -> {
            Session sess = null;
            try {
                sess = d.isBluetooth()
                        ? Session.bluetooth(d.address(), Protocol.kekForType(d.machineType()))
                        : Session.usb(d.address(), Protocol.kekForType(d.machineType()));
                sess.handshake();
                byte[] status = sess.machineStatus();
                double[] pos = sess.position();
                this.session = sess; this.connected = d;
                this.controller = new CutController(sess, new UiListener());
                ui(() -> { connState.setText("Connected: " + d.display());
                           if (pos != null) appendLog(String.format("carriage at (%.3f, %.3f)", pos[0], pos[1]));
                           appendLog("connected, session ready; machine status " + Protocol.hex(status));
                           updateEnabled(); });
            } catch (Exception ex) {
                if (sess != null) try { sess.close(); } catch (Exception ignored) {}  // release the link so retry works
                final String m = ex.getMessage();
                ui(() -> { connState.setText("Connect failed"); appendLog("connect failed: " + m);
                           msg("Connect failed: " + m
                               + (d.isBluetooth() ? "\n\nMake sure the Cricut is powered on, then try Connect again." : "")); });
            }
        });
    }

    private void chooseFile() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PDF files", "pdf"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        runBg(() -> {
            try {
                CutImporter.Result r = CutImporter.importCuts(f.getAbsolutePath());
                this.job = r; this.jobFile = f;
                ui(() -> {
                    fileLabel.setText("<html>" + esc(f.getName()) + "  —  " + r.polylines.size() + " cuts, sheet "
                            + round(r.sheetWin) + "×" + round(r.sheetHin) + " in"
                            + (r.layer != null ? ", layer “" + esc(r.layer) + "”"
                               : "<br><b>No “" + CutImporter.DEFAULT_LAYER + "” layer — cutting everything on the page.</b>")
                            + "</html>");
                    preview.setJob(r);
                    appendLog("loaded " + f.getName() + ": " + r.polylines.size() + " cut paths, "
                            + r.pointCount() + " points"
                            + (r.layer != null ? " (layer '" + r.layer + "')"
                                               : " (no '" + CutImporter.DEFAULT_LAYER + "' layer; cutting all paths)"));
                    if (r.pageCount > 1) appendLog("note: PDF has " + r.pageCount + " pages; only page 1 is used");
                    updateEnabled();
                });
            } catch (Exception ex) {
                ui(() -> msg("Could not read cut geometry: " + ex.getMessage()));
            }
        });
    }

    private void start() {
        if (session == null || job == null || controller.isRunning()) return;
        startBtn.setEnabled(false);
        runBg(() -> {
            final int dial = controller.dial();          // serial round-trips: keep off the EDT
            ui(() -> {
                if (dial < 0) { msg("Could not read the material dial. Check the connection and try again."); startBtn.setEnabled(true); return; }
                boolean custom = (dial == 1);
                Integer pressure = promptMaterialPressure(custom, dial);
                if (pressure == null) { startBtn.setEnabled(true); return; }  // cancelled
                progress.setValue(0);
                final int p = pressure;
                final CutImporter.Result j = job;
                runBg(() -> {
                    try { controller.run(j.polylines, p); }
                    catch (Exception ex) { ui(() -> { msg("Cut error: " + ex.getMessage()); appendLog("ERROR " + ex); }); }
                    finally { ui(this::updateEnabled); }
                });
            });
        });
    }

    /** On Custom: pick substrate + Less/Default/More. On a dial material: Less/Default/More only. */
    private Integer promptMaterialPressure(boolean custom, int dial) {
        JPanel panel = new JPanel(new GridLayout(0, 2, 6, 8));
        JComboBox<String> substrate = new JComboBox<>(Presets.MATERIALS.keySet().toArray(new String[0]));
        JComboBox<Presets.Level> level = new JComboBox<>(Presets.Level.values());
        level.setSelectedItem(Presets.Level.DEFAULT);
        JLabel resolved = new JLabel();

        Runnable refresh = () -> {
            int p = custom
                ? Presets.pressure((String) substrate.getSelectedItem(), (Presets.Level) level.getSelectedItem())
                : Presets.dialPressure(dial, (Presets.Level) level.getSelectedItem());
            resolved.setText("pressure → " + p);
        };

        if (custom) {
            panel.add(new JLabel("Material:")); panel.add(substrate);
            substrate.addActionListener(e -> refresh.run());
        } else {
            panel.add(new JLabel("Material (dial):")); panel.add(new JLabel(Presets.dialLabel(dial)));
        }
        panel.add(new JLabel("Pressure:")); panel.add(level);
        panel.add(new JLabel("")); panel.add(resolved);
        level.addActionListener(e -> refresh.run());
        refresh.run();

        int ok = JOptionPane.showConfirmDialog(this, panel, "Ready to load — cut settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return null;
        return custom
            ? Presets.pressure((String) substrate.getSelectedItem(), (Presets.Level) level.getSelectedItem())
            : Presets.dialPressure(dial, (Presets.Level) level.getSelectedItem());
    }

    /**
     * "Unload" with a mat in, "Stop / Unload" during a cut, "Cancel" at the Load prompt: stops any job,
     * sends the head home, clears the lights and ejects the mat (if there is one).
     */
    private void done() {
        CutController c = controller;
        if (c == null) return;
        if (c.isRunning() && cutting()
                && JOptionPane.showConfirmDialog(this,
                        "Stop the cut?\n\nAnything not cut yet will be skipped, the head returns home and the mat ejects.",
                        "Stop cutting", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION)
            return;
        busy = true;
        updateEnabled();
        runBg(() -> {
            try { c.finish(); }
            catch (Exception ex) { ui(() -> appendLog("ERROR during unload: " + ex)); }
            finally { ui(() -> { busy = false; matLoaded = false; progress.setValue(0); progress.setString("");
                                 updateEnabled(); }); }
        });
    }

    /** Window close: stop any job, release the link (so the machine accepts the next connect), exit. */
    private void shutdown() {
        setVisible(false);
        Thread t = new Thread(() -> {
            try { if (controller != null) controller.stop(); } catch (Exception ignored) {}
            try { if (session != null) session.close(); } catch (Exception ignored) {}
            System.exit(0);
        }, "opencutter-shutdown");
        t.setDaemon(true);
        t.start();
        try { t.join(6000); } catch (InterruptedException ignored) {}
        System.exit(0);
    }

    private void updateEnabled() {
        boolean conn = session != null;
        boolean running = controller != null && controller.isRunning();
        connectBtn.setText(conn ? "Disconnect" : "Connect");
        connectBtn.setEnabled(!busy && (conn || deviceList.getSelectedValue() != null));
        loadFileBtn.setEnabled(conn);
        startBtn.setEnabled(conn && !busy && job != null && !running);
        if (running && jobStatus == CutController.Status.WAITING_LOAD) {
            doneBtn.setText("Cancel");                 // nothing loaded yet: just back out
            doneBtn.setEnabled(!busy);
        } else if (running && cutting()) {
            doneBtn.setText("Stop");                   // emergency stop only (confirmed); Unload comes after Done
            doneBtn.setEnabled(!busy);
        } else if (running) {
            doneBtn.setText("Stop / Unload");          // mat in, waiting for Go: nothing cut yet
            doneBtn.setEnabled(!busy);
        } else {
            doneBtn.setText("Unload");                 // only with a mat actually in the machine
            doneBtn.setEnabled(conn && !busy && Boolean.TRUE.equals(matLoaded));
        }
    }

    private final class UiListener implements CutController.Listener {
        public void onStatus(CutController.Status s) {
            jobStatus = s;
            ui(() -> { appendLog("[" + s + "]");
                switch (s) {
                    case WAITING_LOAD -> progress.setString("Load the mat (Load is blinking)");
                    case WAITING_GO -> { matLoaded = true; progress.setString("Press Go on the machine"); }
                    case CUTTING -> progress.setString("Cutting…");
                    // every frame is sent, but the machine is still cutting what it has queued
                    case FINISHING -> progress.setString("Cutting the last lines — wait for Done…");
                    case DONE -> progress.setString("Done — click Unload to eject the mat");
                    case STOPPED -> progress.setString("Stopped");
                    default -> {}
                }
                updateEnabled();
            });
        }
        public void onProgress(int done, int total) {
            ui(() -> { progress.setMaximum(total); progress.setValue(done);
                progress.setString("Cutting " + done + "/" + total); });
        }
        public void onLog(String line) { ui(() -> appendLog(line)); }
    }

    private void appendLog(String s) { log.append(s + "\n"); log.setCaretPosition(log.getDocument().getLength()); }
    private void msg(String s) { JOptionPane.showMessageDialog(this, s); }
    private void ui(Runnable r) { SwingUtilities.invokeLater(r); }
    private void runBg(Runnable r) { Thread t = new Thread(r, "opencutter-bg"); t.setDaemon(true); t.start(); }
    private static String round(double v) { return String.valueOf(Math.round(v * 100) / 100.0); }
    /** The blade is (or may still be) moving: the cut proper, or the queued last lines. */
    private boolean cutting() {
        return jobStatus == CutController.Status.CUTTING || jobStatus == CutController.Status.FINISHING;
    }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    public static void main(String[] args) {
        prepareNativeDir();
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new App().setVisible(true));
    }

    /**
     * jSerialComm 2.11.0 mis-detects the native arch on some 64-bit Intel boxes (it grabs the ARM64
     * dll). Rather than rely on its detection, we extract the correct Windows x86_64 dll from our own
     * jar and point jSerialComm straight at it via jSerialComm.library.path. Done before SerialPort loads.
     */
    private static void prepareNativeDir() {
        try {
            File base = new File(System.getProperty("user.home"), ".opencutter/native");
            base.mkdirs();
            String res = pickNativeResource();
            if (res != null) {
                File dll = new File(base, nativeFileName());
                extractResource(res, dll);
                System.setProperty("jSerialComm.library.path", base.getAbsolutePath());
            }
            // Belt-and-suspenders: unique temp + randomized name so any fallback can't hit a locked file.
            System.setProperty("jSerialComm.library.randomizeNativeName", "true");
        } catch (Throwable ignored) {}
    }

    /** Resource path of the native lib for this OS+arch inside the jSerialComm jar (bundled in ours). */
    private static String pickNativeResource() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String a = (arch.contains("aarch") || arch.contains("arm64")) ? "aarch64"
                 : (arch.contains("64")) ? "x86_64"
                 : "x86";
        if (os.contains("win")) return "/Windows/" + a + "/jSerialComm.dll";
        if (os.contains("mac") || os.contains("darwin")) return "/OSX/" + a + "/libjSerialComm.dylib";
        if (os.contains("nux")) return "/Linux/" + a + "/libjSerialComm.so";
        return null;
    }

    private static String nativeFileName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "jSerialComm.dll";
        if (os.contains("mac")) return "libjSerialComm.dylib";
        return "libjSerialComm.so";
    }

    private static void extractResource(String res, File dest) throws Exception {
        try (var in = App.class.getResourceAsStream(res)) {
            if (in == null) return;
            dest.setWritable(true);
            java.nio.file.Files.copy(in, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

}
