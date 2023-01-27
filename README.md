# Flipping Utilities plugin for RuneLite
Flipping Utilities is a Runelite plugin that aids over 50,000 players in making investing more profitable and enjoyable.
It calculates your profits in real time, keeps a richly detailed log of your trading activity, lets you quickly input
optimal prices according to your margin checks with just a key press, and a lot more!

old repo: https://github.com/Belieal/flipping-utilities

## Community
Join the community on discord at https://discord.gg/GDqVgMH26s, it's one of the largest trading related communities
for OSRS!

Our discord also offers access to various powerful tools such as item dump alerts allowing you to buy items at greatly
deflated prices and a lot more!

# Table of Contents
- [Features](#features)
    + [The Flipping Tab](#the-flipping-tab)
    + [The Statistics Tab](#the-statistics-tab)
    + [Additional Features](#additional-features)
        - [Slot timers](#slot-timers)
        - [Add untracked trades easily](#add-untracked-trades-easily)
        - [Setup offers quickly](#offer-editors)
        - [Favorites lookup](#favorites-lookup)
- [Development](#development)
    + [General Structure of Codebase](#general-structure-of-codebase)
    + [Example Flow](#example-flow)


# Features
The plugin is divided into three tabs: the slots tab, flipping tab and statistics tab.

### The Flipping Tab
This tab displays info most relevant to you when actively flipping

<p align="center"> 
  <img src = "https://github.com/Flipping-Utilities/rl-plugin/blob/master/images/flipping.png" width="300" height="546">
</p>

The item cards are highly customizable, allowing you to pick what information to show in which section.

<p align="center"> 
  <img src = "https://github.com/Flipping-Utilities/rl-plugin/blob/master/images/customize.png" width="300" height="415">
</p>

### The Statistics Tab
This tab records your trading history, down to every single individual offer you made.

<p align="center"> 
  <img src = "https://github.com/Flipping-Utilities/rl-plugin/blob/master/images/stats.png" width="300" height="950">
</p>

The item cards can be expanded
<p align="center"> 
  <img src = "https://github.com/Flipping-Utilities/rl-plugin/blob/master/images/statCard.png">
</p>

The trade history for each item can be expanded to see the offers:
<p align="center"> 
  <img src = "https://github.com/Flipping-Utilities/rl-plugin/blob/master/images/offers.png" width="300" height="500">
</p>

And the flips:
<p align="center"> 
  <img src = "https://github.com/Flipping-Utilities/rl-plugin/blob/master/images/flips.png" width="300" height="500">
</p>


#### Recipe flips

Sometimes, flips aren't straightforward selling and buying of the same item - they involve combining several items
into one or transforming an item into another. For accurately capturing the profits for these complicated trades, you
can create a "recipe flip".

Go to your offers for an item in the stats tab. If there is a recipe for that item, a recipe flip button will
be present on the offer card. Click it and select the appropriate recipe

<p align="center"> 
  <img src = "https://github.com/Flipping-Utilities/rl-plugin/blob/master/images/recipeCreation.png" width="500" height="450">
</p>

adjust the inputs and outputs if necessary, then combine

<p align="center"> 
  <img src = "https://github.com/Flipping-Utilities/rl-plugin/blob/master/images/recipeCreationStep.png">
</p>

view your recipe flips in the "recipes" tab of the stats tab

<p align="center"> 
  <img src = "https://github.com/Flipping-Utilities/rl-plugin/blob/master/images/viewRecipes.png" width="300" height="633">
</p>

## Additional Features

#### Slot timers

Slot timers help you figure out when you should cancel an offer. They show you how long it has been
since the offer was last active.

<p align="center">
  <img src = "https://github.com/Flipping-Utilities/rl-plugin/blob/master/images/timer.png">
</p>

#### Offer editors

The price and quantity help you set up your offers quickly!

<p align="center">
  <img src = "https://github.com/Flipping-Utilities/rl-plugin/blob/master/images/editor.png">
</p>


#### Add untracked trades easily

Flip on mobile and have untracked trades? Just pop open your ge history and easily add untracked trades

<p align="center">
  <img src = "https://github.com/Flipping-Utilities/rl-plugin/blob/master/images/manual.png">
</p>

#### Favorites lookup

Quickly lookup your favorited items just by typing "1" in the ge search!

<p align="center">
  <img src = "https://github.com/Flipping-Utilities/rl-plugin/blob/master/images/lookup.png">
</p>

# Development

### General Structure of Codebase

This section will talk about the purpose of various parts of the codebase, specifically the folders.

**controller/**
- This folder contains the components that handle some specific responsibility of the plugin, mainly by handling runelite
  events (such as new GE offers) or presenting APIs to alter/view user data. Each class in this folder is instantiated
  by the FlippingPlugin in its `startUp` method, which is run on client startup. Each of these classes handles a specific
  responsibility of the plugin. For example, the `NewOfferEventPipelineHandler` is responsible for consuming new offer
  events and adding it to the data structures that model a user's trade history. The classes are used via the FlippingPlugin
  calling their methods. For example, when the FlippingPlugin gets an offer event in the `onGrandExchangeOfferChanged`
  method it calls `newOfferEventPipelineHandler.onGrandExchangeOfferChanged(newOffer);`.
  Much of the logic in these controller classes used to live in the FlippingPlugin class but was moved out as the
  FlippingPlugin class had become huge and was doing too many things.


**model/**
- This folder contains the classes that represent the data that will be stored on disk. These classes are turned into
  JSON and saved to disk and are also created from JSON that was previously saved to disk. To see what each of these
  classes actually model, check out the [Data model](#Data-model) section.


**db/**
- This folder contains the class responsible for taking the models and saving them
  to disk as JSON. It also loads JSON from disk (previously saved) and turns them into objects.

**ui/**
- This folder contains all the UI code for the plugin which is the code that draws the "plugin" you see, such as the slots
  tab, the flipping tab, the statistics tab, and all the content within them.

**jobs/**
- This folder contains code that is running in background threads to periodically perform some action, such as the
  code that queries the wiki to get the new prices of items, or the code that sends premium users' slots to our server.

**utilities/**
- Just random code that doesn't fit neatly into any of the categories above.

### Example Flow

This section will give an example of how the plugin actually runs and what happens during its lifetime. Much more detail
can be added to this section, which I will hopefully get to sometime...

The starting point to this plugin is the FlippingPlugin class. On Runelite startup, the Runelite code will create an
instance of the FlippingPlugin class and then call its `startUp()` method.

#### On Runelite Client Startup
The FlippingPlugin does three main things on startup (all in the `startUp()` method):
1. It creates instances of all the controller classes, described in the controller section of [General Structure of Codebase](#General-structure-of-codebase)
2. It initializes the various UI classes such as the FlippingPanel and StatsPanel. This will result in the UI being
   drawn eventually.
3. It loads the user's previously saved trading history from the disk via the DataHandler - one of the controller classes, 

Now things have been setup properly, and the UI has been drawn and populated with the user's previously saved data from
disk.

#### During Runelite Client's Lifetime
During the lifetime of the Runelite Client (between startup and shutdown), there are primarily three ways the plugin does work
1. **Handling Runelite events that reflect some change of state in the game**. There are many Runelite events. For example,
   events can trigger in various scenarios: when an account logs in, a GE offer gets placed, an account logs out,
   an account opens the GE interface, and many more. The Runelite code will feed the plugin these events if the plugin implements certain methods. For example,
   when the user places an offer in the GE, Runelite code gives the plugin details of the GE offer event via calling the
   `onGrandExchangeOfferChanged` on the FlippingPlugin class and passing that method a `GrandExchangeOfferChanged` object.
   It knows to do this because of method naming conventions. You don't have to define a method to handle every possible
   type of Runelite event, just the ones your plugin should care about.
2. **Handling UI interactions that the user initiates**. For example, the user may want to see data for another account, so
   he clicks on the account dropdown selector and selects another account. The plugin then needs to re-render its UI with the
   selected account's trades.
3. **Via background threads that are performing some action periodically**. See the jobs section of [General Structure of Codebase](#general-structure-of-codebase).


#### On Runelite Client Shutdown
The Runelite client will tell the plugin when it's shutting down and will allow it to execute some code before
that happens. This occurs in `onClientShutdown` in the FlippingPlugin class. Not much happens other than saving
user data to disk and cancelling any background jobs that were running.


### Data model

This section describes how the plugin models users' trade history. The main model classes used to do this are:
AccountData, FlippingItem, HistoryManager, and OfferEvent.

When you go to `.runelite/flipping/<username>.json` and open it, you will see the JSON version of an `AccountData`
object. Each of the user's osrs accounts get their own AccountData object, each of which is stored in a file
of the format `.runelite/flipping/<account_username>.json`.

AccountData objects are created from the JSON in those files on client startup (or created on account login if they have
no previously saved data for that account). As the user makes trades, deletes trades via the UI, and so on, the
AccountData object for the correct account is mutated. Then, on account logout or client shutdown, it is turned back
into JSON and saved into the same file it was loaded from (or creates a new file if there was no previously saved data).

The AccountData object's most important field is `List<FlippingItem> trades`. This field contains FlippingItem objects.
FlippingItem objects have an itemId (every runescape item has a unique number to identify it) and represents the trade history
for that item on that account. So, for example, if you flipped dragon claws on an account, you would have one and only one
FlippingItem in that account's AccountData object that holds all the buy and sell offers you have ever made for dragon
claws on that account.

The FlippingItem stores all the offers for that item in the `HistoryManager history` field. Put simply, a HistoryManager
is just a list of OfferEvent. An OfferEvent represents a buy and sell offer in which some amount of an
item bought or sold. It has various fields like price, quantity, etc.


## Icon Attributions
All icons were either made by Belieal or downloaded from the creators on www.flaticon.com below.
<div>Icons made by <a href="https://www.flaticon.com/authors/those-icons" title="Those Icons">Those Icons</a> from <a href="https://www.flaticon.com/" title="Flaticon">www.flaticon.com</a></div>
<div>Icons made by <a href="https://www.flaticon.com/authors/pixel-perfect" title="Pixel perfect">Pixel perfect</a> from <a href="https://www.flaticon.com/" title="Flaticon">www.flaticon.com</a></div>
<div>Icons made by <a href="https://www.flaticon.com/authors/freepik" title="Freepik">Freepik</a> from <a href="https://www.flaticon.com/" title="Flaticon">www.flaticon.com</a></div>

*If you notice any bugs or have any suggestions, let us know by making an [issue](https://github.com/Flipping-Utilities/rl-plugin/issues) or PM us on Discord @ gamersriseup#4026 or Anyny0#4452! I'm also happy to answer any questions that you may have. :)*
