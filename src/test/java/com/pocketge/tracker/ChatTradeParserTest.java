package com.pocketge.tracker;

import org.junit.Assert;
import org.junit.Test;

public class ChatTradeParserTest
{
	@Test
	public void extractsFromUsersOwnExamples()
	{
		Assert.assertEquals("venator bow", ChatTradeParser.extractItemName("buying venator bow 68m"));
		Assert.assertEquals("soul reaper axe", ChatTradeParser.extractItemName("buy soul reaper axe 450m"));
	}

	@Test
	public void handlesCommonTradeChatShorthand()
	{
		Assert.assertEquals("twisted bow", ChatTradeParser.extractItemName("s> twisted bow 1.2b"));
		Assert.assertEquals("dragon claws", ChatTradeParser.extractItemName("wtb dragon claws 65m ea"));
		Assert.assertEquals("scythe of vitur", ChatTradeParser.extractItemName("selling scythe of vitur 700m each"));
		Assert.assertEquals("primordial boots", ChatTradeParser.extractItemName("want to buy primordial boots 30m"));
	}

	@Test
	public void handlesPunctuationAndPlainNumbers()
	{
		Assert.assertEquals("rune scimitar", ChatTradeParser.extractItemName("buying, rune scimitar, 15000gp"));
		Assert.assertEquals("abyssal whip", ChatTradeParser.extractItemName("selling abyssal whip 2500000"));
	}

	@Test
	public void returnsNullForNonTradeChatter()
	{
		Assert.assertNull(ChatTradeParser.extractItemName("lol nice kill"));
		Assert.assertNull(ChatTradeParser.extractItemName("anyone want to do a raid"));
		Assert.assertNull(ChatTradeParser.extractItemName(""));
		Assert.assertNull(ChatTradeParser.extractItemName(null));
	}

	@Test
	public void returnsNullWhenNothingSurvivesStripping()
	{
		Assert.assertNull(ChatTradeParser.extractItemName("buying 68m"));
		Assert.assertNull(ChatTradeParser.extractItemName("wtb 100m"));
	}

	@Test
	public void returnsNullForImplausiblyLongMatches()
	{
		Assert.assertNull(ChatTradeParser.extractItemName(
			"buying this is way too many words to plausibly be a real item name 5m"));
	}
}
