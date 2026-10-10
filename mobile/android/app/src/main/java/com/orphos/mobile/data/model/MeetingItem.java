package com.orphos.mobile.data.model;

import com.google.gson.annotations.SerializedName;

/** An upcoming meeting shown on Home. */
public class MeetingItem {

    @SerializedName("id")
    public String id;

    @SerializedName(value = "title", alternate = {"subject"})
    public String title;

    @SerializedName(value = "time", alternate = {"startTime", "start"})
    public String time;

    public MeetingItem() { }

    public MeetingItem(String title, String time) {
        this.title = title;
        this.time = time;
    }
}
