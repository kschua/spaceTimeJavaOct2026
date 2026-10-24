package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Port of lib/cmtools/SolarCal.js — solar-term calendar backed by the
 * same solarData-ytliu table the RN app shipped with.
 */
public class SolarCalendar {
    private final JsonNode solar;
    /** Matches luxon `current.offset` (minutes) — the original shifts each
     *  solar-data term instant by the chart's local zone offset. */
    private final int offsetMinutes;

    public SolarCalendar(DataStore store) {
        this(store, 0);
    }

    public SolarCalendar(DataStore store, int offsetMinutes) {
        this.solar = store.solar;
        this.offsetMinutes = offsetMinutes;
    }

    public SolarCalendar(DataStore store, ZoneOffset offset) {
        this(store, offset == null ? 0 : offset.getTotalSeconds() / 60);
    }

    private JsonNode yearData(int year) {
        JsonNode y = solar.get(String.valueOf(year));
        if (y == null) throw new IllegalArgumentException("no solar data for " + year);
        return y;
    }

    /** The two terms of a (gregorian) month: [0]=first term, [1]=second term.
     *  Each term: {num, at} where at is the exact local DateTime. */
    public List<Term> getYearMonthSolarTerm(int y, int m) {
        int year = y, month = m;
        if (month > 12) { year++; month -= 12; }
        if (month < 1) { year--; month += 12; }
        JsonNode data = yearData(year).get(String.valueOf(month));
        List<Term> result = new ArrayList<>();
        for (JsonNode term : data) {
            int num = term.get(0).asInt();
            JsonNode t = term.get(1);
            LocalDateTime at = LocalDateTime.of(year, month, parseInt(t.get(0)), parseInt(t.get(1)), parseInt(t.get(2)))
                    .plusMinutes(offsetMinutes);
            result.add(new Term(num, at));
        }
        return result;
    }

    private int parseInt(JsonNode n) {
        return n.isInt() ? n.asInt() : Integer.parseInt(n.asText());
    }

    public LocalDateTimeAt getCurrentYearJie(LocalDateTimeAt current) {
        for (int m = 1; m < 6; m++) {
            for (Term t : getYearMonthSolarTerm(current.year(), m)) {
                if (t.num == 0) return new LocalDateTimeAt(LocalDateTime.of(current.year(), m, t.at.getDayOfMonth(), t.at.getHour(), t.at.getMinute()), null);
            }
        }
        for (int m = 12; m > 6; m--) {
            for (Term t : getYearMonthSolarTerm(current.year() - 1, m)) {
                if (t.num == 0) return new LocalDateTimeAt(LocalDateTime.of(current.year() - 1, m, t.at.getDayOfMonth(), t.at.getHour(), t.at.getMinute()), null);
            }
        }
        throw new IllegalStateException();
    }

    /** next jie (first day of a solar "month" boundary) strictly usable as forward marker */
    public Term getForwardJie(LocalDateTimeAt current) {
        List<Term> thisMonth = getYearMonthSolarTerm(current.year(), current.month());
        List<Term> nextMonth = getYearMonthSolarTerm(current.year(), current.month() + 1);
        if (current.isBefore(thisMonth.get(0).at)) {
            return thisMonth.get(0).num % 2 == 0 ? thisMonth.get(0) : thisMonth.get(1);
        } else if (current.isBefore(thisMonth.get(1).at)) {
            return thisMonth.get(1).num % 2 == 0 ? thisMonth.get(1) : nextMonth.get(0);
        } else {
            return nextMonth.get(0).num % 2 == 0 ? nextMonth.get(0) : nextMonth.get(1);
        }
    }

    public Term getReverseJie(LocalDateTimeAt current) {
        List<Term> thisMonth = getYearMonthSolarTerm(current.year(), current.month());
        List<Term> prevMonth = getYearMonthSolarTerm(current.year(), current.month() - 1);
        if (current.isAfter(thisMonth.get(1).at)) {
            return thisMonth.get(1).num % 2 == 0 ? thisMonth.get(1) : thisMonth.get(0);
        } else if (current.isAfter(thisMonth.get(0).at)) {
            return thisMonth.get(0).num % 2 == 0 ? thisMonth.get(0) : prevMonth.get(1);
        } else {
            return prevMonth.get(1).num % 2 == 0 ? prevMonth.get(1) : prevMonth.get(0);
        }
    }

    public TermPair getCurrentSolarTerm(LocalDateTimeAt current) {
        List<Term> thisMonth = getYearMonthSolarTerm(current.year(), current.month());
        List<Term> nextMonth = getYearMonthSolarTerm(current.year(), current.month() + 1);
        List<Term> prevMonth = getYearMonthSolarTerm(current.year(), current.month() - 1);
        Term term = prevMonth.get(1), nextTerm = thisMonth.get(0);
        if (!current.isBefore(thisMonth.get(1).at)) {
            term = thisMonth.get(1);
            nextTerm = nextMonth.get(0);
        } else if (!current.isBefore(thisMonth.get(0).at)) {
            term = thisMonth.get(0);
            nextTerm = thisMonth.get(1);
        }
        return new TermPair(term, nextTerm);
    }

    public static final class Term {
        public final int num;
        public final LocalDateTime at;
        public Term(int num, LocalDateTime at) { this.num = num; this.at = at; }
    }

    public static final class TermPair {
        public final Term thisTerm, nextTerm;
        public TermPair(Term a, Term b) { thisTerm = a; nextTerm = b; }
    }

    /** thin wrapper so "years/months/days" arithmetic mirrors the luxon values used in the JS */
    public static final class LocalDateTimeAt implements Comparable<LocalDateTimeAt> {
        public final LocalDateTime lt;
        public final ZoneOffset offset;
        public LocalDateTimeAt(LocalDateTime lt, ZoneOffset o) { this.lt = lt; this.offset = o; }
        public static LocalDateTimeAt of(int y, int mo, int d, int h, int mi, ZoneOffset o) {
            return new LocalDateTimeAt(LocalDateTime.of(y, mo, d, h, mi), o);
        }
        public int year() { return lt.getYear(); }
        public int month() { return lt.getMonthValue(); }
        public int day() { return lt.getDayOfMonth(); }
        public int hour() { return lt.getHour(); }
        public int minute() { return lt.getMinute(); }
        public boolean isBefore(LocalDateTimeAt o) { return lt.isBefore(o.lt); }
        public boolean isAfter(LocalDateTimeAt o) { return lt.isAfter(o.lt); }
        public boolean isBefore(LocalDateTime o) { return lt.isBefore(o); }
        public boolean isAfter(LocalDateTime o) { return lt.isAfter(o); }
        public long daysUntil(LocalDateTimeAt o) { return java.time.Duration.between(lt, o.lt).toDays(); }
        public long minutesUntil(LocalDateTimeAt o) { return java.time.Duration.between(lt, o.lt).toMinutes(); }
        public LocalDateTimeAt plusDays(long d) { return new LocalDateTimeAt(lt.plusDays(d), offset); }
        public LocalDateTimeAt plusYears(long y) { return new LocalDateTimeAt(lt.plusYears(y), offset); }
        public LocalDateTimeAt minusHours(long h) { return new LocalDateTimeAt(lt.minusHours(h), offset); }
        public LocalDateTimeAt minusYears(long y) { return new LocalDateTimeAt(lt.minusYears(y), offset); }
        public int compareTo(LocalDateTimeAt o) { return lt.compareTo(o.lt); }
        @Override public String toString() { return lt.toString(); }
    }
}
