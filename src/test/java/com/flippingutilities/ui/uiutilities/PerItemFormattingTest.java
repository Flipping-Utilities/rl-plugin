package com.flippingutilities.ui.uiutilities;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PerItemFormattingTest
{
	@Test
	public void valuesUnderTenGpKeepUpToTwoDecimals()
	{
		assertEquals("4.4", UIUtilities.formatPerItem(22.0 / 5));
		assertEquals("1.88", UIUtilities.formatPerItem(1.8812));
		assertEquals("0.5", UIUtilities.formatPerItem(0.5));
		assertEquals("3", UIUtilities.formatPerItem(3));
		assertEquals("-2.67", UIUtilities.formatPerItem(-8.0 / 3));
	}

	@Test
	public void valuesFromTenToHundredGpKeepUpToOneDecimal()
	{
		assertEquals("10", UIUtilities.formatPerItem(9.999));
		assertEquals("10.9", UIUtilities.formatPerItem(10.908));
		assertEquals("42.6", UIUtilities.formatPerItem(42.607));
		assertEquals("62.3", UIUtilities.formatPerItem(62.333));
		assertEquals("99.6", UIUtilities.formatPerItem(99.629));
		assertEquals("13", UIUtilities.formatPerItem(13.02));
		assertEquals("-15.2", UIUtilities.formatPerItem(-15.2));
		assertEquals("55.4", UIUtilities.formatPerItemStack(55.428, true));
	}

	@Test
	public void valuesFromHundredGpUpAreWholeGp()
	{
		assertEquals("100", UIUtilities.formatPerItem(99.96));
		assertEquals("101", UIUtilities.formatPerItem(100.6));
		assertEquals("1,234", UIUtilities.formatPerItem(1234.4));
		assertEquals("-150", UIUtilities.formatPerItem(-150.2));
	}

	@Test
	public void tinyValuesDoNotRenderAsNegativeZero()
	{
		assertEquals("0", UIUtilities.formatPerItem(-0.001));
		assertEquals("0", UIUtilities.formatPerItemStack(-0.001, true));
	}

	@Test
	public void stackVariantAbbreviatesLargeValues()
	{
		assertEquals("2.5", UIUtilities.formatPerItemStack(2.5, false));
		assertEquals("9,999", UIUtilities.formatPerItemStack(9999.4, false));
		assertEquals("12.3K", UIUtilities.formatPerItemStack(12_345.6, false));
	}
}
