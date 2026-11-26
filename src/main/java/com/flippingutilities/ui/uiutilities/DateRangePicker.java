package com.flippingutilities.ui.uiutilities;

import net.runelite.client.ui.FontManager;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * A month grid for picking an inclusive start and end date. The first click starts a selection,
 * the second completes it (reversed clicks are normalised) and a third starts a new one.
 */
public class DateRangePicker extends JPanel
{
	private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
	private static final DateTimeFormatter DAY_ACCESSIBLE_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
	private static final DateTimeFormatter SUMMARY_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
	private static final DayOfWeek FIRST_DAY_OF_WEEK = DayOfWeek.MONDAY;
	private static final int DAYS_PER_WEEK = 7;
	private static final int WEEKS_SHOWN = 6;
	private static final int GRID_CELLS = WEEKS_SHOWN * DAYS_PER_WEEK;

	private enum Target { START, END }

	private final JLabel monthLabel = new JLabel("", SwingConstants.CENTER);
	private final JLabel selectionLabel = new JLabel();
	private final JButton fromField = new JButton();
	private final JButton toField = new JButton();
	private final JButton todayButton = new JButton("Today");
	private final JButton[] dayButtons = new JButton[GRID_CELLS];
	private final LocalDate[] dayDates = new LocalDate[GRID_CELLS];

	private YearMonth displayedMonth;
	private LocalDate startDate;
	private LocalDate endDate;
	private Target target = Target.START;

	public DateRangePicker(LocalDate initialStart, LocalDate initialEnd)
	{
		startDate = initialStart;
		endDate = initialEnd;
		displayedMonth = YearMonth.from(initialStart != null ? initialStart : LocalDate.now());

		setLayout(new BorderLayout(0, 6));
		setBackground(CustomColors.DARK_GRAY);
		setBorder(new EmptyBorder(8, 8, 8, 8));
		setPreferredSize(new Dimension(238, 288));

		add(createDateFields(), BorderLayout.NORTH);
		add(createCalendar(), BorderLayout.CENTER);
		add(createSelectionLabel(), BorderLayout.SOUTH);

		render();
	}

	public boolean hasCompleteSelection()
	{
		return startDate != null && endDate != null;
	}

	public LocalDate getStartDate()
	{
		return startDate;
	}

	public LocalDate getEndDate()
	{
		return endDate;
	}

	/*
	 * Applies the click behaviour without going through a mouse event, so the selection logic is testable.
	 */
	void selectDate(LocalDate date)
	{
		if (date == null)
		{
			return;
		}

		if (target == Target.START || startDate == null)
		{
			startDate = date;
			if (endDate != null && date.isAfter(endDate))
			{
				//the previous end no longer lies after the start, so the range restarts
				endDate = null;
			}
			target = endDate == null ? Target.END : Target.START;
		}
		else if (date.isBefore(startDate))
		{
			endDate = startDate;
			startDate = date;
			target = Target.START;
		}
		else
		{
			endDate = date;
			target = Target.START;
		}

		showMonthOf(date);
		render();
	}

	/**
	 * Makes the next calendar click move the start of the range.
	 */
	void focusStartDate()
	{
		target = Target.START;
		render();
	}

	/**
	 * Makes the next calendar click move the end of the range.
	 */
	void focusEndDate()
	{
		target = Target.END;
		render();
	}

	/**
	 * Moves the end of the range to the given day, pulling the start back if it would pass it.
	 */
	void useToday(LocalDate today)
	{
		endDate = today;
		if (startDate == null || startDate.isAfter(endDate))
		{
			startDate = endDate;
		}
		target = Target.START;
		showMonthOf(today);
		render();
	}

	private void showMonthOf(LocalDate date)
	{
		YearMonth month = YearMonth.from(date);
		if (!month.equals(displayedMonth))
		{
			displayedMonth = month;
		}
	}

	private JPanel createDateFields()
	{
		JPanel fields = new JPanel(new GridLayout(2, 1, 0, 4));
		fields.setBackground(CustomColors.DARK_GRAY);

		styleFieldButton(fromField, "From date");
		fromField.addActionListener(event -> focusStartDate());

		JPanel toRow = new JPanel(new BorderLayout(4, 0));
		toRow.setBackground(CustomColors.DARK_GRAY);
		styleFieldButton(toField, "To date");
		toField.addActionListener(event -> focusEndDate());
		styleFieldButton(todayButton, "Use today as the end date");
		todayButton.addActionListener(event -> useToday(LocalDate.now()));
		toRow.add(toField, BorderLayout.CENTER);
		toRow.add(todayButton, BorderLayout.EAST);

		fields.add(fromField);
		fields.add(toRow);
		return fields;
	}

	private void styleFieldButton(JButton button, String accessibleName)
	{
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setFocusable(true);
		button.setOpaque(true);
		button.setContentAreaFilled(true);
		button.setBackground(CustomColors.DARK_GRAY_LIGHTER);
		button.getAccessibleContext().setAccessibleName(accessibleName);
	}

	private JPanel createCalendar()
	{
		JPanel calendar = new JPanel(new BorderLayout(0, 4));
		calendar.setBackground(CustomColors.DARK_GRAY);
		calendar.add(createHeader(), BorderLayout.NORTH);
		calendar.add(createDaysPanel(), BorderLayout.CENTER);
		return calendar;
	}

