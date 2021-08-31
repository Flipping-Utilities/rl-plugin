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

package com.flippingutilities.ui;

import com.flippingutilities.controller.FlippingPlugin;
import com.flippingutilities.ui.flipping.FlippingPanel;
import com.flippingutilities.ui.settings.SettingsPanel;
import com.flippingutilities.ui.slots.SlotsPanel;
import com.flippingutilities.ui.statistics.StatsPanel;
import com.flippingutilities.ui.uiutilities.CustomColors;
import com.flippingutilities.ui.uiutilities.FastTabGroup;
import com.flippingutilities.ui.uiutilities.Icons;
import com.flippingutilities.ui.uiutilities.UIUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.ComboBoxListRenderer;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.LinkBrowser;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashSet;
import java.util.Set;

@Slf4j
public class MasterPanel extends PluginPanel
{
	@Getter
	private JComboBox<String> accountSelector;
	private FlippingPlugin plugin;
	private FastTabGroup tabGroup;

	/**
	 * THe master panel is always present. The components added to it are components that should always be visible
	 * regardless of whether you are looking at the flipping panel or the statistics panel. The tab group to switch
	 * between the flipping and stats panel, the account selector dropdown menu, and the settings button are all examples
	 * of components that are always present, hence they are on the master panel.
	 *
	 * @param flippingPanel FlippingPanel represents the main tool of the plugin.
	 * @param statPanel     StatPanel represents useful performance statistics to the user.
	 */
	public MasterPanel(FlippingPlugin plugin,
					   FlippingPanel flippingPanel,
					   StatsPanel statPanel,
					   SlotsPanel slotsPanel)
	{
		super(false);

		this.plugin = plugin;

		setLayout(new BorderLayout());

		JPanel mainDisplay = new JPanel();

		accountSelector = accountSelector();

		tabGroup = tabSelector(mainDisplay, flippingPanel, statPanel, slotsPanel);
		JPanel header = createHeader(accountSelector, tabGroup);
		header.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0,0,4,0, ColorScheme.DARKER_GRAY_COLOR.darker()),
				BorderFactory.createEmptyBorder(0,0,5,0)));
		add(header, BorderLayout.NORTH);
		add(mainDisplay, BorderLayout.CENTER);
	}

	/**
	 * The header is at the top of the panel. It is the component that contains the account selector dropdown, the
	 * settings button to the right of the dropdown, and the tab selector which allows a user to select either the
	 * flipping or stats tab.
	 *
	 * @param accountSelector the account selector dropdown
	 * @param tabSelector     a tab group with allows a user to select either the flipping or stats tab to view.
	 * @return a jpanel representing the header.
	 */
	private JPanel createHeader(JComboBox accountSelector, MaterialTabGroup tabSelector)
	{
		JPanel accountSelectorPanel = new JPanel(new BorderLayout());
		accountSelectorPanel.setBackground(CustomColors.DARK_GRAY);
		accountSelectorPanel.add(accountSelector, BorderLayout.CENTER);
		accountSelectorPanel.setBorder(new EmptyBorder(0,0,4,0));

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(CustomColors.DARK_GRAY);
		header.add(accountSelectorPanel, BorderLayout.NORTH);
		header.add(createCommunityPanel(), BorderLayout.CENTER);
		header.add(tabSelector, BorderLayout.SOUTH);
		header.setBorder(new EmptyBorder(0,0,3,0));
		return header;
	}

	private JPanel createCommunityPanel() {
		JPanel communityPanel = new JPanel(new BorderLayout());
		communityPanel.setBackground(CustomColors.DARK_GRAY);
		communityPanel.setBorder(new EmptyBorder(7,0,0,0));

//		JPanel topPanel = new JPanel();
//		topPanel.setBackground(CustomColors.DARK_GRAY);
//		topPanel.setBorder(new EmptyBorder(0,0,10,0));
//
//		JLabel topLabel = new JLabel("Join discord for bot dump alerts!", JLabel.CENTER);
//		topLabel.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
//		topLabel.setFont(FontManager.getRunescapeSmallFont());
//		topLabel.setBackground(CustomColors.DARK_GRAY);
//
//		JLabel questionMarkLabel = new JLabel(Icons.QUESTION_MARK);
//		JPopupMenu popup = new JPopupMenu();
//		popup.add(new JLabel(Icons.DUMP_ALERT_PIC));
//		UIUtilities.addPopupOnHover(questionMarkLabel, popup, false);
//
//		topPanel.add(topLabel);
//		topPanel.add(questionMarkLabel);

		JPanel centerPanel = new JPanel();
		centerPanel.setBorder(new EmptyBorder(0,0,6,43));

		JLabel githubIcon = new JLabel(Icons.GITHUB_ICON);
		githubIcon.setToolTipText("Click to go to Flipping Utilities github");
		githubIcon.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				LinkBrowser.browse("https://github.com/Belieal/flipping-utilities");
			}

			@Override
			public void mouseEntered(MouseEvent e) {
				githubIcon.setIcon(Icons.GITHUB_ICON_ON);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				githubIcon.setIcon(Icons.GITHUB_ICON);
			}
		});

		JLabel twitterIcon = new JLabel(Icons.TWITTER_ICON);
		twitterIcon.setToolTipText("Click to go to Flipping Utilities twitter");
		twitterIcon.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				LinkBrowser.browse("https://twitter.com/flippingutils");
			}

			@Override
			public void mouseEntered(MouseEvent e) {
				twitterIcon.setIcon(Icons.TWITTER_ICON_ON);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				twitterIcon.setIcon(Icons.TWITTER_ICON);
			}
		});


		//centerPanel.setBorder(new EmptyBorder(4,0,0,0));
		centerPanel.setBackground(CustomColors.DARK_GRAY);
		centerPanel.add(createDiscordButton(Icons.DISCORD_ICON));
		centerPanel.add(twitterIcon);
		centerPanel.add(githubIcon);

		//communityPanel.add(topPanel, BorderLayout.NORTH);
		communityPanel.add(centerPanel, BorderLayout.CENTER);

		JLabel profileButton = new JLabel(Icons.USER);
		profileButton.setBorder(new EmptyBorder(0,15,10,0));
		JDialog loginModal = UIUtilities.createModalFromPanel(profileButton, createLoginPanel());
		loginModal.pack();
		profileButton.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				loginModal.setVisible(true);
			}

			@Override
			public void mouseEntered(MouseEvent e) {
				profileButton.setIcon(Icons.USER_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				profileButton.setIcon(Icons.USER);
			}
		});

		JPopupMenu profilePopup = new JPopupMenu();
		profilePopup.add(new JLabel("Click to login!"));
		UIUtilities.addPopupOnHover(profileButton, profilePopup, false);

		communityPanel.add(profileButton, BorderLayout.WEST);
		return communityPanel;
	}

	private JPanel createLoginPanel() {
		JPanel loginPanel = new JPanel(new BorderLayout());
		loginPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		loginPanel.setBorder(new EmptyBorder(20,40,20,25));

		JPanel centerPanelWrapper = new JPanel(new BorderLayout());
		centerPanelWrapper.setBorder(new EmptyBorder(0,30,0,30));

		JPanel centerPanel = new JPanel();
		centerPanel.setBorder(new MatteBorder(1,1,1,1, ColorScheme.MEDIUM_GRAY_COLOR));
		centerPanel.setPreferredSize(new Dimension(1, centerPanel.getHeight()));

		centerPanelWrapper.add(centerPanel, BorderLayout.CENTER);

		loginPanel.add(createTokenPanel(), BorderLayout.WEST);
		loginPanel.add(centerPanelWrapper, BorderLayout.CENTER);
		loginPanel.add(createLoginInstructionsPanel(), BorderLayout.EAST);
		return loginPanel;
	}

	private JPanel createLoginInstructionsPanel() {
		JPanel instructionsPanel = new JPanel(new BorderLayout());

		JPanel header = new JPanel();
		header.setForeground(CustomColors.CHEESE);
		JLabel informationIcon = new JLabel(Icons.INFORMATION, JLabel.CENTER);
		header.add(informationIcon);
		instructionsPanel.add(header, BorderLayout.NORTH);

		JPanel stepsPanel = new JPanel(new DynamicGridLayout(4, 0, 0, 0));

		JPanel firstStepPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JLabel firstStepNumber = new JLabel("<html>1. </html>");
		firstStepNumber.setFont(new Font("Whitney", Font.BOLD, 14));
		firstStepNumber.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
		JLabel firstStepDesc = new JLabel("Join our discord", JLabel.LEFT);
		firstStepDesc.setFont(new Font("Whitney", Font.PLAIN, 12));
		firstStepDesc.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
		firstStepPanel.add(firstStepNumber);
		firstStepPanel.add(firstStepDesc);
		firstStepPanel.add(createDiscordButton(Icons.DISCORD_CHEESE));

		JPanel secondStepPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JLabel secondStepDesc = new JLabel("Type !login in the bot channel");
		secondStepDesc.setFont(new Font("Whitney", Font.PLAIN, 12));
		secondStepDesc.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
		JLabel secondStepNumber = new JLabel("<html>2. </html>");
		secondStepNumber.setFont(new Font("Whitney", Font.BOLD, 14));
		secondStepNumber.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
		secondStepPanel.add(secondStepNumber);
		secondStepPanel.add(secondStepDesc);

		JPanel thirdStepPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
		JLabel thirdStepNumber = new JLabel("<html>3. </html>");
		thirdStepNumber.setFont(new Font("Whitney", Font.BOLD, 14));
		thirdStepNumber.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
		JLabel thirdStepDesc = new JLabel("Enter the token here");
		thirdStepDesc.setFont(new Font("Whitney", Font.PLAIN, 12));
		thirdStepDesc.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
		thirdStepPanel.add(thirdStepNumber);
		thirdStepPanel.add(thirdStepDesc);

		stepsPanel.add(firstStepPanel);
		stepsPanel.add(secondStepPanel);
		stepsPanel.add(thirdStepPanel);

		JLabel bottomText = new JLabel("<html>You will only have to do this once</html>");
		bottomText.setFont(new Font("Whitney", Font.BOLD + Font.ITALIC, 10));
		bottomText.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
		bottomText.setBorder(new EmptyBorder(35,0,0,0));

		stepsPanel.add(bottomText);


		instructionsPanel.add(stepsPanel, BorderLayout.SOUTH);

		return instructionsPanel;
	}

	private JPanel createTokenPanel() {
		JPanel tokenPanel = new JPanel(new BorderLayout());
		tokenPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel header = new JPanel();
		header.setForeground(CustomColors.CHEESE);
		JLabel fuIcon = new JLabel(Icons.FU_ICON, JLabel.CENTER);
		header.add(fuIcon);

		JPanel middlePanel = new JPanel(new BorderLayout());
		middlePanel.setBorder(new EmptyBorder(20,0,20,0));
		middlePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		IconTextField tokenField = new IconTextField();
		tokenField.setBackground(CustomColors.DARK_GRAY);
		tokenField.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(1,1,1,1, ColorScheme.DARKER_GRAY_COLOR.darker()),
				BorderFactory.createEmptyBorder(10,0,10,0)));
		tokenField.setPreferredSize(new Dimension(140, 40));

		JLabel tokenFieldDescriptor = new JLabel("TOKEN", JLabel.LEFT);
		tokenFieldDescriptor.setFont(new Font("Whitney", Font.BOLD, 12));
		tokenFieldDescriptor.setForeground(ColorScheme.GRAND_EXCHANGE_ALCH);
		tokenFieldDescriptor.setBorder(new EmptyBorder(0,0,5,0));

		middlePanel.add(tokenFieldDescriptor, BorderLayout.NORTH);
		middlePanel.add(tokenField, BorderLayout.CENTER);

		JPanel loginButtonWrapper = new JPanel();
		loginButtonWrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel loginButton = new JLabel("Login", JLabel.CENTER);
		loginButton.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,0,0, ColorScheme.GRAND_EXCHANGE_PRICE.darker()),new EmptyBorder(10,20,10,20))
		);
		loginButton.setFont(new Font("Whitney", Font.BOLD, 12));
		loginButton.setBackground(ColorScheme.GRAND_EXCHANGE_PRICE);
		loginButton.setOpaque(true);
		loginButton.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				super.mousePressed(e);
			}

			@Override
			public void mouseEntered(MouseEvent e) {
				loginButton.setBackground(ColorScheme.GRAND_EXCHANGE_PRICE.brighter());
			}

			@Override
			public void mouseExited(MouseEvent e) {
				loginButton.setBackground(ColorScheme.GRAND_EXCHANGE_PRICE);
			}
		});

		loginButtonWrapper.add(loginButton);

		tokenPanel.add(header, BorderLayout.NORTH);
		tokenPanel.add(middlePanel, BorderLayout.CENTER);
		tokenPanel.add(loginButtonWrapper, BorderLayout.SOUTH);

		return tokenPanel;
	}

	private JLabel createDiscordButton(ImageIcon icon) {
		JLabel discordIcon = new JLabel(icon);
		discordIcon.setToolTipText("Click to go to Flipping Utilities discord");
		discordIcon.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				LinkBrowser.browse("https://discord.gg/GDqVgMH26s");
			}

			@Override
			public void mouseEntered(MouseEvent e) {
				discordIcon.setIcon(Icons.DISCORD_ICON_ON);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				discordIcon.setIcon(icon);
			}
		});
		return discordIcon;
	}

	/**
	 * This is the dropdown at the top of the header which allows the user to select which account they want to view.
	 * Its only set to visible if the user has more than once account with a trading history.
	 *
	 * @return the account selector.
	 */
	private JComboBox accountSelector()
	{
		JComboBox viewSelectorDropdown = new JComboBox();
		viewSelectorDropdown.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
		viewSelectorDropdown.setFocusable(false);
		viewSelectorDropdown.setForeground(ColorScheme.GRAND_EXCHANGE_PRICE);
		viewSelectorDropdown.setRenderer(new ComboBoxListRenderer());
		viewSelectorDropdown.setToolTipText("Select which of your account's trades list you want to view");
		viewSelectorDropdown.addItemListener(event ->
		{
			if (event.getStateChange() == ItemEvent.SELECTED)
			{
				String selectedName = (String) event.getItem();
				plugin.changeView(selectedName);
			}
		});

		return viewSelectorDropdown;
	}

	/**
	 * This is the button that you click on to view the setting modal. It is only visible if the account selector is
	 * visible.
	 *
	 * @param callback the callback executed when the button is clicked.
	 * @return the settings button
	 */
	private JLabel settingsButton(Runnable callback)
	{
		JLabel button = new JLabel(Icons.SETTINGS_ICON_OFF);
		button.setToolTipText("Open Settings Panel");
		button.setPreferredSize(Icons.ICON_SIZE);
		button.addMouseListener(new MouseAdapter()
		{
			public void mouseClicked(MouseEvent e)
			{
				callback.run();
			}

			@Override
			public void mouseEntered(MouseEvent e) {
				button.setIcon(Icons.SETTINGS_ICON);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				button.setIcon(Icons.SETTINGS_ICON_OFF);
			}
		});
		return button;
	}

	/**
	 * Adds the tabs for the flipping panel and stats panel onto the main display panel. These tabs can then
	 * be clicked to view the flipping/stats panel
	 *
	 * @param mainDisplay   the panel on which the tabs will be put and on which either the flipping or stats panel will be
	 *                      rendered
	 * @return
	 */
	private FastTabGroup tabSelector(JPanel mainDisplay, JPanel flippingPanel, JPanel statPanel, JPanel slotsPanel)
	{
		FastTabGroup tabGroup = new FastTabGroup(mainDisplay);
		MaterialTab flippingTab = new MaterialTab("flipping", tabGroup, flippingPanel);
		MaterialTab statisticsTab = new MaterialTab("stats", tabGroup, statPanel);
		MaterialTab slotsTab = new MaterialTab("slots", tabGroup, slotsPanel);

		tabGroup.addTab(slotsTab);
		tabGroup.addTab(flippingTab);
		tabGroup.addTab(statisticsTab);

		tabGroup.select(flippingTab);
		return tabGroup;
	}

	public Set<String> getViewSelectorItems()
	{
		Set<String> items = new HashSet<>();
		for (int i = 0; i < accountSelector.getItemCount(); i++)
		{
			items.add(accountSelector.getItemAt(i));
		}
		return items;
	}

	public void addView(JPanel panel, String name) {
		tabGroup.addView(panel, name);
	}

	public void showView(String name) {
		tabGroup.showView(name);
	}

	public void selectPreviouslySelectedTab() {
		tabGroup.selectPreviouslySelectedTab();
	}

	/**
	 * There are certain views that should not be viewable unless the user is logged in because they require the
	 * currently logged in account. This method is used to revert back to a "safe" previously selected tab that is
	 * safe to view when an account is logged out.
	 */
	public void revertToSafeDisplay() {
		tabGroup.revertToSafeDisplay();
	}

	/**
	 * sets up the account selector dropdown that lets you change which account's trade list you
	 * are looking at.
	 */
	public void setupAccSelectorDropdown(Set<String> currentAccounts) {
		//adding an item causes the event listener (changeView) to fire which causes stat panel
		//and flipping panel to rebuild. I think this only happens on the first item you add.
		accountSelector.addItem(FlippingPlugin.ACCOUNT_WIDE);

		currentAccounts.forEach(displayName -> accountSelector.addItem(displayName));

		//sets the account selector dropdown to visible or not depending on whether the config option has been
		//selected and there are > 1 accounts.
		if (currentAccounts.size() > 1) {
			accountSelector.setVisible(true);
		} else {
			accountSelector.setVisible(false);
		}
	}
}
