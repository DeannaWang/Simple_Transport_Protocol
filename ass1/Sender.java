package ass1;

import java.io.*;
import java.net.*;
import java.util.*;

// Receiving thread
class ReceiveThread implements Runnable {
    public void run () {
        while (!Sender.finishedReceivingData()) {
            try {
                Sender.receiveDataAck();
            } catch (Exception e) {
                System.out.println(e.getMessage());
                System.exit(1);
            }
        }
        Sender.notify_receive_finished();
    }
}

// Timeout timer task and delay timer task
class SendTask extends TimerTask {

    private STPSegment segment = null;
    private boolean delay = false;
    private int event = 0;

    // Constructors
    public SendTask (STPSegment seg) {
        setSegment(seg);
        setEvent(0);
        setDelay(false);
    }

    public SendTask (STPSegment seg, int event) {
        this(seg);
        setEvent(event);
        if ((event & STPSegment.DELY) != 0) setDelay(true);
    }

    // Get and set functions
    public void setSegment (STPSegment seg) {
        segment = seg;
    }

    public void setEvent (int newEvent) {
        event = newEvent;
    }

    public void setDelay (boolean dly) {
        delay = dly;
    }

    // Run
    public void run () {
        try {
            // Delayed
            if (delay) {
                Sender.send(segment.getSeq(), segment.getAck(), segment.getFlag(),
                        segment.getData(), segment.getTimestamp(),event | STPSegment.DELY);
            }
            // Timeout
            else {
                Sender.sendThroughPLD(segment.getSeq(), segment.getAck(), segment.getFlag(),
                        segment.getData(), event | STPSegment.TIMEOUT | STPSegment.RXT);
            }
        }
        catch (Exception e) {
            System.out.println(e.getMessage());
            System.exit(1);
        }
    }
}

public class Sender {
    private static DatagramSocket socket = null;
    private static STPLogger logger = null;
    private static int sent = 0;                                        // ACK expected for the newest segment sent
    private static int acked = 0;                                       // Newest ACK received from server
    private static int ack = 0;                                         // Next ACK to send to server
    private static boolean finished = false;                            // Finish reading the file
    private static DatagramPacket rcvPacket = null;
    private static STPSegment rcvSegment = null;
    private static STPSegment sndSegment = null;
    private static STPSegment rordSegment = null;
    private static int rordEvent = 0;
    private static int dupAckCount = 0;
    private static int rordCount = 0;
    private static HashMap<Integer, STPSegment> unacknowledged = null;  // seq -> segment
    private static InetAddress serverHost = null;
    private static int serverPort = 0;

    private static Timer timeOutTimer = null;
    private static SendTask timeOutTask = null;
    private static Timer delayTimer = null;
    private static int estimatedRTT = 500;
    private static int devRTT = 250;

    private static final Object dataLock = new Object();
    private static final Object receiveFinishedLock = new Object();

    private static double gamma = 0;
    private static double pDrop = 0;
    private static double pDuplicate = 0;
    private static double pCorrupt = 0;
    private static double pOrder = 0;
    private static int maxOrder = 0;
    private static double pDelay = 0;
    private static int maxDelay = 0;
    private static Random random = null;

    // Initializing
    private static void init () throws Exception {
        socket = new DatagramSocket();
        rcvPacket = new DatagramPacket(new byte[STPSegment.HEADER_SIZE], STPSegment.HEADER_SIZE);
        unacknowledged = new HashMap<Integer, STPSegment>();
        delayTimer = new Timer ();
        logger = new STPLogger("Sender_log.txt");
    }

    // PLD Module
    private static STPSegment sendFake (int seq, int ack, short flag, byte[] data) {
        sndSegment = new STPSegment(seq, ack, flag, data);
        storeSegment(sndSegment);
        sent = sent > (seq + data.length) ? sent : (seq + data.length);
        return sndSegment;
    }

    private static void sendThroughPLD (int seq, int ack, short flag, byte[] data) throws Exception {
        sendThroughPLD(seq, ack, flag, data, 0);
    }

