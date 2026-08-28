package ru.voidrp.gamesync.model;

import java.util.List;

/** Result of the weekly top-nation reward tick. */
public final class NationSeasonAwardResponse {
    public List<NationSeasonWinner> awarded;
    public boolean skipped;
}
