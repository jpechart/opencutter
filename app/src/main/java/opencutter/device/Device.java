package opencutter.device;

/**
 * A discovered machine. {@code kind} is "USB" (address = COM port) or "BT" (address = Bluetooth MAC).
 */
public record Device(String kind, String address, String name, int machineType, boolean supported) {
    public boolean isBluetooth() { return "BT".equals(kind); }

    public String display() {
        String where = isBluetooth() ? "Bluetooth" : address;
        return name + "  [" + where + "]" + (supported ? "" : "  (unsupported)");
    }
}
