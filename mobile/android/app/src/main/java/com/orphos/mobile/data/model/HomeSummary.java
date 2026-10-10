package com.orphos.mobile.data.model;

import java.util.List;

/** Home dashboard: tasks progress, AI summary, upcoming meetings — matches the mockup. */
public class HomeSummary {
    public int tasksDone;
    public int tasksTotal;
    public int criticalCount;
    public String availability;        // e.g. "Busy"
    public List<Point> summary;
    public List<Meeting> meetings;

    /** A colour-coded AI-summary bullet. */
    public static class Point {
        public String text;
        public String severity;        // CRITICAL | WARNING | INFO
        public Point() { }
        public Point(String text, String severity) { this.text = text; this.severity = severity; }
    }

    public static class Meeting {
        public String time;
        public String title;
        public Meeting() { }
        public Meeting(String time, String title) { this.time = time; this.title = title; }
    }
}
