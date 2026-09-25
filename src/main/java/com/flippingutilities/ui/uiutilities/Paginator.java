package com.flippingutilities.ui.uiutilities;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class Paginator extends JPanel
{
	@Getter
	private int pageNumber = 1;
	private int totalPages = 1;
	private int numItems;
	@Getter
	private final JLabel pageOfLabel;
	private final JTextField pageInput;
	private final JButton arrowRight;
	private final JButton arrowLeft;
	private final Runnable onPageChange;
	private int pageSize = 20;

	public Paginator(Runnable onPageChange)
	{
		this.onPageChange = onPageChange;

		pageInput = new JTextField("1", 3);
		pageInput.setFont(FontManager.getRunescapeBoldFont());
		pageInput.setHorizontalAlignment(JTextField.CENTER);
		pageInput.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		pageInput.setBorder(new MatteBorder(0, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR));
		pageInput.setForeground(Color.WHITE);
		pageInput.getAccessibleContext().setAccessibleName("Page number");

		pageOfLabel = new JLabel("of 1", SwingUtilities.CENTER);
		pageOfLabel.setFont(FontManager.getRunescapeBoldFont());

		arrowLeft = createPageButton("Previous page", Icons.ARROW_LEFT, Icons.ARROW_LEFT_HOVER, -1);
		arrowRight = createPageButton("Next page", Icons.ARROW_RIGHT, Icons.ARROW_RIGHT_HOVER, 1);
		JLabel pageLabel = new JLabel("Page");
		pageLabel.setFont(FontManager.getRunescapeBoldFont());
		pageLabel.setLabelFor(pageInput);

		setLayout(new FlowLayout(FlowLayout.CENTER, 5, 0));
		add(arrowLeft);
		add(pageLabel);
		add(pageInput);
		add(pageOfLabel);
		add(arrowRight);
		setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		setBorder(new EmptyBorder(3, 0, 0, 0));

		pageInput.addActionListener(e -> submitPageInput());
		pageInput.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				submitPageInput();
			}
		});
		updateControls();
	}

	private JButton createPageButton(String name, Icon icon, Icon hoverIcon, int offset)
	{
		JButton button = new JButton(icon);
		button.setRolloverIcon(hoverIcon);
		button.setToolTipText(name);
		button.getAccessibleContext().setAccessibleName(name);
		button.setPreferredSize(new Dimension(24, 24));
		button.setContentAreaFilled(false);
		button.setBorder(new EmptyBorder(1, 1, 1, 1));
		button.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				button.setBorder(BorderFactory.createLineBorder(ColorScheme.LIGHT_GRAY_COLOR));
			}

			@Override
			public void focusLost(FocusEvent e)
			{
				button.setBorder(new EmptyBorder(1, 1, 1, 1));
			}
		});
		button.addActionListener(e -> changePage(pageNumber + offset));
		// Space is provided by JButton; support Enter without installing a window-wide shortcut.
		button.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "changePage");
		button.getActionMap().put("changePage", new AbstractAction()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				button.doClick();
			}
		});
		return button;
	}

	/** Sets the font for all text components in the paginator. */
	public void setComponentsFont(Font font)
	{
		pageInput.setFont(font);
		for (Component component : getComponents())
		{
			if (component instanceof JLabel)
			{
				component.setFont(font);
			}
		}
	}

	/** Updates the selected page without triggering a rebuild of its owner. */
	public void setPageNumber(int pageNumber)
	{
		this.pageNumber = Math.max(1, Math.min(pageNumber, totalPages));
		updateControls();
	}

	public void setPageSize(int pageSize)
	{
		if (pageSize < 1)
		{
			throw new IllegalArgumentException("Page size must be positive");
		}
		this.pageSize = pageSize;
		updateTotalPages(numItems);
	}

	private void submitPageInput()
	{
		try
		{
			changePage(Integer.parseInt(pageInput.getText().trim()));
		}
		catch (NumberFormatException ex)
		{
			updateControls();
		}
	}

	private void changePage(int newPage)
	{
		if (newPage >= 1 && newPage <= totalPages && newPage != pageNumber)
		{
			int oldPage = pageNumber;
			pageNumber = newPage;
			try
			{
				onPageChange.run();
			}
			catch (Exception ex)
			{
				log.warn("Could not change page because the page callback failed", ex);
				pageNumber = Math.min(oldPage, totalPages);
			}
		}
		updateControls();
	}

	public void updateTotalPages(int numItems)
	{
		this.numItems = Math.max(0, numItems);
		totalPages = Math.max(1, (int) ((this.numItems + (long) pageSize - 1) / pageSize));
		setPageNumber(pageNumber);
	}

	private void updateControls()
	{
		pageInput.setText(String.valueOf(pageNumber));
		pageInput.setEnabled(totalPages > 1);
		pageInput.getAccessibleContext().setAccessibleDescription("Page " + pageNumber + " of " + totalPages);
		pageOfLabel.setText("of " + totalPages);
		arrowLeft.setEnabled(pageNumber > 1);
		arrowRight.setEnabled(pageNumber < totalPages);
	}

	public <T> List<T> getCurrentPageItems(List<T> items)
	{
		// Clamp before slicing, including callers whose list changed since their last rebuild.
		updateTotalPages(items.size());
		int startIndex = (pageNumber - 1) * pageSize;
		int endIndex = (int) Math.min(startIndex + (long) pageSize, items.size());
		return new ArrayList<>(items.subList(startIndex, endIndex));
	}
}