    public static void sendThroughPLD (int seq, int ack, short flag, byte[] data, int event) throws Exception {
        synchronized (dataLock) {
            if (finishedReceivingData()) {
                return;
            }
            long timestamp = (event & STPSegment.RXT) != 0 ? 0 : new Date().getTime();
            float p = random.nextFloat();
            if (p < pDrop) {
                sndSegment = sendFake(seq, ack, flag, data);
                event &= ~STPSegment.SND;
                logger.log(sndSegment, event | STPSegment.DROP);
                checkReorder();
                return;
            }
            p = random.nextFloat();
            if (p < pDuplicate) {
                sndSegment = send(seq, ack, flag, data, timestamp, event | STPSegment.SND);
                sndSegment = send(seq, ack, flag, data, timestamp, event | STPSegment.SND | STPSegment.DUP);
                return;
            }
            p = random.nextFloat();
            if (p < pCorrupt) {
                sndSegment = send(seq, ack, flag, data, timestamp, random.nextInt(data.length * 8),
                        event | STPSegment.SND | STPSegment.CORR);
                return;
            }
            p = random.nextFloat();
            if (p < pOrder) {
                if (rordSegment == null) {
                    rordSegment = new STPSegment(seq, ack, flag, data, timestamp);
                    storeSegment(rordSegment);
                    sent = sent > (seq + data.length) ? sent : (seq + data.length);
                    rordEvent = event | STPSegment.SND | STPSegment.RORD;
                } else {
                    sndSegment = send(seq, ack, flag, data, timestamp, event | STPSegment.SND);
                }
                return;
            }
            p = random.nextFloat();
            if (p < pDelay) {
                sndSegment = sendFake(seq, ack, flag, data);
                sndSegment.setTimestamp(timestamp);
                SendTask delayTask = new SendTask(sndSegment, event | STPSegment.SND | STPSegment.DELY);
                delayTimer.schedule(delayTask, random.nextInt(maxDelay + 1));
                return;
            }
            sndSegment = send(seq, ack, flag, data, timestamp, event | STPSegment.SND);
        }
    }

    // Send segment
    private static STPSegment send (int seq, int ack, short flag) throws Exception {
        return send(seq, ack, flag, null, 0);
    }

    private static STPSegment send (int seq, int ack, short flag, byte[] data, int event) throws Exception {
        return send(seq, ack, flag, data, 0, event);
    }

    public static STPSegment send (int seq, int ack, short flag, byte[] data, long timestamp, int event) throws Exception {
        return send(seq, ack, flag, data, timestamp, STPSegment.NONCORR, event);
    }

    private static STPSegment send (int seq, int ack, short flag, byte[] data, long timestamp, int corrupt, int event) throws Exception {
        // Segment without data
        if (data == null || data.length == 0) {
            sndSegment = new STPSegment(seq, ack, flag);
            socket.send(STPSegment.toPacket(sndSegment, serverHost, serverPort));
            sent = sent > seq ? sent : seq;
            logger.log(sndSegment, STPSegment.SND);
        }
        // Data segment
        else {
            synchronized (dataLock) {
                sndSegment = new STPSegment(seq, ack, flag, data, timestamp);
                storeSegment(sndSegment);
                socket.send(STPSegment.toPacket(new STPSegment(seq, ack, flag, data.clone(), timestamp),
                        serverHost, serverPort, corrupt));
                sent = sent > (seq + data.length) ? sent : (seq + data.length);
                logger.log(sndSegment, event);
                checkReorder();
            }
        }
        return sndSegment;
    }

