package ass1;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

public class STPLogger {
    private FileWriter fileWriter = null;
    private PrintWriter printWriter = null;
    private long startTime = 0;
    private HashMap<Integer, Integer> statistics; // event -> occurrence
    public int test_TIMEOUT = 0;

    // Get statistic result
    public int getStat (int tag) {
        if (statistics.containsKey(tag)) {
            return statistics.get(tag);
        }
        return 0;
    }

    // Join tags with sep
    private static String join (String sep, ArrayList <String> tags) {
        if (tags.size() == 0) {
            return "";
        }
        StringBuilder strBuilder = new StringBuilder();
        strBuilder.append(tags.get(0));
        for (int i = 1; i < tags.size(); i++) {
            strBuilder.append(sep);
            strBuilder.append(tags.get(i));
        }
        return strBuilder.toString();
    }

    private static String join (String sep, String[] tags) {
        if (tags.length == 0) {
            return "";
        }
        StringBuilder strBuilder = new StringBuilder();
        strBuilder.append(tags[0]);
        for (int i = 1; i < tags.length; i++) {
            strBuilder.append(sep);
            strBuilder.append(tags[i]);
        }
        return strBuilder.toString();
    }

    // Increase statistic value
    private void increase (int event) {
        increase(event, 1);
    }

    private void increase (int event, int num) {
        if (!statistics.containsKey(event)) {
            statistics.put(event, num);
        }
        else {
            statistics.put(event, statistics.get(event) + num);
        }
    }

    // Event string
    private String event (int event) {
        ArrayList <String> events = new ArrayList <String> ();

        if ((event & STPSegment.SND) != 0) {
            events.add("snd");
            increase(STPSegment.SND);
        }
        else if ((event & STPSegment.RCV) != 0) {
            events.add("rcv");
            increase(STPSegment.RCV);
        }

        if ((event & STPSegment.DROP) != 0) {
            events.add("drop");
            increase(STPSegment.DROP);
        }
        else if ((event & STPSegment.CORR) != 0) {
            events.add("corr");
            increase(STPSegment.CORR);
        }
        else if ((event & STPSegment.DUP) != 0) {
            events.add("dup");
            increase(STPSegment.DUP);
        }
        else if ((event & STPSegment.RORD) != 0) {
            events.add("rord");
            increase(STPSegment.RORD);
        }

        if ((event & STPSegment.DELY) != 0) {
            events.add("dely");
            increase(STPSegment.DELY);
        }

        if ((event & STPSegment.DA) != 0) {
            events.add("DA");
            increase(STPSegment.DA);
        }

        if ((event & STPSegment.RXT) != 0) {
            events.add("RXT");
            increase(STPSegment.RXT);
        }

        if ((event & STPSegment.TIMEOUT) != 0) {
            increase(STPSegment.TIMEOUT);
        }

        return join("/", events);
    }

    // Packet type string
    private String packetType (STPSegment seg, int event) {
        ArrayList <String> types = new ArrayList <String> ();
        int flag = seg.getFlag();

        if ((flag & STPSegment.SYN) != 0) {
            types.add("S");
        }
        if ((flag & STPSegment.ACK) != 0) {
            types.add("A");
        }
        if ((flag & STPSegment.FIN) != 0) {
            types.add("F");
        }
        if (seg.getData().length > 0) {
            types.add("D");
            increase(STPSegment.DATA);
            if ((event & STPSegment.SND) !=0) {
                increase(STPSegment.SND | STPSegment.DATA, seg.getData().length);
            }
            else if ((event & STPSegment.RCV) !=0) {
                increase(STPSegment.RCV | STPSegment.DATA, seg.getData().length);
            }
        }

        return join("", types);
    }

    // Constructor
    public STPLogger (String filename) throws Exception {
        fileWriter = new FileWriter(filename, true);
        printWriter = new PrintWriter(fileWriter);
        statistics = new HashMap<Integer, Integer>();
    }

    // Write logs
    public void logHeader (String[] info) {
        log("");
        log("Run: " + new Date().toString());
        log(join(" ", info));
        log();
    }

    public void log () {
        log("------------------------------------------------------------------");
    }

    public void log (String info) {
        printWriter.println(info);
    }

    // <event> <time> <type-of-packet> <seq-number> <number-of-bytes-data> <ack-number>
    public void log (STPSegment seg, int event) {
        int interval = 0;
        if (startTime == 0) {
            startTime = new Date().getTime();
        }
        else {
            interval = (int)(new Date().getTime() - startTime);
        }
        printWriter.printf("%-16s%10.2f%10s%10d%10d%10d\n", event(event), interval / 1000.0, packetType(seg, event),
                seg.getSeq(), seg.getData().length, seg.getAck());
    }

    // Finished
    public void close () throws Exception {
        printWriter.close();
        fileWriter.close();
    }
}
