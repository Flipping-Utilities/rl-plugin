package com.flippingutilities.ui.statistics;

import com.flippingutilities.utilities.TimeRange;
import org.junit.Test;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class StatsCustomRangeTest
{
	private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
	private static final LocalDate TO = LocalDate.of(2026, 11, 20);

	@Test
	public void customRangeIsOfferedInTheIntervalDropdown() throws Exception
	{
		try (StatsEmptyStateTest.Fixture fixture = new StatsEmptyStateTest.Fixture())
		{
			SwingUtilities.invokeAndWait(() ->
			{
				JComboBox dropdown = StatsEmptyStateTest.find(fixture.panel, JComboBox.class, null);
				boolean offered = false;
				for (int i = 0; i < dropdown.getItemCount(); i++)
				{
					if ("Custom range...".equals(dropdown.getItemAt(i)))
					{
						offered = true;
					}
				}
				assertTrue("The interval dropdown should offer a custom range", offered);
			});
		}
	}

	@Test
	public void applyingACustomRangeCoversBothSelectedDays() throws Exception
	{
		try (StatsEmptyStateTest.Fixture fixture = new StatsEmptyStateTest.Fixture())
		{
			SwingUtilities.invokeAndWait(() -> fixture.panel.applyCustomRange(FROM, TO));

			TimeRange expected = TimeRange.ofLocalDates(FROM, TO, ZoneId.systemDefault());
			assertEquals(expected, fixture.panel.getInterval());
			assertTrue(fixture.panel.getIntervalName().contains("1 Nov 2026"));
			assertTrue(fixture.panel.getIntervalName().contains("20 Nov 2026"));
			SwingUtilities.invokeAndWait(() ->
			{
				JComboBox dropdown = StatsEmptyStateTest.find(fixture.panel, JComboBox.class, null);
				assertEquals(fixture.panel.getIntervalName(), dropdown.getSelectedItem());
			});
		}
	}

	@Test
	public void applyingReversedDatesNormalisesTheRange() throws Exception
	{
		try (StatsEmptyStateTest.Fixture fixture = new StatsEmptyStateTest.Fixture())
		{
			SwingUtilities.invokeAndWait(() -> fixture.panel.applyCustomRange(TO, FROM));

			assertEquals(TimeRange.ofLocalDates(FROM, TO, ZoneId.systemDefault()), fixture.panel.getInterval());
		}
	}

	@Test
	public void cancellingRestoresThePreviouslySelectedInterval() throws Exception
	{
		try (StatsEmptyStateTest.Fixture fixture = new StatsEmptyStateTest.Fixture())
		{
			SwingUtilities.invokeAndWait(() -> fixture.panel.applyCustomRange(FROM, TO));
			assertFalse(fixture.panel.getInterval().isOpenEnded());

			SwingUtilities.invokeAndWait(() -> fixture.panel.restoreLastNonCustomInterval());

			assertEquals("Session", fixture.panel.getIntervalName());
			assertTrue(fixture.panel.getInterval().isOpenEnded());
			SwingUtilities.invokeAndWait(() ->
			{
				JComboBox dropdown = StatsEmptyStateTest.find(fixture.panel, JComboBox.class, null);
				assertNotNull(dropdown.getSelectedItem());
				assertEquals("Session", dropdown.getSelectedItem());
			});
		}
	}

	@Test
	public void choosingAPresetClearsTheCustomEnd() throws Exception
	{
		try (StatsEmptyStateTest.Fixture fixture = new StatsEmptyStateTest.Fixture())
		{
			SwingUtilities.invokeAndWait(() -> fixture.panel.applyCustomRange(FROM, TO));

			SwingUtilities.invokeAndWait(() -> fixture.panel.setTimeInterval("All"));

			assertTrue(fixture.panel.getInterval().isOpenEnded());
			assertEquals("All", fixture.panel.getIntervalName());
		}
	}

	@Test
	public void customRangePromptIsDeferredUntilTheDropdownPopupCloses() throws Exception
	{
		try (StatsEmptyStateTest.Fixture fixture = new StatsEmptyStateTest.Fixture())
		{
			AtomicBoolean prompted = new AtomicBoolean();
			StatsPanel[] panel = new StatsPanel[1];

			SwingUtilities.invokeAndWait(() ->
			{
				panel[0] = new StatsPanel(fixture)
				{
					@Override
					void promptForCustomRange()
					{
						prompted.set(true);
					}
				};
				JComboBox dropdown = StatsEmptyStateTest.find(panel[0], JComboBox.class, null);
				dropdown.setSelectedItem("Custom range...");
				assertFalse("the date picker must not block the still-open dropdown", prompted.get());
			});

			SwingUtilities.invokeAndWait(() -> {});

			SwingUtilities.invokeAndWait(() ->
			{
				JComboBox dropdown = StatsEmptyStateTest.find(panel[0], JComboBox.class, null);
				assertFalse(dropdown.isPopupVisible());
				assertTrue("the date picker should open once the popup is gone", prompted.get());
			});
		}
	}
}
