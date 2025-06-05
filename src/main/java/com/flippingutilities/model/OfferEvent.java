/*
 * Copyright (c) 2020, Belieal <https://github.com/Belieal>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.flippingutilities.model;


import com.flippingutilities.utilities.Constants;
import com.flippingutilities.utilities.GeTax;
import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * This class stores information from a {@link GrandExchangeOfferChanged} event and is populated with
 * extra information such as ticksSinceFirstOffer and quantitySinceLastOffer based on previous offers
 * belonging to the same trade as it.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OfferEvent
{
	@Getter
	private String uuid;
	@SerializedName("b")
	private boolean buy;
	@SerializedName("id")
	private int itemId;
	@SerializedName("cQIT")
	private int currentQuantityInTrade;
	@SerializedName("p")
	private int price;
	@SerializedName("t")
	private Instant time;
	@SerializedName("s")
	private int slot;
	@SerializedName("st")
	private GrandExchangeOfferState state;
	@SerializedName("tAA")
	private int tickArrivedAt;

	//this isn't TRULY ticksSinceFirstOffer as the ticks don't constantly increase. This is only relevant within a
	//short time frame (before the tick counter resets). As such, it should only be used to compare two events
	//very close to each other, such as the whether an offer is a margin check.
	@SerializedName("tSFO")
	private int ticksSinceFirstOffer;
	@SerializedName("tQIT")
	private int totalQuantityInTrade;
	private Instant tradeStartedAt;
	private boolean beforeLogin;
	/**
	 * a offer always belongs to a flipping item. Every flipping item was flipped by an account and only one account and
	 * has a flipped by attribute. So, the reason this attribute is here is because during the process of creating
	 * the account wide trade list, we merge flipping items flipped by several different accounts into one. Thus, in that
	 * case, a flipping item would have been flipped by multiple accounts so each offer needs to be marked to
	 * differentiate offer. This functionality is currently only used in getFlips as, when getting the flips for the
	 * account wide list, you don't want to match offers from different accounts!
	 */
	private transient String madeBy;

	//Used in theGeHistoryTabOfferPanel and RecipeFlipPanel
	private transient String itemName;
	//used in the live slot view to show what price something was listed at
	private transient int listedPrice;
	private transient int spent;

	/**
	 * @return post tax values
	 */
	public int getPrice() {
		final long t = time.getEpochSecond();
		if (buy || t < Constants.GE_TAX_START || Constants.TAX_EXEMPT_ITEMS.contains(itemId) ||
			(t >= Constants.GE_TAX_INCREASED && Constants.NEW_TAX_EXEMPT_ITEMS.contains(itemId))) {
			return price;
		}
		// if this occurred prior to the tax rate increasing, use the old rate
		if (t < Constants.GE_TAX_INCREASED) {
			return GeTax.getOldPostTaxPrice(price);
		}
		return GeTax.getPostTaxPrice(price);
	}

	public int getPreTaxPrice() {
		return price;
	}

	public int getTaxPaid() {
		return (getPreTaxPrice() - getPrice()) * currentQuantityInTrade;
	}

	public int getTaxPaidPerItem() {
		return getPreTaxPrice() - getPrice();
	}

	/**
	 * Returns a boolean representing that the offer is a complete offer. A complete offer signifies
	 * the end of that trade, thus the end of the slot's history. The HistoryManager uses this to decide when
	 * to clear the history for a slot.
	 *
	 * @return boolean value representing that the offer is a complete offer
	 */
	public boolean isComplete()
	{
		return isComplete(state);
	}

	public boolean isCancelled()
	{
		return state == GrandExchangeOfferState.CANCELLED_BUY || state == GrandExchangeOfferState.CANCELLED_SELL;
	}

	public static boolean isComplete(GrandExchangeOfferState state) {
		return
			state == GrandExchangeOfferState.BOUGHT ||
				state == GrandExchangeOfferState.SOLD ||
				state == GrandExchangeOfferState.CANCELLED_BUY ||
				state == GrandExchangeOfferState.CANCELLED_SELL;
	}

	public static boolean isBuy(GrandExchangeOfferState state) {
		return state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY
			|| state == GrandExchangeOfferState.BUYING;
	}

	/**
	 * when an offer is complete, two events are generated: a buying/selling event and a bought/sold event.
	 * this method identifies the redundant buying/selling event before the bought/sold event.
	 */
	public boolean isRedundantEventBeforeOfferCompletion()
	{
		return (state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.SELLING) && currentQuantityInTrade == totalQuantityInTrade;
	}

	/**
	 * A margin check is defined as an offer that is either a BOUGHT or SOLD offer and has a currentQuantityInTrade of 1. This
	 * resembles the typical margin check process wherein you buy an item (currentQuantityInTrade of 1) for a high press, and then
	 * sell that item (currentQuantityInTrade of 1), to figure out the optimal buying and selling prices.
	 *
	 * @return boolean value representing whether the offer is a margin check or not
	 */
	public boolean isMarginCheck()
	{
		return (state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.SOLD) && totalQuantityInTrade == 1
			&& ticksSinceFirstOffer <= 2;
	}

	/**
	 * We get an event for every empty slot on login and when a slot is cleared
	 *
	 * @return whether this OfferEvent was caused by an empty slot
	 */
	public boolean isCausedByEmptySlot()
	{
		return (itemId == 0 || state == GrandExchangeOfferState.EMPTY);
	}

	/**
	 * When we first place an offer for a slot we get an offer event that has a quantity traded of 0. This offer marks
	 * the tick the offer was placed. The reason we need to also check if it wasn't a complete offer is because you can
	 * cancel a buy or a sell, and provided you didn't buy or sell anything, the quantity in the offer can be 0, but its
	 * not the start of the offer.
	 *
	 * @return boolean value representing whether the offer is a start of a trade.
	 */
	public boolean isStartOfOffer()
	{
		return currentQuantityInTrade == 0 && !isComplete() && !isCausedByEmptySlot();
	}

	public OfferEvent clone()
	{
		return new OfferEvent(
				uuid,
				buy,
				itemId,
				currentQuantityInTrade,
				price,
				time,
				slot,
				state,
				tickArrivedAt,
				ticksSinceFirstOffer,
				totalQuantityInTrade,
				tradeStartedAt,
				beforeLogin,
				madeBy,
				itemName,
				listedPrice,
				spent
		);
	}

	public boolean equals(Object other)
	{
		if (other == this)
		{
			return true;
		}

		if (!(other instanceof OfferEvent))
		{
			return false;
		}

		OfferEvent otherOffer = (OfferEvent) other;

		return
			isDuplicate(otherOffer)
			&& uuid.equals(otherOffer.uuid)
			&& tickArrivedAt == otherOffer.tickArrivedAt
			&& ticksSinceFirstOffer == otherOffer.ticksSinceFirstOffer
			&& time.equals(otherOffer.time);
	}

	/**
	 * This checks whether the given OfferEvent is a "duplicate" of this OfferEvent. Some fields such as
	 * tickArrivedAt are omitted because even if they are different, the given offer is still redundant due to all
	 * the other information being the same and should be screened out by screenOfferEvent in FlippingPlugin, where
	 * this method is used.
	 *
	 * @param other the OfferEvent being compared.
	 * @return whether or not the given offer event is redundant
	 */
	public boolean isDuplicate(OfferEvent other)
	{
		return state == other.getState()
			&& currentQuantityInTrade == other.getCurrentQuantityInTrade()
			&& slot == other.getSlot()
			&& totalQuantityInTrade == other.getTotalQuantityInTrade() && itemId == other.getItemId()
			&& getPrice() == other.getPrice();
	}

	public static OfferEvent fromGrandExchangeEvent(GrandExchangeOfferChanged event)
	{
		GrandExchangeOffer offer = event.getOffer();

		boolean isBuy = offer.getState() == GrandExchangeOfferState.BOUGHT
			|| offer.getState() == GrandExchangeOfferState.CANCELLED_BUY
			|| offer.getState() == GrandExchangeOfferState.BUYING;

		return new OfferEvent(
			UUID.randomUUID().toString(),
			isBuy,
			offer.getItemId(),
			offer.getQuantitySold(),
			offer.getQuantitySold() == 0 ? 0 : offer.getSpent() / offer.getQuantitySold(),
			Instant.now().truncatedTo(ChronoUnit.SECONDS),
			event.getSlot(),
			offer.getState(),
			0,
			0,
			offer.getTotalQuantity(),
			null,
			false,
			null,
			null,
			offer.getPrice(),
			offer.getSpent());
	}

	/**
	 * Sets the ticks since the first offer event of the trade that this offer event belongs to. The ticks since first
	 * offer event is used to determine whether an offer event is a margin check or not.
	 *
	 * @param lastOfferForSlot the last offer event for the slot this offer event belongs to
	 */
	public void setTicksSinceFirstOffer(OfferEvent lastOfferForSlot)
	{
		int tickDiffFromLastOffer = Math.abs(tickArrivedAt - lastOfferForSlot.getTickArrivedAt());
		ticksSinceFirstOffer = tickDiffFromLastOffer + lastOfferForSlot.getTicksSinceFirstOffer();
	}

	public String prettyRepr() {
		return String.format("slot=%d, buy=%b, itemId=%d, state=%s, tq=%d",slot, buy, itemId, state, totalQuantityInTrade);
	}

	public static OfferEvent dummyOffer(boolean buyState, boolean marginCheck, int price, int id, String itemName) {
		return new OfferEvent(
				UUID.randomUUID().toString(),
				buyState,
				id,
				0,
				price,
				Instant.now(),
				-1,
				buyState ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD,
				1,
				marginCheck? 1 : 10,
				1,
				null,
				false,
				"",
				itemName,
				0,
				0);
	}
}

