package com.flippingutilities.ui.uiutilities;

import lombok.Getter;
import lombok.Setter;
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
	@Setter
	private int pageNumber = 1;
	private int totalPages;
	@Getter
	private JLabel pageOfLabel;
	private JTextField pageInput;
	@Setter
	private JLabel arrowRight;
	@Setter
	private JLabel arrowLeft;
	Runnable onPageChange;
	@Setter
	private int pageSize = 20;

	public Paginator(Runnable onPageChange)
	{
		this.onPageChange = onPageChange;
		
		// Page input field
		this.pageInput = new JTextField("1", 3);
		this.pageInput.setFont(FontManager.getRunescapeBoldFont());
		this.pageInput.setHorizontalAlignment(JTextField.CENTER);
		this.pageInput.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		this.pageInput.setBorder(new MatteBorder(0, 1, 1, 1, ColorScheme.MEDIUM_GRAY_COLOR));
		this.pageInput.setForeground(Color.WHITE);
		
		// "of X" label
		this.pageOfLabel = new JLabel("of 1", SwingUtilities.CENTER);
		this.pageOfLabel.setFont(FontManager.getRunescapeBoldFont());
		
		this.arrowLeft = new JLabel(Icons.ARROW_LEFT);
		this.arrowRight = new JLabel(Icons.ARROW_RIGHT);
		this.arrowRight.setForeground(Color.blue);
		
		setLayout(new FlowLayout(FlowLayout.CENTER, 5, 0));
		add(arrowLeft);
		add(new JLabel("Page") {{ setFont(FontManager.getRunescapeBoldFont()); }});
		add(pageInput);
		add(pageOfLabel);
		add(arrowRight);
		setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		setBorder(new EmptyBorder(3, 0, 0, 0));
		
		arrowLeft.addMouseListener(onMouse(false));
		arrowRight.addMouseListener(onMouse(true));
		pageInput.addActionListener(this::onPageInputSubmit);
		pageInput.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				onPageInputSubmit(null);
			}
		});
	}
	/**
	 * Sets the font for all text components in the paginator.
	 */
	public void setComponentsFont(java.awt.Font font)
	{
		pageInput.setFont(font);
		pageOfLabel.setFont(font);
		for (java.awt.Component c : getComponents())
		{
			if (c instanceof JLabel)
			{
				((JLabel) c).setFont(font);
			}
		}
	}

	private void onPageInputSubmit(ActionEvent e)
	{
		if (totalPages <= 1)
		{
			return;
		}

		String input = pageInput.getText().trim();
		try
		{
			int newPage = Integer.parseInt(input);
			if (newPage >= 1 && newPage <= totalPages && newPage != pageNumber)
			{
				int oldPage = pageNumber;
				pageNumber = newPage;
				try
				{
					onPageChange.run();
				}
				catch (Exception exc)
				{
					log.warn("couldn't change page number cause callback failed");
					pageNumber = oldPage;
				}
			}
			// Always reset input to show current page
			pageInput.setText(String.valueOf(pageNumber));
		}
		catch (NumberFormatException ex)
		{
			pageInput.setText(String.valueOf(pageNumber));
		}
	}

	public void updateTotalPages(int numItems)
	{
		if (numItems <= pageSize) {
			totalPages = 1;
		}
		else {
			totalPages = (int) Math.ceil((float)numItems/ pageSize);
		}

		pageInput.setText(String.valueOf(pageNumber));
		pageOfLabel.setText("of " + totalPages);
	}

	private MouseAdapter onMouse(boolean isIncrease)
	{
		return new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (isIncrease)
				{
					if (pageNumber < totalPages)
					{
						pageNumber++;
						try
						{
							onPageChange.run();
						}
						catch (Exception exc)
						{
							log.warn("couldn't increase page number cause callback failed");
							pageNumber--;
						}
					}
				}
				else
				{
					if (pageNumber > 1)
					{
						pageNumber--;
						try
						{
							onPageChange.run();
						}
						catch (Exception exc)
						{
							log.warn("couldn't decrease page number cause callback failed");
							pageNumber++;
						}

					}
				}
				pageInput.setText(String.valueOf(pageNumber));
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				if (isIncrease)
				{
					arrowRight.setIcon(Icons.ARROW_RIGHT_HOVER);
				}
				else
				{
					arrowLeft.setIcon(Icons.ARROW_LEFT_HOVER);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				if (isIncrease)
				{
					arrowRight.setIcon(Icons.ARROW_RIGHT);
				}
				else
				{
					arrowLeft.setIcon(Icons.ARROW_LEFT);
				}
			}
		};
	}

	public <T> List<T> getCurrentPageItems(List<T> items)
	{
		List<T> pageItems = new ArrayList<>();
		int startIndex = (pageNumber - 1) * pageSize;
		int endIndex = Math.min(startIndex + pageSize, items.size());
		for (int i = startIndex; i < endIndex; i++)
		{
			pageItems.add(items.get(i));
		}
		return pageItems;
	}
}
