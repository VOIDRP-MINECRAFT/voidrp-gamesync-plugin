package ru.voidrp.gamesync.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the main thread after a player market trade is confirmed.
 * Fired for BOTH the seller and the buyer (two separate events per fill).
 */
public final class PlayerMarketTradeEvent extends Event {

    public enum Role { SELLER, BUYER }

    private static final HandlerList HANDLERS = new HandlerList();

    private final String playerName;
    private final Role role;
    private final String itemKey;
    private final int amount;
    private final double grossTotal;

    public PlayerMarketTradeEvent(String playerName, Role role, String itemKey, int amount, double grossTotal) {
        this.playerName = playerName;
        this.role       = role;
        this.itemKey    = itemKey;
        this.amount     = amount;
        this.grossTotal = grossTotal;
    }

    /** Name of the player whose action triggered this side of the trade. */
    public String getPlayerName()  { return playerName; }
    public Role   getRole()        { return role; }
    public String getItemKey()     { return itemKey; }
    public int    getAmount()      { return amount; }
    public double getGrossTotal()  { return grossTotal; }

    @Override public HandlerList getHandlers()              { return HANDLERS; }
    public static HandlerList    getHandlerList()           { return HANDLERS; }
}
