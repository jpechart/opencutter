package opencutter.protocol;

/** A byte transport to the machine: USB serial (jSerialComm) or Bluetooth RFCOMM (Winsock/JNA). */
public interface Link extends AutoCloseable {
    void write(byte[] data);
    /** Read until the link goes quiet (nothing for the read gap) or maxMillis elapse. */
    byte[] readReply(long maxMillis);
    /** Read until at least n bytes arrive or maxMillis elapse. */
    byte[] readAtLeast(int n, long maxMillis);
    /** Discard any buffered input. */
    void drain();
    @Override void close();
}
