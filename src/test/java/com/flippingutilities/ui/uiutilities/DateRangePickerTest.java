package com.flippingutilities.ui.uiutilities;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DateRangePickerTest
{
	private static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);
	private static final LocalDate NOV_10 = LocalDate.of(2026, 11, 10);
	private static final LocalDate NOV_20 = LocalDate.of(2026, 11, 20);

	@Test
	public void initialRangeIsPreselected() throws Exception
	{
		DateRangePicker picker = newPicker(NOV_1, NOV_10);

		assertTrue(picker.hasCompleteSelection());
		assertEquals(NOV_1, picker.getStartDate());
		assertEquals(NOV_10, picker.getEndDate());
	}

	@Test
	public void firstClickStartsTheSelectionAndSecondClickCompletesIt() throws Exception
	{
		DateRangePicker picker = newPicker(null, null);

		onEdt(() -> picker.selectDate(NOV_1));
		assertFalse(picker.hasCompleteSelection());
		assertEquals(NOV_1, picker.getStartDate());
		assertNull(picker.getEndDate());

		onEdt(() -> picker.selectDate(NOV_20));
		assertTrue(picker.hasCompleteSelection());
		assertEquals(NOV_1, picker.getStartDate());
		assertEquals(NOV_20, picker.getEndDate());
	}

	@Test
	public void clickingAnEarlierDaySecondNormalisesTheRange() throws Exception
	{
		DateRangePicker picker = newPicker(null, null);

		onEdt(() -> picker.selectDate(NOV_20));
		onEdt(() -> picker.selectDate(NOV_1));

		assertTrue(picker.hasCompleteSelection());
		assertEquals(NOV_1, picker.getStartDate());
		assertEquals(NOV_20, picker.getEndDate());
	}

	@Test
	public void clickingTheSameDayTwiceSelectsASingleDay() throws Exception
	{
		DateRangePicker picker = newPicker(null, null);

		onEdt(() -> picker.selectDate(NOV_10));
		onEdt(() -> picker.selectDate(NOV_10));

		assertTrue(picker.hasCompleteSelection());
		assertEquals(NOV_10, picker.getStartDate());
		assertEquals(NOV_10, picker.getEndDate());
	}

	@Test
	public void thirdClickStartsANewSelection() throws Exception
	{
		DateRangePicker picker = newPicker(null, null);

		onEdt(() -> picker.selectDate(NOV_1));
		onEdt(() -> picker.selectDate(NOV_10));
		onEdt(() -> picker.selectDate(NOV_20));

		assertFalse(picker.hasCompleteSelection());
		assertEquals(NOV_20, picker.getStartDate());
		assertNull(picker.getEndDate());
	}

	@Test
	public void focusingTheFromFieldMakesTheNextClickSetTheStart() throws Exception
	{
		DateRangePicker picker = newPicker(NOV_1, NOV_20);

		onEdt(() -> picker.focusStartDate());
		onEdt(() -> picker.selectDate(NOV_10));

		assertEquals(NOV_10, picker.getStartDate());
		assertEquals(NOV_20, picker.getEndDate());
	}

	@Test
	public void movingTheStartPastTheEndRestartsTheRange() throws Exception
	{
		DateRangePicker picker = newPicker(NOV_1, NOV_10);

		onEdt(() -> picker.focusStartDate());
		onEdt(() -> picker.selectDate(NOV_20));

		assertEquals(NOV_20, picker.getStartDate());
		assertNull(picker.getEndDate());
	}

	@Test
	public void focusingTheToFieldMakesTheNextClickSetTheEnd() throws Exception
	{
		DateRangePicker picker = newPicker(NOV_1, NOV_20);

		onEdt(() -> picker.focusEndDate());
		onEdt(() -> picker.selectDate(NOV_10));

		assertEquals(NOV_1, picker.getStartDate());
		assertEquals(NOV_10, picker.getEndDate());
	}

	@Test
	public void todayFillsInTheEndOfTheRange() throws Exception
	{
		DateRangePicker picker = newPicker(NOV_1, NOV_10);
		LocalDate today = LocalDate.of(2026, 11, 26);

		onEdt(() -> picker.useToday(today));

		assertEquals(NOV_1, picker.getStartDate());
		assertEquals(today, picker.getEndDate());
	}

	@Test
	public void todayBeforeTheStartMovesTheStartBack() throws Exception
	{
		DateRangePicker picker = newPicker(LocalDate.of(2026, 11, 27), LocalDate.of(2026, 11, 30));
		LocalDate today = LocalDate.of(2026, 11, 26);

		onEdt(() -> picker.useToday(today));

		assertEquals(today, picker.getStartDate());
		assertEquals(today, picker.getEndDate());
	}

	private static DateRangePicker newPicker(LocalDate start, LocalDate end) throws Exception
	{
		DateRangePicker[] created = new DateRangePicker[1];
		SwingUtilities.invokeAndWait(() -> created[0] = new DateRangePicker(start, end));
		return created[0];
	}

	private static void onEdt(Runnable action) throws Exception
	{
		SwingUtilities.invokeAndWait(action);
	}
}
