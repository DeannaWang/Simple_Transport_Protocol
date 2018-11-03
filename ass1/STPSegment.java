package ass1;

import java.io.*;
import java.net.DatagramPacket;
import java.net.InetAddress;

public class STPSegment implements Serializable {

    // Packet types
    public static final short ACK = 1;
    public static final short SYN = 1 << 1;
    public static final short FIN = 1 << 2;
    // DATA type is not recorded in the header, so it can be an integer. For logging use only.
    public static final int DATA = 1 << 3;

    // Events (not put in header; for logging use only)
    public static final int SND = 1 << 4;
    public static final int RCV = 1 << 5;
    public static final int DROP = 1 << 6;
    public static final int CORR = 1 << 7;
    public static final int DUP = 1 << 8;
    public static final int RORD = 1 << 9;
    public static final int DELY = 1 << 10;
    public static final int DA = 1 << 11;
    public static final int RXT = 1 << 12;
    public static final int TIMEOUT = 1 << 13;

    // Other Constants
    public static final int NONCORR = -1;           // Not corrupt
    public static final int HEADER_SIZE = 24;       // Bytes

    // Header
    private int seq = 0;
    private int ack = 0;
    private short flag = 0;
    private short checksum = 0;
    private int len = 0;
    private long timestamp = 0;

    // Data
    private byte[] data = null;

    private byte[] getSegmentByteArray () throws Exception {
        // Generate byte array
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
        dataOutputStream.writeInt(this.seq);
        dataOutputStream.writeInt(this.ack);
        dataOutputStream.writeShort(this.flag);
        dataOutputStream.writeShort(this.checksum);
        dataOutputStream.writeInt(this.len);
        dataOutputStream.writeLong(this.timestamp);
        dataOutputStream.write(this.data);
        dataOutputStream.flush();
        byte[] segmentByteArray = byteArrayOutputStream.toByteArray();
        dataOutputStream.close();
        byteArrayOutputStream.close();
        return segmentByteArray;
    }

    // Constructors
    public STPSegment (int newSeq, int newAck, short newFlag) {
        setSeq(newSeq);
        setAck(newAck);
        setFlag(newFlag);
        setData(new byte[0]);
    }

    public STPSegment (int newSeq, int newAck, short newFlag, byte[] newData) {
        this(newSeq, newAck, newFlag);
        setData(newData);
    }

    public STPSegment (int newSeq, int newAck, short newFlag, byte[] newData, long newTimestamp) {
        this(newSeq, newAck, newFlag, newData);
        setTimestamp(newTimestamp);
    }

    private STPSegment (int newSeq, int newAck, short newFlag, short newChecksum, byte[] newData, long newTimestamp) {
        this(newSeq, newAck, newFlag, newData, newTimestamp);
        checksum = newChecksum;
    }

    // Set and get functions
    public void setSeq (int newSeq) {
        seq = newSeq;
    }

    public int getSeq () {
        return seq;
    }

    public void setAck (int newAck) {
        ack = newAck;
    }

    public int getAck () {
        return ack;
    }

    public void setFlag (short newFlag) {
        flag = newFlag;
    }

    public short getFlag () {
        return flag;
    }

    public void setTimestamp (long newTimestamp) {
        timestamp = newTimestamp;
    }

    public long getTimestamp () {
        return timestamp;
    }

    public void setData (byte[] newData) {
        data = newData;
        len = data.length;
    }

    public byte[] getData () {
        return data;
    }

    // Checksum
    private short getChecksum () throws Exception {

        byte[] segByteArray = getSegmentByteArray();

        // Add up 16-bit words
        int cs = 0;
        for (int i = 0; i < segByteArray.length; i += 2) {
            int cmp = (segByteArray[i] * 0x100) & 0xff00;
            if (i < segByteArray.length - 1) {
                cmp += (int)segByteArray[i + 1] & 0xff;
            }
            cs += cmp;
            cs += cs / 0xffff;
            cs &= 0xffff;
        }
        return (short)(~cs & 0xffff);
    }

    public void updateChecksum() throws Exception {
        checksum = 0;
        checksum = getChecksum();
    }

    public boolean verifyChecksum () throws Exception {
        return (int)getChecksum() == 0xffffffff;
    }

    // Interface to transform from / to DatagramPacket object
    public static DatagramPacket toPacket (STPSegment seg, InetAddress host, int port) throws Exception {
        return toPacket(seg, host, port, NONCORR);
    }

    public static DatagramPacket toPacket (STPSegment seg, InetAddress host, int port, int corrupt) throws Exception {
        seg.updateChecksum();
        if (corrupt > NONCORR && corrupt < seg.data.length * 8) {
            if ((seg.data[corrupt / 8] & (1 << (corrupt % 8))) != 0) {
                seg.data[corrupt / 8] &= ~(1 << (corrupt % 8));
            }
            else {
                seg.data[corrupt / 8] |= 1 << (corrupt % 8);
            }
        }
        byte[] segByteArray = seg.getSegmentByteArray();
        return new DatagramPacket(segByteArray, segByteArray.length, host, port);
    }

    public static STPSegment fromPacket (DatagramPacket p) throws Exception {
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(p.getData());
        DataInputStream dataInputStream = new DataInputStream(byteArrayInputStream);
        int seq = dataInputStream.readInt();
        int ack = dataInputStream.readInt();
        short flag = dataInputStream.readShort();
        short checksum = dataInputStream.readShort();
        int len = dataInputStream.readInt();
        long timestamp = dataInputStream.readLong();
        byte[] data = new byte[len];
        dataInputStream.read(data);
        dataInputStream.close();
        byteArrayInputStream.close();
        return new STPSegment(seq, ack, flag, checksum, data, timestamp);
    }
}