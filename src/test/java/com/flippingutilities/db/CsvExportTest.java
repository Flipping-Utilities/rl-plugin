package com.flippingutilities.db;

import com.flippingutilities.model.FlippingItem;
import com.flippingutilities.model.OfferEvent;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import net.runelite.client.util.Filepath;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

public class CsvExportTest {
    private static final String HEADER = "# Displaying trades for selected time interval: All time\r\n"
        + "name,date,quantity,price,state\r\n";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Locale previousLocale;
    private TimeZone previousTimeZone;

    @Before
    public void setDateFormatDefaults() {
        previousLocale = Locale.getDefault();
        previousTimeZone = TimeZone.getDefault();
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @After
    public void restoreDateFormatDefaults() {
        Locale.setDefault(previousLocale);
        TimeZone.setDefault(previousTimeZone);
    }

    @Test
    public void emptyExportRetainsIntervalCommentAndHeader() throws Exception {
        assertEquals(HEADER, export(Collections.emptyList(), "All time"));
    }

    @Test
    public void exportsTradesInOrderWithLongPricesProfitAndItemSeparators() throws Exception {
        FlippingItem first = item("Twisted bow", offer(true, 2, 3_000_000_000L),
            offer(false, 2, 3_100_000_000L));
        FlippingItem second = item("Coins", offer(true, 1, Long.MAX_VALUE));

        assertEquals(HEADER
            + "Twisted bow,2020-01-02 03:04 PM,2,3000000000,BOUGHT\r\n"
            + "Twisted bow,2020-01-02 03:04 PM,2,3100000000,SOLD\r\n"
            + "# Total profit: 200000000\r\n\r\n"
            + "Coins,2020-01-02 03:04 PM,1,9223372036854775807,BOUGHT\r\n"
            + "# Total profit: 0\r\n\r\n", export(Arrays.asList(first, second), "All time"));
    }

    @Test
    public void preservesCsvEscapingWhitespaceAndUnicodeInNames() throws Exception {
        String[][] examples = {
            {"Rune, sword", "\"Rune, sword\""},
            {"Rune \"sword\"", "\"Rune \"\"sword\"\"\""},
            {"Rune\nsword", "\"Rune\nsword\""},
            {"Rune\rsword", "\"Rune\rsword\""},
            {"Rune\r\nsword", "\"Rune\r\nsword\""},
            {" Rune sword", "\" Rune sword\""},
            {"Rune sword ", "\"Rune sword \""},
            {"\tRune sword", "\"\tRune sword\""},
            {"Rune sword\t", "\"Rune sword\t\""},
            {"", "\"\""},
            {"# Rune sword", "\"# Rune sword\""},
            {"(Rune sword)", "\"(Rune sword)\""},
            {"Rune épée 🗡", "Rune épée 🗡"},
            {"Épée runique", "\"Épée runique\""}
        };
        for (String[] example : examples) {
            assertEquals(example[0], HEADER
                + example[1] + ",2020-01-02 03:04 PM,1,100,BOUGHT\r\n"
                + "# Total profit: 0\r\n\r\n",
                export(Collections.singletonList(item(example[0], offer(true, 1, 100))), "All time"));
        }
    }

    @Test
    public void nullNameRemainsAnUnquotedEmptyField() throws Exception {
        assertEquals(HEADER + ",2020-01-02 03:04 PM,1,100,BOUGHT\r\n"
            + "# Total profit: 0\r\n\r\n",
            export(Collections.singletonList(item(null, offer(true, 1, 100))), "All time"));
    }

    @Test
    public void multilineIntervalKeepsEveryLineAComment() throws Exception {
        assertEquals("# Displaying trades for selected time interval: First\r\n"
            + "# Second\r\n# Third\r\n# Fourth\r\n# \r\n"
            + "name,date,quantity,price,state\r\n",
            export(Collections.emptyList(), "First\r\nSecond\rThird\nFourth\n"));
    }

    @Test
    public void itemWithoutTradesStillIncludesItsProfitComment() throws Exception {
        assertEquals(HEADER + "# Total profit: 0\r\n\r\n",
            export(Collections.singletonList(item("Rune sword")), "All time"));
    }

    private String export(List<FlippingItem> items, String interval) throws Exception {
        File file = temporaryFolder.newFile();
        TradePersister.exportToCsv(Filepath.Unchecked.getRooted(file.toPath()), items, interval);
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static FlippingItem item(String name, OfferEvent... offers) {
        FlippingItem item = new FlippingItem(1, name, 100, "Player");
        item.getHistory().getCompressedOfferEvents().addAll(Arrays.asList(offers));
        return item;
    }

    private static OfferEvent offer(boolean buy, int quantity, long price) {
        OfferEvent offer = new OfferEvent();
        offer.setBuy(buy);
        offer.setCurrentQuantityInTrade(quantity);
        offer.setPrice(price);
        offer.setTime(Instant.parse("2020-01-02T15:04:00Z"));
        offer.setState(buy ? GrandExchangeOfferState.BOUGHT : GrandExchangeOfferState.SOLD);
        offer.setMadeBy("Player");
        return offer;
    }
}
