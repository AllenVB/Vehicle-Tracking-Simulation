package com.fleet.vts.iettfeed.source;

import java.util.List;

/**
 * A real-time vehicle position source. İETT is the first implementation; another
 * agency (e.g. Ankara EGO) would slot in behind the same contract without
 * touching the mapping/scheduling layers.
 */
public interface LiveVehicleSource {

    /** Fetch the latest positions for all configured units. Never throws — a
     *  source that is down/slow returns an empty or partial list. */
    List<LiveReading> poll();
}