    // Receive data segment
    public static void receiveDataAck () throws Exception {
        socket.receive(rcvPacket);
        synchronized (dataLock) {
            rcvSegment = STPSegment.fromPacket(rcvPacket);

            // Calculate RTT
            if (rcvSegment.getTimestamp() != 0) {
                int sampleRTT = (int) (new Date().getTime() - rcvSegment.getTimestamp());
                estimatedRTT = (int) ((1 - 0.125) * estimatedRTT + 0.125 * sampleRTT);
                devRTT = (int) ((1 - 0.25) * devRTT + 0.25 * Math.abs(estimatedRTT - sampleRTT));
            }

            int newAck = rcvSegment.getSeq() + rcvSegment.getData().length;
            ack = ack > newAck ? ack : newAck;

            // New acks
            if (rcvSegment.getAck() > acked) {
                dupAckCount = 0;
                while (rcvSegment.getAck() > acked) {
                    STPSegment oldestSegment = unacknowledged.get(acked);
                    acked += oldestSegment.getData().length;
                    unacknowledged.remove(oldestSegment.getSeq());
                }
                if (timeOutTimer != null) {
                    timeOutTimer.cancel();
                    timeOutTimer = null;
                }
                if (!unacknowledged.isEmpty() && !finishedReceivingData()) {
                    timeOutTimer = new Timer();
                    timeOutTask = new SendTask(unacknowledged.get(acked));
                    timeOutTimer.schedule(timeOutTask, timeOutInterval(), timeOutInterval());
                }
                else if (finishedReceivingData()) {
                    delayTimer.cancel();
                }
                logger.log(rcvSegment, STPSegment.RCV);
                dataLock.notifyAll();
            }

            // Duplicated acks
            else if (rcvSegment.getAck() == acked) {
                logger.log(rcvSegment, STPSegment.RCV | STPSegment.DA);
                if (!unacknowledged.containsKey(acked)) {
                    dupAckCount = 0;
                }
                // Fast retransmission
                else if (++dupAckCount == 3) {
                    dupAckCount = 0;
                    STPSegment oldestSegment = unacknowledged.get(acked);
                    sendThroughPLD(oldestSegment.getSeq(), ack, oldestSegment.getFlag(),
                            oldestSegment.getData(), STPSegment.RXT);
                }
            }
        }
    }

    // All data received (let the timer know)
    public static boolean finishedReceivingData () {
        return finished && (sent == acked);
    }

    // Finish waiting out the receiving thread
    public static void notify_receive_finished () {
        synchronized (receiveFinishedLock) {
            receiveFinishedLock.notifyAll();
        }
    }

    // Check if reorder waiting is finished
    private static void checkReorder () throws Exception {
        if (rordSegment != null && ++rordCount >= maxOrder) {
            socket.send(STPSegment.toPacket(rordSegment, serverHost, serverPort));
            logger.log(rordSegment, rordEvent);
            rordSegment = null;
            rordCount = 0;
            rordEvent = 0;
        }
    }

    // Store segments which are sent but not acknowledged
    private static void storeSegment (STPSegment seg) {
        if (unacknowledged.isEmpty() && acked == seg.getSeq()) {
            if (timeOutTimer != null) {
                timeOutTimer.cancel();
            }
            timeOutTimer = new Timer();
            timeOutTask = new SendTask(seg);
            timeOutTimer.schedule(timeOutTask, timeOutInterval(), timeOutInterval());
        }
        if (!unacknowledged.containsKey(seg.getSeq()) && seg.getSeq() >= acked) {
            unacknowledged.put(seg.getSeq(), seg);
        }
    }

    // Calculate timeout interval
    private static int timeOutInterval () {
        return (int)(estimatedRTT + gamma * devRTT) + 2;
    }

