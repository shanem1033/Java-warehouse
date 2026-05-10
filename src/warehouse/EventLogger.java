package warehouse;

import java.util.LinkedHashMap;
import java.util.Map;

public final class EventLogger {
    private final Clock clock;

    public EventLogger(Clock clock) {
        this.clock = clock;
    }

    public synchronized void log(String tid, String event, Map<String, Object> fields) {
        // One synchronized logger keeps each event on a single clean line 
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("tick", clock.nowTick());
        out.put("tid", tid);
        out.put("event", event);
        out.putAll(fields);

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Object> entry : out.entrySet()) {
            if (!first) {
                sb.append(' ');
            }
            first = false;
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        System.out.println(sb);
    }
}
