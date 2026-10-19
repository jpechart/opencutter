package opencutter.device;

import com.fazecast.jSerialComm.SerialPort;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds Cricut machines.
 *  - USB CDC: jSerialComm ports carrying Cricut's vendor id (0x20D3). Connect over that COM port.
 *  - Bluetooth: paired Cricut devices from Windows PnP, identified by MAC. We connect by MAC over an
 *    RFCOMM socket (channel 6), so this works even though the outgoing COM port is an idle phantom.
 * No port is opened during discovery. The Bluetooth lookup is cached briefly.
 */
public final class Discovery {
    private Discovery() {}

    private static final int CRICUT_VID = 0x20D3;
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    // PowerShell prints "FriendlyName|InstanceId", e.g. "CricutAIR2-6D7A|BTHENUM\\...DEV_E8EB1B776D7A..." -> name, mac
    private static final Pattern MAC = Pattern.compile("DEV_([0-9A-Fa-f]{12})");

    /** The only machine type we hold a key for (Explore Air 2). Anything else is best-effort. */
    public static final int DEFAULT_MACHINE_TYPE = 10;

    private static volatile long btCacheTime = 0;
    private static volatile List<Device> btCache = List.of();

    public static List<Device> scan() {
        Map<String, Device> out = new LinkedHashMap<>();

        for (SerialPort p : SerialPort.getCommPorts()) {
            boolean usb = false;
            try { usb = p.getVendorID() == CRICUT_VID; } catch (Throwable ignored) {}
            String desc = (safe(p.getPortDescription()) + " " + safe(p.getDescriptivePortName())).toLowerCase();
            if (!usb) usb = desc.contains("cricut") || desc.contains("20d3");
            if (usb) {
                String com = p.getSystemPortName();
                out.put("USB:" + com, new Device("USB", com, "Cricut (USB)", DEFAULT_MACHINE_TYPE, true));
            }
        }
        for (Device d : bluetoothCricuts()) out.putIfAbsent("BT:" + d.address(), d);
        return new ArrayList<>(out.values());
    }

    /** Paired Cricut Bluetooth devices (name + MAC) via a cached Windows PnP query. */
    private static List<Device> bluetoothCricuts() {
        if (!WINDOWS) return List.of();
        if (System.currentTimeMillis() - btCacheTime < 3000) return btCache;
        btCacheTime = System.currentTimeMillis();
        List<Device> found = new ArrayList<>();
        try {
            // One line per device: "<FriendlyName>|<InstanceId>" (InstanceId carries DEV_<mac>).
            String ps = "Get-PnpDevice -Class Bluetooth -ErrorAction SilentlyContinue | "
                      + "Where-Object { $_.FriendlyName -like 'Cricut*' } | "
                      + "ForEach-Object { $_.FriendlyName + '|' + $_.InstanceId }";
            Process proc = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", ps)
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Matcher m = MAC.matcher(line);
                    if (!m.find()) continue;
                    int bar = line.indexOf('|');
                    String friendly = bar > 0 ? line.substring(0, bar).trim() : "Cricut";
                    found.add(new Device("BT", formatMac(m.group(1)), friendly + " (Bluetooth)",
                            DEFAULT_MACHINE_TYPE, true));
                }
            }
            proc.waitFor();
        } catch (Throwable ignored) {}
        btCache = found;
        return found;
    }

    private static String formatMac(String hex12) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) { if (i > 0) sb.append(':'); sb.append(hex12, i, i + 2); }
        return sb.toString().toUpperCase();
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
