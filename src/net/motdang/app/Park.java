package net.motdang.app;

import java.util.ArrayList;

/** One place a vehicle was left. Plain data; ParkStore turns it into JSON. */
public final class Park {
    public static final String GUESSED = "guessed", TOLD = "told", BIKE = "bike", CAR = "car";

    public double lat, lng, acc;
    public long t;
    public String how = GUESSED, vehicle = BIKE, note = "", level = "";
    /** lat, lng, time ms. The first point is the park point. */
    public ArrayList<double[]> trail = new ArrayList<>();
    /** 1 once the vehicle is taken or found; leftT is when. */
    public int left;
    public long leftT;

    public Park() {}

    public Park(double lat, double lng, double acc, long t, String how, String vehicle) {
        this.lat = lat;
        this.lng = lng;
        this.acc = acc;
        this.t = t;
        this.how = how;
        this.vehicle = CAR.equals(vehicle) ? CAR : BIKE;
        trail.add(new double[]{lat, lng, t});
    }
}