	private JPanel createHeader()
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(CustomColors.DARK_GRAY);

		monthLabel.setFont(FontManager.getRunescapeBoldFont());
		monthLabel.setForeground(CustomColors.OFF_WHITE);

		header.add(createNavigationButton("<", "Previous month", -1), BorderLayout.WEST);
		header.add(monthLabel, BorderLayout.CENTER);
		header.add(createNavigationButton(">", "Next month", 1), BorderLayout.EAST);
		return header;
	}

	private JButton createNavigationButton(String text, String accessibleName, int monthDelta)
	{
		JButton button = new JButton(text);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setFocusable(true);
		button.getAccessibleContext().setAccessibleName(accessibleName);
		button.addActionListener(event ->
		{
			displayedMonth = displayedMonth.plusMonths(monthDelta);
			render();
		});
		return button;
	}

	private JPanel createDaysPanel()
	{
		JPanel daysPanel = new JPanel(new GridLayout(WEEKS_SHOWN + 1, DAYS_PER_WEEK, 2, 2));
		daysPanel.setBackground(CustomColors.DARK_GRAY);

		for (int i = 0; i < DAYS_PER_WEEK; i++)
		{
			JLabel weekday = new JLabel(FIRST_DAY_OF_WEEK.plus(i).getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
				SwingConstants.CENTER);
			weekday.setFont(FontManager.getRunescapeSmallFont());
			weekday.setForeground(Color.GRAY);
			daysPanel.add(weekday);
		}

		for (int i = 0; i < GRID_CELLS; i++)
		{
			JButton dayButton = new JButton();
			dayButton.setFont(FontManager.getRunescapeSmallFont());
			dayButton.setMargin(new Insets(0, 0, 0, 0));
			dayButton.setFocusPainted(false);
			dayButton.setOpaque(true);
			dayButton.setContentAreaFilled(true);
			final int cell = i;
			dayButton.addActionListener(event -> selectDate(dayDates[cell]));
			dayButtons[i] = dayButton;
			daysPanel.add(dayButton);
		}

		return daysPanel;
	}

	private JLabel createSelectionLabel()
	{
		selectionLabel.setFont(FontManager.getRunescapeSmallFont());
		selectionLabel.setForeground(CustomColors.CHEESE);
		selectionLabel.setPreferredSize(new Dimension(0, 20));
		selectionLabel.setVerticalAlignment(SwingConstants.CENTER);
		return selectionLabel;
	}

	private void render()
	{
		monthLabel.setText(MONTH_FORMAT.format(displayedMonth));
		fromField.setText("From: " + (startDate == null ? "-" : SUMMARY_FORMAT.format(startDate)));
		toField.setText("To: " + (endDate == null ? "-" : SUMMARY_FORMAT.format(endDate)));
		fromField.setForeground(target == Target.START ? CustomColors.CHEESE : CustomColors.OFF_WHITE);
		toField.setForeground(target == Target.END ? CustomColors.CHEESE : CustomColors.OFF_WHITE);

		LocalDate firstOfMonth = displayedMonth.atDay(1);
		int leadingDays = Math.floorMod(firstOfMonth.getDayOfWeek().getValue() - FIRST_DAY_OF_WEEK.getValue(), DAYS_PER_WEEK);
		LocalDate gridStart = firstOfMonth.minusDays(leadingDays);

		for (int i = 0; i < GRID_CELLS; i++)
		{
			LocalDate date = gridStart.plusDays(i);
			dayDates[i] = date;
			JButton button = dayButtons[i];
			button.setText(String.valueOf(date.getDayOfMonth()));
			button.getAccessibleContext().setAccessibleName(DAY_ACCESSIBLE_FORMAT.format(date));
			styleDay(button, date);
		}

		selectionLabel.setText(describeSelection());
	}

	private void styleDay(JButton button, LocalDate date)
	{
		boolean inDisplayedMonth = YearMonth.from(date).equals(displayedMonth);
		boolean isBoundary = date.equals(startDate) || date.equals(endDate);
		boolean isInRange = startDate != null && endDate != null
			&& !date.isBefore(startDate) && !date.isAfter(endDate);

		button.setBorder(BorderFactory.createLineBorder(isBoundary ? Color.BLACK : CustomColors.DARK_GRAY));

		if (isBoundary)
		{
			button.setBackground(CustomColors.CHEESE);
			button.setForeground(Color.BLACK);
		}
		else if (isInRange)
		{
			button.setBackground(CustomColors.IN_RANGE);
			button.setForeground(Color.WHITE);
		}
		else
		{
			button.setBackground(CustomColors.DARK_GRAY_LIGHTER);
			button.setForeground(inDisplayedMonth ? CustomColors.OFF_WHITE : Color.GRAY);
		}
	}

	private String describeSelection()
	{
		if (startDate == null)
		{
			return "Pick a start date";
		}
		if (endDate == null)
		{
			return "From " + SUMMARY_FORMAT.format(startDate) + " - pick an end date";
		}
		return SUMMARY_FORMAT.format(startDate) + " - " + SUMMARY_FORMAT.format(endDate);
	}
}