    public static void main(String[] args) throws Exception {

        // Check input
        if (args.length != 14) {
            System.out.println("Usage: java ass1.Sender receiver_host_ip receiver_port filename MWS MSS gamma pDrop" +
                    " pDuplicate pCorrupt pOrder maxOrder pDelay maxDelay seed");
            return;
        }

        String filename = null;
        int MSS = 0;
        int MWS = 0;

        try {
            serverHost = InetAddress.getByName(args[0]);
            serverPort = Integer.parseInt(args[1]);

            filename = args[2];
            MWS = Integer.parseInt(args[3]);
            MSS = Integer.parseInt(args[4]);
            gamma = Double.parseDouble(args[5]);
            pDrop = Double.parseDouble(args[6]);
            pDuplicate = Double.parseDouble(args[7]);
            pCorrupt = Double.parseDouble(args[8]);
            pOrder = Double.parseDouble(args[9]);
            maxOrder = Integer.parseInt(args[10]);
            pDelay = Double.parseDouble(args[11]);
            maxDelay = Integer.parseInt(args[12]);
            int seed = Integer.parseInt(args[13]);
            random = new Random(seed);
            if (pOrder != 0 && (maxOrder < 1 || maxOrder > 6)) {
                System.out.println("Invalid input!");
                System.exit(1);
            }
        }
        catch (Exception e) {
            System.out.println("Invalid input!");
            System.exit(1);
        }

        // Initializing
        init();
        logger.logHeader(args);

        // Send SYN
        send(sent, 0, STPSegment.SYN);
        sent += 1;

        // Receive SYNACK
        socket.receive(rcvPacket);
        rcvSegment = STPSegment.fromPacket(rcvPacket);
        ack = rcvSegment.getSeq() + 1;
        acked = rcvSegment.getAck();
        logger.log(rcvSegment, STPSegment.RCV);

        // Send ACK
        send(sent, ack, STPSegment.ACK);

        FileInputStream fileInputStream = new FileInputStream(filename);
        DataInputStream dataInputStream = new DataInputStream(fileInputStream);

        // Start receiving thread
        Thread rcvThread = new Thread(new ReceiveThread());
        rcvThread.start();

        // Reading file not finished
        while (!finished) {
            // How many data can be sent
            int gap = 0;
            synchronized (dataLock) {
                gap = MWS - sent + acked;
                if (gap == 0) {
                    dataLock.wait();
                }
            }
            while (gap > 0 && !finished) {
                int tosend = gap >= MSS ? MSS : gap;
                byte[] data = new byte[tosend];

                // Read some content
                if ((tosend = dataInputStream.read(data, 0, tosend)) > 0) {
                    if (tosend < data.length) {
                        byte[] tmp = data;
                        data = new byte[tosend];
                        System.arraycopy(tmp, 0, data, 0, tosend);
                    }
                    sendThroughPLD(sent, ack, (short) 0, data);
                    gap = MWS - sent + acked;
                }
                // Reach the end of file
                else {
                    finished = true;
                }
            }
        }

        // Wait until receiving is finished
        synchronized (receiveFinishedLock) {
            receiveFinishedLock.wait();
        }

        // Send FIN
        send(acked, ack, STPSegment.FIN);
        sent += 1;

        // Receive ACK in response of FIN
        while (sent != rcvSegment.getAck()) {
            socket.receive(rcvPacket);
            rcvSegment = STPSegment.fromPacket(rcvPacket);
            logger.log(rcvSegment, STPSegment.RCV);
        }
        acked = rcvSegment.getAck();

        // Receive FIN from server
        int flag = 0;
        while ((flag & STPSegment.FIN) == 0) {
            socket.receive(rcvPacket);
            rcvSegment = STPSegment.fromPacket(rcvPacket);
            logger.log(rcvSegment, STPSegment.RCV);
            flag = rcvSegment.getFlag();
        }
        acked = rcvSegment.getAck();
        ack += 1;

        // Send ACK in response of FIN
        send(acked, ack, STPSegment.ACK);

        // Write statistic result
        logger.log();
        logger.log("Size of the file (in Bytes): " + (acked - 2));
        logger.log("Segments transmitted (including drop & RXT): " +
                (logger.getStat(STPSegment.SND) + logger.getStat(STPSegment.DROP)));
        logger.log("Number of Segments handled by PLD: " + logger.getStat(STPSegment.DATA));
        logger.log("Number of Segments Dropped: " + logger.getStat(STPSegment.DROP));
        logger.log("Number of Segments Corrupted: " + logger.getStat(STPSegment.CORR));
        logger.log("Number of Segments Re-ordered: " + logger.getStat(STPSegment.RORD));
        logger.log("Number of Segments Duplicated: " + logger.getStat(STPSegment.DUP));
        logger.log("Number of Segments Delayed: " + logger.getStat(STPSegment.DELY));
        logger.log("Number of Retransmissions due to timeout: " + logger.getStat(STPSegment.TIMEOUT));
        logger.log("Number of Fast Retransmissions: " +
                (logger.getStat(STPSegment.RXT) - logger.getStat(STPSegment.TIMEOUT)));
        logger.log("Number of Duplicate Acknowledgements received: " + logger.getStat(STPSegment.DA));
        logger.log();

        socket.close();
        dataInputStream.close();
        fileInputStream.close();
        logger.close();
    }
}
