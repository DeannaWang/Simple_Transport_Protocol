package ass1;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.net.*;
import java.util.*;

public class Receiver {

    private static DatagramSocket socket = null;
    private static HashMap<Integer, STPSegment> buffer = new HashMap<Integer, STPSegment>(); // seq -> rcvSegment
    private static int cmltAck = 0;
    private static int sent = 0;
    private static STPSegment rcvSegment = null;
    private static STPSegment sndSegment = null;
    private static InetAddress clientHost = null;
    private static int clientPort = 0;

    private static void send (short flag) throws Exception {
        send(flag, 0);
    }

    private static void send (short flag, long timestamp) throws Exception {
        sent = sent > rcvSegment.getAck() ? sent : rcvSegment.getAck();
        sndSegment.setSeq(sent);
        sndSegment.setAck(cmltAck);
        sndSegment.setFlag(flag);
        sndSegment.setTimestamp(timestamp);
        socket.send(STPSegment.toPacket(sndSegment, clientHost, clientPort));
    }

    private static int corr (STPSegment seg) throws Exception {
        if (!seg.verifyChecksum()) {
            return STPSegment.CORR;
        }
        return 0;
    }

    public static void main(String[] args) throws Exception {

        // Check input
        if (args.length != 2) {
            System.out.println("Usage: java ass1.Receiver receiver_port filename");
            return;
        }

        int port = 80;
        String filename = null;

        try {
            port = Integer.parseInt(args[0]);
            filename = args[1];
        }
        catch (Exception e) {
            System.out.println("Invalid input!");
            System.exit(1);
        }

        // Create a datagram socket
        socket = new DatagramSocket(port);

        STPLogger logger = new STPLogger("Receiver_log.txt");
        logger.logHeader(args);

        // Initialize the connection
        DatagramPacket rcvPacket = new DatagramPacket(new byte[STPSegment.HEADER_SIZE], STPSegment.HEADER_SIZE);
        socket.receive(rcvPacket);

        clientHost = rcvPacket.getAddress();
        clientPort = rcvPacket.getPort();

        // Receive SYN
        rcvSegment = STPSegment.fromPacket(rcvPacket);
        logger.log(rcvSegment, STPSegment.RCV | corr(rcvSegment));

        cmltAck = rcvSegment.getSeq() + 1;

        // Send SYNACK
        sndSegment = new STPSegment(0, cmltAck, (short)(STPSegment.ACK | STPSegment.SYN));
        socket.send(STPSegment.toPacket(sndSegment, clientHost, clientPort));
        logger.log(sndSegment, STPSegment.SND);

        // Receive ACK
        socket.receive(rcvPacket);
        rcvSegment = STPSegment.fromPacket(rcvPacket);
        logger.log(rcvSegment, STPSegment.RCV | corr(rcvSegment));

        FileOutputStream fileOutputStream = new FileOutputStream(filename);
        DataOutputStream dataOutputStream = new DataOutputStream(fileOutputStream);

        // Processing loop
        while (true) {
            rcvPacket = new DatagramPacket(new byte[4096], 4096);
            socket.receive(rcvPacket);
            STPSegment tmpSegment = STPSegment.fromPacket(rcvPacket);

            if (!tmpSegment.verifyChecksum()) {
                logger.log(tmpSegment, STPSegment.RCV | STPSegment.CORR);
                continue;
            }
            if (tmpSegment.getSeq() < cmltAck) {
                logger.log(tmpSegment, STPSegment.RCV | STPSegment.DUP);
                send(STPSegment.ACK, tmpSegment.getTimestamp());
                logger.log(sndSegment, STPSegment.SND | STPSegment.DA);
                continue;
            }
            rcvSegment = tmpSegment;
            logger.log(rcvSegment, STPSegment.RCV);

            // Receive finished
            if ((rcvSegment.getFlag() & STPSegment.FIN) != 0) {

                // Receive and send FIN
                cmltAck += 1;
                send(STPSegment.ACK);
                logger.log(sndSegment, STPSegment.SND);
                send(STPSegment.FIN);
                logger.log(sndSegment, STPSegment.SND);
                socket.receive(rcvPacket);
                rcvSegment = STPSegment.fromPacket(rcvPacket);
                logger.log(rcvSegment, STPSegment.RCV | corr(rcvSegment));

                // Write statistic result
                logger.log();
                logger.log("Amount of Data Received (bytes): " + logger.getStat(STPSegment.RCV | STPSegment.DATA));
                logger.log("Total segments received: " + logger.getStat(STPSegment.RCV));
                logger.log("Data Segments with bit errors: " + logger.getStat(STPSegment.CORR));
                logger.log("Duplicate data segments received: " + logger.getStat(STPSegment.DUP));
                logger.log("Duplicate Acks sent: " + logger.getStat(STPSegment.DA));
                logger.log();
                socket.close();
                dataOutputStream.close();
                fileOutputStream.close();
                logger.close();
                break;
            }
            // New packet to acknowledge
            else if (rcvSegment.getSeq() == cmltAck) {
                long timestamp = rcvSegment.getTimestamp();
                cmltAck += rcvSegment.getData().length;
                dataOutputStream.write(rcvSegment.getData());
                while (buffer.containsKey(cmltAck)) {
                    rcvSegment = buffer.get(cmltAck);
                    buffer.remove(cmltAck);
                    cmltAck += rcvSegment.getData().length;
                    dataOutputStream.write(rcvSegment.getData());
                }
                send(STPSegment.ACK, timestamp);
                logger.log(sndSegment, STPSegment.SND);
            }
            // Packet not in order
            else {
                if (!buffer.containsKey(rcvSegment.getSeq()) && rcvSegment.getSeq() > cmltAck) {
                    buffer.put(rcvSegment.getSeq(), rcvSegment);
                }
                send(STPSegment.ACK, rcvSegment.getTimestamp());
                logger.log(sndSegment, STPSegment.SND | STPSegment.DA);
            }
        }
    }
}
