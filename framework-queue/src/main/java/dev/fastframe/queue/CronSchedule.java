package dev.fastframe.queue;

import java.time.ZonedDateTime;
import java.util.BitSet;
import java.util.Objects;

/** 5-field cron: minute hour day-of-month month day-of-week. No seconds or quartz modifiers. */
public final class CronSchedule {
    private final BitSet minutes, hours, days, months, weekdays;
    private final boolean unrestrictedDay, unrestrictedWeekday;
    private CronSchedule(BitSet m, BitSet h, BitSet d, BitSet mon, BitSet dow, boolean dayAny, boolean dowAny) {
        this.minutes=m; this.hours=h; this.days=d; this.months=mon; this.weekdays=dow;
        this.unrestrictedDay=dayAny; this.unrestrictedWeekday=dowAny;
    }
    public static CronSchedule parse(String expression) {
        String[] fields = Objects.requireNonNull(expression).trim().split("\\s+");
        if (fields.length != 5) throw new IllegalArgumentException("Cron must have exactly 5 fields");
        return new CronSchedule(field(fields[0],0,59,false), field(fields[1],0,23,false),
                field(fields[2],1,31,false), field(fields[3],1,12,false),
                field(fields[4],0,7,true), fields[2].equals("*"),fields[4].equals("*"));
    }
    private static BitSet field(String expression, int min, int max, boolean weekday) {
        BitSet result = new BitSet(max+1);
        for (String part : expression.split(",", -1)) {
            String[] split=part.split("/", -1);
            if (split.length > 2) throw new IllegalArgumentException("Invalid cron field: " + expression);
            int step = split.length == 2 ? number(split[1],1,max) : 1;
            int from, to;
            if (split[0].equals("*")) { from=min; to=max; }
            else if (split[0].contains("-")) {
                String[] bounds=split[0].split("-", -1);
                if (bounds.length != 2) throw new IllegalArgumentException("Invalid range");
                from=number(bounds[0],min,max); to=number(bounds[1],min,max);
            } else {
                from=number(split[0],min,max); to=split.length == 2 ? max : from;
            }
            if (from > to) throw new IllegalArgumentException("Descending cron range");
            for (int i=from; i<=to; i+=step) result.set(weekday && i==7 ? 0 : i);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("Empty cron field");
        return result;
    }
    private static int number(String value, int min, int max) {
        try { int x=Integer.parseInt(value); if (x<min||x>max) throw new IllegalArgumentException("Invalid cron number"); return x; }
        catch(NumberFormatException e) { throw new IllegalArgumentException("Invalid cron number: " + value, e); }
    }
    public boolean matches(ZonedDateTime time) {
        int weekday=time.getDayOfWeek().getValue()%7;
        boolean day=days.get(time.getDayOfMonth()), week=weekdays.get(weekday);
        boolean dayMatches=unrestrictedDay && unrestrictedWeekday ||
                unrestrictedDay && week || unrestrictedWeekday && day || (!unrestrictedDay && !unrestrictedWeekday && (day || week));
        return minutes.get(time.getMinute()) && hours.get(time.getHour())
                && months.get(time.getMonthValue()) && dayMatches;
    }
}
